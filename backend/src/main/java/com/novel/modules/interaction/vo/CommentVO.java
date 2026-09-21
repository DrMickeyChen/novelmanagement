package com.novel.modules.interaction.vo;

import com.novel.modules.interaction.entity.NovelComment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论出参。<b>读者端和管理端共用一个类，但必须走两个不同的工厂方法。</b>
 *
 * <p><b>★★ 为什么是两个工厂方法，而不是一个带 {@code boolean admin} 参数的方法：</b>
 * 两边的字段集<b>不一样</b>，而且差的正是最敏感的那几个：
 * <pre>
 *   forReader —— 正文 / 评分 / 点赞数 / 时间 / 昵称 / 已赞态 / 是不是本人
 *   forAdmin  —— 正文 / 评分 / 点赞数 / 时间 / 昵称 + userId / status / statusDesc / auditTime / auditRemark / deleted
 * </pre>
 * <p>★ 注意 {@code liked} 和 {@code mine} <b>只有读者端有</b>，
 * 因为它们都是「<b>相对于当前登录用户</b>」的事实 —— 管理端列表里
 * 每一行说的是别人，这两个字段放在那里没有意义。
 * 一个布尔参数的做法，其默认值无论取哪个都是错的：
 * 默认 {@code false}（当读者处理）→ 管理端忘了传就少一半字段，界面上是空列；
 * 默认 {@code true}（当管理员处理）→ <b>读者端忘了传就把 {@code auditRemark}
 * 和 {@code userId} 发出去了</b>，而且不报错、测试也过（字段有值就是有值）。
 * <p>两个方法名把这件事变成编译期选择，写错方法名在 code review 里一眼可见。
 *
 * <p><b>★ 读者侧为什么不返回 {@code userId}：</b>
 * 和 {@code ReadHistoryVO} 同一条规矩 —— 调用方不需要它（他看的是评论，
 * 不是用户 id），返回它却给了「把用户 id 暴露到前端」一个理由，
 * 而一旦暴露，就会有人拿它去拼别的接口。作者身份用 {@code nickname} 表达就够了。
 * <p><b>★ 读者侧为什么不返回 {@code status}：</b>
 * 那个接口只返回 {@code status = 1} 的评论（写在 SQL 的 WHERE 里，
 * 不是查出来再过滤），所以这一列恒等于 1，是个常量。
 * 返回一个恒定的字段，只会让前端误以为「可以根据它做判断」。
 *
 * <p><b>★ 读者侧为什么不返回 {@code auditRemark}：</b>
 * 它是管理员写给自己看的备注（见 {@code CommentAuditDTO#remark}），
 * 内容可能是「这条疑似水军，先留着观察」—— 发给读者是事故。
 *
 * <p><b>★★ {@code nickname} 可能为空，而且 JSON 里会【整个 key 消失】。</b>
 * 全局配了 {@code default-property-inclusion: non_null}，
 * 而评论人可能已被注销（{@code sys_user.deleted = 1}，{@code ContentUserLookup}
 * 查不到）。前端必须写 {@code row.nickname ?? '已注销用户'}，
 * <b>不要写 {@code row.nickname === null}</b> —— 那个判断永远不成立。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "评论")
public class CommentVO {

    @Schema(description = "评论 id")
    private Long id;

    /**
     * 评论对象类型：<b>1 书评（{@code targetId} 是作品 id）、2 章评（是章节 id）</b>。
     *
     * <p><b>★★ P5 时这里叫 {@code novelId}，P6 拆成了两个字段。</b>
     * 拆的理由不是「不准确」，而是<b>保留一个叫 {@code novelId} 却可能装
     * {@code chapterId} 的字段，前端会拿它去查作品详情 —— 而且查得到一个
     * 不相干的 id</b>（作品 100101 真的存在，只是那是另一个人的书），
     * 于是页面上显示的是别人作品的标题，请求本身却完全合法。
     * <p>P5 就把它写成了警告放在这里，P6 照做。★ 拆的成本当时是零
     * （reader-web 还没调用过评论接口），现在也是。
     */
    @Schema(description = "评论对象类型：1书评 2章评", example = "1")
    private Integer targetType;

