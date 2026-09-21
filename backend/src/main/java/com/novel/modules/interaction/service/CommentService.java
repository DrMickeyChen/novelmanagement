package com.novel.modules.interaction.service;

import com.novel.common.enums.CommentStatus;
import com.novel.common.result.PageResult;
import com.novel.modules.interaction.dto.CommentAuditDTO;
import com.novel.modules.interaction.dto.CommentCreateDTO;
import com.novel.modules.interaction.dto.CommentReplyDTO;
import com.novel.modules.interaction.vo.CommentVO;

/**
 * 评论服务。<b>读者侧和管理侧共用一个接口</b> —— 它们操作的是同一张表、
 * 同一套状态机，拆成两个接口只会让「改状态」那段逻辑有两个入口。
 *
 * <p><b>★★ 十个方法分成两组，分界线是「谁在调」：</b>
 * <pre>
 *   读者侧（{@code ReaderCommentController}，前八个）
 *     userId 一律取自 {@code UserContext}，方法签名里【没有】userId 参数
 *     ★ P6 补了四个：章评列表 / 发章评 / 回复 / 展开回复。
 *     书评和章评是【两个方法】、不是给已有那个加一个 targetType 参数 ——
 *     参数化的版本里，「把 chapterId 传进 novelId 那个位置」是编译器
 *     完全看不出来的错误（两个都是 Long），写出来能编译、能跑、
 *     只是永远查不到东西。
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
     * 某一章的章评列表（分页），<b>只返回已显示的</b>，每条一级评论带
     * 最多 3 条二级回复和回复总数。
     *
     * <p><b>★ 和 {@link #readerPage} 是两条独立的方法，理由见类注释。</b>
     *
     * <p><b>★ 也刻意【不校验】章节是否存在 / 是否发布</b> —— 一字不差地
     * 照搬 {@link #readerPage} 的理由：可见性写在 WHERE 里已经够了，
     * 而「3001 章节不存在」和「空列表」的差别对匿名调用者就是一份
     * 「某个 chapterId 真不真」的探针。★ 但<b>写入</b>（{@link #createChapterComment}）
     * 必须校验 —— 那才是有判据差别的那个方向：只读不校验没有代价，
     * 写入不校验就能给任意 id 造数据。
     *
     * @param chapterId 章节 id（章评的 {@code target_id}）
     */
    PageResult<CommentVO> chapterReaderPage(Long chapterId, long page, long size);

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
     * 发表章评。<b>和 {@link #create} 共用同一个 10 秒频控</b> ——
     * 两个接口是同一个「发评论」动作，各限各的等于额度翻倍。
     *
     * <p><b>★ 校验比发书评多两道（缺一不可）：</b>
     * 作品已上架 → 章节属于这本书 → 章节已发布。
     * 只按 {@code chapterId} 查的话，把 {@code chapterId} 换成别的书的章节
     * 就能给一本完全无关的书刷评论。三道校验的顺序和返回码
     * 逐字照抄 {@code HistoryServiceImpl#report}（同一个场景，不该有两套说法）。
     *
     * @param chapterId 章节 id，来自 URL 路径
     * @return 新评论的 id
     */
    Long createChapterComment(Long novelId, Long chapterId, CommentCreateDTO dto);

    /**
     * 回复一条一级评论（<b>只支持两级</b>）。
     *
     * <p><b>★★ {@code targetType} / {@code targetId} 从父评论【继承】，不从参数传。</b>
     * 回复一条书评和回复一条章评，在库里唯一的区别就是这个字段 ——
     * 让调用方自己填，就必然出现「父是书评、子填了章评」这种自相矛盾的行，
     * 而它在列表里<b>表现为这条回复干脆不出现</b>（查询按 target 过滤）。
     *
     * <p><b>★ 回复一条回复返回 {@code COMMENT_REPLY_NOT_ALLOWED(4006)}</b>，
     * 不静默挂到祖父下面。三级回复要做「回复 @某人」前缀和递归渲染，
     * 产品价值远小于成本，而<b>静默展平比明确拒绝更难解释</b>：
     * 用户明明回复的是 B，界面上却挂在 A 下面。
     *
     * <p><b>★ 不重新校验「作品已上架」</b>：父评论必须是「已显示」的，
     * 而那是管理员审过的 —— 目标在那一刻是合法的，而回复够不到
     * 一个没有任何评论指向的 id，所以它没法用来造数据。
     * （对照 {@link #createChapterComment}：那个的 target 直接来自 URL。）
     *
     * @param parentId 父评论 id
     * @param dto      只有正文，<b>没有 score</b> —— 回复打分会让
     *                 {@code novel.score} 被「嗯」「+1」这类回复反复稀释
     */
    Long reply(Long parentId, CommentReplyDTO dto);

    /**
     * 展开某条一级评论的全部二级回复（分页）。
     *
     * <p><b>★ 列表接口最多内联 3 条，这一条才是「还有 5 条回复」的去处。</b>
     * 没有它，前端只能假装那 3 条就是全部（而 {@code replyCount} 又说不是）。
     *
     * <p>★ 排序按 <b>id 正序</b>（从早到晚），和一级评论列表的倒序相反 ——
     * 一级评论是「最新的内容最值得看」，回复是一段对话，正着读才对。
     *
     * @param commentId 一级评论 id。★ 传一条二级回复的 id 会返回空列表，
     *                  不是报错（它确实没有回复）
     */
    PageResult<CommentVO> replyPage(Long commentId, long page, long size);

    /**
     * 点赞。<b>幂等</b> —— 同一个人对同一条评论点第二次不会重复计数，
     * 仍返回成功和当前的点赞总数（用户意图和第一次相同）。
     *
     * @param commentId 评论 id
     * @return 点赞之后的总数，供前端直接更新按钮上的数字
     */
    int like(Long commentId);

    /**
     * 取消点赞（幂等）。<b>返回取消之后的总数。</b>
     *
     * <p><b>★ 和 {@link #like} 对称，但幂等的方向相反：</b>
     * 「取消一条本来就没赞过的」是<b>成功</b>，不报错 ——
     * 用户的意图是「让它变成未赞」，而它已经是了。
     *
     * <p><b>★★ 不需要判「这条评论是不是我的」。</b>
     * Set 的成员是 userId，取消时删的只能是自己的那个成员，
     * 天然安全 —— 这一点和 {@link #delete}（必须判归属）不一样。
     *
     * <p><b>★ 计数是【重算】的，不是 -1：</b>
     * {@code like_count} 是 Set 基数的投影。Redis 的 Set 丢过一次之后，
     * 两者会分叉，而 -1 会把这个分叉原样保留下去。重算的代价是
     * 「数字可能变小」—— 那是修复，不是错误。见实现类里的完整论证。
     *
     * @return 取消之后的点赞总数（不会为负）
     */
    int unlike(Long commentId);

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
