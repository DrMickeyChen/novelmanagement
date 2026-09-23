package com.novel.search.config;

import com.novel.search.service.IndexRebuildService;
import com.novel.search.service.IndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时的索引编排 —— 保证「服务起来了」等于「索引在一个已知状态」。
 *
 * <h3>★ 为什么是 {@code ApplicationRunner}，而不是 {@code @PostConstruct}</h3>
 *
 * <p>两者差在<b>失败时会发生什么</b>：
 *
 * <table border="1">
 *   <caption>三种时机对照</caption>
 *   <tr><th>时机</th><th>ES 不可达时</th><th>能用吗</th></tr>
 *   <tr><td>{@code @PostConstruct}</td><td>异常冒成 bean 创建失败 → <b>服务起不来</b></td><td>✗</td></tr>
 *   <tr><td>{@code ApplicationRunner}</td><td>同样会冒异常 → <b>服务起不来</b>（除非自己 catch）</td><td>△</td></tr>
 *   <tr><td>{@code @Scheduled}</td><td>启动不受影响</td><td>✗ 但启动那一刻没有索引</td></tr>
 * </table>
 *
 * <p>★★ 所以关键<b>不是</b>「选了 ApplicationRunner 就安全」——
 * {@code ApplicationRunner} 抛异常一样会让启动失败。
 * 真正让它安全的是 <b>{@link IndexService#ensureIndex()} 自己把异常吞掉了</b>（只记 WARN）。
 * ★ 这一条必须说清楚，否则下一个人会以为「换成 Runner 就万事大吉」，
 * 然后把 ensureIndex 里的 try/catch 删掉做「清理」——
 * <b>那一删，ES 一挂整个搜索服务就起不来了。</b>
 *
 * <p>★ 那既然异常都被吞了，为什么还要用 Runner 而不是 {@code @PostConstruct}？
 * 因为语义：<b>{@code @PostConstruct} 的约定是「这个 bean 自己准备就绪」，
 * 而这里做的是「确保一个【外部系统】处于已知状态」</b> ——
 * 后者不该混进 bean 的生命周期里。★ 而且 Runner 跑在<b>整个容器就绪之后</b>，
 * 那时 Feign / Nacos / Redis 都已可用 ——
 * ★ <b>S8 兑现了这句话</b>：下面那段「索引是空的就灌一次全量」正是靠这个时机才有意义，
 * 它要 Feign 调 content、要 Redis 拿锁，两样在 bean 初始化期都还没就绪。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final IndexService indexService;
    private final IndexRebuildService indexRebuildService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动检查：确认 ES 别名存在……");
        indexService.ensureIndex();

        /*
         * ── S8：索引是空的就自动灌一遍 ──
         *
         * ★★ 两种场面归成同一类，见 IndexService#isEmpty 的注释：
         *   ① 【首次部署】别名压根不存在 —— ★ 注意 ensureIndex() 现在
         *      【什么都不建】（S8 起建空索引是它最该避免的事），
         *      所以这一支是靠下面的 rebuildAll() 从零建出来的；
         *   ② 【别名没了】—— 实测里它就是这个样子出现的：
         *      ★★★ ES 在索引被删掉时会【连带删掉指向它的别名】，
         *      所以「别名悬空」不会以悬空的形态存在，它以「别名不存在」出现。
         *
         * ★★ 原文这里写的是「② 上次重建中途崩了：别名悬着、或指向一个空索引」——
         *   **S8 之后这一支基本不可达了，理由是别名本身**：
         *   重建崩在切换【之前】→ 别名还指着那个完好的旧索引（有数据）；
         *   崩在切换【之后】→ 别名指着新索引（也有数据）；
         *   而切换是【一次】_aliases 调用（先摘旧再加新），中间没有第三种状态。
         *   → 于是「崩溃」不再产出空索引，「空」只剩【首次部署】这一个成因。
         *   ★ 留这段话是因为它正是 S8 的收益，而不是一句过时的描述。
         *
         * ★★★ 判据是【别名】不是【索引】（S8 起两者的区别是承重的）：
         *   若这里问的是「索引存在吗」，那在别名世界里它【永远为真】，
         *   这一整段自愈从此再也不会触发 —— 一条永远为真的判据
         *   （铁律 8 的第二种形状：判据本身永远为真）。见 IndexService#isEmpty。
         */
        if (indexService.isEmpty()) {
            log.info("索引是空的（★ 按上面的分析，实际上只剩【首次部署】这一个成因），自动灌一次全量……");
            try {
                IndexRebuildService.RebuildReport report = indexRebuildService.rebuildAll();
                /*
                 * ★★★ S8 实测补的：skipped 必须【分开说】，不能和成功共用一句「完成」。
                 *
                 *   原来的写法是 `启动自愈完成 | 灌入=0 条 耗时=0ms skipped=true` ——
                 *   字面上每个字段都对，但第一眼读到的词是【完成】。
                 *   而它实际的含义是【什么都没做，索引现在仍然是空的】。
                 *   ★ 这正是铁律 8 的形状：一条让「做了」和「没做」长得一样的日志。
                 *   ★ 而且实测真的踩到了：一次【成功】的重建留下的幽灵锁
                 *     （S8 之前不释放）让这条自愈静默跳过，ES 里一个索引都没有。
                 */
                if (report.skipped()) {
                    /*
                     * ★★ S9 之后「没拿到锁」的成因收窄了，可以照着念：
                     *   失败的重建**也会放锁**了（见 IndexRebuildService#rebuildAll），
                     *   所以现在留着锁的只有两种 —— 【另一个重建真的在跑】、
                     *   或者【上一次被 kill -9】（那种只能等 TTL 到期）。
                     *   ★ 换句话说这句话从 S9 起是【真的】，S8 那会儿它有一半场合是假的。
                     */
                    log.warn("启动自愈【没有执行】：没拿到锁（另一个实例正在重建，或上一次被强杀留下的锁）—— "
                                    + "★ 索引现在仍然是空的，全站搜索走 MySQL LIKE 降级，直到那一轮跑完。"
                                    + "人工入口：POST /api/admin/search/reindex | 耗时={}ms",
                            report.elapsedMs());
                } else {
                    log.info("启动自愈完成 | 灌入={} 条 耗时={}ms",
                            report.indexed(), report.elapsedMs());
                }

            } catch (Exception e) {
                /*
                 * ★★★ 【这里必须吞掉，这是本方法最重要的一行。】
                 *
                 * 本类是个 ApplicationRunner，异常冒出去 = 【服务启动失败】。
                 * 而这一步失败的最常见原因是「ES 或 content 还没起来」——
                 * 那是启动顺序问题，不是配置错误。
                 *
                 * ★★ 让它冒出去的后果是灾难性的：R7 定下的规矩是
                 *   「ES 挂了，搜索服务必须能起来」（起来之后走降级路径，站点整体可用）。
                 *   而 if 这一支里如果抛出，就变成「ES 挂了 → 搜索服务起不来 →
                 *   网关 503」—— 把一条【可降级】的故障升级成【全站不可用】。
                 *   ★ 更糟的是它的触发条件：只在「索引恰好是空的」那一次启动时才会走到，
                 *     也就是说它平时不出现，只在最需要服务能起来的场面出现。
                 *
                 * ★ 所以这里吞掉、记 WARN，代价是「索引保持空着，搜索全程降级」——
                 *   那正是判断 3 里那条现成的、正确的降级路径。
                 *   恢复手段有两个：下一个 6 小时周期，或者管理端点手工触发。
                 *   ★ 两者的存在是这一步敢吞掉的前提，不是巧合。
                 */
                log.warn("启动自愈失败，索引暂时为空 —— 搜索将全程走降级路径（MySQL LIKE）。"
                                + "★ 服务照常提供，不阻塞启动。恢复：等下一轮定时重建（6 小时），"
                                + "或让 ADMIN 调 POST /api/admin/search/reindex。原因={}: {}",
                        e.getClass().getSimpleName(), e.getMessage(), e);
            }
        } else {
            log.info("索引非空，跳过启动自愈（增量消息负责它的新鲜度）");
        }
    }
}
