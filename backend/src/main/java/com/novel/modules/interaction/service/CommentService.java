package com.novel.modules.interaction.service;

import com.novel.common.enums.CommentStatus;
import com.novel.common.result.PageResult;
import com.novel.modules.interaction.dto.CommentAuditDTO;
import com.novel.modules.interaction.dto.CommentCreateDTO;
import com.novel.modules.interaction.vo.CommentVO;

/**
 * 评论服务。<b>读者侧和管理侧共用一个接口</b> —— 它们操作的是同一张表、
 * 同一套状态机，拆成两个接口只会让「改状态」那段逻辑有两个入口。
 *
 * <p><b>★★ 六个方法分成两组，分界线是「谁在调」：</b>
 * <pre>
 *   读者侧（{@code ReaderCommentController}，前四个）
 *     userId 一律取自 {@code UserContext}，方法签名里【没有】userId 参数
 *   管理侧（{@code AdminCommentController}，后两个）
 *     管理员审的就是别人的评论，所以不做归属校验 ——
 *     但【也不接受】调用方指定的 userId，审核人同样从 {@code UserContext} 取
 * </pre>
 * <p>这条分界线不是「代码整洁」，是<b>安全边界</b>：
 * 任何一个方法签名里一旦出现 {@code Long userId}，调用方就有了
 * 「以别人的身份发言 / 删别人的评论」的能力。
 * 字段不存在，这条路在类型层面就是封死的（同 {@code HistoryReportDTO} 的说明）。
 *
 * <p><b>★ 为什么管理侧的 {@code audit} 不叫 {@code transitStatus}
 * （{@code NovelService} / {@code ChapterService} 用的是那个名字）：</b>
 * 那两个服务里 {@code transitStatus} 是<b>全服务唯一能改状态的入口</b>，
 * 名字里的 {@code transit} 强调「必须先过 {@code canTransitTo}」。
 * 这里只有审核一个场景，状态机也只在 {@code audit} 里用一次 ——
 * 换成一个更直白的名字，读的人不用先去猜它和那两个的关系。
 * <b>规矩（改状态必过 {@code canTransitTo}）是一样的，只是叫法不同。</b>
 *
 * @author MickeyChen
 */
public interface CommentService {

    /* ================================================================
     *  读者侧
     * ================================================================ */

    /**
     * 某本书的评论列表（分页），<b>只返回已显示的</b>。
     *
     * <p><b>★ 这个接口是【匿名】的</b>（在 {@code SecurityConfig} 里
     * 被 {@code GET /api/novels/**} 那条方法限定的白名单覆盖）。
     * 所以它和 {@code ReaderNovelController} 一样，可见性条件必须写在
     * SQL 的 WHERE 里，不能查出来再过滤 —— 没有任何一层能兜住这个泄露。
     *
     * <p>★ 刻意<b>不校验作品是否存在 / 是否上架</b>：见实现类的长注释。
     *
     * @param novelId 作品 id
     */
    PageResult<CommentVO> readerPage(Long novelId, long page, long size);

    /**
     * 发表评论。<b>落库状态恒为「待审核」</b>，读者看不到自己刚发的评论。
     *
     * <p><b>★ 有 10 秒频控</b>，同一用户 10 秒内第二次调用返回
     * {@code COMMENT_TOO_FAST(4004)}（HTTP 200）。
     * 频控按 {@code userId} 不按 IP —— 见实现类的注释。
     *
     * @param novelId 作品 id，来自 URL 路径
     * @param dto     正文和可选的评分
     * @return 新评论的 id，供前端提示「已提交，等待审核」
     */
    Long create(Long novelId, CommentCreateDTO dto);

    /**
     * 点赞。<b>幂等</b> —— 同一个人对同一条评论点第二次不会重复计数，
     * 仍返回成功和当前的点赞总数（用户意图和第一次相同）。
     *
     * @param commentId 评论 id
     * @return 点赞之后的总数，供前端直接更新按钮上的数字
     */
    int like(Long commentId);

    /**
     * 删除自己的评论（逻辑删）。
     *
     * <p>错的、不是自己的、已经删过的，返回的码各不相同
     * （{@code 4001} / {@code 4002} / {@code 4003}），见实现类。
     *
     * <p><b>★ 这里【没有】管理员删评论的入口。</b>
     * 管理端对评论的处置手段是「隐藏」（{@code status = 2}）——
     * 隐藏会留痕（{@code audit_user_id} / {@code audit_time} / {@code audit_remark}），
     * 删除不会。让管理员用删除来处置内容，等于把处置记录一起删掉。
     */
    void delete(Long commentId);

    /* ================================================================
     *  管理侧
     * ================================================================ */

    /**
     * 评论列表（分页），<b>★★ 含已被逻辑删除的评论</b>。
     *
     * <p><b>为什么要看得见已删除的：</b>「读者的某条评论不见了」是一类真实诉求 ——
     * 有人投诉一条评论，管理员打开列表却找不到它，因为评论人自己先删了。
     * <b>「读者看不见了」和「无从查证」是两件事。</b>
     *
     * <p><b>★ 这个方法是全项目唯一一处不滤 {@code deleted} 的查询</b>
     * （手写 SQL，见 {@code NovelCommentMapper#selectAdminPage}）。
     * 所以每行的 {@code deleted} 标记必须原样返回给前端 ——
     * 否则管理员会对一条已经不存在的内容点「隐藏」，而后端返回成功
     * （{@code selectById} 带 {@code deleted = 0}，改的是不存在的行）。
     *
     * @param status  状态筛选，{@code null} = 全部
     * @param novelId 作品筛选，{@code null} = 全部
     * @param keyword 正文关键词，{@code null} / 空 = 不筛
     */
    PageResult<CommentVO> adminPage(CommentStatus status, Long novelId, String keyword,
                                    long page, long size);

    /**
     * 审核：通过（→ 已显示）或隐藏（→ 已隐藏）。
     *
     * <p><b>★ 状态流转必须过 {@code CommentStatus.canTransitTo}。</b>
     * 少了这一步，「已显示的又被改成待审核」这类脏数据就会进库，
     * 而且它<b>同时</b>产生两个后果：评论从读者眼前消失、待审队列里多一条 ——
     * 排查时看到的是「凭空多出来的待审评论」，很难联想到是状态被改回去了。
     *
     * <p><b>★ 重复审核（比如把「已显示」的再点一次通过）会返回
     * {@code COMMENT_STATUS_ILLEGAL(4005)}，不是静默成功。</b>
     * 这和 {@code NovelService#transitStatus} 的行为一致 ——
     * 「幂等」和「明确报错」二选一，本项目选后者：
     * 静默成功会让管理员以为「刚才那一下没点上」，然后再点，
     * 而真正的原因（界面上这条已经是那个状态了）永远不会被发现。
     *
     * <p><b>★ 记一条 {@code audit_log}</b>（{@code AuditTargetType.COMMENT}），
     * 和作品 / 章节的审核一样。所以「谁在什么时候隐藏了哪条评论」
     * 在审核日志页查得到。
     * <p><b>但读者发表评论【不记】</b> —— 见实现类的注释：
     * {@code audit_log} 记的是<b>状态流转</b>，而评论是<b>一出生就是待审核</b>的，
     * 没有「提交」这一次流转（对照作品：先建为草稿，再提交审核，那才是一次流转）。
     */
    void audit(Long commentId, CommentAuditDTO dto);
}
