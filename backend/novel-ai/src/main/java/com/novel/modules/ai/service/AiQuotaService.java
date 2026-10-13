package com.novel.modules.ai.service;

/**
 * AI 每日配额（E1）。
 *
 * <h3>★★★ 它保护的东西和本项目已有的每一条限流都不一样：<b>这次是钱</b></h3>
 *
 * <p>评论 10 秒频控防的是<b>刷屏</b>，登录失败计数防的是<b>撞库</b> ——
 * 两者失败时选的方向都是「放过去」（见 {@code CommentServiceImpl#requireNotTooFast}
 * 里那段「Redis 抽风时让评论发出去是更安全的方向」）。
 *
 * <p>★★ <b>配额的方向【正好相反】：宁可少给，不可多发。</b>
 * 因为这里超发的每一单位都是<b>真实支出</b>，而且没有任何补偿手段 ——
 * 钱花出去就回不来了。所以本类的所有失败路径都<b>不让请求通过</b>，
 * 包括 Redis 挂掉（会直接抛 500，而不是「当成没超限」）。
 *
 * <p>★ 这个取舍要写在这里，因为它是<b>和项目里其它限流相反</b>的一条 ——
 * 下一个人看到「别的限流都容错、就这个不容错」时，会以为是漏了。
 *
 * <h3>★★ 「检查」和「记账」是两件事，不要合并</h3>
 *
 * <p>{@link #acquire} 是<b>权威闸门</b>（原子、精确）；
 * {@link #remaining} 只是<b>给界面看的数字</b>。两者之间必然有一个窗口，
 * 这是明确的取舍：<b>选精确扣减</b>，代价是并发下退化成错误帧
 * （用户看到「生成失败」而不是「额度用完」，但<b>额度没有超发</b>）。
 *
 * @author MickeyChen
 */
public interface AiQuotaService {

    /** 今天已经用掉多少次。★ 只读，用于展示 */
    int usedToday(Long userId);

    /**
     * 今天还剩多少次。★ <b>它不是一个可以拿来做判断的数</b> ——
     * 判断要用 {@link #acquire}，见类注释。
     */
    int remaining(Long userId, int dailyLimit);

    /**
     * 占一个名额。<b>这是唯一的闸门。</b>
     *
     * <p>顺序（每一步都有理由，别按直觉重排）：
     * <pre>
     *   ① SET key 0 NX EX 90000      ← TTL 的建立必须是原子的
     *   ② INCR key                   ← 保留 TTL，不会把过期时间冲掉
     *   ③ n &gt; limit ? DECR; 抛 5004  ← 先占位再判断，满了就【还回去】
     * </pre>
     *
     * @throws com.novel.common.exception.BusinessException 额度用完（{@code AI_QUOTA_EXCEEDED}）
     */
    void acquire(Long userId, int dailyLimit);
}
