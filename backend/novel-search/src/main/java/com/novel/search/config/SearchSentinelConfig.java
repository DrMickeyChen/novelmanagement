package com.novel.search.config;

import com.novel.common.sentinel.SentinelRules;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * <b>novel-search 的熔断名单（M5）。</b>
 *
 * <p>规则的构造与阈值的理由都在 {@link SentinelRules}，这里只管名单。
 * 本服务只有一个客户端，而它<b>没写 {@code path}</b>：
 *
 * <pre>
 *   ContentClient  name="novel-content"   ← 没有 path 属性
 * </pre>
 *
 * <h3>★★ 所以这四条的资源名是 {@code http://novel-content/...} 而不是 {@code …/internal/...} 起头</h3>
 *
 * <p>这看起来像笔误，其实是公式的直接结果（见 {@link SentinelRules} 类注释）：
 * {@code path} 缺省时 {@code cleanPath()} 是空串，资源名就退化成
 * {@code "http://" + name}。<b>方法上的 {@code /internal/...} 是自己带的</b>，
 * 于是四种名字看起来长得一样、来源却完全不同。
 *
 * <p>★ 实测对照（M5 S4，从 {@code /tree} 里读出来的原文）：
 * <pre>
 *   interaction 的 ContentClient  → POST:http://novel-content/internal/novels/briefs
 *   search 的 ContentClient       → POST:http://novel-content/internal/novels/reader-vos
 * </pre>
 * <b>两条都是「internal 开头」，但一条来自类的 path、一条来自方法。</b>
 * 照抄另一条会拼出一个永远不触发的名字。
 *
 * <h3>★★ 这四条全部不降级，理由和 interaction 一样但更硬</h3>
 *
 * <p>search 的 {@code NovelVO} 装配<b>必须回 content 拿</b>（search 没有数据库，
 * 见 {@code ContentClient} 的类注释）—— 没有 content 就没有书。
 * 唯一能「降级」的是<b>检索内核</b>（ES 挂了走 MySQL LIKE），
 * 而那件事 M4 已经做完了，和这里的四条边无关。
 *
 * <p>★ 其中 {@code /internal/search} 就是那条降级路径本身 ——
 * <b>它也要经过本名单</b>，因为「ES 挂了」和「content 挂了」是两件事：
 * 前者有降级，后者没有。给这条边配熔断的含义是
 * 「content 慢到一定程度时，降级路径也快速失败」，而不是「降级降级」。
 *
 * <p>★★ 顺带记一条已知的归因偏差（M5 判断 4，<b>不修</b>）：
 * {@code SearchService} 的 {@code try} 把「ES 检索 + 它内部那次 Feign 调 content」
 * 一起包住了，catch 里写的却是「ES 检索失败，降级到数据库 LIKE」。
 * 所以<b>停 content 时日志会指向 ES</b>。M5 不重写那段（它是 M4 的既有设计），
 * 只在这里和文档里各记一笔。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class SearchSentinelConfig {

    @PostConstruct
    public void loadRules() {
        SentinelRules.loadSlowCallRules("novel-search",
                // ↓ ContentClient（name="novel-content"，★ 没有 path）
                "POST:http://novel-content/internal/novels/reader-vos",
                "POST:http://novel-content/internal/novels/index-docs",
                "GET:http://novel-content/internal/novels/index-page",
                "GET:http://novel-content/internal/search");
    }
}
