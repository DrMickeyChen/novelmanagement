package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.novel.search.document.NovelDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * {@link #newPhysicalIndexName()} / {@link #createPhysicalIndex(String)} / {@link #switchAlias(String)} /
 * {@link #bulkUpsert(List)} / {@link #delete(Long)} / {@link #isEmpty()}。
 * ★★ <b>两者的失败语义是相反的，这是分开的真正理由</b>：
 * 原语失败必须抛（调用方要据此决定 ACK 还是重试），
 * 编排失败要「记下来 + 等下一个周期」——而「等下一个周期」这件事
 * 只有编排那一层知道周期有多长。
 *
 * <h3>★★★ M5 · S8：{@code novel_index} 从【索引】变成【别名】</h3>
 *
 * <p>M4 的全量重建是「<b>先 DELETE 索引，再 PUT 一个空的，然后往里灌</b>」。
 * 它在 DELETE 到灌完之间留下了一个窗口，而那个窗口有<b>两种</b>后果，
 * 严重程度是反直觉的（M4 亲眼实测到过第二种）：
 *
 * <pre>
 *   索引不存在   → 检索抛异常 → 降级到 MySQL LIKE → 结果【是全集】，只是排序退化  ← 好
 *   索引存在但空 → 检索无异常 → 直接返回 0 条     → 结果【是空的】，且无人报错    ← 坏
 * </pre>
 *
 * <p>M4 的处置是<b>止血</b>（{@code dropQuietly()}：重建失败时把索引删掉，
 * 让系统停在「可降级」的那一侧）。<b>M5 的处置是根治：换用别名。</b>
 *
 * <p>做法是把「检索用的名字」和「数据住在哪个索引里」拆开：
 * <ul>
 *   <li>{@code novel_index} —— <b>别名</b>。检索代码、增量写入、删文档全都继续用它，
 *       <b>一行不改</b>（搜别名和搜索引是同一个词法）；</li>
 *   <li>{@code novel_index_v{时间戳}} —— <b>物理索引</b>，真正装数据的地方。
 *       每次全量重建建一个新的，灌满之后<b>原子切换</b>别名指向它，再删掉旧的。</li>
 * </ul>
 *
 * <p>★★★ <b>于是「索引存在但为空」这个状态物理上无法出现</b>：
 * 别名只在「新索引已经装满」之后才被指过去，
 * 而在那之前它一直指着旧的、完好的索引。搜索<b>全程无需降级</b>，
 * 重建失败时旧索引甚至<b>原封不动</b>。
 *
 * <p>★ 代价如实写，两条：
 * <ol>
 *   <li><b>增量写入在重建期间落在旧索引上</b>，切换之后那些改动就随旧索引一起被删掉了
 *       —— 完整论证与它的恢复路径见 {@code IndexRebuildService} 的类注释；</li>
 *   <li><b>多了一层间接</b>：排障时 {@code GET /novel_index} 不再能直接看到 mapping，
 *       要走 {@code GET /_alias/novel_index} 先问出物理索引名。
 *       ★ 这也是下面每个方法的日志都<b>同时打别名和物理索引名</b>的原因。</li>
 * </ol>
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

    /**
     * <b>别名名</b>（M5 · S8 起）★ 配置项在 application.yml 的 {@code novel.search.index-name}。
     *
     * <p>★ 配置项的名字和值都没改，改的是<b>它指向的东西的性质</b> ——
     * 从「一个物理索引」变成「一个别名」。检索代码因此一行都不用动，
     * 但<b>排障的手感变了</b>：{@code GET /novel_index} 不再是看 mapping 的地方，
     * 要先 {@code GET /_alias/novel_index} 问出物理索引名。见类注释。
     */
    @Value("${novel.search.index-name}")
    private String indexName;

    /**
     * 物理索引名 = {@code <别名>_v<yyyyMMddHHmmss>}。
     *
     * <p>★ 带时间戳是为了<b>排障时一眼看出新旧</b>：{@code GET /_cat/indices/novel_index_v*}
     * 直接就是一份重建历史。★ 也因此它<b>不能</b>是固定名 —— 固定名意味着
     * 「新索引装满之前必须先删旧的」，那就把 S8 要消掉的那个空窗又请回来了。
     */
    private static final String PHYSICAL_SUFFIX = "_v";

    /** ★ 用秒级时间戳，不加分隔符：它只出现在运维命令里，越短越好敲。 */
    private static final DateTimeFormatter PHYSICAL_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 为<b>下一次全量重建</b>生成一个新的物理索引名。
     *
     * <p>★ 单独暴露成方法（而不是让编排层自己拼字符串）：名字的形状是本类的知识，
     * 而 {@link #deleteStaleIndices(String)} 要靠同一个前缀去认「哪些是本类建的索引」——
     * 两处各写一遍的话，改了一处就会开始删错东西。
     *
     * <p>★ <b>同一秒内生成两个同名是做不到的</b>，不是「概率很小」：
     * 全量重建被分布式锁串行化，而一次重建要几秒；即使上一轮崩了，
     * 锁的 TTL（30 分钟）也会挡住同秒内的重试。所以不需要去重逻辑 ——
     * 真要撞上，{@code create} 会报 {@code resource_already_exists_exception}，
     * 那是一条【响亮】的失败，比一段没人看得懂的防重代码好。
     */
    public String newPhysicalIndexName() {
        return indexName + PHYSICAL_SUFFIX + LocalDateTime.now().format(PHYSICAL_NAME_FORMAT);
    }

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
     * 【全量重建第一步】按 {@link #INDEX_DEFINITION} 建一个<b>新的空物理索引</b>。
     *
     * <p>★★★ <b>S8 之前这里做的是「先 DELETE 再 PUT」</b>，而 DELETE 与灌完之间
     * 那个窗口就是本期的全部动机（见类注释）。现在它<b>只是建一个新的</b>：
     * 不碰别名、不碰旧索引 —— 建完到切换之间的任何一刻，
     * 检索读到的都还是那个完好的旧索引。
     *
     * <p>★★ <b>为什么重建仍然必须「另起一个索引」而不是往当前索引上覆盖灌</b>：
     * {@link #bulkUpsert} 只做 upsert，<b>它永远删不掉任何东西</b>。
     * 而索引里确实会留下该消失的文档 —— 被<b>逻辑删除</b>的作品（{@code @TableLogic}
     * 是 MyBatis 往 WHERE 里注入条件，ES 不知道有这一列）、
     * 以及消息丢失期间任何一次状态变化留下的陈旧值。
     * <b>不换新索引的话，那些墓碑会永远留在那里</b>，{@code total} 一直虚高。
     * ★ 所以「换个新索引」承担的是原来 DELETE 的职责，而它<b>不再有破坏性</b>。
     *
     * <p>★ 用 {@code withJson} 而不是类型化 DSL：mapping 里全是分析器名字符串，
     * 用类型化 DSL 写出来是一大坨 builder，可读性远不如这段 JSON ——
     * 而这段 JSON 恰好也是排障时手工敲给 ES 的那一段，能一一对上。
     *
     * <p>★ {@code .index(...)} 与 {@code .withJson(...)} 可以共存：
     * {@code withJson} 只覆盖 JSON 里<b>出现过的</b>字段，这段 JSON 里没有 {@code "index"}，
     * 所以前面设的索引名不会被冲掉。
     *
     * <p>★★ <b>本方法外抛，与 {@link #ensureIndex()} 的「吞掉只记 WARN」相反。</b>
     * 理由和 {@link #bulkUpsert} 一样：这是一次<b>显式</b>的重建，
     * 调用方（定时任务 / 管理端点）必须知道它成没成 ——
     * 吞掉的话，症状会是「管理员点了重建、页面说成功、索引其实是空的」。
     *
     * @param physicalName 新物理索引名，来自 {@link #newPhysicalIndexName()}
     */
    public void createPhysicalIndex(String physicalName) {
        try {
            client.indices().create(c -> c
                    .index(physicalName)
                    .withJson(new StringReader(indexDefinition())));

            log.info("物理索引创建成功：{}（analyzer=ik_max_word / search_analyzer=ik_smart，max_result_window={}）",
                    physicalName, maxResultWindow);

            /*
             * ★ 建完再确认一次。create 内部没有读回校验，
             *   而【建失败 + 后面接着灌】的症状是 bulk 报 index_not_found_exception ——
             *   那条错误信息会把人引向「bulk 写错了」，而不是「索引没建出来」。
             *   多花一次往返，换一个准确的报错。
             */
            if (!client.indices().exists(e -> e.index(physicalName)).value()) {
                throw new IllegalStateException("物理索引创建后仍不存在：index=" + physicalName);
            }

        } catch (IOException e) {
            throw new IllegalStateException("物理索引创建失败（IO）：index=" + physicalName, e);
        }
    }

    /**
     * 【全量重建最后一步】把别名<b>原子地</b>指向新索引。
     *
     * <p>★★★ <b>「原子」不是修饰词，是这个方法存在的全部意义。</b>
     * 拆成「先删旧指向、再加新指向」两次调用，中间就有一段
     * <b>别名指向空</b>的窗口 —— 而那正是 S8 要消掉的那个状态，
     * 只是从「索引变空」换成了「别名变空」。所以两个动作必须放在
     * <b>同一次 {@code _aliases} 请求</b>里，由 ES 保证它一次性生效。
     *
     * <p>★★ <b>为什么还必须显式「摘掉旧的」</b>：{@code PUT /<新索引>/_alias/<别名>}
     * 是<b>追加</b>语义，不是替换 —— 不摘的话别名会同时指向新旧两个索引，
     * 于是<b>每次搜索都返回双份结果</b>，而且每重建一次多一份。
     * 这个坑不会报错，只会让条数慢慢变成两倍。
     *
     * <p>★★ <b>顺带处理一次迁移</b>：M5 之前的部署里 {@code novel_index} 是一个
     * <b>实体索引</b>而不是别名，而 <b>ES 不允许别名和索引同名</b> ——
     * 直接切会吃到 400（{@code invalid_alias_name_exception}）。
     * 所以先看看这个名字是不是被实体索引占着（{@code exists && !existsAlias}），
     * 是就删掉它。★ <b>这个删除是安全的</b>：调用方保证走到这里时
     * 新索引<b>已经装满</b>，数据全在它里面，被删的那个本来就要被替换掉。
     *
     * <h3>★★★ 为什么切换之前必须 refresh —— 这是 M5 实测抓出来的一个真 bug</h3>
     *
     * <p>「灌满」和「<b>检索得到</b>」在 ES 里<b>不是同一件事</b>：
     * bulk 写入默认走 {@code refresh=false}，文档要等下一次 refresh
     * （默认间隔 <b>1 秒</b>）才对搜索可见。
     *
     * <p>于是 S8 的第一版——只做了原子切换、没做 refresh——留下了这样一个窗口：
     *
     * <pre>
     *   t0  bulk 把 12 条写进新索引     ← 写入成功，但【还搜不到】
     *   t1  原子切换别名 → 新索引       ← 别名指过去了
     *   t2  用户的检索落在这个索引上    → 0 条，HTTP 200，没有任何报错
     *   t3  到点了，ES 自己 refresh     → 又恢复了
     * </pre>
     *
     * <p>★★★ <b>也就是说：「索引存在但为空」并没有被消掉，只是从「整个重建期间」
     * 缩短成了「bulk 完成到下次 refresh 之间」。</b>实测（并发 120 次/秒打检索，
     * 同时触发重建）：<b>切换点之后连续 354ms 内，应用和 ES 同时报 0 条</b>；
     * 整轮共采到 <b>123 次</b> {@code total=0}。★ 那正是 S8 存在的理由本身。
     *
     * <p>★★ <b>所以 refresh 不是性能优化，是正确性的一部分</b>，
     * 而且它必须写在这里、不能交给调用方：本方法的不变式是
     * <b>「别名永远不许指向一个检索不到东西的索引」</b>，
     * 这个不变式只有和「切换」写在同一个方法里才拦得住 ——
     * 放在调用方的话，下一个调用方忘了写，这个 bug 就<b>静默地</b>回来了
     * （同一个形状的教训见铁律 8：靠「大家都记得」维持的护栏等于没有护栏）。
     *
     * <p>★ 代价可以忽略：一次 {@code _refresh} 调用，一次重建只发生一次。
     *
     * @param newPhysicalName 已经灌满的新物理索引名
     */
    public void switchAlias(String newPhysicalName) {
        try {
            deleteLegacyPhysicalIndexIfPresent();

            /*
             * ★★★ 先让新索引里的文档【可见】，再切别名。理由见方法注释 ——
             *   少了这一行的症状是「切换后有个几百毫秒的窗口，搜索静默返回 0 条」。
             */
            client.indices().refresh(r -> r.index(newPhysicalName));

            /*
             * ★ 一次请求里把「摘旧的 + 挂新的」一起提交。actions 的顺序无关紧要，
             *   ES 把它们当一个原子批次执行。
             */
            List<Action> actions = new ArrayList<>();
            for (String current : aliasTargets()) {
                actions.add(Action.of(
                        a -> a.remove(r -> r.index(current).alias(indexName))));
            }
            actions.add(Action.of(
                    a -> a.add(g -> g.index(newPhysicalName).alias(indexName))));

            client.indices().updateAliases(u -> u.actions(actions));

            log.info("别名已原子切换 | alias={} → {}（同时摘除旧指向 {} 个）",
                    indexName, newPhysicalName, actions.size() - 1);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "别名切换失败（IO）：alias=" + indexName + " → " + newPhysicalName, e);
        }
    }

    /**
     * 别名当前指向哪些物理索引。<b>★ 正常恰好一个，多于是 bug 的证据。</b>
     *
     * <p>★ 先问 {@code existsAlias} 再取列表：{@code GET /_alias/<不存在的别名>}
     * 会返回 404 并抛异常，而「还没有别名」是首次部署的正常状态，不该走异常分支。
     */
    private List<String> aliasTargets() throws IOException {
        if (!client.indices().existsAlias(e -> e.name(indexName)).value()) {
            return List.of();
        }
        return new ArrayList<>(client.indices().getAlias(g -> g.name(indexName)).result().keySet());
    }

    /**
     * M5 迁移专用：把占着别名名字的<b>实体索引</b>删掉。
     *
     * <p>★★ 判据必须是 <b>{@code exists && !existsAlias}</b> 两条一起 ——
     * 单看 {@code exists} 是错的：{@code HEAD /novel_index} 在它是<b>别名</b>时
     * 同样返回 200（ES 会把别名解析成它指向的索引）。
     * ★ 只判 {@code exists} 的后果是<b>每次重建都把当前索引删掉</b>，
     * 而那正好是 S8 要根除的那个失败模式，只不过这次是代码亲手做出来的。
     */
    private void deleteLegacyPhysicalIndexIfPresent() throws IOException {
        if (client.indices().exists(e -> e.index(indexName)).value()
                && !client.indices().existsAlias(e -> e.name(indexName)).value()) {
            client.indices().delete(d -> d.index(indexName));
            log.warn("迁移：发现名为 {} 的【实体索引】（M5 之前的部署形态），已删除。"
                    + "★ 此刻新索引已经装满，别名马上接管，检索不受影响", indexName);
        }
    }

    /**
     * 【切换之后】删掉过期的物理索引，<b>只留 {@code keep} 那一个</b>。
     *
     * <p>★ 认定方式是按前缀列出（{@code novel_index_v*}）而不是「记住上一次的名字」——
     * 后者要额外存状态，且<b>崩过一次就再也找不到那个孤儿索引了</b>。
     * 前缀是 {@link #newPhysicalIndexName()} 用的同一个常量，两处不会漂移。
     *
     * <p>★★ <b>这个方法的失败【绝不影响正确性】</b>：旧索引此刻已经没有任何人指向它，
     * 它只是占空间。所以它<b>best-effort、绝不外抛</b> ——
     * 让一次「删旧索引失败」把一次<b>已经成功</b>的重建报成失败，
     * 会让人以为索引没切过去，而事实正好相反。
     *
     * <p>★ 它顺带承担了另一个职责：<b>清理上一次重建崩溃留下的孤儿索引</b>
     * （建好了、没灌完、进程没了）。有了它，ES 里不会慢慢攒下一堆
     * 没人指向的 {@code novel_index_v*}。
     */
    public void deleteStaleIndices(String keep) {
        try {
            Set<String> existing = client.indices().get(g -> g
                    .index(indexName + PHYSICAL_SUFFIX + "*")
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)).result().keySet();

            for (String name : existing) {
                if (name.equals(keep)) {
                    continue;
                }
                client.indices().delete(d -> d.index(name));
                log.info("已删除过期物理索引 | index={}（保留 {}）", name, keep);
            }

        } catch (Exception e) {
            log.warn("清理过期物理索引失败 —— 它们只占空间，不影响检索正确性 | keep={} 原因={}: {}",
                    keep, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 【重建失败的补救】把索引删掉 —— <b>让检索回落到降级路径</b>。
     *
     * <p>★★★ <b>它的来历（M4 · S8）：一个实测逼出来的方法。</b>
     * 起因是一次真实的重建失败（ES 十秒无响应）：重建「先删后建」，
     * DELETE 那一步成功了、{@code create} 超时了 —— 于是索引<b>没了</b>，
     * 之后每一次检索都抛「索引不存在」→ 自动降级 →
     * <b>结果仍然完整，只是排序退回阅读量</b>。
     *
     * <p>★★ 真正要命的是【另一半】：手工再触发一次，{@code create} 这次成功了，
     * 而 {@code refill()} 的 Feign 读超时 —— 于是留下一个
     * <b>「存在、且是空的」索引</b>。这时候检索<b>既不抛异常、也不降级</b>，
     * 直接返回 {@code total=0}：<b>用户看到「没有找到相关的作品」，
     * 而库里明明有这本书，并且没有任何一条日志说过哪里不对。</b>
     *
     * <p>★★★ <b>两种失败态的严重程度是反直觉的</b>，这条判据在 S8 之后依然成立：
     * <pre>
     *   索引不存在   → 抛异常 → 降级到 MySQL LIKE → 结果【是全集】，只是排序退化  ← 好
     *   索引存在但空 → 无异常 → 返回 0 条         → 结果【是空的】，且无人报错    ← 坏
     * </pre>
     *
     * <p>★★★ <b>而 S8 之后，「存在但空」只能出现在【重建中途】的新索引上，
     * 而新索引还没有任何东西指向它</b> —— 所以那个坏状态<b>根本不可达了</b>。
     * 这正是本方法<b>被降级成一把通用小工具</b>的原因：
     * 它现在的用途是「重建失败时把那个没人指向的半成品删掉」，<b>不删也不会有人读到它</b>。
     * ★ 于是它从「止血」（决定系统停在哪个状态）变成了「扫地」。
     *
     * <p>★ <b>best-effort，绝不外抛</b>：它是异常处理路径上的一步，
     * 本身再失败也只能记 WARN —— 调用方此刻手上已经有一个更要紧的异常要往外带，
     * 这里再抛一个只会把它盖掉，让人以为是「删索引失败」而不是「重建失败」。
     *
     * @param physicalName 要删的物理索引名。★ <b>故意要求显式传名</b>而不是默认删
     *                     {@code indexName} —— 那个名字现在是<b>别名</b>，
     *                     让它出现在「删除」的语境里，本身就是个随时会误伤的写法。
     */
    public void deleteIndexQuietly(String physicalName) {
        try {
            if (client.indices().exists(e -> e.index(physicalName)).value()) {
                client.indices().delete(d -> d.index(physicalName));
                log.warn("已删除【空的/半成品】物理索引 | index={}"
                        + "（★ 它没有被别名指向，删不删都影响不到检索）", physicalName);
            }
        } catch (Exception e) {
            log.warn("删除半成品物理索引失败 —— 它只是占空间，不影响检索 | index={}, 原因={}: {}",
                    physicalName, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 别名指向的那个索引里有没有文档。<b>★ 别名不存在也算「空」。</b>
     *
     * <p>★★ <b>「不存在」和「存在但 0 条」必须归成同一类</b>，否则启动自愈会漏掉一半的场面：
     * 首次部署时别名不存在（该灌），上次重建在切换之前崩了则别名还在、
     * 但它指向的旧索引可能已经空了（<b>同样该灌</b>）。
     *
     * <p>★★ <b>探不到 ES 时返回 {@code true}（= 当作空的，去重建一次）</b>。
     * ★★★ <b>这一格 S8 之前是 {@code false}，S8 把它翻了过来</b>，理由是别名引入了一种新状态：
     * <b>「别名存在，但它指着的物理索引没了」</b>（别名悬空）。
     * 那种状态下 {@code existsAlias} 为真、{@code count} 抛异常，正好落进这个 catch ——
     * 返回 false 的话，悬空的别名<b>永远自愈不了</b>，全站静默降级到 6 小时后的定时重建。
     * <p>★ 反过来（返回 true）最坏只是「本不该重建时多重建一次」——
     * 重建幂等、几秒钟、自带锁。★ 判据：<b>3 秒的无害重建 好过 6 小时的静默降级。</b>
     *
     * <p>★ S8 改的是<b>问的对象</b>：从 {@code HEAD /novel_index}（问索引存不存在）
     * 改成 {@code HEAD /_alias/novel_index}（问别名存不存在）。
     * ★★ <b>这不是等价改写，改错了会静默永远不重建</b>：
     * 判「索引存不存在」在别名场景下<b>永远为真</b>（别名解析到它指向的索引，
     * {@code HEAD} 返回 200），于是启动自愈再也触发不了 ——
     * 又是一条「判据本身永远为真」。（铁律 8 第二种形状，M4 已经踩过一次。）
     */
    public boolean isEmpty() {
        try {
            if (!client.indices().existsAlias(e -> e.name(indexName)).value()) {
                return true;
            }
            long count = client.count(c -> c.index(indexName)).count();
            return count == 0;
        } catch (Exception e) {
            /*
             * ★★ 拿不准 → 返回 false（别动）。★ 一度想把它翻成 true，被实测推翻了：
             *
             *   当时的理由是「别名可能悬空（别名在、指向的物理索引没了），
             *   而那条路正好落进这个 catch，返回 false 就永远自愈不了」。
             *   ★★★ M5 实测：**ES 在索引被删掉时会【连带删掉指向它的别名】** ——
             *   那个我们以为会造出悬空别名的动作（删索引）根本造不出它，
             *   它是以「别名不存在」的形态出现的，走上面那个 return true 的分支。
             *   （见启动日志：删掉物理索引后重启，ensureIndex 报的是
             *   「别名 novel_index 不存在」，而不是悬空。）
             *   ★ 把话说准：悬空别名【不是物理上不可能】，而是【本项目的代码造不出来】——
             *   换别名只有 switchAlias 一处，它是「先摘旧、再加新」的【一次】调用；
             *   剩下能造出它的只有手工去 _aliases 摘掉全部指向，那不在自愈的职责里。
             *
             * ★★ 而翻成 true 的代价被当时低估了：那句「多花 3 秒重建一次」
             *   只在【12 本的开发库】上成立。生产是什么量级？**一次全量重建 =
             *   把整个书库重新灌一遍**。于是这个改动真正的语义是
             *   「一次 ES 网络抖动 = 一次全库重建」——正是注释原文反对的那件事。
             *
             * ★ 结论：保持 false。**别用一个证伪不了的小场景，去换一个大代价的行为。**
             */
            log.warn("无法确认索引状态，本次跳过启动自愈（不动比乱动安全）：index={}, 原因={}: {}",
                    indexName, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * 别名是否指向一个<b>非空</b>的索引 —— {@code IndexRebuildService} 用它做一个取舍。
     *
     * <p>★★★ <b>它存在的理由是一条具体的、会造成「静默返回 0 条」的场景</b>：
     * 一次重建<b>一条都没灌进来</b>（{@code refill} 返回 0）。这时有两种成因，
     * 而 search 这边<b>分不出来</b>：
     * <ol>
     *   <li>content 那边真的一本书都没有 —— 那么切过去是对的；</li>
     *   <li>契约漂移 / 对端返回了空列表 —— 那么切过去<b>就是把一个好好的索引
     *       换成一个空索引</b>，全站搜索从此静默返回 0 条。</li>
     * </ol>
     * M4 时代这个取舍做不了（旧索引已经被 DELETE 掉了，没有「留住它」这个选项）。
     * <b>S8 之后做得了，所以必须做</b>：别名此刻还指着旧索引，
     * 只要不切，用户就什么都不会损失。
     *
     * <p>★★ <b>拿不准时返回 {@code true}（= 别动）</b>，方向与 {@link #isEmpty()} 一致：
     * 宁可让这次重建白跑，也不要让索引静默变空。
     */
    public boolean aliasTargetsNonEmptyIndex() {
        try {
            if (!client.indices().existsAlias(e -> e.name(indexName)).value()) {
                return false;
            }
            return client.count(c -> c.index(indexName)).count() > 0;
        } catch (Exception e) {
            log.warn("无法确认当前索引是否非空，本次按【非空】处理（宁可保留旧索引）：index={}, 原因={}: {}",
                    indexName, e.getClass().getSimpleName(), e.getMessage());
            return true;
        }
    }

    /**
     * 启动检查：<b>别名在不在位</b>。
     *
     * <p>★★★ <b>S8 把它的行为改小了：以前它「不存在就建一个空的」，现在它什么都不建。</b>
     * 这一处改动是本期的核心，理由是一条已经实测过的判据：
     *
     * <pre>
     *   建一个空索引 → 检索【不抛异常】→ 返回 0 条 → 用户看到「没有找到相关的作品」  ← 坏
     *   什么都不建   → 检索【抛异常】  → 降级到 MySQL LIKE → 结果是全集，只是排序退化  ← 好
     * </pre>
     *
     * <p>★ 一个空的索引<b>没有任何用处</b>：它既搜不出东西，又会让系统失去
     * 「我这儿没有索引，请降级」这个信号。所以这里<b>只诊断、不动手</b>，
     * 把建索引这件事<b>整条</b>交给启动自愈（{@code IndexInitializer} →
     * {@code rebuildAll()}）—— 那条路会一次建出「已经装满」的索引再挂别名，
     * 中途任何一步失败都不会留下一个空索引。
     *
     * <p>★ 幂等：别名在位时什么都不做（<b>尤其不会去覆盖已有的 mapping</b> ——
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
            if (client.indices().existsAlias(e -> e.name(indexName)).value()) {
                log.info("别名已存在，跳过创建：{}（★ 它现在指向的物理索引见 GET /_alias/{}）",
                        indexName, indexName);
                return;
            }
            /*
             * ★★ 这一行是【刻意不做事】的，措辞必须把「故意」写出来 ——
             *    否则下一个人会把它当成「忘了建索引」的 bug 修掉，
             *    而那一修正好会把本类注释里那个最坏状态请回来。
             */
            log.warn("别名 {} 不存在 —— 索引从未建过，或上次重建失败后被清理。"
                            + "★ 本次【故意不创建空索引】：留一个空的别名/索引会让搜索静默返回 0 条，"
                            + "而没有它只会让检索抛异常 → 自动降级到 MySQL LIKE → 结果仍是全集。"
                            + "这里交给启动自愈触发一次全量重建来建它。", indexName);

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
     * <p>★★ <b>S8 起它有两个入口，差别只在「写进哪个名字」</b>：
     * <ul>
     *   <li>{@link #bulkUpsert(List)} —— 写向<b>别名</b>。增量消息（S7）走这条，
     *       ES 会把它解析到当前索引上；</li>
     *   <li>{@link #bulkUpsert(List, String)} —— 写向一个<b>指定的物理索引</b>。
     *       全量重建走这条 —— 它必须把数据灌进那个<b>别名还没指过去</b>的新索引，
     *       这正是「切换之前用户读不到半成品」的机制本身。</li>
     * </ul>
     * ★ 两者共用下面同一个实现，只有目标名不同：
     * <b>把「走别名」和「走指定索引」写成两段 bulk 代码，迟早会有一边漏掉
     * 「显式给 _id」或者「解析 errors」这类正确性细节。</b>
     *
     * @param documents 待写入文档；{@code null} 或空列表视为无事可做
     * @throws RuntimeException ES 不可达或写入失败 —— ★ <b>本方法【故意】外抛</b>，
     *         与 {@link #ensureIndex()} 的「吞掉只记 WARN」相反：
     *         索引写失败必须让调用方（消费者）知道，否则消息会被 ACK 掉、索引永久陈旧。
     */
    public void bulkUpsert(List<NovelDocument> documents) {
        bulkUpsert(documents, indexName);
    }

    /**
     * 写入一批文档到<b>指定的物理索引</b>。★ 完整语义见 {@link #bulkUpsert(List)}。
     *
     * @param target 目标索引名：别名（增量）或某个具体物理索引（全量重建）
     */
    public void bulkUpsert(List<NovelDocument> documents, String target) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(target);
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

            log.debug("索引写入成功 | index={} count={}", target, documents.size());

        } catch (IOException e) {
            /*
             * ★ IOException 单独捕获并转成 RuntimeException：ES 客户端的 IOException
             *   是【受检】异常，而消费者那边要按「基础设施异常 → 抛出去重试」处理
             *   （见 NovelIndexConsumer）。统一成 RuntimeException 让上层的分类不依赖具体异常类型。
             */
            throw new IllegalStateException("索引写入失败（IO）：index=" + target, e);
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
