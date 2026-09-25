package com.novel.modules.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API Key 的加解密（E1）。<b>AES-256/GCM</b>，密钥来自环境变量 {@code AI_KEY_SECRET}。
 *
 * <h3>★★★ 为什么是 GCM 而不是 CBC —— 不是为了「更安全」，是为了一条判据</h3>
 *
 * <p>本类的验收项里有一条是：
 * <blockquote>故意用<b>错误的密钥</b>解密 → 断言<b>抛异常</b>，
 * 而不是返回一段乱码当 Key。</blockquote>
 *
 * <p>★ 这条判据只有在<b>带认证的</b>加密模式下才成立：
 * <ul>
 *   <li>CBC + PKCS5Padding：错密钥解出来是乱码，<b>大多数时候不报错</b>，
 *       然后那段乱码被当成 Key 发去上游 → 上游返 401 →
 *       排查方向变成「Key 是不是过期了 / 是不是填错了」，<b>而真相是「解密这一步就错了」</b>。</li>
 *   <li>GCM：错密钥<b>必然</b>抛 {@code AEADBadTagException}（认证标签对不上）。</li>
 * </ul>
 * ★ 这是本项目反复出现的同一个形状：<b>把「配错了」变成「上游 401」，排查方向差得远。</b>
 * （同 {@code AuditLogServiceImpl} 的「降级只对能诚实地渲染出来的字段成立」。）
 *
 * <h3>★★ 密钥怎么来 —— 以及为什么【生产路径上】绝不给默认值</h3>
 *
 * <p>{@code AI_KEY_SECRET} 走环境变量，<b>与 {@code JWT_SECRET} / {@code INTERNAL_TOKEN} 同一条规矩</b>：
 * 给了默认值，生产环境忘配时会静默用一个<b>写在 git 里、全世界都知道</b>的字符串当密钥。
 * 那时「加密」变成一个<b>装饰</b>，而<b>没有任何症状</b> ——
 * 它不像「登录坏了」那样会立刻被发现，是这一期最坏的一种失败。
 *
 * <p>★★ <b>限定（E3 收尾补）：上面这条规矩说的是【生产路径】。</b>
 * {@code application.yml} 那行仍是 {@code ${AI_KEY_SECRET:}}（空默认），而
 * {@code application-dev.yml} 里有一条 dev 常量默认值（与 {@code JWT_SECRET} 同款），
 * 所以本地开发是零配置的。★ 说「它有 / 没有默认值」之前，先点明说的是哪条 profile。
 * ★★ 那份 dev 默认值让 <b>dev 库里那把 Key 在效果上等于明文</b>（密钥就写在仓库里）——
 * 所以 <b>dev 只放低限额、随时可吊销的 Key，真 Key 只放生产</b>。理由写在该文件里。
 *
 * <p>★★ <b>但这里和 JWT 有一处【故意的不同】：密钥没配时，本类【不在启动期报错】。</b>
 * 判据是「新克隆的仓库不配任何东西必须能跑起来」—— 启动就要求 {@code AI_KEY_SECRET}，
 * 等于把「只想跑起来看看」的人挡在门外。所以：<b>密钥在【用到的那一刻】才检查</b>，
 * {@link #isConfigured()} 为假时 {@link #encrypt} / {@link #decrypt} 直接抛，
 * 由调用方翻译成 5002（「尚未配置 API Key」）或 500。
 *
 * <p>★★★ 那条判据有两个支撑点，其中【一个已经不在了】（E3 期间改 provider 默认值时拆掉的）：
 * <ol>
 *   <li>P4 用 {@code novel.ai.provider} 上的 {@code matchIfMissing=true} 让缺配置时兜底到 mock ——
 *       <b>所以那时候「不配任何东西」连 AI 面板都有演示输出</b>。这个兜底现在挪给了真实实现
 *       （见 {@code application.yml}），于是 AI 面板在不配 Key 时会返 5002，这是刻意接受的代价；</li>
 *   <li>{@code AI_KEY_SECRET} 的空默认值 —— 即本类这一段。<b>它没变，也不许变。</b>
 *       （★ 指 {@code application.yml} 那条<b>生产</b>路径；dev 那条常量默认值是 E3 收尾另加的，
 *       见上 —— 它不影响「应用能不能启动」这条判据：两条路径下都不影响。）</li>
 * </ol>
 * ★★ 关键区分：那条判据其实是【两件事】被写成了同一句话 ——
 * 「<b>应用能不能启动</b>」（本类负责，仍然成立）与「<b>AI 面板有没有输出</b>」
 * （原由 matchIfMissing 负责，现在没了）。把它们混成一句，就会在读到「不配任何东西也能跑」
 * 时以为 AI 也能用 —— 而它现在不能。
 *
 * <h3>★ 密文格式：{@code v1:{base64(IV ‖ 密文+认证标签)}}</h3>
 *
 * <p>IV 是<b>每次加密随机生成的 12 字节</b>（GCM 推荐长度），拼在密文前面一起 base64 ——
 * IV 不是秘密，但它<b>必须每次不同</b>：GCM 下同一个 (密钥, IV) 加密两次，
 * 攻击者可以直接异或出两份明文，而且能伪造认证标签。
 * ★ 前缀 {@code v1:} 是留给将来换算法用的：没有它，将来换算法时<b>老密文会被当成新格式解，
 * 报出来的错和「密钥对不上」一模一样</b>，又是一次指向错误方向的排查。
 *
 * <h3>★★ 密钥派生：SHA-256 直接当 AES 密钥 —— 这个取舍的边界要说清楚</h3>
 *
 * <p>{@code AI_KEY_SECRET} 是任意字符串，而 AES 要 16/24/32 字节，所以必须派生。
 * 这里用 {@code SHA-256(secret)} 取 32 字节（AES-256），并且<b>把派生结果缓存起来</b>
 * （见 {@link #cachedKey}）—— 否则每次读配置都要重新派生一遍，而那落在请求路径上。
 *
 * <p>★ <b>为什么不上 PBKDF2：</b>慢 KDF 防的是「密钥是个人选的弱口令、可以被离线爆破」。
 * 本项目的 {@code AI_KEY_SECRET} 是<b>运维生成的高熵随机串</b>（同 {@code INTERNAL_TOKEN} 的性质），
 * 爆破它的代价与爆破 SHA-256 原像相当，慢 KDF 在这里买不到东西，却要在每个请求上付一次。
 * ★★ <b>这条边界是硬的：如果哪天有人把 {@code AI_KEY_SECRET} 改成一个「好记的密码」，
 * 这个取舍就不成立了</b> —— 那时必须换成 PBKDF2 + 每条密文自带 salt，
 * 而 salt 可以塞进上面那个 {@code v1:} 格式里（这是留版本前缀的第二个理由）。
 *
 * <p>★ 全程不打印明文、不打印密钥、不打印密文。异常信息里也一个都不带 ——
 * 见 {@link #decrypt} 的注释。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
public class AiCrypto {

    /** 当前密文格式版本。★ 换算法时改这里，并让 {@code decrypt} 按前缀分派 */
    private static final String VERSION_PREFIX = "v1:";

    /** GCM 的 IV 长度（字节）。★ 12 是 GCM 的推荐值，不是随便取的 */
    private static final int IV_LENGTH = 12;

    /** GCM 认证标签长度（bit）。128 = 最长，也是默认 */
    private static final int TAG_LENGTH_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    private final SecureRandom random = new SecureRandom();

    /** 环境变量 {@code AI_KEY_SECRET}。★ 未配置时是空串，不是 null */
    private final String secret;

    /**
     * 派生出来的 AES 密钥缓存。
     *
     * <p>★ 用 {@code AtomicReference} 而不是构造器里算好：<b>密钥没配时不派生</b>（派生会抛）。
     * ★ 只缓存一个值、不做容量控制 —— {@code secret} 在本进程生命周期内不会变
     * （它来自环境变量，改它必须重启），所以这个缓存<b>不可能指向一个过期的密钥</b>。
     */
    private final AtomicReference<SecretKeySpec> cachedKey = new AtomicReference<>();

    public AiCrypto(@Value("${novel.ai.key-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    /**
     * 密钥配好了吗。★ <b>调用方靠它把「没配密钥」翻译成 5002</b>，而不是让异常冒到 500。
     *
     * <p>★ 判的是空白而不只是 null：{@code AI_KEY_SECRET=""} 与没配是同一件事，
     * 而 Spring 对 `${AI_KEY_SECRET:}` 的缺省值恰好就是空串。
     */
    public boolean isConfigured() {
        return !secret.isBlank();
    }

    /**
     * 加密。★ 只在管理端保存 Key 时调用。
     *
     * @throws IllegalStateException 密钥没配 —— 调用方应先查 {@link #isConfigured()}
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        SecretKeySpec key = keyOrThrow();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherBytes, 0, out, iv.length, cipherBytes.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            /*
             * ★ 这里【不能】带 e.getMessage() 出去给用户看，也不能把 plainText 拼进去。
             *   加密失败只可能是环境问题（JCE 不可用 / 密钥长度受限），
             *   异常原文对管理员没有信息量，对日志的读者却有泄漏风险。
             *   所以：日志里记类型，抛出去的只记「失败了」。
             */
            log.error("AI Key 加密失败 | 异常类型={}", e.getClass().getName());
            throw new IllegalStateException("AI Key 加密失败，请检查服务端 AI_KEY_SECRET 与 JDK 环境", e);
        }
    }

    /**
     * 解密。★ 只在真正要调模型之前调用。
     *
     * @throws IllegalStateException 密钥没配、密文格式不对、<b>或密钥与加密时不一致</b>
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (!cipherText.startsWith(VERSION_PREFIX)) {
            /*
             * ★ 这一支是给「有人手工往库里塞了一段明文 Key」准备的。
             *   不兼容处理它，是因为「明文能直接用」这个后门一旦存在，
             *   加密就只是建议 —— 而它必须是一条【所有人都不例外】的规矩。
             *   报错文案直说「不是本系统写入的格式」，否则管理员会去怀疑密钥。
             */
            throw new IllegalStateException("AI Key 不是本系统写入的密文格式（缺 " + VERSION_PREFIX + " 前缀）");
        }

        SecretKeySpec key = keyOrThrow();
        byte[] raw = Base64.getDecoder().decode(cipherText.substring(VERSION_PREFIX.length()));
        if (raw.length <= IV_LENGTH) {
            throw new IllegalStateException("AI Key 密文长度不合法");
        }

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            /*
             * ★★★ 走到这里最常见的原因是【AI_KEY_SECRET 和当初加密时不一样】。
             *     这是本类最想要的那个行为：GCM 让它【必然抛】，而不是返回乱码。
             *
             * ★ 抛出去的信息【一个字节的上游线索都不带】：
             *   没有密文、没有异常原文（AEADBadTagException 的 message 是空的，带也没用）、
             *   更没有明文。只说清楚「怎么修」。
             * ★ 日志里带上异常类型：运维要靠它区分
             *   「密钥不一致（AEADBadTagException）」和「JCE 环境坏了（其他异常）」。
             */
            log.error("AI Key 解密失败 | 异常类型={} | 提示：AI_KEY_SECRET 是否与加密时一致？",
                    e.getClass().getName());
            throw new IllegalStateException(
                    "AI Key 解密失败：AI_KEY_SECRET 与加密时不一致（改过密钥就需要重新录入 Key）", e);
        }
    }

    /**
     * 取派生好的 AES 密钥；没配密钥就抛。
     *
     * <p>★ {@code compareAndSet(null, k)} 是刻意的：<b>Redis 式的「先算再比再放」在这里没必要</b>，
     * 因为两个线程同时派生会得到<b>完全相同的</b> {@code SecretKeySpec}（同一输入、同一算法、无随机量），
     * 所以后写的覆盖先写的完全无害。用它只是为了不每次进来都加锁。
     */
    private SecretKeySpec keyOrThrow() {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置 AI_KEY_SECRET，无法加解密 AI Key");
        }
        SecretKeySpec cached = cachedKey.get();
        if (cached != null) {
            return cached;
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec key = new SecretKeySpec(derived, KEY_ALGORITHM);
            cachedKey.compareAndSet(null, key);
            return key;
        } catch (Exception e) {
            log.error("AI_KEY_SECRET 派生 AES 密钥失败 | 异常类型={}", e.getClass().getName());
            throw new IllegalStateException("AI_KEY_SECRET 派生失败，请检查 JDK 环境", e);
        }
    }
}
