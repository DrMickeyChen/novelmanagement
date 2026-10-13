package com.novel.modules.ai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 违禁审核的风险等级（E1/S7）。
 *
 * <h3>★★★ 它是【两张表共用】的一份词汇表</h3>
 *
 * <pre>
 *   ai_sensitive_word.level   1~3     第一层：关键词命中，直接给出等级
 *   ai_audit_result.risk_level 0~3    第二层：AI 复核后的判定（含 0 = 无风险）
 * </pre>
 * ★ {@code schema.sql} 里 {@code ai_sensitive_word.level} 的列注释逐字写着
 * 「与 {@code ai_audit_result.risk_level} 同刻度」，指的就是这一份。
 * ★★ <b>别在这里发明第二套刻度</b>：第一层命中的等级要作为<b>预填值</b>交给第二层，
 * 两套刻度之间就得写一个映射函数，而那个函数一旦漏改，
 * 症状是<b>风险等级静默地整体偏低</b>（比如把 3 映射成 2），没有任何地方会报错。
 *
 * <h3>★★ 为什么 {@link #NONE} 必须存在，而词库却用不到它</h3>
 *
 * <p>{@code ai_audit_result.risk_level} 是 {@code NOT NULL} 且<b>没有「未知」取值</b> ——
 * 所以「审过了、判无风险」只能表达为 {@code 0}。
 * ★ 而这正是判断 3.6 那条的落点：<b>「没审成」必须表达为「没有这一行」</b>，
 * 不能借 {@code 0} 来表示。<b>谁给审核失败补一个 {@code risk_level = 0}，
 * 谁就是把「审核失败」渲染成了「审核通过」。</b>
 *
 * <p>★ 而 {@code ai_sensitive_word.level} 用不到 {@code 0}：一条「无风险的违禁词」
 * 是自相矛盾的。所以词库那边<b>不</b>用本枚举做字段类型（见 {@code SensitiveWord} 的类注释），
 * 只用它取中文名。
 *
 * @author MickeyChen
 */
@Getter
public enum AiRiskLevel {

    /** ★ 只有 {@code ai_audit_result} 会用这个值 —— 语义是「审过了，判无风险」，不是「没审」 */
    NONE(0, "无风险"),

    LOW(1, "低"),

    MEDIUM(2, "中"),

    HIGH(3, "高");

    /**
     * 编号。<b>★ 同时是落库值与对外 JSON 值</b>（同 {@code AiScene}）：
     * <pre>
     *   {@code @EnumValue} → ai_audit_result.risk_level
     *   {@code @JsonValue} → 管理端接口里的 riskLevel
     * </pre>
     * ★ 编号必须与 {@code docs/sql/schema.sql} 里那两列的注释逐字一致：
     * 这份对应关系<b>一半在 SQL 里、一半在 Java 里，两边都不报错</b>。
     */
    @EnumValue
    @JsonValue
    private final Integer code;

    private final String label;

    AiRiskLevel(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * <b>给「读库出来的裸 TINYINT」用的宽松查表</b>：{@code null} 与未知编号都返回
     * {@code null}，<b>不抛异常</b>。
     *
     * <p>★★ <b>为什么它和 {@link #fromCode} 是两套语义，而不是「一个够宽的」：</b>
     * <ul>
     *   <li>{@code fromCode} 的对象是<b>用户输入</b>（{@code ?level=9}），
     *       那时 400 是准确的答案；</li>
     *   <li>这里对象是<b>库里已经存着的值</b>（{@code ai_sensitive_word.level} 是裸 TINYINT，
     *       没有 CHECK 约束）。★ 一行脏数据不该让<b>整个列表接口 500</b> ——
     *       那是拿「一条坏行」去惩罚「另外 50 条好行」，管理员连看都看不到。</li>
     * </ul>
     * ★★ 而返回 {@code null} 不是「降级成假数据」：调用方把 {@code levelLabel} 留空，
     * <b>裸数字 9 照样显示在列表上</b> —— 假话是「把 9 说成『低』」，不是「不说 9 是什么」。
     * 这正是铁律 10 的取向：<b>让取不到的东西消失，不要给它编一个看着正常的值。</b>
     */
    public static String labelOf(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(l -> l.code.equals(code))
                .findFirst()
                .map(AiRiskLevel::getLabel)
                .orElse(null);
    }

    /**
     * 按编号查等级，{@code null} 进 {@code null} 出。
     *
     * <p>★ 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException}
     * —— 与 {@code AiScene.fromCode} / {@code AuditTargetType.fromCode} 同形。
     * <b>它只用于用户输入；读库值请用 {@link #labelOf}</b>，理由见那个方法。
     */
    public static AiRiskLevel fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(l -> l.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的风险等级：" + code));
    }
}
