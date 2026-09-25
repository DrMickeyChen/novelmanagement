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
 * 违禁审核词库的一条词，对应 {@code ai_sensitive_word}（E1/S7）。
 *
 * <p>★★★ <b>这张表是词库的唯一真相来源 —— 别把词表放进代码或配置文件。</b>
 * 那是<b>业务数据</b>：要能追溯来源、能被运营增删改、要有 {@code enabled} 做灰度，
 * 三件事只有表能给。放进 {@code novel-common} 会把「运营改一个词」变成「发一次版」。
 *
 * <h3>★★ 三个字段有坑</h3>
 * <ol>
 *   <li><b>没有 {@code deleted} 列，也没有 {@code @TableLogic}</b> ——
 *       同 {@code AiPromptTemplate}：加注解会让每次查询都带 {@code deleted = 0}
 *       而那一列<b>不存在</b>。停用靠 {@link #enabled}，
 *       ★ <b>「删掉一个词」和「停用一个词」在审核上的效果一样</b>（都立刻不再命中），
 *       区别只在可追溯性 —— 所以产品上只提供停用（同 {@code novel_category} 的取法）。</li>
 *   <li>{@link #level} 的刻度与 {@code ai_audit_result.risk_level} <b>同一个</b>
 *       （{@code AiRiskLevel}：1 低 / 2 中 / 3 高）。
 *       ★ 别在这里发明第二套刻度：第一层命中的等级要直接作为第二层的
 *       <b>预填值</b>传给 AI 复核，两套刻度之间就得写一个映射函数，
 *       而那个函数一旦漏改，症状是<b>风险等级静默地整体偏低</b>。
 *       <br>★★ <b>但这一列刻意用 {@code Integer} 而不是 {@code AiRiskLevel}</b>：
 *       词库的取值是那个枚举的<b>真子集</b>（{@code NONE(0)} 用不到 ——
 *       「一条无风险的违禁词」是自相矛盾）。用枚举当字段类型，
 *       {@code level=0} 就变成<b>类型上合法</b>的一个值，只能靠业务代码再拦一道；
 *       而 {@code Integer} + {@code @Min(1) @Max(3)} 在<b>反序列化阶段</b>就挡掉了它，
 *       连一行业务校验都不用写。★ 判据是「这个枚举有没有一个取值在本场景里无意义」。</li>
 *   <li>{@link #word} 上有唯一键 {@code uk_word}。★ 它是<b>裸 {@code VARCHAR}</b>，
 *       而 MySQL 的 {@code utf8mb4_unicode_ci} 排序规则是<b>大小写与全半角不敏感</b>的 ——
 *       所以「ABC」和「abc」在<b>数据库层面就已经是同一个词</b>，
 *       而内存里的 DFA 是<b>大小写敏感</b>的精确匹配。
 *       ★★ 两边判据不一致，但方向是安全的：库比内存<b>更严</b>，
 *       所以最坏情况是「管理员想加一个只差大小写的词，被 5008 挡回来」，
 *       而不是「库里有两个词、内存里只剩一个」。（S7 的实证见 docs/E1）</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_sensitive_word")
public class SensitiveWord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 违禁词。★ 上限 64 与列宽对齐：不对齐就是「一路传到 MySQL 才报 Data too long」，那是个 500 */
    private String word;

    /** 1 低 2 中 3 高。★ 与 {@code ai_audit_result.risk_level} 同刻度，见类注释 */
    private Integer level;

    /** 分类（政治/色情/暴力/广告…）。★ 只给运营看，不参与判定 */
    private String category;

    /** 1 启用 0 停用。★ 停用后<b>下一次 reload 立刻</b>不再命中，见 {@code SensitiveWordScanner} */
    private Integer enabled;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
