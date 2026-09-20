package com.novel.modules.content.service;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 「取一个作品 / 章节，并且确认它归当前用户」—— 作者侧每个写接口的第一道校验。
 *
 * <p><b>★★ 为什么要抽成一个类，而不是在 {@code NovelServiceImpl} 和
 * {@code ChapterServiceImpl} 各写一份 private 方法：</b>
 * 因为这份校验里有一条 {@code log.warn("[越权尝试] ...")}。
 * 越权返回的是 {@code 2002} / {@code 3002} —— 走 <b>HTTP 200</b>，
 * 在 APM 上<b>完全看不出来</b>（这正是我们刚做完的 HTTP 状态码工作留下的一个已知盲区，
 * 见方案 C-6）。<b>那条 WARN 是唯一的补偿措施。</b>
 * <p>两处各写一份的话，改一处忘一处是迟早的事，而漏掉的那一处不会有任何症状 ——
 * 只是从此有一半的越权尝试在日志里查不到。抽成一个类，
 * 「越权必须留痕」这件事就只有一处实现。
 *
 * <p><b>★ 责任边界（很重要，别把它当成通用工具）：</b>
 * 它只做「存在性 + 归属」两件事，<b>不管状态</b>。
 * 「已上架的书不能编辑」那类判断属于状态机，不在这里 ——
 * 混在一起会让这个类变成一个「什么都管一点」的上帝类，
 * 而它现在小到可以一眼看完，这是它的优点。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelAccessGuard {

    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;

    /**
     * 取作品，不存在就抛 {@code NOVEL_NOT_FOUND(2001)}。
     *
     * <p><b>★ 管理端用这个，不用 {@link #requireOwned}</b> ——
     * 管理员审核的就是别人的书，给他做归属校验等于审核功能不存在。
     * 「URL 闸管门、归属校验管物」里的后者，<b>只对作者端生效</b>。
     *
     * <p>{@code selectById} 自动带 {@code deleted = 0}（{@code @TableLogic}），
     * 所以「已删除的作品」在这里等同于「不存在」。
     */
    public Novel requireExisting(Long novelId) {
        Novel novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BusinessException(ResultCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    /**
     * 取「我的」作品：不存在 → {@code 2001}，不是我的 → {@code 2002}。
     * <b>作者侧每个写接口第一行都调它。</b>
     *
     * <p><b>★★ 这里返回 2002 而不是 403，这件事必须讲清楚</b>
     * （它和 P1 收尾做的 HTTP 状态码工作直接相关）：
     * <pre>
     *   角色不够      —— URL 闸 / {@code @PreAuthorize} 拦下  → HTTP 403   ★ 监控可见
     *   动了别人的资源 —— 本方法拦下                          → HTTP 200 + code 2002
     * </pre>
     * 语义上这本来就是两件事：403 是「你不该进这个门」，2002 是「这门不是你的」。
     * 合并成一个 403 会让「作者点错了链接」和「有人在扫别人的书」
     * 在日志里长得一模一样 —— 而后者是需要报警的安全事件。
     *
     * <p><b>★ 但 2002 走的是 HTTP 200，APM 上看不出来，所以那条 WARN 不能省。</b>
     */
    public Novel requireOwned(Long novelId) {
        Novel novel = requireExisting(novelId);
        if (!Objects.equals(novel.getAuthorId(), UserContext.requireUserId())) {
            log.warn("[越权尝试] userId={} 试图操作 novelId={}（属于 authorId={}）",
                    UserContext.getUserId(), novelId, novel.getAuthorId());
            throw new BusinessException(ResultCode.NOVEL_NOT_YOURS);
        }
        return novel;
    }

    /**
     * 取「我作品下的」章节：章节不存在 → {@code 3001}；
     * 存在但不属于我 → {@code 3002}。
     *
     * <p><b>★ 章节的归属要经作品判定，不能只看章节自己。</b>
     * {@code chapter} 表上只有 {@code novel_id}，没有 {@code author_id} ——
     * 作者信息挂在 {@code novel} 上。所以这里多一次主键查询。
     * 值得：多查一次换来「归属的判定标准全项目只有一处」，
     * 而如果图省事在 {@code chapter} 上冗余一个 {@code author_id}，
     * 就多了一个会在「换作者」（P4 可能有）时失同步的字段。
     *
     * <p><b>★ 作品查不到时也返回 3002 而不是 3001：</b>
     * 那种情况是「小说被删了、章节成了孤儿」（正常流程下不会发生 ——
     * 作品下有章节时不允许删除，见 {@code NOVEL_HAS_CHAPTERS}）。
     * 返回 3002 是「不存在的东西默认不属于你」这个保守方向，
     * 同时会打一条措辞不同的 WARN，真要排查时能从日志里区分出来。
     */
    public Chapter requireOwnedChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }

        Novel novel = novelMapper.selectById(chapter.getNovelId());
        Long userId = UserContext.requireUserId();

        if (novel == null) {
            log.warn("[孤儿章节] chapterId={} 的 novelId={} 已不存在，userId={} 试图操作它",
                    chapterId, chapter.getNovelId(), userId);
            throw new BusinessException(ResultCode.CHAPTER_NOT_YOURS);
        }
        if (!Objects.equals(novel.getAuthorId(), userId)) {
            log.warn("[越权尝试] userId={} 试图操作 chapterId={}（属于 novelId={} authorId={}）",
                    userId, chapterId, novel.getId(), novel.getAuthorId());
            throw new BusinessException(ResultCode.CHAPTER_NOT_YOURS);
        }
        return chapter;
    }

    /**
     * 取章节并确认它属于指定作品 —— <b>阅读器专用</b>。
     *
     * <p><b>★ 为什么阅读器需要「属于指定作品」这一层，而不只是按 chapterId 查：</b>
     * 阅读器的 URL 是 {@code /api/novels/{novelId}/chapters/{cid}}，
     * 两个 id 都是调用方给的。只按 {@code cid} 查的话，
     * 有人把 {@code cid} 改成别的书的章节 id，就能从一本已上架的书
     * <b>读到另一本书（可能未上架）的章节</b> —— URL 上的 {@code novelId} 形同虚设。
     * 所以必须两边都对上。
     *
     * <p>★ 返回 {@code 3001}（章节不存在）而不是别的码：对读者来说，
     * 「这一章不在这本书里」和「这一章不存在」是同一件事，
     * 分清了对匿名调用者就是一份「哪些 cid 是真实存在的」的探针。
     */
    public Chapter requireChapterInNovel(Long novelId, Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !Objects.equals(chapter.getNovelId(), novelId)) {
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }
        return chapter;
    }
}
