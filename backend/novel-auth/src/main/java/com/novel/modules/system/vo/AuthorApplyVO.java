package com.novel.modules.system.vo;

import com.novel.modules.system.entity.AuthorApply;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作者申请出参。<b>管理端和读者端共用一个类，但必须走两个不同的工厂方法。</b>
 *
 * <p><b>★★ 和 {@code CommentVO} 是同一套做法，但字段的取舍【正好相反】——
 * 这一条比字段本身重要：</b>
 * <pre>
 *   字段          评论（CommentVO）      作者申请（本类）
 *   ─────────────────────────────────────────────────────
 *   auditRemark   仅管理端              两端都有  ← ★ 反的
 *   status        仅管理端              两端都有  ← ★ 反的
 *   userId        仅管理端              仅管理端
 * </pre>
 * 两处「反的」各有各的理由，都不是笔误：
 * <ul>
 *   <li><b>{@code auditRemark}：</b>评论的备注是管理员写给自己看的
 *       （「这条疑似水军，先留着观察」），发给读者是事故；
 *       而作者申请的备注<b>就是驳回理由</b>，申请人不看到它就只知道
 *       「被拒了」，既不知道改什么，也不知道还能不能再申请。
 *       <b>同一个字段名，一边是内部备注、一边是要送达的通知。</b></li>
 *   <li><b>{@code status}：</b>评论的读者侧接口只查 {@code status = 1}
 *       （写在 SQL 的 WHERE 里），所以那一列恒等于 1，返回它只会让前端
 *       误以为「可以根据它做判断」；而 {@code /api/reader/author-apply/mine}
 *       返回的正是申请人<b>自己的</b>那一份，三种状态都要显示
 *       （待审 → 「审核中，请耐心等待」；通过 → 「去创作中心」；
 *       驳回 → 「原因如下，可重新提交」）。</li>
 * </ul>
 * <p>★ 结论不是「评论那样做错了」，而是<b>字段该不该外露取决于「谁需要它」，
 * 不取决于字段本身叫什么</b>。照抄另一个 VO 的字段表是这里最容易犯的错。
 *
 * <p><b>★★ 不返回 {@code deleted}，这是有意的。</b>
 * 评论的列表要显示「已删除」标记（那条查询刻意不滤 {@code deleted}），
 * 所以 {@code CommentVO} 必须返回它。而作者申请<b>根本没有删除接口</b>，
 * 逻辑删除的行永远查不出来 —— 返回一个恒为 {@code false} 的字段，
 * 和返回恒为 1 的 {@code status} 是同一类错误。
 *
 * <p><b>★ 也不返回 {@code auditUserId}（审核人 id）。</b>
 * 申请列表里「谁审的」不是审核员要看的（他要看的是「审没审、什么结果」），
 * 而「谁在什么时候审的谁」这件事 {@code audit_log} 已经回答得更完整
 * （审核流水页有操作人昵称）。多返回一个 id，只会给前端一个
 * 「拿它去拼别的接口」的理由。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者申请")
public class AuthorApplyVO {

    @Schema(description = "申请 id")
    private Long id;

    /** ★ 仅管理端。读者端为 null（JSON 里 key 消失）—— 见类注释 */
    @Schema(description = "申请人 id。★ 仅管理端返回")
    private Long userId;

    /**
     * 申请人登录名。★ 仅管理端。
     *
     * <p>★ <b>昵称可以重复、可以改、可以为空，登录名不会。</b>
     * 审核一份申请时要判断「这个人是不是刚被驳回又交了一份」，
     * 昵称在这个场景里是不足以定位人的（见 {@code AdminUserController}：
     * 用户列表的关键词也是「用户名<b>或</b>昵称」，两个都给才好用）。
     */
    @Schema(description = "申请人登录名。★ 仅管理端返回", example = "reader01")
    private String username;

    @Schema(description = "申请人昵称。申请人已注销时为空。★ 仅管理端返回")
    private String nickname;

    @Schema(description = "笔名", example = "墨白")
    private String penName;

    @Schema(description = "创作说明")
    private String intro;

