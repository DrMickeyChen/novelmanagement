package com.novel.modules.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.interaction.dto.HistoryReportDTO;
import com.novel.modules.interaction.entity.ReadHistory;
import com.novel.modules.interaction.mapper.ReadHistoryMapper;
import com.novel.modules.interaction.service.HistoryService;
import com.novel.modules.interaction.vo.ReadHistoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
     * ★ 跨模块依赖只有这两个，而且都是在「借一次校验」。
     * 二期 M3 拆服务时，这两处变成 Feign 调用 —— 调用方（本类）的代码不用改。
     */
    private final NovelService novelService;
    private final NovelAccessGuard guard;

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
         * ★ 复用 NovelService#requirePublished —— 它就是为「读者侧要写点东西、
         *   但写的不是业务数据」这类接口加的，阅读量埋点是第一个调用方，这里是第二个。
         *   不重新写一份查询，是因为「什么算可读的作品」这个判断
         *   必须全项目只有一处实现（它还要保证「不存在」和「未上架」不可区分）。
         */
        novelService.requirePublished(dto.getNovelId());

        /*
         * 第 2 步：章节必须存在，且确实属于上面那本作品。
         * ★ 两个 id 都来自请求，所以必须两边对上 —— 只按 chapterId 查的话，
         *   把 chapterId 换成别的书的章节就能上报成功，
         *   dto.novelId 形同虚设，进度会被写到一本完全无关的书上。
         */
        Chapter chapter = guard.requireChapterInNovel(dto.getNovelId(), dto.getChapterId());

        /*
         * 第 3 步：章节必须是已发布的。
         *
         * ★ 这一步单独存在、不能并入第 2 步：requireChapterInNovel 只回答
         *   「这一章在不在这本书里」，不回答「读者能不能看到它」。
         *   少了它，作者还没提交审核的草稿章、被驳回的章，都能被上报成功 ——
         *   而「上报成功 / 失败」的差别就是一把探针，
         *   能反推出一本未上架的书里有哪些章节 id。这是个 POST，
         *   看起来人畜无害，恰恰是最容易被漏掉的一条泄露通道。
         *
         * ★ 返回 3001（章节不存在）而不是别的码：和 readerDetail 一样，
         *   「这章没发布」和「这章不存在」对调用方是同一件事，
         *   区分了就等于确认了「这个 id 是真的，只是没发布」。
         */
        if (!chapter.getStatus().isVisibleToReader()) {
            log.debug("进度上报：章节未发布，拒绝 | userId={} novelId={} chapterId={} status={}",
                    userId, dto.getNovelId(), dto.getChapterId(), chapter.getStatus());
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
