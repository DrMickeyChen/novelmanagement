package com.novel.modules.interaction.vo;

import com.novel.modules.interaction.entity.NovelComment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论出参。<b>读者端和管理端共用一个类，但必须走两个不同的工厂方法。</b>
 *
 * <p><b>★★ 为什么是两个工厂方法，而不是一个带 {@code boolean admin} 参数的方法：</b>
 * 两边的字段集<b>不一样</b>，而且差的正是最敏感的那几个：
 * <pre>
 *   forReader —— 正文 / 评分 / 点赞数 / 时间 / 昵称
 *   forAdmin  —— 上面全部 + userId / status / statusDesc / auditTime / auditRemark / deleted
 * </pre>
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
     * 所属作品 id。
     *
     * <p><b>★ 它等于实体里的 {@code targetId}，因为 P5 只写书评
     * （{@code targetType = 1}）。</b>
     * <p><b>★★ P6 加章评时，这个字段必须拆成 {@code targetType} + {@code targetId}，
     * 不要保留一个叫 {@code novelId} 却可能装 {@code chapterId} 的字段。</b>
     * 那种字段的坏处不是「不准确」，而是<b>前端拿它去查作品详情时会查到一个
     * 不相干的 id</b>，而请求本身完全合法（作品 100101 真的存在，
     * 只是那是另一个人的书）—— 于是页面上显示的是别人作品的标题。
     * 这里提前写下这条，是因为改一个已有字段的语义，比加一个新字段难得多。
     */
    @Schema(description = "所属作品 id")
    private Long novelId;

    @Schema(description = "评论正文")
    private String content;

    /** 评分 1~5。★ 没打分时为 null，JSON 里 key 会消失，前端写 {@code ?? null} */
    @Schema(description = "评分 1~5，未打分为空")
    private Integer score;

    @Schema(description = "点赞数")
    private Integer likeCount;

    @Schema(description = "发表时间")
    private LocalDateTime createTime;

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
     */
    public static CommentVO forReader(NovelComment entity, String nickname) {
        if (entity == null) {
            return null;
        }
        CommentVO vo = new CommentVO();
        vo.setId(entity.getId());
        vo.setNovelId(entity.getTargetId());
        vo.setContent(entity.getContent());
        vo.setScore(entity.getScore());
        vo.setLikeCount(entity.getLikeCount());
        vo.setCreateTime(entity.getCreateTime());
        vo.setNickname(nickname);
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
        vo.setNovelId(entity.getTargetId());
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
