package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.internal.InternalResults;
import com.novel.common.internal.NovelIndexDoc;
import com.novel.search.client.ContentClient;
import com.novel.search.document.NovelDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 索引管理 —— 建索引、写文档、删文档。
 *
 * <p>★ 本类只管「索引的结构与生命周期」；<b>检索</b>在 {@link SearchService}。
 * 分开是因为两者的失败后果完全不同：
 * 检索失败要降级（对用户透明），索引管理失败要告警（否则索引会悄悄变旧）。
 *
 * <p>★ 而「增量重建」与「全量重建」的<b>编排</b>（何时调、调几轮、加不加锁）也不在本类，
 * 在 {@code IndexRebuildService}。本类提供的是<b>原语</b>：
 * {@link #ensureIndex()} / {@link #recreateIndex()} / {@link #bulkUpsert} /
 * {@link #delete} / {@link #isEmpty()}。
 * ★★ <b>两者的失败语义是相反的，这是分开的真正理由</b>：
 * 原语失败必须抛（调用方要据此决定 ACK 还是重试），
 * 编排失败要「记下来 + 等下一个周期」——而「等下一个周期」这件事
 * 只有编排那一层知道周期有多长。
 *
 * <h3>★★★ 为什么索引必须【显式声明 mapping】—— 这是 M4 最容易静默失败的一处</h3>
 *
 * <p>直觉上「让 ES 自动推断 mapping」很省事，而它会在此处<b>必然出错</b>，
 * 且错法非常隐蔽。链条是这样的：
 *
 * <ol>
 *   <li>{@code lastUpdateTime} 对<b>新书</b>是 {@code null}（还没发过章节）；
 *   <li>ES 的 dynamic mapping <b>不会</b>为「全部为 null」的字段建 mapping ——
 *       字段压根不出现在 {@code _mapping} 里；
 *   <li>此后任何按它排序的查询直接 <b>HTTP 400</b>：
 *       {@code No mapping found for [lastUpdateTime] in order to sort on}；
 *   <li>★★ 而 S6 的检索是 <b>try/catch 包着的</b>（为了 ES 挂了能降级走 LIKE）——
 *       这个 400 <b>恰好会被那个 catch 吞掉</b>，于是：
 *       <b>搜索不报错、有结果，只是排序悄悄从「相关性」退回了 view_count</b>。
 * </ol>
 *
 * <p>★★★ 也就是说：不写 mapping 的后果不是「搜索 500」，而是
 * <b>「M4 做了半天，用户看到的东西和 M4 之前一模一样」</b>。这是最坏的一类失败。
 *
 * <p>★ 已实测（不是推的）：建了一个只含「无章节新书」的索引，
 * 动态 mapping 里确实<b>没有</b> {@code lastUpdateTime}，
 * 按它排序返回 <b>400</b>；同一份查询打到显式 mapping 的索引上返回 <b>200</b>。
 *
 * <h3>★★★ {@code missing} 【不是】合法的 mapping 参数 —— 已实测，写进去建索引就 400</h3>
 *
 * <p>初稿的设计里，mapping 的 {@code lastUpdateTime} 上写了
 * {@code "missing": "_last"}，想法是「新书没有更新时间，别让它排到最前」。
 * <b>那是错的</b>，实测报文：
 *
 * <pre>
 *   400 mapper_parsing_exception
 *   unknown parameter [missing] on mapper [lastUpdateTime] of type [date]
 * </pre>
 *
 * <p>★★ <b>{@code missing} 是【查询期】的参数，写在 sort 子句里</b>，不是 mapping 参数：
 *
 * <pre>
 *   "sort": [ { "lastUpdateTime": { "order": "desc", "missing": "_last" } } ]
 * </pre>
 *
 * <p>★★★ 而这条错误的<b>后果被上一节放大了</b>：{@code ensureIndex()} 是
 * try/catch + 只记 WARN 的（理由见下），所以「建索引 400」<b>不会让服务起不来</b>——
 * 它会变成「索引永远建不出来」→ 每次检索都抛「索引不存在」→ 被 catch 吞掉 →
 * <b>静默地永远走降级路径</b>。
 * <b>整个 M4 的 ES 部分会一行都不生效，而日志里只有一行 WARN。</b>
 *
 * <p>★ 两条教训合并成一句：<b>「能被 catch 吞掉的错误」必须用【正面验证】去验，
 * 不能靠「没看到报错」下结论。</b> 本类的验证方式是 S3 的阳性对照：
 * 删掉索引 → 重启服务 → 确认索引被建出来且 mapping 里有 6 个字段。
 *
 * <h3>★ 为什么 {@code ensureIndex()} 失败只记 WARN、不外抛</h3>
 *
 * <p>与 R7 绑在一起：<b>ES 不可达时服务必须能起来</b>。
 * 起来之后搜索走降级路径（判断 3），站点整体可用 ——
 * 这比「ES 一挂，搜索服务跟着起不来，网关 503」好得多。
 * ★ 代价正是上面那一节：失败信息的音量必须靠别的办法补回来
 * （全量重建的条数断言 + 定时自愈），不能只靠这一行 WARN。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexService {

    private final ElasticsearchClient client;

    /** 索引名。★ 配置项在 application.yml 的 {@code novel.search.index-name}。 */
    @Value("${novel.search.index-name}")
    private String indexName;

    /**
     * 单次查询能翻到的最深位置，与 {@code novel.search.max-result-window} 同源。
     *
     * <p>★★ 它被写进索引的 <b>{@code max_result_window}</b>（一个 <b>static</b> setting，
     * 建索引之后改不了），同时也被 {@link SearchService} 用作入口处的夹取阈值。
     * <b>让两边读同一个配置项</b>，是为了不出现「应用以为能翻到 20000、索引只让翻到 10000」
     * 这种只能靠 400 才能发现的漂移。
     *
     * <p>★ 注意它只在<b>建索引</b>时生效：索引已存在时改配置不会自动应用，
     * 要走一次全量重建（DELETE + PUT）才会带上新值。这是 static setting 的固有性质。
     */
    @Value("${novel.search.max-result-window}")
    private int maxResultWindow;

    /**
     * 索引定义 —— settings + mappings，一次 PUT 全给它。
     *
     * <p>★ 这里的每一个类型选择都对应一条实测结论，改动前请先看对应注释：
     * <ul>
     *   <li>{@code id: long + index:false} —— 只存不索引（没有查询按 id 检索）；</li>
     *   <li>三个 {@code text} 字段都配 <b>{@code analyzer: ik_max_word} +
     *       {@code search_analyzer: ik_smart}</b> —— 建索引切细（召回）、查询切粗（精度）；</li>
     *   <li>{@code status: keyword} —— {@code term} 精确匹配，<b>绝不能被分词</b>；</li>
     *   <li>{@code lastUpdateTime: date} —— 默认 format，收 ISO-8601 字符串。</li>
     * </ul>
     *
     * <p>★★ {@code number_of_replicas: 0} 是<b>必须显式写</b>的，不是调优：
     * 本环境是<b>单节点</b> ES，而副本数的默认值是 1 ——
     * 单节点上放不下副本，集群健康会变成 <b>yellow</b>。
     * ★ 而 S0/S1 的验收判据写的就是 {@code status = green}，
     * 不写这一行的话，<b>本索引一被建出来，那条判据就开始失败</b>，
     * 而失败的原因看起来像是「ES 坏了」。
     *
     * <p>★★ <b>不要在这里加 {@code "missing": "_last"}</b> —— 见类注释，
     * 那会让建索引直接 400。
     *
     * <p>★ {@code %d} 是 {@link #maxResultWindow} 的占位符，
     * 由 {@link #indexDefinition()} 填。JSON 里除了它没有别的 {@code %}。
     */
    private static final String INDEX_DEFINITION = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "max_result_window": %d
              },
              "mappings": {
                "properties": {
                  "id":             { "type": "long", "index": false },
                  "title":          { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "authorName":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "intro":          { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "status":         { "type": "keyword" },
                  "lastUpdateTime": { "type": "date" }
                }
              }
            }
            """;

    private String indexDefinition() {
        return INDEX_DEFINITION.formatted(maxResultWindow);
    }

    /**
     * 索引名，<b>只为日志与排障</b>。
     *
     * <p>★ 为什么需要这个访问器：全量重建的日志分散在<b>两个类</b>里
     * （原语在本类、编排在 {@code IndexRebuildService}），
     * 而「这次重建动的是哪个索引」必须能从两边任一条日志里读出来 ——
     * 否则在索引名不同的环境（dev 与 prod 可能不一样）里，
     * 排障的人得对着代码猜日志属于哪套环境。
     */
    public String indexName() {
        return indexName;
    }

    /**
     * 真正把索引建出来。<b>★ 外抛异常</b> —— 调用方各自决定怎么处理
     * （{@link #ensureIndex()} 吞掉，{@link #recreateIndex()} 外抛）。
     *
     * <p>★ 用 {@code withJson} 而不是类型化 DSL：mapping 里全是分析器名字符串，
     * 用类型化 DSL 写出来是一大坨 builder，可读性远不如这段 JSON ——
     * 而这段 JSON 恰好也是排障时手工敲给 ES 的那一段，能一一对上。
     *
     * <p>★ {@code .index(indexName)} 与 {@code .withJson(...)} 可以共存：
     * {@code withJson} 只覆盖 JSON 里<b>出现过的</b>字段，这段 JSON 里没有 {@code "index"}，
     * 所以前面设的索引名不会被冲掉。
     */
    private void createIndex() throws IOException {
        client.indices().create(c -> c
                .index(indexName)
                .withJson(new StringReader(indexDefinition())));

        log.info("索引创建成功：{}（analyzer=ik_max_word / search_analyzer=ik_smart，max_result_window={}）",
                indexName, maxResultWindow);
    }

    /**
     * 【全量重建第一步】把索引整个删掉再按 {@link #INDEX_DEFINITION} 建回来。
     *
     * <p>★★ <b>为什么重建必须走「删了重建」而不是直接覆盖灌</b>：
     * {@link #bulkUpsert} 只做 upsert，<b>它永远删不掉任何东西</b>。
     * 而索引里确实会留下该消失的文档 —— 被<b>逻辑删除</b>的作品（{@code @TableLogic}
     * 是 MyBatis 往 WHERE 里注入条件，ES 不知道有这一列）、
     * 以及消息丢失期间任何一次状态变化留下的陈旧值。
     * <b>不删索引的话，那些墓碑会永远留在那里</b>，{@code total} 一直虚高。
     *
     * <p>★★ <b>代价：DELETE 与下面的灌数据之间有一个窗口，索引是空的。</b>
     * 这个窗口里每一次检索都会抛「索引不存在」→ 被 {@code SearchService} 的 catch 接住
     * → <b>自动走降级路径（MySQL LIKE）</b>。也就是说窗口内搜索<b>仍然可用</b>，
     * 只是排序退回阅读量。★ 这是刻意的取舍，不是没想到：
     * 更稳的做法是「临时索引 + alias 原子切换」，那属于 M5（见 M4 文档）。
     *
     * <p>★★ <b>本方法外抛，与 {@link #ensureIndex()} 的「吞掉只记 WARN」相反。</b>
     * 理由和 {@link #bulkUpsert} 一样：这是一次<b>显式</b>的重建，
     * 调用方（定时任务 / 管理端点）必须知道它成没成 ——
     * 吞掉的话，症状会是「管理员点了重建、页面说成功、索引其实是空的」。
     *
     * <p>★ 索引本来就不存在时不算错（幂等）：先问再删，而不是靠忽略 404。
     */
    public void recreateIndex() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                client.indices().delete(d -> d.index(indexName));
                log.info("重建：旧索引已删除 | index={}", indexName);
            } else {
                log.info("重建：索引本来就不存在，直接创建 | index={}", indexName);
            }

            createIndex();

            /*
             * ★ 建完再确认一次。createIndex() 内部没有读回校验，
             *   而【建失败 + 后面接着灌】的症状是 bulk 报 index_not_found_exception ——
             *   那条错误信息会把人引向「bulk 写错了」，而不是「索引没建出来」。
             *   多花一次往返，换一个准确的报错。
             */
            if (!client.indices().exists(e -> e.index(indexName)).value()) {
                throw new IllegalStateException("索引创建后仍不存在：index=" + indexName);
            }

        } catch (IOException e) {
            throw new IllegalStateException("索引重建失败（IO）：index=" + indexName, e);
        }
    }

    /**
     * 【重建失败的补救】把索引删掉 —— <b>让检索回落到降级路径</b>。
     *
     * <p>★★★ <b>这是 S8 实测逼出来的一个方法，不是设计时就想到的。</b>
     * 起因是一次真实的重建失败（ES 十秒无响应）：{@link #recreateIndex()} 先删后建，
     * DELETE 那一步成功了、{@link #createIndex()} 超时了 —— 于是索引<b>没了</b>，
     * 之后每一次检索都抛「索引不存在」→ 自动降级 →
     * <b>结果仍然完整，只是排序退回阅读量</b>。
     *
     * <p>★★ 真正要命的是【另一半】：手工再触发一次，{@code createIndex()} 这次成功了，
     * 而 {@code refill()} 的 Feign 读超时 —— 于是留下一个
     * <b>「存在、且是空的」索引</b>。这时候检索<b>既不抛异常、也不降级</b>，
     * 直接返回 {@code total=0}：<b>用户看到「没有找到相关的作品」，
     * 而库里明明有这本书，并且没有任何一条日志说过哪里不对。</b>
     *
     * <p>★★★ <b>两种失败态的严重程度是反直觉的</b>，这一条是本方法存在的全部理由：
     * <pre>
     *   索引不存在   → 抛异常 → 降级到 MySQL LIKE → 结果【是全集】，只是排序退化  ← 好
     *   索引存在但空 → 无异常 → 返回 0 条         → 结果【是空的】，且无人报错    ← 坏
     * </pre>
     * 所以重建失败时，<b>「把索引删掉」比「留着半成品」更接近正确</b>。
     * ★ 半成品（灌了几页就断了）同理：那会让一部分书<b>永远搜不到</b>，
     * 而降级路径至少保证每本书都还能被搜到、只是不按相关性排。
     *
     * <p>★ <b>best-effort，绝不外抛</b>：它是异常处理路径上的一步，
     * 本身再失败也只能记 WARN —— 调用方此刻手上已经有一个更要紧的异常要往外带，
     * 这里再抛一个只会把它盖掉，让人以为是「删索引失败」而不是「重建失败」。
     *
     * <p>★★ <b>它只解决「失败时停在哪个状态」，不解决「窗口本身」</b> ——
     * 重建期间仍然有一段时间索引不存在。彻底的做法是 M5 的
     * 「临时索引 + alias 原子切换」：那样连删都不用删，失败时<b>旧索引原封不动</b>，
     * 连降级都不需要。★ 这次实测把那个 M5 项从「更稳一点」变成了
     * <b>「修掉一个真实的破坏性失败模式」</b>，并给了它一个可复现的复现步骤。
     */
    public void dropQuietly() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                client.indices().delete(d -> d.index(indexName));
                log.warn("重建失败，已把【空的/半成品】索引删掉，检索将回落到降级路径（MySQL LIKE）"
                        + " | index={}。★ 这是刻意的：留着它会让搜索静默返回 0 条", indexName);
            }
        } catch (Exception e) {
            log.warn("重建失败后清理索引也没成功，索引可能停在空/半成品状态"
                            + "（★ 那种状态下搜索会静默少结果，不报错） | index={}, 原因={}: {}",
                    indexName, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 索引里有没有文档。<b>★ 索引不存在也算「空」。</b>
     *
     * <p>★★ <b>「不存在」和「存在但 0 条」必须归成同一类</b>，否则启动自愈会漏掉一半的场面：
     * 首次部署时索引不存在（该灌），上次重建在 DELETE + 建完之后崩了则索引存在但是空的
     * （<b>同样该灌</b>，而且更危险 —— 它看起来「索引已经建好了」）。
     *
     * <p>★★ <b>探不到 ES 时返回 {@code false}（= 别动）</b>，方向是刻意选的：
     * 这个方法的调用方下一步就是<b>删索引</b>。在「连不上、什么都不知道」的时候
     * 返回 true，等于让一次网络抖动变成一个删索引的理由。
     * <b>拿不准就别动</b> —— 这里少灌一次的代价只是「等下一个周期」，
     * 而误判的代价是「索引被清空」。
     */
    public boolean isEmpty() {
        try {
            if (!client.indices().exists(e -> e.index(indexName)).value()) {
                return true;
            }
            long count = client.count(c -> c.index(indexName)).count();
            return count == 0;
        } catch (Exception e) {
            log.warn("无法确认索引状态，本次跳过启动自愈（不动比乱动安全）：index={}, 原因={}: {}",
                    indexName, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * 确保索引存在 —— 不存在就按 {@link #INDEX_DEFINITION} 建一个。
     *
     * <p>★ 幂等：已存在时什么都不做（<b>尤其不会去覆盖已有的 mapping</b> ——
     * 索引里可能已经有数据，改 mapping 是需要重建的破坏性操作，不该藏在
     * 「确保存在」这个语义里）。
     *
     * <p>★ 由 {@code IndexInitializer}（ApplicationRunner）在启动时调用，
     * <b>不是 {@code @PostConstruct}</b>：
     * {@code @PostConstruct} 跑在 bean 初始化期，那时如果 ES 不可达，
     * 异常会往上冒成「bean 创建失败」→ <b>整个服务起不来</b>，
     * 而这里要的恰恰是「ES 挂了也能起」。
     *
     * <p>★★ 失败只记 WARN、不外抛，理由见类注释。但请记住那个代价：
     * <b>这一行 WARN 是唯一的声音，它必须能被验证到（S3 的阳性对照）。</b>
     */
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.info("索引已存在，跳过创建：{}", indexName);
                return;
            }
            createIndex();

        } catch (Exception e) {
            /*
             * ★★ 这一行 WARN 的措辞是刻意写全的：它要能让人一眼看出
             *    「搜索已经在降级路径上跑了」，而不是一句笼统的「索引初始化失败」。
             *    因为这是 ensureIndex 失败时【唯一】的可见信号。
             */
            log.warn("索引初始化失败，搜索将全程走降级路径（MySQL LIKE）：index={}, 原因={}: {}。"
                            + "★ 注意：服务不会因此崩溃，也不会重试 —— 这是一条【静默降级】。"
                            + "修复步骤：先确认 ES 可达（curl <es-host>:9200/_cluster/health 应是 green），再重启本服务。",
                    indexName, e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /* ================================================================
     *  写入 —— 增量重建（S7，消息触发）与全量重建（S8）共用
     *
     *  ★★ 本类只提供【原语】：建 / 删索引、灌一批文档、删一个文档、问一句空不空。
     *     「什么时候调、按什么顺序调、几页」是【编排】，在 IndexRebuildService ——
     *     那里还要拿 Redis 锁、要 Feign 回调 content 取数，都是 ES 之外的事。
     *     ★ 这么分是因为两者的失败后果不同：原语失败要抛，
     *       编排失败要「记下来 + 等下一个周期」，判断在编排那一侧。
     * ================================================================ */

    /**
     * 写入 / 覆盖一批文档。<b>★ 增量与全量共用这一个实现</b>，理由见下。
     *
     * <p>★★ <b>为什么用 {@code _id = novelId}</b>：ES 的 index 操作是
     * 「有则整条覆盖、无则插入」（upsert 语义），而我们把 {@code _id} 设成作品 id。
     * 于是「重建某一本」天然幂等 —— <b>不需要先查、不需要比对、重复调用结果一样</b>。
     * ★ 这正是 {@code NovelIndexMessage} 敢只放一个 id 的底气：
     * 消息重复、乱序，最终都收敛到同一个文档。
     *
     * <p>★★ <b>为什么增量也用 bulk、不图省事用单条 index</b>：
     * 单条和 bulk 的差别不只是性能，而是<b>失败时的可见性</b>。
     * bulk 的响应体里每条独立报错（{@code items[].error}），而
     * <b>HTTP 状态码只表示「这次批量请求本身成没成」</b> —— 某一条 mapping 冲突时
     * 请求是 <b>200</b>，错在响应体里。★ 不解析 {@code errors} 标志的话，
     * 症状是「灌了 1000 条、成功了 998 条、没有任何报错」。
     *
     * <p>★ 空列表直接返回：ES 的 bulk 收到空体会报 {@code Validation Failed: 1: no requests added}，
     * 那是个纯噪音的 400。
     *
     * @param documents 待写入文档；{@code null} 或空列表视为无事可做
     * @throws RuntimeException ES 不可达或写入失败 —— ★ <b>本方法【故意】外抛</b>，
     *         与 {@link #ensureIndex()} 的「吞掉只记 WARN」相反：
     *         索引写失败必须让调用方（消费者）知道，否则消息会被 ACK 掉、索引永久陈旧。
     */
    public void bulkUpsert(List<NovelDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(indexName);
                for (NovelDocument doc : documents) {
                    /*
                     * ★ 每一条都显式给 _id = id。不给的话 ES 会自己生成随机 id，
                     *   于是「重建同一本书」变成「每重建一次多一条文档」——
                     *   检索结果里出现重复的书，而且越重建越多。
                     */
                    b.operations(op -> op.index(idx -> idx
                            .id(String.valueOf(doc.getId()))
                            .document(doc)));
                }
                return b;
            });

            /*
             * ★★ 必须解析 errors 标志 —— 见方法注释：bulk 的部分失败是 200。
             *   这里【外抛】而不是记 WARN，因为调用方要据此决定「ACK 还是重试」。
             */
            if (response.errors()) {
                String detail = response.items().stream()
                        .filter(i -> i.error() != null)
                        .map(i -> i.id() + "=" + i.error().reason())
                        .limit(5)
                        .collect(Collectors.joining("; "));
                throw new IllegalStateException(
                        "bulk 写入部分失败：" + detail + "（仅列前 5 条，共 " + documents.size() + " 条）");
            }

            log.debug("索引写入成功 | index={} count={}", indexName, documents.size());

        } catch (IOException e) {
            /*
             * ★ IOException 单独捕获并转成 RuntimeException：ES 客户端的 IOException
             *   是【受检】异常，而消费者那边要按「基础设施异常 → 抛出去重试」处理
             *   （见 NovelIndexConsumer）。统一成 RuntimeException 让上层的分类不依赖具体异常类型。
             */
            throw new IllegalStateException("索引写入失败（IO）：index=" + indexName, e);
        }
    }

    /**
     * 按作品 id 删除索引文档。<b>★ 逻辑删除在 ES 侧的对应物。</b>
     *
     * <p>★★ 为什么必须有这个方法：{@code novel} 的逻辑删除靠 {@code @TableLogic}
     * —— 那是 MyBatis 在 SQL 里注入 {@code deleted = 0}，
     * <b>ES 完全不知道 `deleted` 这一列存在</b>。
     * 不显式删的话，那条文档会永久留在索引里，
     * 后果是 {@code total} 虚高 + 页内空洞（装配端查不到这本书，那一页就少一条）。
     *
     * <p>★ <b>删一个不存在的 id 是成功</b>（ES 返回 {@code result: not_found}），
     * 不抛异常 —— 这正是我们要的：重复消费、或「删了两次」都不该报错。
     *
     * <p>★ 与 {@link #bulkUpsert} 一样故意外抛。
     */
    public void delete(Long id) {
        if (id == null) {
            return;
        }
        try {
            client.delete(d -> d.index(indexName).id(String.valueOf(id)));
            log.debug("索引文档已删除 | index={} id={}", indexName, id);
        } catch (IOException e) {
            throw new IllegalStateException("索引删除失败：index=" + indexName + " id=" + id, e);
        }
    }
}
