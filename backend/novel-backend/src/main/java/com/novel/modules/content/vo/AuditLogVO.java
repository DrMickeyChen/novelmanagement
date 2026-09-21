package com.novel.modules.content.vo;

import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.modules.content.entity.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核流水出参，服务 {@code GET /api/admin/logs}。
 *
 * <p><b>★ 这张表是只追加的流水，所以 VO 里不要有「编辑 / 删除」需要的字段</b> ——
 * 没有 {@code updateTime}、没有状态位。流水一旦能被改写就失去了追责价值。
 * 出参里出现 {@code updateTime} 会误导前端去做一个「编辑备注」的按钮，
 * 而后端根本没有对应的接口。
 *
 * <p><b>★★ 这个 VO 里最值得说明的是 {@link #targetTitle} 和 {@link #operatorName}
 * 这两个「不在 audit_log 表里」的字段。</b>
 * 数据库里 {@code audit_log} 只存了 {@code target_id} 和 {@code operator_id}
 * 两个裸 id（<b>没有外键</b>，这是流水表的常态 —— 外键会挡住「删掉的书」的日志）。
 * 只返回 id 的话，管理端的「流水查询」页显示出来是这样：
 * <pre>
 *   作品 #5   通过   操作人 #1
 * </pre>
 * 审核员看到这行字<b>完全不知道发生了什么</b>，还得自己去作品列表翻 id=5 是谁。
 * 日志页的价值就是「一眼看清」，所以书名和人名必须由后端补上。
 *
 * <p><b>代价：每页多两条查询</b>（一次批量查书名、一次批量查昵称），
 * 不是每行两条 —— 调用方必须用批量接口（{@code ContentUserLookup#nicknamesOf}
 * 就是为这个写的），<b>不要在转换时逐条查</b>。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "审核流水")
public class AuditLogVO {

    @Schema(description = "流水 id", example = "1")
    private Long id;

    /**
     * 审核对象类型：1作品 2章节 3评论 4作者申请。
     *
     * <p>★ 数字对外（{@code AuditTargetType.code} 上的 {@code @JsonValue}）。
     * P2 只会产生 1 和 2，3/4 是 P5/P6 的。
     */
    @Schema(description = "审核对象类型：1作品 2章节 3评论 4作者申请", example = "1")
    private AuditTargetType targetType;

    /** 类型文案，如「作品」。★ 只用于显示 */
    @Schema(description = "类型文案，★ 仅供显示", example = "作品")
    private String targetTypeDesc;

    /**
     * 审核对象的 id。
     *
     * <p><b>★ 这个字段必须始终返回，不能因为查到了 {@link #targetTitle} 就省掉它</b> ——
     * 它是「这本书后来被删了」时唯一还能定位到对象的东西。
     * 前端要能从日志行跳到作品详情，靠的就是 {@code targetType + targetId}。
     */
    @Schema(description = "审核对象的 id", example = "5")
    private Long targetId;

    /**
     * 审核对象的标题（书名 / 章节名）。
     *
     * <p><b>★ 可能是 {@code null}，而且这很正常，前端必须处理：</b>
     * 作品或章节被<b>逻辑删除</b>之后，按 id 就查不到它了
     * （{@code @TableLogic} 会自动加上 {@code deleted = 0}），
     * 于是这一列补不出标题。
     * <p>这是<b>有意接受的行为，不要去「修」</b>：
     * 为了补出已删除对象的标题而写一条绕过 {@code @TableLogic} 的原生 SQL，
     * 等于让审核日志能看到已删除的数据 —— 那正是逻辑删除想遮住的东西。
     * 流水里的 {@code targetId} 还在，账依然查得到，
     * 只是要显示成「作品 #5（已删除）」。
     *
     * <p>又因为全局配了 {@code default-property-inclusion: non_null}，
     * <b>补不出来时这个字段在 JSON 里直接不出现</b>，
     * 前端写 {@code row.targetTitle || `${row.targetTypeDesc} #${row.targetId}`} 即可。
     */
    @Schema(description = "审核对象标题（书名/章节名）。★ 对象已删除时为空", example = "测试书")
    private String targetTitle;

    /**
     * 动作：<b>1提交 2通过 3驳回 4下架 5重新上架 6退回草稿</b>。
     *
     * <p><b>★ 1/2/3 对齐数据库列注释，4/5/6 是在那三值上的扩展</b>
     * （列注释只写了「1提交 2通过 3驳回」）。详见 {@code AuditAction} 的类注释。
     *
     * <p>★ 统计「审核通过率」时<b>只能数 1/2/3</b>，
     * 把上下架也算进去会让分母混进非审核动作。
     */
    @Schema(description = "动作：1提交 2通过 3驳回 4下架 5重新上架 6退回草稿", example = "2")
    private AuditAction action;

    /** 动作文案，如「通过」。★ 只用于显示 */
    @Schema(description = "动作文案，★ 仅供显示", example = "通过")
    private String actionDesc;

    /** 操作人 {@code sys_user.id}。★ 同 {@link #targetId}，始终返回 */
    @Schema(description = "操作人 id", example = "1")
    private Long operatorId;

    /**
     * 操作人昵称。
     *
     * <p>★ 用<b>昵称</b>而不是登录名：日志页是给人看的，
     * 显示「系统管理员」比显示「admin」更清楚；
     * 而且日志页将来可能开放给非管理员角色看（比如作者看「我的作品被谁审过」），
     * 那时登录名不该跟着出去。
     *
     * <p>和 {@link #targetTitle} 不同，<b>这个基本不会为空</b> ——
     * {@code sys_user} 只封禁不删除（登录校验查的是 {@code status}），
     * 所以按 id 总能查到人。真为空时前端显示 {@code 操作人 #1}。
     */
    @Schema(description = "操作人昵称", example = "系统管理员")
    private String operatorName;

    /**
     * 备注。
     *
     * <p>驳回时是<b>驳回理由</b>（作者能看到它，所以措辞要经得起被看到），
     * 通过时一般是空。下架时管理端可以写明原因 ——
     * 「书怎么突然不见了」正是靠这一列查出来的，这也是 {@code AuditAction}
     * 要扩展 4/5 两个值的理由。
     */
    @Schema(description = "备注。驳回时是驳回理由", example = "简介与正文不符，请修改后重新提交")
    private String remark;

    /** 发生时间。★ 日志页按它倒序排，格式由 {@code JacksonConfig} 统一 */
    @Schema(description = "发生时间", example = "2026-09-20 10:30:00")
    private LocalDateTime createTime;

    /**
     * 从实体转换。
     *
     * <p><b>{@code targetTitle} / {@code operatorName} 由调用方批量查好后传进来</b> ——
     * 这个 VO 不查库。一页 20 条如果在这里逐条查，就是 40 次查询，
     * 而调用方可以两次 {@code selectBatchIds} / 一次 {@code IN} 查完。
     *
     * @param targetTitle  审核对象标题，补不出来传 {@code null}
     * @param operatorName 操作人昵称，查不到传 {@code null}
     */
    public static AuditLogVO of(AuditLog entity, String targetTitle, String operatorName) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(entity.getId());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetTypeDesc(entity.getTargetType() == null ? null : entity.getTargetType().getDesc());
        vo.setTargetId(entity.getTargetId());
        vo.setTargetTitle(targetTitle);
        vo.setAction(entity.getAction());
        vo.setActionDesc(entity.getAction() == null ? null : entity.getAction().getDesc());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(operatorName);
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
