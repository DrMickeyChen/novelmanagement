package com.novel.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 配置（E1）—— 对应表 {@code ai_config}，<b>全局唯一一条</b>。
 *
 * <h3>★★★ {@code apiKey} 这一列存的是密文，而它【必须】是密文</h3>
 *
 * <p>加密/解密全部在 {@code AiCrypto} 里，这个实体只是搬运工 ——
 * <b>任何地方拿到 {@code AiConfig} 都不该直接读 {@code getApiKey()} 去调模型</b>，
 * 要走 {@code AiConfigService#decryptCurrentApiKey()}。
 * ★ 理由不是洁癖：密文直接当明文用，上游会返回 401，
 * 而<b>排查方向会指向「Key 配错了」而不是「解密这一步被绕过了」</b> —— 一句指向错误方向的线索。
 *
 * <h3>★★ {@code provider} 这一列【不是】决定哪个实现在跑的那个 provider</h3>
 *
 * <p>这里存的 {@code 'DEEPSEEK'} 只是<b>这一行记录的厂商名</b>，取值恒为大写常量；
 * 真正决定「背后是假数据还是真模型」的是 YAML 里的
 * {@code novel.ai.provider}（{@code mock} / {@code deepseek}，小写，启动期决定哪个 Bean 存在）。
 *
 * <p>★★★ <b>两者【词汇表不同、大小写不同、生效时机也不同】，绝对不要「顺手统一」。</b>
 * 统一之后的症状：管理员在页面上把 provider 改成 {@code deepseek}，保存成功、界面显示正常，
 * <b>而跑的还是 MockAiStreamSource</b> —— 因为决定权根本不在库里。
 * 这个坑现在还没人能踩到，只因为管理端这一栏是只读的（见 {@code AiConfigVO}）。
 *
 * <h3>★ 为什么没有唯一键约束</h3>
 *
 * <p>DDL 上 {@code ai_config} 只有主键，没有「只允许一行」的约束（已记在案的缺口）。
 * 处置是<b>服务层保证 + 启动/读取时自检告警</b>，<b>不顺手加唯一键</b> ——
 * 那会牵动 {@code seed.sql} 里「id 恒为 1」的假设，而本仓库没有 ALTER 惯例（见 {@code schema.sql} 的文件头）。
 * 见 {@code AiConfigServiceImpl#current()}。
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_config")
public class AiConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 厂商名，恒为 {@code 'DEEPSEEK'}。★ 详见类注释 —— 它不决定哪个实现在跑 */
    private String provider;

    /** ★ AES/GCM 密文，不是明文。读写都走 {@link com.novel.modules.ai.service.AiCrypto} */
    private String apiKey;

    /**
     * OpenAI 兼容端点，如 {@code https://api.deepseek.com/v1}。
     *
     * <p>★★ <b>它同时是 E1 的测试钩子</b>：把它指向本地假端点（{@code docs/tools/fake-openai.py}），
     * 就能在<b>不改一行代码、不重新打包</b>的前提下做「上游挂了 / 超时 / 返回坏形状」的实验 ——
     * 与 M4 把 {@code ES_URIS} 做成环境变量是同一个手法。
     */
    private String baseUrl;

    /** 模型名，如 {@code deepseek-chat} */
    private String model;

    /**
     * 温度。★ 列类型是 {@code DECIMAL(2,1)}，用 {@code BigDecimal} 接。
     *
     * <p>★ 别换成 {@code Double}：{@code double} 表达不了 {@code 0.7} 的精确值，
     * 而这一列是定点数 —— 类型不匹配时 MyBatis 会静默按精度规则转，
     * 「存进去 0.7、读出来 0.7」看着没问题，但比较和序列化时会露馅。
     */
    private BigDecimal temperature;

    /** 单次生成的最大 token 数。★ 它也是「花钱」的直接旋钮之一 */
    private Integer maxTokens;

    /**
     * 每用户每日调用次数上限。
     *
     * <p>★★ <b>这是全项目唯一的一个每日上限</b>（E1/S4 起）。
     * P4 时 YAML 里还有一个 {@code novel.ai.daily-quota}，两个数并存 ——
     * 那个已经删掉了：改它不会有任何效果、也不会有任何提示，
     * 而「一个转了却什么都没发生的旋钮」和「一条不会失败的护栏」是同一种坏东西。
     * ★ 判据：<b>能改的那个才是真的</b>（管理端「AI 配置」页写的就是这一列）。
     */
    private Integer dailyLimit;

    /** 1 启用 0 停用。★ 关闭时四个生成端点一律返 5001，见 {@code AiConfigService#requireUsable()} */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
