package com.novel.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.dto.AiConfigSaveDTO;
import com.novel.modules.ai.entity.AiConfig;
import com.novel.modules.ai.mapper.AiConfigMapper;
import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.service.AiCrypto;
import com.novel.modules.ai.service.AiRuntimeConfig;
import com.novel.modules.ai.vo.AiConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 配置的读写与闸门（E1）。
 *
 * <h3>★★ 「全局唯一一条」靠服务层保证 —— 以及为什么【不】顺手加唯一键</h3>
 *
 * <p>{@code ai_config} 的 DDL 上只有主键，<b>没有「只允许一行」的约束</b>（已记在案的缺口）。
 * 处置：{@link #currentRow()} 一律按 {@code id ASC} 取第一条，并<b>在真的出现多行时告警</b>。
 *
 * <p>★ 为什么不加唯一键：那要改已有表的 DDL，而本仓库<b>没有 ALTER / 迁移惯例</b>，
 * 且会牵动 {@code seed.sql} 里「{@code ai_config} 的 id 恒为 1」这个假设
 * （同 {@code schema.sql} 文件头记的 R15）。
 * <p>★ 为什么不放在 {@code @PostConstruct} 里自检：那会让<b>启动多依赖一次数据库读取</b>，
 * 而它换来的只是一句告警。读取时顺手判，代价是零。
 *
 * <h3>★★ 取第一条是【确定性】的，不是「随便挑一条」</h3>
 *
 * <p>{@code ORDER BY id ASC} 让「有两行时用哪一行」变成一个<b>可预期的答案</b>。
 * 不带排序的 {@code LIMIT 1} 在 MySQL 上通常也返回第一条，但那是<b>执行计划恰好如此</b>，
 * 不是承诺 —— 而「AI 用的是哪套配置」不该建立在执行计划上。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigServiceImpl implements AiConfigService {

    /** 掩码的固定前半段。★ 长度恒定，见 {@link #mask} */
    private static final String MASK_PREFIX = "sk-****";

    /**
     * 短于这个长度的 Key <b>不显示末四位</b>。
     *
     * <p>★ 一个正常 Key 是 35 个字符左右；短于 8 位只可能是配置写错了。
     * 那时宁可不给尾巴 —— 4 个字符在 6 个字符的串里就是<b>大半把钥匙</b>。
     */
    private static final int MIN_LENGTH_FOR_TAIL = 8;

    /**
     * {@code daily_limit} 列不可用时的<b>展示</b>回落值。
     *
     * <p>★ 与 {@code schema.sql} 里 {@code daily_limit INT NOT NULL DEFAULT 50}
     * 的默认值一致 —— 两处都是 50 不是巧合，是「回落要落到 DDL 本来会给的值」，
     * 这样「有人手工把它改成 NULL」在界面上和「没动过」看起来一样，
     * 而不是凭空冒出一个新数字。
     */
    private static final int DEFAULT_DAILY_LIMIT = 50;

    private final AiConfigMapper configMapper;
    private final AiCrypto crypto;

    /**
     * YAML {@code novel.ai.provider} —— <b>决定哪个 Bean 存在的那一个</b>。
     *
     * <p>★ 它和库里的 {@code ai_config.provider} 是<b>两回事</b>，
     * 详见 {@code AiConfig} 的类注释。这里把它读进来只为了两件事：
     * ① 返回给管理端展示（让「填了 Key 不生效」可见）；
     * ② 在日志里说清楚当前跑的是哪个实现。
     */
    @Value("${novel.ai.provider:deepseek}")
    private String effectiveProvider;

    @Override
    public AiConfigVO get() {
        return toVO(currentRow());
    }

    @Override
    public AiConfigVO save(AiConfigSaveDTO dto) {
        boolean clear = Boolean.TRUE.equals(dto.getClearApiKey());
        boolean hasNewKey = hasText(dto.getApiKey());

        /*
         * ★★ 两个都传是自相矛盾的请求。返回 400，而不是「按 clear 处理」或「按新 Key 处理」——
         *   猜用户想要什么，正是本项目一直在避免的那类「看起来正常的假结果」：
         *   无论猜哪一边，都有 50% 的概率悄悄做错一件不可逆的事（Key 被清掉 / 旧 Key 没换掉）。
         */
        if (clear && hasNewKey) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "apiKey 与 clearApiKey 不能同时给：要换 Key 就只传 apiKey，要清空就只传 clearApiKey=true。");
        }

        AiConfig row = currentRow();

        if (clear) {
            row.setApiKey(null);
            log.warn("管理员清空了 AI 的 API Key | configId={} | 之后所有真实调用会返回 5002", row.getId());
        } else if (hasNewKey) {
            /*
             * ★★★ 明文【只在这里出现一次】，加密后立刻丢掉。
             *   ★ 不 log 明文、不 log 密文、不把它塞进任何实体的其它字段。
             *
             * ★★★ 但【没配 AI_KEY_SECRET】必须先查、翻译成 5002 —— 不能让 encrypt 抛出去变 500。
             *
             *   这是本文件里两条同类路径的【不一致】，而它们是同一个原因：
             *     decryptCurrentApiKey()（本文件下面）查了 isConfigured() → 5002 + 指向环境变量的文案；
             *     save()（这里）没查 → AiCrypto.keyOrThrow() 抛 IllegalStateException
             *       → GlobalExceptionHandler 兜底 → 前端只看到「服务器开小差了，请稍后再试」。
             *   ★ AiCrypto.encrypt 的 javadoc 明写「调用方应先查 isConfigured()」，
             *     而这里是全项目【唯一】一个加密调用点 —— 也是唯一没照做的调用点。
             *
             *   判据直接沿用 decryptCurrentApiKey 那条（见下面 :228 的注释）：
             *   缺 AI_KEY_SECRET 是【部署时漏配】，管理员能修，文案必须直接指向那个变量。
             *   丢进 500 之后，屏幕上那句话是在【叫人去重试】—— 而重试一万次都不会成功。
             *   ★ 实测症状（E3 期间修掉的就是它）：界面显示「服务器开小差了，请稍后再试」，
             *     真原因只出现在 backend/logs/novel-content.log 里，界面上一个字都不透露。
             *
             *   ★ 这条【不是】「那就存明文吧」的替代品 —— 那条永远不会做：
             *     encrypt 的异常在 setApiKey(...) 的实参求值时就抛了，一个字节都不会落库。
             *   ★ 边界（与 decrypt 那条逐字同形）：缺变量 → 5002（可操作）；
             *     配了却用不了（派生失败 / JCE 坏）→ 仍然 500，那才是程序缺陷，需要人翻日志看异常类型。
             */
            if (!crypto.isConfigured()) {
                log.error("管理员要录入 AI 的 API Key，但服务端未配置 AI_KEY_SECRET | 请设置该变量并重启 content");
                throw new BusinessException(ResultCode.AI_API_KEY_MISSING,
                        "服务端未配置 AI_KEY_SECRET，无法加密保存 API Key，请联系运维。");
            }
            row.setApiKey(crypto.encrypt(dto.getApiKey()));
        }
        // else：不传 = 保持原值。★ 见 AiConfigSaveDTO 的类注释（读接口不返回 Key，
        //   输入框总是空的，所以「空 = 清空」会让「只想改温度」顺手抹掉 Key 且无任何提示）

        /*
         * ★★★ 这里【必须】用显式的 lambdaUpdate().set(...)，不能用 updateById(row)。
         *
         *   验收实测抓到的 bug：MyBatis-Plus 的 updateById 默认策略是 FieldStrategy.NOT_NULL，
         *   【null 字段会被整个跳过、不进 UPDATE 语句】。
         *   所以 `row.setApiKey(null)`（clearApiKey=true 那一支）**什么都没做** ——
         *   库里的旧密文原样还在。
         *
         *   ★★ 而更坏的是它【不报错】：响应体是拿内存里那个 row 渲染的，
         *   于是它诚实地报告「configured=false, 已清空」—— 而库里那把 Key 还在。
         *   **一句「清空成功」的假话，而且它出现在一个安全操作上。**
         *
         *   ★ 这就是铁律 8 的第四种形状：**判据（响应体）和被测对象（库里的值）不是同一个东西。**
         *   凡是「某个字段可以被显式置空」的写操作，都要怀疑这一条。
         *
         *   ★ updateTime 也得自己 set：MetaObjectHandler 只对【实体方式】的更新生效，
         *   update(null, wrapper) 走不到它。不写的话这一列会靠 DDL 的
         *   ON UPDATE CURRENT_TIMESTAMP 更新 —— 那用的是【数据库时区】，
         *   和本项目「时间口径只有一个（应用时区）」的规矩不一致。
         */
        configMapper.update(null, Wrappers.<AiConfig>lambdaUpdate()
                .eq(AiConfig::getId, row.getId())
                .set(AiConfig::getApiKey, row.getApiKey())
                .set(AiConfig::getBaseUrl, dto.getBaseUrl())
                .set(AiConfig::getModel, dto.getModel())
                .set(AiConfig::getTemperature, dto.getTemperature())
                .set(AiConfig::getMaxTokens, dto.getMaxTokens())
                .set(AiConfig::getDailyLimit, dto.getDailyLimit())
                .set(AiConfig::getEnabled, dto.getEnabled())
                .set(AiConfig::getUpdateTime, LocalDateTime.now()));
        // ★ provider 不接收入参：这一列恒为 'DEEPSEEK'，且它不决定哪个实现在跑
        //   （见 AiConfig 类注释）。让它可写就会造出一个「改了却不生效」的假旋钮。
        //   ★ 它也因此【不在上面那条 UPDATE 里】—— 没有任何一条路径能改它。

        /*
         * ★★ 日志与响应都必须基于【重新读回来的那一行】，不能基于内存里的 row。
         *   上面那个 bug 能骗过响应体，就是因为响应体信了内存。
         *   多读一次单行主键级查询，换的是「响应＝库里的真值」这条硬保证。
         */
        AiConfig saved = currentRow();
        log.info("AI 配置已更新 | configId={} | enabled={} | model={} | baseUrl={} | 本次是否更换 Key={}",
                saved.getId(), saved.getEnabled(), saved.getModel(), saved.getBaseUrl(),
                clear ? "已清空" : (hasNewKey ? "是" : "否"));

        return toVO(saved);
    }

    @Override
    public int dailyLimit() {
        Integer limit = currentRow().getDailyLimit();
        if (limit == null || limit <= 0) {
            /*
             * ★ 见接口注释：这里【不】返回 0。
             *   0 会让额度区显示「0/0」—— 一个看起来像「今天已经用完了」的假象，
             *   而它真实的含义是「这个字段被人改坏了」。
             * ★ 也不抛：这个方法服务于一个只读的展示接口，
             *   为了一个展示数字把面板整个打挂，代价大于收益。
             *   ★★ 而真正的闸门走的是 {@code requireUsable()} → {@code AiRuntimeConfig.dailyLimit()}，
             *      那一条【不经过这里】、也不会回落：同一个坏值到了那儿会在拆箱时
             *      变成一个响亮的 500。（对照本仓库那条「降级只对能诚实渲染的字段成立」——
             *      展示数字可以回落，扣减依据不行。）
             */
            log.warn("ai_config.daily_limit 的值不可用（{}），展示口径回落为 {} | 真正扣减仍按库里的原始值走",
                    limit, DEFAULT_DAILY_LIMIT);
            return DEFAULT_DAILY_LIMIT;
        }
        return limit;
    }

    @Override
    public AiRuntimeConfig requireUsable() {
        AiConfig row = currentRow();

        /*
         * ★★ 顺序是有讲究的：先判「停用」再判「没配 Key」。
         *   反过来的话，一个管理员刚清空 Key、还没关开关，
         *   用户看到的是 5002「尚未配置 API Key」—— 而管理员的意图其实是「先别用」。
         *   5001「AI 功能未启用」才是对当前状态的准确描述。
         */
        if (row.getEnabled() == null || row.getEnabled() != 1) {
            throw new BusinessException(ResultCode.AI_DISABLED,
                    "AI 功能当前未启用，请联系管理员。");
        }
        if (!hasText(row.getApiKey())) {
            throw new BusinessException(ResultCode.AI_API_KEY_MISSING,
                    "AI 尚未配置 API Key，请联系管理员。");
        }
        /*
         * ★ 这里【不】解密，只确认「有密文」。理由有两条：
         *   ① 解密要在真正发请求前才做（见 AiConfigService 的类注释）；
         *   ② 密钥没配（AI_KEY_SECRET 为空）时，本方法不该抛 500 ——
         *      那是「服务端没配好」，而 requireUsable() 的语义是「业务上能不能用」。
         *      真正的解密失败会由 decryptCurrentApiKey() 抛，那里的文案指向 AI_KEY_SECRET。
         */
        return new AiRuntimeConfig(row.getBaseUrl(), row.getModel(),
                row.getTemperature(), row.getMaxTokens(), row.getDailyLimit());
    }

    @Override
    public String decryptCurrentApiKey() {
        String cipherText = currentRow().getApiKey();
        if (!hasText(cipherText)) {
            // ★ 与 requireUsable() 同一个码：从调用方的角度看是同一件事
            throw new BusinessException(ResultCode.AI_API_KEY_MISSING,
                    "AI 尚未配置 API Key，请联系管理员。");
        }
        /*
         * ★ 密钥没配 → 5002 而不是 500。
         *   判据：「这条链上 401/503 是身份与可用性，500 是程序缺陷」——
         *   缺 AI_KEY_SECRET 是【部署时漏配】，管理员能修，且文案明确指向那个环境变量；
         *   把它丢进 500「服务器开小差了」，运维要翻日志才知道该配什么。
         *   （对照：密钥【对不上】是 500，见 AiCrypto —— 那个需要人去翻日志看异常类型。）
         */
        if (!crypto.isConfigured()) {
            log.error("AI 调用需要明文 Key，但服务端未配置 AI_KEY_SECRET | 请在环境中设置该变量并重启");
            throw new BusinessException(ResultCode.AI_API_KEY_MISSING,
                    "服务端未配置 AI_KEY_SECRET，无法解密 API Key，请联系运维。");
        }
        return crypto.decrypt(cipherText);
    }

    /**
     * 取那唯一一行；顺带做「确实只有一行」的自检。
     *
     * <p>★ 少一行是 {@code 500} 而不是业务码：{@code seed.sql} 保证 {@code id=1} 存在，
     * 读到 0 行说明<b>有人删了它</b>，那是数据被破坏，不是用户操作错误。
     */
    private AiConfig currentRow() {
        List<AiConfig> rows = configMapper.selectList(
                Wrappers.<AiConfig>lambdaQuery().orderByAsc(AiConfig::getId));
        if (rows.isEmpty()) {
            log.error("ai_config 表里一行都没有 —— 种子数据被删了？");
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "AI 配置缺失，请检查数据库初始化。");
        }
        if (rows.size() > 1) {
            /*
             * ★★ 只告警、不自动清理。自动删掉多余的行是不可逆的，
             *   而「多出来那行是谁、为什么在那」正是运维要看的信息。
             * ★ 告警里带上 id 列表：只说「有多行」等于没说。
             */
            log.warn("ai_config 有 {} 行，本次使用 id 最小的那一行（id={}）| 全部 id={} | "
                            + "该表在 DDL 上没有唯一约束，请人工确认后清理",
                    rows.size(), rows.get(0).getId(),
                    rows.stream().map(AiConfig::getId).toList());
        }
        return rows.get(0);
    }

    /**
     * 填 Key 那三个字段：{@code apiKeyConfigured} / {@code apiKeyMasked} / {@code apiKeyNotice}。
     *
     * <h4>★★★ 掩码：输出长度恒定，与真实 Key 的长度无关</h4>
     *
     * <p>★ 反例（必须避免的写法）：{@code plain.substring(0,2) + "*".repeat(plain.length()-6) + 末4位}
     * —— 星号个数等于真实长度，等于把长度白送出去。
     *
     * <h4>★★★ 解密失败【接住】，不往上抛 —— 这是验收实测改出来的一版</h4>
     *
     * <p>上一版在这里让 {@code crypto.decrypt} 的异常直接冒出去 → 接口 500。
     * 理由是「不要渲染一个看起来正常、却配着一把解不开的 Key 的配置页」——
     * ★ <b>那个理由对 GET 成立，对 PUT 不成立</b>，因为 PUT 的响应体里也有这个 VO：
     * 管理员改温度 → <b>库里改成功了</b>，但响应 500 → 界面报失败 → 他重试，每次再写一遍。
     * <b>「真成功被报成失败」和「假成功」的后果一样：人都做了错误的下一步。</b>
     *
     * <p>★ 所以拆开：<b>加密层继续抛</b>（{@code AiCrypto#decrypt} 一个字都没改，
     * 它是「绝不把乱码当 Key 用」那条护栏的落点），
     * <b>这一层负责不让一个已经成功的写操作被谎报成失败</b>。
     * 翻译结果见 {@code AiConfigVO#apiKeyNotice}。
     */
    private void fillKeyFields(AiConfigVO vo, String cipherText) {
        if (!hasText(cipherText)) {
            vo.setApiKeyConfigured(false);
            vo.setApiKeyMasked(null);
            vo.setApiKeyNotice(null);
            return;
        }
        vo.setApiKeyConfigured(true);
        try {
            String plain = crypto.decrypt(cipherText);
            vo.setApiKeyMasked(plain == null || plain.length() < MIN_LENGTH_FOR_TAIL
                    ? MASK_PREFIX
                    : MASK_PREFIX + plain.substring(plain.length() - 4));
            vo.setApiKeyNotice(null);
        } catch (RuntimeException e) {
            /*
             * ★ 这里【故意不 log 异常原文】—— AiCrypto 已经用 ERROR 记过一次了，
             *   再记一次是重复；而它已经把「哪个异常类型」写清楚了。
             * ★ 只 DEBUG 记一句「翻译成了 notice」，方便排查时确认走到了这一支。
             */
            log.debug("AI Key 无法解密，已在响应里改为提示文案 | configId={}", vo.getId());
            vo.setApiKeyMasked(null);
            vo.setApiKeyNotice("已保存的 API Key 无法解密：服务端 AI_KEY_SECRET 与录入时不一致，请重新录入一次 Key。");
        }
    }

    private AiConfigVO toVO(AiConfig row) {
        AiConfigVO vo = new AiConfigVO();
        vo.setId(row.getId());
        vo.setProvider(row.getProvider());
        fillKeyFields(vo, row.getApiKey());
        vo.setBaseUrl(row.getBaseUrl());
        vo.setModel(row.getModel());
        vo.setTemperature(row.getTemperature());
        vo.setMaxTokens(row.getMaxTokens());
        vo.setDailyLimit(row.getDailyLimit());
        vo.setEnabled(row.getEnabled());
        vo.setEffectiveProvider(effectiveProvider);
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
