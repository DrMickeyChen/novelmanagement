package com.novel.modules.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.result.PageResult;
import com.novel.common.security.UserContext;
import com.novel.interaction.client.ContentGateway;
import com.novel.common.vo.NovelBriefVO;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.modules.interaction.entity.ReadHistory;
import com.novel.modules.interaction.entity.UserShelf;
import com.novel.modules.interaction.mapper.ReadHistoryMapper;
import com.novel.modules.interaction.mapper.UserShelfMapper;
import com.novel.modules.interaction.service.ShelfService;
import com.novel.modules.interaction.vo.ShelfItemVO;
import com.novel.modules.interaction.vo.ShelfStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架服务实现。
 *
 * <p>读写模式和给二期 M3（拆 interaction 服务）的交接说明，见
 * {@code docs/P6-书架评论.md} —— 那一节是 M3 的输入，改这个类之前先读它。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShelfServiceImpl implements ShelfService {

    private final UserShelfMapper shelfMapper;
    private final ReadHistoryMapper readHistoryMapper;

    /**
     * ★ 跨模块依赖只有这一个，方向固定为 {@code interaction → content}。
     *
     * <p><b>★★ M2 兑现了这里原来那句「二期拆服务时它们变成 Feign 调用，
     * 本类的代码不用改」—— 而且比预期的更早（原计划 M3）。</b>
     * 变化恰好只有注入的这一行：{@code NovelService} + {@code ChapterService}
     * 两个接口换成了 {@link ContentGateway} 一个适配器，
     * <b>下面每一处调用的逻辑一个字节没动</b>。
     * <p>★ 为什么不直接注入 {@code ContentClient}（那个 Feign 接口）：
     * 它的方法返回 {@code Result} 信封，而「HTTP 200 不等于成功」，
     * 每个调用点都得拆一次信封。{@code ContentGateway} 把这件事收在一处
     * —— 漏掉的后果是<b>校验静默失效</b>，不是报错。完整论证写在那里。
     *
     * <p><b>★ 反过来 content 一次都没有读过 {@code user_shelf}</b> ——
     * 它只被写 {@code novel.collect_count} 这一个冗余列。
     * 单向是刻意的：双向依赖拆不开。
     */
    private final ContentGateway contentGateway;

    @Override
    public PageResult<ShelfItemVO> myPage(long page, long size) {
        /*
         * ★★ userId 是查询条件的一部分，不是「查出来再过滤」。
         *   「我的书架」这个接口一旦漏了它，就是「所有人的书架」。
         *   写成 WHERE 条件，泄露在 SQL 层面就不可能。同 HistoryServiceImpl#get。
         */
        Long userId = UserContext.requireUserId();

        Page<UserShelf> result = shelfMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<UserShelf>()
                        .eq(UserShelf::getUserId, userId)
                        .orderByDesc(UserShelf::getCreateTime)
                        /*
                         * ★ 按 id 兜底，不能只按 create_time 排。
                         *   create_time 是 DATETIME（秒精度），同一秒加入的两本书
                         *   排序值相同 —— MySQL 对相同排序值的行返回顺序不保证稳定，
                         *   翻页时会出现「某本书重复出现、另一本永远看不到」。
                         *   完整论证见 NovelServiceImpl#applyReaderSort。
                         */
                        .orderByDesc(UserShelf::getId));

        List<UserShelf> rows = result.getRecords();
        if (rows.isEmpty()) {
            // ★ 提前返回，省掉后面三次批量查。空页是「新用户第一次进书架」的常态。
            return new PageResult<>(List.of(), result.getTotal(),
                    result.getCurrent(), result.getSize(), result.getPages());
        }

        List<Long> novelIds = rows.stream().map(UserShelf::getNovelId).distinct().toList();

        /*
         * ★★ 下面三次都是「按 id 集合一次 IN 查完」，不是每行查一次。
         *   一页 20 本 = 20 次 → 不批量就是 60+ 次查询。三个批量查分别负责
         *   作品信息、我的进度、章节标题 —— 缺一个列表就渲染不出来，
         *   而写成循环它「结果完全正确」，功能测试永远发现不了。
         */
        Map<Long, NovelBriefVO> briefs = contentGateway.novelBriefsOf(novelIds);
        Map<Long, ReadHistory> histories = historiesOf(userId, novelIds);
        Map<Long, ChapterBriefVO> chapters = contentGateway.chapterBriefsOf(
                histories.values().stream().map(ReadHistory::getChapterId).toList());

        List<ShelfItemVO> items = rows.stream()
                .map(shelf -> {
                    ReadHistory history = histories.get(shelf.getNovelId());
                    return ShelfItemVO.of(shelf, briefs.get(shelf.getNovelId()), history,
                            history == null ? null : chapters.get(history.getChapterId()));
                })
                .toList();
        return new PageResult<>(items, result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    @Override
    public ShelfStatusVO status(Long novelId) {
        Long userId = UserContext.requireUserId();
        boolean onShelf = isOnShelf(userId, novelId);
        /*
         * ★ 收藏数走 briefsOf 而不是再加一个「查 collect_count」的接缝方法：
         *   它一次 IN 就能把作品信息全带回来，而作品不可见时返回 null ——
         *   「查不到收藏数」和「这本书现在读不了」本来就是同一件事。
         */
        return ShelfStatusVO.of(novelId, onShelf, collectCountOf(novelId));
    }

    @Override
    public ShelfStatusVO add(Long novelId) {
        Long userId = UserContext.requireUserId();

        /*
         * ★ 写入路径必须校验作品存在且已上架（同 HistoryServiceImpl#report）。
         *   不校验就能给任意 id 在 user_shelf 里造行 —— 那是给一本不存在的书
         *   刷收藏数，而 collect_count 是读者端会显示的。
         *   requirePublished 保证「不存在」和「未上架」返回同一个 2001，
         *   所以它顺带也不是一个「哪些 id 是真实的书」的探针。
         */
        contentGateway.requirePublished(novelId);

        /*
         * ★ 用 addIfAbsent（INSERT ... ON DUPLICATE KEY UPDATE）而不是
         *   「先 selectOne 判断再 insert」：后者是查一次 + 写一次两个步骤，
         *   两个标签页同时点「加入书架」时都查到「没有」，然后都去 insert，
         *   第二个撞 uk_user_novel 抛 DuplicateKeyException —— 用户正常的
         *   点两下变成了 500。完整论证见 UserShelfMapper#addIfAbsent。
         */
        shelfMapper.addIfAbsent(userId, novelId, LocalDateTime.now());
        return ShelfStatusVO.of(novelId, true, refreshCollectCount(novelId));
    }

    @Override
    public ShelfStatusVO remove(Long novelId) {
        Long userId = UserContext.requireUserId();

        /*
         * ★ 不校验作品是否上架 —— 完整论证见 ShelfService#remove：
         *   下架的书也要能从书架里移除，否则那一行永远清不掉。
         *
         * ★ 物理删除（这张表没有 deleted 列）。这个表的语义是「现在在不在架」，
         *   逻辑删会留下无数行「曾经加过又移出」的垃圾，去重还得再加一个条件。
         *   也正因为是物理删，这里可以放心用 delete(wrapper) ——
         *   不存在「忘了补 deleted = 0」的问题（那列根本不存在，
         *   补了反而 Unknown column，见 UserShelf 的类注释）。
         *
         * ★ userId 同样在 WHERE 里：只删自己那一行，天然删不到别人的。
         */
        shelfMapper.delete(new LambdaQueryWrapper<UserShelf>()
                .eq(UserShelf::getUserId, userId)
                .eq(UserShelf::getNovelId, novelId));

        // 不在书架里也照样重算并返回成功：幂等，且顺带把漂移的计数纠回来
        return ShelfStatusVO.of(novelId, false, refreshCollectCount(novelId));
    }

    /**
     * 我的阅读进度，返回 {@code novelId → ReadHistory}。
     *
     * <p>★ 一次 {@code IN} 查完。只查当前用户（{@code user_id} 在 WHERE 里），
     * 所以扫不出别人的进度。
     */
    private Map<Long, ReadHistory> historiesOf(Long userId, List<Long> novelIds) {
        Map<Long, ReadHistory> histories = new LinkedHashMap<>();
        for (ReadHistory row : readHistoryMapper.selectList(new LambdaQueryWrapper<ReadHistory>()
                .eq(ReadHistory::getUserId, userId)
                .in(ReadHistory::getNovelId, novelIds))) {
            histories.put(row.getNovelId(), row);
        }
        return histories;
    }

    /**
     * 重算并写回 {@code novel.collect_count}，返回重算后的值。
     *
     * <p><b>★★ 重算，不是 {@code collect_count + 1}。</b>
     * 增量的正确性依赖「每一条加 / 减路径都记得调、且方向正确」，而
     * 「移出时忘了减」是必然发生的漂移 —— 更糟的是漂移出来的数字
     * <b>看起来永远合理</b>（显示 87 人收藏，实际 86 人，没人会去数）。
     * 重算是一处实现、永远自洽，代价只是一次 {@code COUNT(*)}，
     * 而 {@code user_shelf.idx_novel_id} 正是为它建的。
     *
     * <p>★ <b>并发下最后一次写可能落后一格</b>（两个请求各自 COUNT 到 5 和 6，
     * 后提交的那个可能写的是 5）。这不修，因为它<b>不会累积</b> ——
     * 下一次任何人对这本书做书架操作就纠正了。这正是选重算而不是增量的原因：
     * 增量错一次就永久偏移，重算错一次下次自己就好了。
     *
     * <p>★ 值在这里算、行由 content 写：{@code user_shelf} 是
     * interaction 的表，{@code novel} 是 content 的。跨表 SQL 一行都不出现。
     * <p><b>★★ M2 起这里是一次真实的 Feign 调用</b>（原计划 M3），
     * 于是多了一个改造前不存在的问题：<b>本地重算成功、远程写失败，
     * 收藏数就停在旧值上，而且不会自愈。</b>
     * M2 接受它，记为 M3（MQ）的动机之一 —— 详见
     * {@code ContentGateway#refreshCollectCount} 的注释。
     */
    private int refreshCollectCount(Long novelId) {
        long count = shelfMapper.selectCount(new LambdaQueryWrapper<UserShelf>()
                .eq(UserShelf::getNovelId, novelId));
        contentGateway.refreshCollectCount(novelId, count);
        log.debug("收藏数已重算 | novelId={} count={}", novelId, count);
        return (int) count;
    }

    /** 取作品当前收藏数；作品不可见（下架 / 已删）时返回 {@code null} */
    private Integer collectCountOf(Long novelId) {
        NovelBriefVO brief = contentGateway.novelBriefsOf(List.of(novelId)).get(novelId);
        return brief == null ? null : brief.getCollectCount();
    }

    /** 这本书在不在我的书架。★ userId 在 WHERE 里 */
    private boolean isOnShelf(Long userId, Long novelId) {
        return shelfMapper.exists(new LambdaQueryWrapper<UserShelf>()
                .eq(UserShelf::getUserId, userId)
                .eq(UserShelf::getNovelId, novelId));
    }

    /**
     * 分页对象 + 页码兜底。
     *
     * <p>★ 上限不在这里管，由 {@code MybatisPlusConfig} 的
     * {@code PaginationInnerInterceptor.setMaxLimit(100)} 负责；这里只管下限。
     * 下限必须管：<b>MP 的分页插件在 {@code size < 0} 时不做分页，会把整张表返回</b>
     * （负数在 MP 里表示「不分页」），传个 {@code size=-1} 就能一次拖走全部书架。
     * 同 {@code CommentServiceImpl#buildPage}。
     */
    private Page<UserShelf> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page,
                size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size);
    }
}