    /** 评论对象的 id（书评是作品 id，章评是章节 id）。★ 和 {@link #targetType} 一起才有意义 */
    @Schema(description = "评论对象 id：书评时是作品 id，章评时是章节 id")
    private Long targetId;

    /** 类型文案「书评」/「章评」。★ 仅管理端返回（读者端已经知道自己在看哪个评论区） */
    @Schema(description = "评论对象类型文案。★ 仅管理端返回", example = "书评")
    private String targetTypeDesc;

    @Schema(description = "评论正文")
    private String content;

    /** 评分 1~5。★ 没打分时为 null，JSON 里 key 会消失，前端写 {@code ?? null} */
    @Schema(description = "评分 1~5，未打分为空")
    private Integer score;

    @Schema(description = "点赞数")
    private Integer likeCount;

    /**
     * 当前登录用户<b>是否赞过这一条</b>。★ 只有读者端、且已登录才返回。
     *
     * <p><b>★★ 匿名时是 {@code null}（JSON 里 key 消失），不是 {@code false}。</b>
     * {@code false} 的意思是「这个人没赞过」，而匿名请求里我们<b>根本不知道他是谁</b> ——
     * 同 {@link #deleted} 用 {@code Boolean} 的理由。前端写 {@code row.liked ?? false}。
     *
     * <p><b>★ 这一列【不在库里】，是查 Redis 查出来的</b>：谁赞过存在
     * {@code novel:like:user:{commentId}} 这个 Set 上（全库没有 {@code comment_like} 表）。
     * 一页评论一次 pipeline 批量查，见 {@code CommentServiceImpl#fillLiked}。
     */
    @Schema(description = "当前用户是否已点赞。★ 未登录时不返回")
    private Boolean liked;

    /**
     * 这一条是<b>不是当前登录用户自己写的</b>。★ 只有读者端、且已登录才返回。
     *
     * <p><b>★★ 为什么必须有它：</b>读者端要显示「删除」按钮，而后端对
     * 别人的评论返回 {@code 4002 只能删自己的评论}。让前端「先显示、点了再说」
     * 的代价是<b>每条别人的评论上挂一个必然失败的按钮</b> ——
     * 用户点它、收到一句「只能删自己的评论」，然后开始怀疑这个站是不是坏了。
     * <p>而前端<b>判断不了</b>这件事：{@link #userId} 是刻意不给读者端的
     * （见类注释），{@link #nickname} 又只是昵称 —— 两个人都叫「书友」是常态，
     * 拿昵称当判据会<b>让用户删掉陌生人的评论</b>（后端会拦，但前端那一瞬间
     * 已经把人吓到了）。所以「是不是我写的」只能由服务端算好告诉我们。
     *
     * <p><b>★ 匿名时是 {@code null}（key 消失），不是 {@code false}</b> ——
     * 同 {@link #liked}：{@code false} 的意思是「这不是你写的」，
     * 而匿名请求里我们根本不知道他是谁。前端写 {@code row.mine ?? false}，
     * 于是游客看不到任何删除按钮（他本来也删不了）。
     */
    @Schema(description = "是否是当前用户自己写的。★ 未登录时不返回")
    private Boolean mine;

    @Schema(description = "发表时间")
    private LocalDateTime createTime;

    /**
     * 这条一级评论<b>一共有多少条二级回复</b>（只数读者看得见的）。
     *
     * <p><b>★ 为什么需要它、而不是让前端看 {@link #replies} 的长度：</b>
     * {@code replies} 是<b>截断过的</b>（最多 {@code INLINE_REPLY_LIMIT} 条），
     * 长度只说明「这次带回来几条」。要渲染「还有 5 条回复」必须知道总数，
     * 而让前端拿长度当总数，表现是<b>「展开」按钮永远不出现</b>——
     * 因为长度永远不超过 3。
     *
     * <p>★ 二级回复自己这一项恒为 {@code 0}（回复没有回复，见
     * {@code COMMENT_REPLY_NOT_ALLOWED(4006)}），但<b>不会为 null</b>：
     * 前端在同一个位置渲染两种行时，少一个判空就够呛。
     */
    @Schema(description = "二级回复总数。一级评论才有意义")
    private Integer replyCount;

