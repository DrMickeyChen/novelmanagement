package com.novel.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 违禁审核结果，对应 {@code ai_audit_result} —— <b>E1/S8 起第一次被 Java 引用</b>
 * （P0 就建好了表，前两期零引用）。
 *
 * <h3>★★★ 读这张表之前必须先知道的三条语义（DDL 里也写着，这里再钉一遍）</h3>
 *
 * <ol>
 *   <li><b>【没有这一行】= 没审成，不是 {@code risk_level=0}。</b>
 *       {@code risk_level} 是 {@code NOT NULL} 且没有「未知」取值 ——
 *       这一列<b>在结构上容不下「审核没跑成」</b>，所以「没审成」只能表达为「没有行」。
 *       ★★ 判据：如果哪天有人给失败的审核补一个 {@code risk_level=0}，
 *       那就是<b>把「审核失败」渲染成了「审核通过」</b> —— 本系统最坏的一种假话。</li>
 *   <li><b>{@code status=2} 且 {@code audit_user_id IS NULL} = 机器排除的。</b>
 *       必须靠 {@code audit_user_id} 分开「机器判无风险、直接排除」与「人看过之后排除」：
 *       前者在页面上要显示成「机器排除」而不是「已排除」——
 *       说「已排除」等于<b>替一个人认领了一个他没做过的动作</b>。
 *       ★ {@code ai_usage_log} 那套 {@code user_id=0} 的哨兵在这里用不上：
 *       这一列<b>可空</b>，{@code NULL} 本就是准确表达。</li>
 *   <li><b>「判的是不是当前这一版」用【已有的两个时间戳】回答</b>：
 *       本行的 {@code create_time}（AI 判定时间）与该章节/评论的 {@code update_time}。
 *       ★ <b>不加 {@code content_hash} 列</b> —— 本仓库没有 ALTER 惯例（E1 的 R15），
 *       而「改了 CREATE 语句」对已有的 dev 库<b>不会生效、也不会报错</b>。</li>
 * </ol>
 *
 * <h3>★ 这个实体里【没有】{@code updateTime}，也【没有】{@code @TableLogic} —— 不是漏了</h3>
 *
 * <p>表里就没有这两列（{@code deleted} 也没有，同 {@code ai_usage_log}）。
 * ★ 多写一个不存在的字段，症状是 <b>INSERT/UPDATE 报 {@code Unknown column}</b> ——
 * 而报错信息里看不出是实体多了一个字段。
 * <p>★★ 而且「同一 {@code (target_type, target_id)} 就地覆盖」<b>不能</b>用
 * {@code updateById}：它在「字段合法地变成 {@code null}」时会静默跳过
 * （{@code ai_comment} 为空、{@code audit_user_id} 要清空都是这种），
 * 所以覆盖走的是 {@code AiAuditResultMapper} 上那条显式 UPDATE，<b>哪一列写什么一目了然</b>。
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_audit_result")
public class AiAuditResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待人工确认 —— 关键词命中或 AI 判有风险，<b>进人工队列</b>。
     * <p>★ 只有这一档会被人处置（S9 的联动 UPDATE 的乐观条件就是 {@code status=0}）。
     */
    public static final int STATUS_PENDING = 0;

    /** 人工确认违规（由 {@code audit_log} 的唯一汇聚点联动写入，见 S9） */
    public static final int STATUS_CONFIRMED = 1;

    /** 排除。★ 机器排除时 {@code audit_user_id} 为 {@code NULL}，见类注释第 ② 条 */
    public static final int STATUS_EXCLUDED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1 小说 2 章节 3 评论。★ 与 {@code AuditTargetType} 的码对齐；这里存裸值，与 DDL 一致 */
    private Integer targetType;

    private Long targetId;

    /** 0 无风险 1 低 2 中 3 高。★ {@code NOT NULL} —— 见类注释第 ① 条 */
    private Integer riskLevel;

    /**
     * 命中的关键词，逗号分隔。
     * <p>★★ <b>只来自【我们自己的】词库扫描，不采信模型回显的 {@code hitWords}。</b>
     * 模型可能改写词形、可能凭空补一个（它见过的词比词库多），
     * 而这一列是管理员点「去处置」时唯一的依据 —— 它必须与词库可对齐。
     */
    private String hitWords;

    /**
     * AI 给出的判定理由。
     * <p>★ <b>可以为空，而且空是有信息的</b>：关键词层命中但 AI 层没跑成
     * （词库不可用 / 渲染超长 / 上游 4xx）时，这一格就是空，而 {@code risk_level}
     * 仍然带着关键词层的等级 —— 那是一条<b>只有第一层的判定</b>，不是没审。
     */
    private String aiComment;

    /** 见 {@code STATUS_*} 常量 */
    private Integer status;

    /** ★ 处置人。{@code NULL} + {@code status=2} = 机器排除 */
    private Long auditUserId;

    /** 处置时间 */
    private LocalDateTime auditTime;

    /**
     * AI 判定时间。★ 由 {@code MybatisMetaObjectHandler} 填，<b>覆盖写的时候会被刷成新值</b> ——
     * 那正是它该表达的：这一行现在记的是<b>最新一次</b>判定的时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
