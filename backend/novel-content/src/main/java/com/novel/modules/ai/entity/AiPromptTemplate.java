package com.novel.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.modules.ai.enums.AiScene;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 提示词模板，对应 {@code ai_prompt_template}（E1）。
 *
 * <p><b>★ 为什么是表而不是配置文件</b>：改一句提示词不该重新发版。
 * 而这在 E1 尤其重要 —— 提示词的措辞直接决定模型输出能不能被解析
 * （判断 4 的「静默为 0」、审核的 JSON 围栏），调它是<b>日常操作</b>，不是发版事件。
 *
 * <h3>★★ 三个字段有坑</h3>
 * <ol>
 *   <li>{@link #scene} 用 {@link AiScene}，靠它的 {@code @EnumValue} 落库成 1~5。
 *       ★ <b>别把它改成裸 {@code Integer}</b>：那样「scene=7」这种值会一路进到库里，
 *       而 {@code AiVarResolver} 查不到第 7 个场景只会静默退化成「所有变量都不适用」→
 *       模板里的占位符<b>全部被整行移除</b> → 发给模型的是一段空壳，
 *       而<b>界面上一切正常</b>。</li>
 *   <li><b>没有 {@code deleted} 字段，也没有 {@code @TableLogic}。</b>
 *       这张表没有逻辑删除列 —— 加注解会让每次查询都带 {@code deleted = 0} 而<b>列不存在</b>。
 *       ★ 模板的管理方式是 {@code enabled} 开关，不是删除（同 {@code novel_category} 的取法）。</li>
 *   <li>{@code updateTime} <b>有</b>（表里有这一列），跟着 {@code FieldFill} 自动刷新 ——
 *       管理端靠它显示「这条模板最后一次改动是什么时候」。</li>
 * </ol>
 *
 * <p>★ <b>模板的任何一条写入都必须过 {@code PromptRenderer.validateTemplate}</b>：
 * 未知占位符和「本场景不适用」都在写出库之前拒绝。理由见那个方法的注释 ——
 * 半径差三个数量级（1 个管理员的下一次点击 vs 全体作者 × 该场景）。
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_prompt_template")
public class AiPromptTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景（1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要）。★ 落库值 + 对外 JSON 值都是 {@code AiScene.code} */
    private AiScene scene;

    /** 模板名。★ 与 scene 组成唯一键 {@code uk_scene_name} —— 同一场景下可以有多条模板，靠 {@code sort} 排序 */
    private String name;

    /** system 消息的模板。可以有占位符，也可以是一段固定的话 */
    private String systemPrompt;

    /**
     * user 消息的模板 —— <b>带占位符的那一份</b>，也是真正决定模型看到什么的那一份。
     *
     * <p>★ 五个占位符的词汇表见 {@code AiPlaceholder}，「哪个场景有哪些」见 {@code AiVarResolver}。
     */
    private String userTemplate;

    /** 1 启用 0 停用。★ 停用 <b>不是删除</b>：停用后该场景取不到模板 → 生成接口返 {@code 5005} */
    private Integer enabled;

    /** 同场景多模板时的排序，小的优先。★ {@code AiPromptService} 取「第一条启用的」就按它排 */
    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
