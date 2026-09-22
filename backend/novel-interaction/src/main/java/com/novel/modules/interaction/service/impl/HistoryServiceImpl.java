package com.novel.modules.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.security.UserContext;
import com.novel.common.vo.NovelBriefVO;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.interaction.client.ContentGateway;
import com.novel.modules.interaction.dto.HistoryReportDTO;
import com.novel.modules.interaction.entity.ReadHistory;
import com.novel.modules.interaction.mapper.ReadHistoryMapper;
import com.novel.modules.interaction.service.HistoryService;
import com.novel.modules.interaction.vo.ReadHistoryItemVO;
import com.novel.modules.interaction.vo.ReadHistoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读进度服务实现。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    /** 进度百分比的上下界。★ 夹紧用的常量，改这里就同时改了夹紧逻辑 */
    private static final int PROGRESS_MIN = 0;
    private static final int PROGRESS_MAX = 100;

    private final ReadHistoryMapper readHistoryMapper;

    /**
     * ★ 跨模块依赖只有这一个，而且三处都是在「借一次校验 / 借一次查询」。
     *
     * <p><b>★★ M2 兑现了这里原来那句「二期 M3 拆服务时它们变成 Feign 调用 ——
     * 调用方（本类）的代码不用改」—— 而且比预期早了一期。</b>
     * 改动恰好只有注入的这一行（三个接口 → 一个适配器），
     * <b>下面每一处调用的逻辑一个字节没动</b>。
     *
     * <p>★ 为什么是 {@link ContentGateway} 而不是直接注入 {@code ContentClient}：
     * Feign 方法返回 {@code Result} 信封，而「HTTP 200 不等于成功」，
     * 每个调用点都得拆一次信封 —— <b>漏掉的那个调用点会静默失效</b>。
     * 完整论证写在 {@code ContentGateway} 的类注释里。
     */
    private final ContentGateway contentGateway;

    @Override
    public void report(HistoryReportDTO dto) {
        /*
         * ★★ 先取用户，再校验。
         *
         * 顺序上其实无所谓（这个接口在 /api/reader/** 下，匿名根本进不来），
         * 但 requireUserId 放在第一行是有意的：它是本方法【唯一】的安全边界，
         * 让它出现在最显眼的位置，读代码的人第一眼就知道 userId 从哪来。
         * 一旦有人把它挪到方法中间、甚至换成 dto.getUserId()，
         * 这个接口就变成了「往别人的阅读记录里写」。
         */
        Long userId = UserContext.requireUserId();

        /*
         * 第 1 步：作品必须存在且已上架。
         * ★ 复用 ContentGateway#requirePublished —— 它就是为「读者侧要写点东西、
         *   但写的不是业务数据」这类接口加的，阅读量埋点是第一个调用方，这里是第二个。
         *   不重新写一份查询，是因为「什么算可读的作品」这个判断
         *   必须全项目只有一处实现（它还要保证「不存在」和「未上架」不可区分）。
         */
        contentGateway.requirePublished(dto.getNovelId());

        /*
         * 第 2 步：章节必须存在，且确实属于上面那本作品。
         * ★ 两个 id 都来自请求，所以必须两边对上 —— 只按 chapterId 查的话，
         *   把 chapterId 换成别的书的章节就能上报成功，
         *   dto.novelId 形同虚设，进度会被写到一本完全无关的书上。
         */
        ChapterStatus chapterStatus = contentGateway.requireChapterStatus(
                dto.getNovelId(), dto.getChapterId());

        /*
         * 第 3 步：章节必须是已发布的。
         *
         * ★ 这一步单独存在、不能并入第 2 步：requireChapterStatus 只回答
         *   「这一章在不在这本书里」，不回答「读者能不能看到它」。
         *   ★ M2 之后这两个问题分别住在【两个进程】里（content 的
         *   NovelAccessGuard 与 ChapterStatus），"不能并入" 因此不再
         *   只是分层口味问题 —— 那是一次额外的跨服务往返，而这个往返
         *   换来的是「未发布」和「不存在」分不开（两者返回同一个码）。
         *   少了它，作者还没提交审核的草稿章、被驳回的章，都能被上报成功 ——
         *   而「上报成功 / 失败」的差别就是一把探针，
         *   能反推出一本未上架的书里有哪些章节 id。这是个 POST，
         *   看起来人畜无害，恰恰是最容易被漏掉的一条泄露通道。
         *
         * ★ 返回 3001（章节不存在）而不是别的码：和 readerDetail 一样，
         *   「这章没发布」和「这章不存在」对调用方是同一件事，
         *   区分了就等于确认了「这个 id 是真的，只是没发布」。
         */
        if (!chapterStatus.isVisibleToReader()) {
            log.debug("进度上报：章节未发布，拒绝 | userId={} novelId={} chapterId={} status={}",
                    userId, dto.getNovelId(), dto.getChapterId(), chapterStatus);
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }

        int progress = clampProgress(dto.getProgress());

        /*
         * ★ 时间由这里传入，不在 SQL 里写 NOW() —— 理由见 ReadHistoryMapper#upsert。
         *   一句话：和 MybatisMetaObjectHandler 保持同一个时区口径，
         *   并且保证「重读同一章、进度没变」时 update_time 也会往前推。
         */
        readHistoryMapper.upsert(userId, dto.getNovelId(), dto.getChapterId(), progress,
                LocalDateTime.now());

        /*
         * ★ 打一条 debug 而不是 info：这是阅读器每滚一屏就会调一次的接口，
         *   info 级别会在日志里刷屏，把真正有用的日志挤掉。
         *   排查「进度没记住」时把 com.novel 调到 debug 即可看到全部上报。
         */
        log.debug("进度已记录 | userId={} novelId={} chapterId={} progress={}",
                userId, dto.getNovelId(), dto.getChapterId(), progress);
    }

    @Override
    public ReadHistoryVO get(Long novelId) {
        Long userId = UserContext.requireUserId();

        /*
         * ★★ userId 是查询条件的一部分，不是「查出来再比对」。
         *   这两种写法的差别在数据量大了以后才显现：查出来再过滤，
         *   意味着先把别人的进度读进了内存 —— 一旦后面有人重构掉那个 if，
         *   泄露就已经发生了。写成 WHERE 条件，泄露在 SQL 层面就不可能。
         *
         * ★ 用 selectOne 是安全的：uk_user_novel(user_id, novel_id) 是唯一键，
         *   结果集最多一行，不会抛 TooManyResultsException。
         *   （★ 换个没有唯一键的表，selectOne 就必须先 selectList 再取第一条，
         *   否则「数据恰好只有一条」是运气，不是保证。）
         *
         * ★ 用 wrapper 而不是手写 @Select：这张表没有 deleted 列，
         *   所以这里不涉及「手写要记得补 deleted = 0」那条规矩（见 ReadHistoryMapper）。
         */
        ReadHistory row = readHistoryMapper.selectOne(new LambdaQueryWrapper<ReadHistory>()
                .eq(ReadHistory::getUserId, userId)
                .eq(ReadHistory::getNovelId, novelId));

        // row 为 null 是正常情况（这本书第一次读），返回 null 让 Controller 包成 code 200
        return ReadHistoryVO.of(row);
    }

    @Override
    public PageResult<ReadHistoryItemVO> myPage(long page, long size) {
        Long userId = UserContext.requireUserId();

        Page<ReadHistory> result = readHistoryMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<ReadHistory>()
                        .eq(ReadHistory::getUserId, userId)
                        /*
                         * ★ 这个索引(idx_user_time)存在的唯一理由就是这条排序。
                         *   P2 建表时它就没有任何查询在用 —— 这里终于用上了。
                         */
                        .orderByDesc(ReadHistory::getUpdateTime)
                        /*
                         * ★ id 兜底。update_time 是 DATETIME（秒精度），
                         *   同一秒读完两本书排序值相同，而 MySQL 对相同排序值
                         *   返回顺序不保证稳定 —— 现象是翻页时某条重复出现、
                         *   另一条永远看不到。完整论证见 NovelServiceImpl#applyReaderSort。
                         */
                        .orderByDesc(ReadHistory::getId));

        List<ReadHistory> rows = result.getRecords();
        if (rows.isEmpty()) {
            // ★ 提前返回，省掉后面两次批量查（新用户的历史页是空的，这是常态）
            return new PageResult<>(List.of(), result.getTotal(),
                    result.getCurrent(), result.getSize(), result.getPages());
        }

        /*
         * ★★ 两次批量查，不是每行两次。
         *   注意第二次查的是【章节】而不是「每本书的最新章」——
         *   历史列表要显示的是「我读到哪」，那是 history.chapter_id，
         *   和 novel.last_chapter_id（作品的最新章）是两个东西。
         */
        Map<Long, NovelBriefVO> briefs = contentGateway.novelBriefsOf(
                rows.stream().map(ReadHistory::getNovelId).toList());
        Map<Long, ChapterBriefVO> chapters = contentGateway.chapterBriefsOf(
                rows.stream().map(ReadHistory::getChapterId).toList());

        List<ReadHistoryItemVO> items = rows.stream()
                .map(h -> ReadHistoryItemVO.of(h, briefs.get(h.getNovelId()),
                        chapters.get(h.getChapterId())))
                .toList();
        return new PageResult<>(items, result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 分页对象 + 页码兜底。
     *
     * <p>★ 上限由 {@code MybatisPlusConfig} 的 {@code setMaxLimit(100)} 管；
     * 下限必须在这里管 —— <b>MP 的分页插件在 {@code size < 0} 时不做分页，
     * 会把整张表返回</b>（负数在 MP 里表示「不分页」）。
     * 同 {@code ShelfServiceImpl#buildPage}。
     */
    private Page<ReadHistory> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page,
                size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size);
    }

    /**
     * 把进度百分比夹到 {@code [0, 100]}，{@code null} 视为 0。
     *
     * <p><b>★ 为什么是「夹」而不是「校验失败返回 400」：</b>
     * 这个数字是前端用 {@code scrollTop / (scrollHeight - clientHeight)} 算出来的，
     * 四舍五入、字体加载导致的高度变化、缩放，都可能让它短暂地越界一两个点。
     * 用 {@code @Max(100)} 拦住它，结果是<b>整条进度都丢掉</b>（HTTP 400），
     * 而越界的那个数字本身并不危险 —— 它只是存进一个 {@code int} 列。
     * <p>换句话说：这个字段的重要性，配不上「为它拒掉一次上报」的代价。
     * 这和 P2 里「待审核期间不能改正文」那种必须硬拒的场景是两类问题。
     *
     * <p>★ {@code null} 兜成 0 而不是 NPE：{@code progress} 在 DTO 上是可选的，
     * 而列是 {@code NOT NULL DEFAULT 0}。不兜的话，
     * 一个只传了 novelId + chapterId 的客户端会拿到 500。
     */
    private int clampProgress(Integer progress) {
        if (progress == null) {
            return PROGRESS_MIN;
        }
        return Math.min(PROGRESS_MAX, Math.max(PROGRESS_MIN, progress));
    }
}