    /**
     * 审核状态：0待审 1已通过 2已驳回。<b>以数字对外</b>
     * （{@code AuditStatus.code} 上的 {@code @JsonValue} 决定）。
     *
     * <p>★ <b>只用于判断，不要拿它显示。</b>要显示用下面的 {@code statusDesc}。
     */
    @Schema(description = "审核状态：0待审 1已通过 2已驳回", example = "0")
    private Integer status;

    /** 审核状态文案，如「待审核」。★ 文案由后端给、颜色由前端给，这是全项目的规矩 */
    @Schema(description = "审核状态文案，★ 仅供显示", example = "待审核")
    private String statusDesc;

    @Schema(description = "提交时间")
    private LocalDateTime createTime;

    @Schema(description = "审核时间，未审核为空")
    private LocalDateTime auditTime;

    /** ★ 两端都返回，但含义不同 —— 见类注释里那张表 */
    @Schema(description = "审核备注：驳回时是给申请人看的理由")
    private String auditRemark;

    /**
     * 管理端视图。比读者端多 {@code userId} / {@code username} / {@code nickname}。
     *
     * <p><b>★ 用户名和昵称由调用方补齐，不在这里查库</b> ——
     * 这个方法每行会被调用一次，里面查库就是 N+1。
     * 批量补齐在 {@code AuthorApplyServiceImpl#toVOList} 里做（两次查询搞定一页）。
     * <p>{@code AdminUserVO#of} 出于同样的理由收 {@code roles} 而不是自己查。
     */
    public static AuthorApplyVO forAdmin(AuthorApply entity, String username, String nickname) {
        if (entity == null) {
            return null;
        }
        AuthorApplyVO vo = base(entity);
        vo.setUserId(entity.getUserId());
        vo.setUsername(username);
        vo.setNickname(nickname);
        return vo;
    }

    /**
     * 读者端视图 —— <b>只给申请人看自己的那一份</b>。
     *
     * <p><b>★★ 「只给自己看」这件事不靠这个方法保证，靠接口保证：</b>
     * {@code /api/reader/author-apply/mine} 的查询条件里写死了
     * {@code user_id = 当前登录用户}。这个方法<b>不做归属校验</b> ——
     * 它拿不到当前用户，也没有「当前用户」这个概念。
     * <p>所以：<b>任何用 {@code forReader} 返回别人申请的代码都是漏洞</b>，
     * 而且它不会报错（返回的字段看起来一切正常，只是那个人的笔名和驳回理由）。
     * 这和 {@code CommentVO#forReader} 的约定完全一致：
     * 视图方法负责裁剪字段，不负责鉴权。
     */
    public static AuthorApplyVO forReader(AuthorApply entity) {
        return base(entity);
    }

    /**
     * 两端共有的字段。
     *
     * <p>★ 抽出来不是为了省几行，而是为了让「哪些字段是共有的」这件事
     * <b>只有一个答案</b>：将来有人要给读者端加一个字段，
     * 改这里就会同时影响两端，他会立刻意识到这是个需要判断的决定，
     * 而不是在两份复制粘贴的代码里改了一份。
     */
    private static AuthorApplyVO base(AuthorApply entity) {
        AuthorApplyVO vo = new AuthorApplyVO();
        vo.setId(entity.getId());
        vo.setPenName(entity.getPenName());
        vo.setIntro(entity.getIntro());
        /*
         * ★ 先判 null 再取 code/desc，不写成 entity.getStatus().getCode()。
         *   status 列是 NOT NULL，所以今天不可能为 null —— 但这是一次
         *   「拆箱就崩」的写法，而崩溃的代价是整个接口 500。
         *   CommentVO#forAdmin 对同一件事写了同样的判断，保持一致。
         */
        vo.setStatus(entity.getStatus() == null ? null : entity.getStatus().getCode());
        vo.setStatusDesc(entity.getStatus() == null ? null : entity.getStatus().getDesc());
        vo.setCreateTime(entity.getCreateTime());
        vo.setAuditTime(entity.getAuditTime());
        // ★ 备注在两端都返回，理由见类注释（驳回理由必须送达申请人）
        vo.setAuditRemark(entity.getAuditRemark());
        return vo;
    }
}
