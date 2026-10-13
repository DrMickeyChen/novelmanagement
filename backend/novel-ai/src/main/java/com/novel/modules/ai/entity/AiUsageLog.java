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
 * AI 调用流水（E1）—— 对应表 {@code ai_usage_log}，<b>只增不改</b>。
 *
 * <h3>★★ 它是「花钱」这个维度在本系统里的唯一账本</h3>
 *
 * <p>E1 之前，这个系统里没有一件事是<b>有成本</b>的 ——
 * 一次生成失败重试十次，代价也只是几毫秒 CPU。
 * 接上真实模型之后，<b>每一次调用都是钱</b>，而「花了多少」必须能看见，
 * 否则「额度用完」就只是一句凭空的拦截。
 *
 * <p>★ <b>成功和失败【都】写一行</b>（{@code success} 列区分）。
 * 只记成功的话，「上游一直 401」这件事在账本上表现为<b>没有任何记录</b> ——
 * 与「没人用过 AI」长得一模一样。这是本项目一路在猎的那种
 * 「失败被渲染成空」的形状，只是这次换到了运维视角。
 *
 * <h3>★★★ {@code errorMsg} 只能来自 {@code AiFailure} 枚举，绝不能是异常消息</h3>
 *
 * <p>见 {@code AiFailure} 的类注释：真实 DeepSeek 的 401 响应体里带 Key 片段。
 * 把 {@code e.getMessage()} 写进这一列 = 把一把 Key 存进数据库 + 渲染到用量页上。
 *
 * <h3>★ 为什么这一张表【没有】{@code deleted} 列，实体里也没有 {@code @TableLogic}</h3>
 *
 * <p>流水是不可变的：写下来就不该改，更不该删。
 * ★ 与 {@code sys_role_menu} 那条规矩同源但理由不同 —— 那边是「DDL 里就没有这一列」，
 * 这边是「有也不该用」。共同点是：<b>实体上不许出现 {@code @TableLogic}</b>，
 * 否则 MyBatis-Plus 会在每条查询后面自动拼一个 {@code deleted = 0}，
 * 而那一列不存在 → SQL 报错（症状是一句 {@code Unknown column 'deleted'}，
 * 而它指向的是「你的表建错了」，不是「你多写了一个注解」）。
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_usage_log")
public class AiUsageLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 谁花的钱。★ 见 {@code AiUsageRecorder} 对「用户身份丢了」的处理 */
    private Long userId;

    /** 场景码，对齐 {@code AiScene#getCode()}（1 大纲 2 续写 3 灵感 4 润色 5 审核） */
    private Integer scene;

    private Long novelId;

    private Long chapterId;

    /** 调用时用的模型名。★ 记下来才有意义：改了模型之后旧记录仍然说明当时用的是哪个 */
    private String model;

    /** ★ 三个 token 列全 0 读作「未统计」（上游没给 usage），不是「没消耗」—— 见 {@code AiChatUsage} */
    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 本次调用耗时（毫秒）。★ 它把「上游变慢」变成一个能看见的趋势 */
    private Integer durationMs;

    /** 1 成功 0 失败。★ 被用户中断也是 0，见 {@code AiFailure#ABORTED} */
    private Integer success;

    /** 失败原因，取值来自 {@code AiFailure#getCode()}；成功时为 {@code null} */
    private String errorMsg;

    /**
     * 写入时间。★ 由 {@code MybatisMetaObjectHandler} 填充，不用数据库的
     * {@code DEFAULT CURRENT_TIMESTAMP} —— 那是数据库时区，而本项目的时间口径只有一个。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
