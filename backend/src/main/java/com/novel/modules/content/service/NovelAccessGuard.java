package com.novel.modules.content.service;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.entity.OutlineNode;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.mapper.OutlineNodeMapper;
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
    private final OutlineNodeMapper outlineNodeMapper;

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

    /* ==================== 大纲树（P4） ==================== */

    /**
     * 取「我作品下的」大纲节点：节点不存在 → {@code 3006}；存在但不属于我 → {@code 3007}。
     *
     * <p><b>形状和 {@link #requireOwnedChapter} 完全一致，理由也一样：</b>
     * {@code novel_outline} 上没有 {@code author_id}，归属只能经 {@code novel} 判定，
     * 所以这里多一次主键查询。
     *
     * <p>★ 作品查不到时（节点成了孤儿）返回 {@code 3007} 而不是 {@code 3006}，
     * 并打一条<b>措辞不同</b>的 WARN —— 和 {@code requireOwnedChapter} 同样的保守方向：
     * 不存在的东西默认不属于你，但真要排查时能从日志里区分出是哪种。
     */
    public OutlineNode requireOwnedOutlineNode(Long nodeId) {
        return checkOwnedByNovel(outlineNodeMapper.selectById(nodeId), nodeId);
    }

    /**
     * 同 {@link #requireOwnedOutlineNode}，但<b>对节点行加排他锁</b>。
     *
     * <p><b>★ 只给「由大纲节点创建正文」用</b>（{@code OutlineServiceImpl#createChapter}），
     * 理由见 {@code OutlineNodeMapper#selectByIdForUpdate} 的注释 ——
     * 那里要「先读 chapter_id 判空、再决定新建还是幂等」，
     * 读和写之间不能有别人插进来。
     *
     * <p><b>★★ 必须在事务里调用</b>，否则 InnoDB 在语句结束就释放锁，
     * 这个方法等于没加锁，且不会有任何报错。
     *
     * <p>★ 别的接口<b>不要</b>图省事用它：加锁会阻塞并发的其他写请求
     * （比如同一时刻的自动保存），而我们只在真正需要「读-判-写」原子性的地方才付这个代价。
     */
    public OutlineNode requireOwnedOutlineNodeForUpdate(Long nodeId) {
        return checkOwnedByNovel(outlineNodeMapper.selectByIdForUpdate(nodeId), nodeId);
    }

    /**
     * 取节点并确认它属于<b>指定的作品</b>：不存在或不属于 → {@code 3007}。
     *
     * <p><b>★★ 这个方法补的是 P4 归属校验里唯一的洞，而且这个洞不在 URL 上，在请求体里。</b>
     * <p>建节点（{@code dto.parentId}）和拖拽（{@code dto.targetParentId}）
     * 都会从<b>请求体</b>里收到一个节点 id。如果只校验 URL 上那个 id 的归属，
     * 就能把<b>自己的节点挂到别人的节点下面</b> —— 而这条 UPDATE 语法完全合法，
     * 数据库不会有任何抱怨。
     * <p>症状极其隐蔽：{@code GET /outline} 走的是
     * {@code requireOwned(自己的 novelId)} 查全量，<b>别人的节点根本查不出来</b>,
     * 于是自己那个节点变成孤儿 —— <b>在树上完全不显示，且没有任何错误</b>。
     * 作者会以为「保存失败」，再拖一次，循环。
     *
     * <p>★ 返回 {@code 3007}（而不是 {@code 3006}）是刻意的：
     * 这个方法的语义是「确认它在这本书里」，「不在」是不满足，
     * 无论原因是它压根不存在还是它属于别人 —— 对调用方是同一件事，
     * 而区分开会给出一份「哪些 nodeId 真实存在」的探针。
     */
    public OutlineNode requireNodeInNovel(Long novelId, Long nodeId) {
        OutlineNode node = outlineNodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getNovelId(), novelId)) {
            throw new BusinessException(ResultCode.OUTLINE_NODE_NOT_YOURS);
        }
        return node;
    }

    /**
     * 两个公开方法共用的那一段：节点 → 作品 → 作者。
     *
     * <p>抽出来是因为「越权必须留痕」这件事只能有一处实现（见类注释），
     * 加锁版和非加锁版只该在<b>怎么取节点</b>上不同，不该在<b>怎么判归属</b>上分家。
     *
     * @param node   已经取到的节点，可能为 {@code null}
     * @param nodeId 只用于日志
     */
    private OutlineNode checkOwnedByNovel(OutlineNode node, Long nodeId) {
        if (node == null) {
            throw new BusinessException(ResultCode.OUTLINE_NODE_NOT_FOUND);
        }

        Novel novel = novelMapper.selectById(node.getNovelId());
        Long userId = UserContext.requireUserId();

        if (novel == null) {
            log.warn("[孤儿大纲节点] nodeId={} 的 novelId={} 已不存在，userId={} 试图操作它",
                    nodeId, node.getNovelId(), userId);
            throw new BusinessException(ResultCode.OUTLINE_NODE_NOT_YOURS);
        }
        if (!Objects.equals(novel.getAuthorId(), userId)) {
            log.warn("[越权尝试] userId={} 试图操作 outlineNodeId={}（属于 novelId={} authorId={}）",
                    userId, nodeId, novel.getId(), novel.getAuthorId());
            throw new BusinessException(ResultCode.OUTLINE_NODE_NOT_YOURS);
        }
        return node;
    }
}