    /**
     * 内联返回的二级回复（最多几条，见 {@code CommentServiceImpl#INLINE_REPLY_LIMIT}），
     * <b>按时间正序</b> —— 回复是对话，从早到晚读才对。
     *
     * <p><b>★ 只有一级评论才有这个字段，二级回复自己是 {@code null}
     * （JSON 里 key 消失）。</b>两级封顶是产品决定，不是实现限制。
     * <p><b>★ 没有回复时是空数组 {@code []}，不是 null。</b>
     * 列表页拿到 {@code undefined} 和 {@code []} 都要写一遍兜底，
     * 能确定的就确定下来（同 {@code ShelfItemVO#available} 的取向）。
     *
     * <p>★ 要看全部回复<b>要另外调</b> {@code GET /api/novels/{id}/comments/{cid}/replies}
     * —— 不能在已经分页的接口上假装全都有。前端点「展开」时重新请求。
     */
    @Schema(description = "内联的二级回复（最多 3 条）。仅一级评论返回")
    private List<CommentVO> replies;

    /** ★ 仅管理端返回。读者端为 null（JSON 里 key 消失）—— 见类注释 */
    @Schema(description = "评论人 id。★ 仅管理端返回")
    private Long userId;

    @Schema(description = "评论人昵称。评论人已注销时为空")
    private String nickname;

    /**
     * 审核状态：0待审 1已显示 2已隐藏。<b>以数字对外</b>
     * （{@code CommentStatus.code} 上的 {@code @JsonValue} 决定）。★ 仅管理端返回。
     *
     * <p>★ <b>只用于判断，不要拿它显示。</b>要显示用下面的 {@code statusDesc} ——
     * 和 {@code NovelVO#status} / {@code ChapterVO#status} 是同一条规矩。
     */
    @Schema(description = "审核状态：0待审 1已显示 2已隐藏。★ 仅管理端返回", example = "0")
    private Integer status;

    /**
     * 审核状态的中文文案，如「待审核」。★ 仅管理端返回。
     *
     * <p><b>★ 为什么这里是文案而 {@code StatusDistVO} 用的是 {@code label}：</b>
     * 两个接口都在返回中文，名字不同是因为用途不同 ——
     * 那一个的字符串要同时当饼图的图例和系列的 {@code name}（两边必须逐字相同），
     * 这一个只是列表里的一个标签。<b>但它们都不该由前端翻译。</b>
     *
     * <p><b>★★ 这一条是 S9 补的，补的理由值得记下来：</b>
     * 最初只返回了数字，注释里还写了一段「文案交给前端
     * {@code utils/status.js} 翻译，后端返回中文会让码表分散在两处」。
     * 那段话<b>说反了</b>：{@code status.js} 的类注释第一句就是
     * 「这里<b>只有颜色</b>，没有文案，文案一律用后端返回的
     * {@code statusDesc}」—— 而 {@code NovelVO} / {@code ChapterVO} /
     * {@code OutlineChapterVO} 三个 VO 全都带 {@code statusDesc}。
     * 也就是说项目里的规矩是「文案后端给、颜色前端给」，
     * 而这一条是唯一的例外，例外本身就是 bug。
     * <p>★ 教训不是「注释写错了」，而是<b>同一件事在项目里必须只有一个说法</b>：
     * 当时如果前端照着那段注释真的又写了一份 {@code 0→'待审核'} 的映射表，
     * 那么「已显示」改叫「已通过」时就要改两个项目，而没人会记得。
     */
    @Schema(description = "审核状态文案，★ 仅供显示", example = "待审核")
    private String statusDesc;

    @Schema(description = "审核时间，未审核为空。★ 仅管理端返回")
    private LocalDateTime auditTime;

    @Schema(description = "审核备注。★ 仅管理端返回")
    private String auditRemark;

