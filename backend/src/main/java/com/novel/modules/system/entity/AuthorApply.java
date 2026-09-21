package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.common.enums.AuditStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作者申请，对应 {@code author_apply}。读者提交，管理员审核，通过后拿到 AUTHOR 角色。
 *
 * <p><b>★ 这张表归 {@code modules/system}，不归 {@code modules/content}。</b>
 * 判据不是「它写了什么」，而是<b>它改了谁</b>：审核通过的副作用是往
 * {@code sys_user_role} 里插一行 —— 改的是「用户的角色」，
 * 而用户和角色都是 {@code modules/system} 的地盘。
 * <p>放进 content 的话，content 就得反过来写 system 的表，
 * 那正是 {@code ContentUserLookup} 的类注释里明令禁止的方向。
 *
 * <p><b>★ {@code status} 用 {@link AuditStatus}，不是 {@code CommentStatus}。</b>
 * 两个枚举的码值正好都是 0/1/2，写混了不会报错 ——
 * 但「申请的状态」和「评论的状态」从此由一个枚举表示，改一处影响两处。
 * {@code AuditStatus} 的类注释里点名了它的两个用武之地，这就是其中一个
 * （另一个是 {@code novel_comment.status}）。
 *
 * <p><b>★★ 这张表【有】{@code update_time}，所以下面那个字段是合法的。</b>
 * 别拿 {@code NovelComment} 当模板 —— 那张表没有这一列，实体上也就没有这个字段。
 * 加错了的症状是 {@code Unknown column 'update_time' in 'field list'}，
 * 而且报错时机是「管理员点通过的那一刻」，不是启动时。
 *
 * @author MickeyChen
 */
@Data
@TableName("author_apply")
public class AuthorApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 申请人 {@code sys_user.id}。
     *
     * <p>★ 审核日志里那条流水的 {@code target_id} 是【本表的 id】，不是这个字段 ——
     * 两者混用会把「申请 #5」显示成「5 号用户的申请」，而且是另一个人的。
     * 见 {@code ContentUserLookup#authorApplyTitlesOf} 的注释。
     */
    private Long userId;

    /** 笔名，最长 50 字。★ 这是申请的唯一必填内容 —— 不填笔名不知道以什么名义写书 */
    private String penName;

    /**
     * 创作说明，最长 500 字，可空。
     *
     * <p>★ 列为空是有意的：强制写一段「创作说明」只会得到「我爱写作」这种凑数内容，
     * 而它看起来像一份有效的申请材料。审核员真正看的是笔名和申请人本身。
     */
    private String intro;

    /** 审核状态：0待审 1通过 2驳回。★ 用 {@link AuditStatus}，见类注释 */
    private AuditStatus status;

    /** 审核人 {@code sys_user.id}，未审核时为 null */
    private Long auditUserId;

    /** 审核时间，未审核时为 null */
    private LocalDateTime auditTime;

    /**
     * 审核备注。<b>驳回时是【给申请人看的理由】</b>，不是管理员的自留地。
     *
     * <p><b>★ 这一点和评论的 {@code audit_remark} 正好相反，是本模块最容易照抄错的地方：</b>
     * 评论的备注读者看不到（P5 没有反馈通道），所以前端连展示位都没有；
     * 而作者申请的备注<b>必须原样返回给申请人</b> —— 驳回之后他不看到理由，
     * 就只知道「被拒了」，既不知道改什么，也不知道能不能再申请。
     * <p>见 {@code AuthorApplyVO#forReader}：那个工厂方法【带】这个字段。
     */
    private String auditRemark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除。
     *
     * <p>★ P5 <b>没有任何接口会写它</b>（申请只能提交和审核，不能删）——
     * 加了 {@code @TableLogic} 是为了让所有查询自动带上 {@code deleted = 0}，
     * 手写 SQL 时也必须自己补（本模块目前一条手写 SQL 都没有，见
     * {@code AuthorApplyMapper}）。
     */
    @TableLogic
    private Integer deleted;
}