    /**
     * 是否已被逻辑删除。★ 仅管理端返回。
     *
     * <p><b>★ 用 {@code Boolean} 而不是 {@code boolean}：</b>
     * 读者侧要让它保持 {@code null}（JSON 里 key 消失），
     * 基本类型做不到 —— 它会变成 {@code false} 并被序列化出去，
     * 读者端于是收到一个恒为 {@code false} 的 {@code deleted} 字段。
     *
     * <p><b>★ 这个字段存在的唯一理由：管理端的列表包含已删除的评论</b>
     * （见 {@code NovelCommentMapper#selectAdminPage}）。
     * 不返回它的话，那些行看起来和正常评论一模一样，
     * 管理员会对一条已经不存在的内容做审核 —— 而更新会命中 0 行
     * （{@code selectById} 带 {@code deleted = 0}），返回一个「成功」。
     */
    @Schema(description = "是否已删除。★ 仅管理端返回")
    private Boolean deleted;

    /**
     * 读者端视图。★ <b>只喂这个方法的实体必须已经确认是 {@code status = 1}</b>
     * （调用方的查询条件里就有），这里不做二次过滤 ——
     * 过滤写两遍，其中一遍迟早会被改掉。
     *
     * <p>★ {@code replyCount} 默认 0、{@code replies} 留 {@code null}：
     * 一级评论的回复由 {@code CommentServiceImpl#withReplies} 统一填，
     * 二级回复<b>永远不该有</b>这两个字段（两级封顶）。默认值写在这里，
     * 是为了让「忘了填」表现为「显示 0 条回复」而不是前端拿到 {@code null}。
     *
     * <p><b>★ {@code viewerId} 是「看这一页的人」，不是评论人</b>
     * （评论人是 {@code entity.getUserId()}）—— 两个 id 传反了不会报错，
     * 只会让删除按钮<b>在别人的评论上出现、在自己的评论上消失</b>。
     * 传 {@code null} 表示匿名，{@link #mine} 保持 null。
     *
     * <p>★ {@code liked} 不在这里填，它要查 Redis（见
     * {@code CommentServiceImpl#fillLiked}）—— 这个方法是逐行调用的，
     * 在里面查就是一行一次往返。
     */
    public static CommentVO forReader(NovelComment entity, String nickname, Long viewerId) {
        if (entity == null) {
            return null;
        }
        CommentVO vo = new CommentVO();
        vo.setId(entity.getId());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setReplyCount(0);
        vo.setContent(entity.getContent());
        vo.setScore(entity.getScore());
        vo.setLikeCount(entity.getLikeCount());
        vo.setCreateTime(entity.getCreateTime());
        vo.setNickname(nickname);
        if (viewerId != null) {
            vo.setMine(viewerId.equals(entity.getUserId()));
        }
        return vo;
    }

    /**
     * 管理端视图。比读者端多 {@code userId} / {@code status} / {@code auditTime}
     * / {@code auditRemark} / {@code deleted}。
     *
     * <p>★ {@code deleted} 从 {@code Integer} 转成 {@code Boolean} 时必须
     * <b>先判 null</b>：这一列现在一定不为 null（{@code NOT NULL DEFAULT 0}），
     * 但将来若有人给实体加上 {@code @TableField(select = false)}
     * （像 {@code Novel} 那样），它就会恒为 null，
     * 而 {@code entity.getDeleted() == 1} 对 {@code Integer} 拆箱会直接 NPE。
     */
    public static CommentVO forAdmin(NovelComment entity, String nickname) {
        if (entity == null) {
            return null;
        }
        CommentVO vo = new CommentVO();
        vo.setId(entity.getId());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setTargetTypeDesc(NovelComment.targetTypeDesc(entity.getTargetType()));
        vo.setReplyCount(0);
        vo.setContent(entity.getContent());
        vo.setScore(entity.getScore());
        vo.setLikeCount(entity.getLikeCount());
        vo.setCreateTime(entity.getCreateTime());
        vo.setNickname(nickname);
        vo.setUserId(entity.getUserId());
        vo.setStatus(entity.getStatus() == null ? null : entity.getStatus().getCode());
        vo.setStatusDesc(entity.getStatus() == null ? null : entity.getStatus().getDesc());
        vo.setAuditTime(entity.getAuditTime());
        vo.setAuditRemark(entity.getAuditRemark());
        vo.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        return vo;
    }
}
