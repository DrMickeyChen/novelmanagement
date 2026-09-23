package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.novel.common.enums.NovelStatus;
import com.novel.common.internal.InternalResults;
import com.novel.common.result.PageResult;
import com.novel.common.result.PageResults;
import com.novel.common.vo.NovelVO;
import com.novel.search.client.ContentClient;
import com.novel.search.document.NovelDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * <b>搜索的编排：ES 检索 → 回 content 装配 → 拼分页体；ES 挂了就降级。</b>
 *
 * <h3>★★ 三步的形状</h3>
 * <pre>
 *   search(keyword, page, size)
 *     ├─ keyword 为空          → PageResult.empty()        ★ 在 try 之外
 *     ├─ from+size 超过窗口     → PageResult.empty()        ★ 在 try 之外
 *     ├─ try    ES 检索 → id 列表 + total
 *     │         → content /internal/novels/reader-vos 装配
 *     │         → PageResult&lt;NovelVO&gt;
 *     └─ catch  WARN「ES 检索失败，降级到数据库 LIKE」
 *               → content /internal/search（M4 之前那个实现，原样返回）
 * </pre>
 *
 * <h3>★★★ 为什么前两个判断【必须在 try 之外】—— 这是本类最容易写错的地方</h3>
 *
 * <p>{@code try} 的唯一目的是「ES 挂了能降级」。而下面这两件事<b>不是 ES 挂了</b>，
 * 它们是<b>正常的空结果</b>。放进 try 里会被那个 catch 吞掉，后果分别是：
 * <pre>
 *   ① keyword 为空 → 降级路径同样返回空列表，看不出区别 —— 这一条危害小
 *   ② from+size &gt; max_result_window → ★★ 灾难
 *      ES 会返回 400（Result window is too large），那个 400 被 catch 吞掉 →
 *      静默走降级路径 → 用户看到的是「能用」，只是排序从相关性悄悄变回 view_count。
 *      **M4 最隐蔽的一种失败：它看起来完全正常。**
 * </pre>
 * <p>★ 所以本服务在<b>进 ES 之前</b>就把越界的页码挡掉，不让它变成一次 400。
 * <b>「能被 catch 吞掉的错误」必须用正面验证去验，不能靠「没看到报错」下结论</b> ——
 * 同一条方法论写在 {@code IndexService} 的类注释里（那里是 mapping 写错的情形）。
 *
 * <h3>★★ 排序：相关性优先，然后才是「谁更新」</h3>
 *
 * <p>{@code _score DESC, lastUpdateTime DESC, id DESC}，三段各有理由：
 * <ul>
 *   <li><b>{@code _score}</b> —— 这就是 M4 要的东西。{@code NovelServiceImpl#search}
 *       里那两行注释自己预言过「二期 M4 上 ES 之后，这里会换成真正的相关性打分」。</li>
 *   <li><b>{@code lastUpdateTime}</b> 兜底 —— 同分时让「最近更新的」靠前。</li>
 *   <li>★ <b>{@code id} 末位兜底</b> —— 照项目铁律「按时间倒序的列表一律带 {@code id DESC} 兜底」。
 *       没有它，同分的书在翻页时会<b>重复或漏掉</b>（Elasticsearch 不保证同分文档的稳定顺序）。</li>
 * </ul>
 *
 * <p>★ {@code lastUpdateTime} 的 {@code missing: "_last"} 是<b>自文档化，不是修复</b> ——
 * ES 对缺失值的<b>默认</b>就是排在最后。★ 它写在 sort 子句里，
 * <b>绝不能写进 mapping</b>（{@code missing} 不是合法的 mapping 参数，
 * 写进去会让<b>建索引本身 400</b>，已实测，见 {@code IndexService} 的长注释）。
 *
 * <h3>★★★ 降级路径对用户【完全透明】，但日志里必须一眼看得出</h3>
 *
 * <p>两条路的响应体形状完全一致（都是 content 造的 {@code PageResult<NovelVO>}），
 * 所以前端不需要知道自己在走哪条路。
 * <p>★★ <b>但集合可能不同、顺序一定不同</b>（相关性 vs {@code view_count DESC}）——
 * 所以<b>对拍验收只能对集合，不能对顺序</b>。
 * <p>★ 还有一条能力退化要如实记：降级路径<b>不搜简介</b>（M4 之前的口径），
 * 所以「剑冢」这类只在简介里出现的词，在 ES 在线时搜得到、ES 挂了就搜不到。
 * 那是<b>已知且可接受</b>的，不是 bug —— 降级的目标是「仍然可用」，不是「完全等价」。
 *
 * <h3>★ 为什么降级不另写一个实现</h3>
 *
 * <p>降级路径最怕的是「平时不跑，真出事时才发现它也是坏的」。
 * 而 content 那个 {@code search()} 是全站唯一一条经过一期完整验收的搜索实现 ——
 * <b>接回来用是唯一能保证它不是坏的办法</b>。所以 M4 没有重写它，
 * 只是把它从 {@code /api/search} 挪到 {@code /internal/search}。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    /**
     * ★ 三个权重：书名 &gt; 作者名 &gt; 简介。
     * <p>简介最长、最容易「碰巧命中」，所以权重最低；
     * 书名命中最能说明「用户找的就是这本」，所以最高。
     * <p>★ 这套加权是判断 4 的验收依据：<b>书名命中的必须排在只有简介命中的前面</b> ——
     * 那验的是 boost，不是 analyzer 组合（后者在短查询上看不出差异，见 IndexService）。
     */
    private static final String FIELD_TITLE = "title^3";
    private static final String FIELD_AUTHOR = "authorName^2";
    private static final String FIELD_INTRO = "intro^1";

    /** 兜底页大小，和 content 的 {@code DEFAULT_PAGE_SIZE} 对齐。 */
    private static final long DEFAULT_PAGE_SIZE = 10;

    /** 页大小上限，和 content 的 {@code buildPage} 对齐（那边由分页插件夹到 100）。 */
    private static final long MAX_PAGE_SIZE = 100;

    private final ElasticsearchClient client;
    private final ContentClient contentClient;

    @Value("${novel.search.index-name}")
    private String indexName;

    @Value("${novel.search.max-result-window}")
    private int maxResultWindow;

    /**
     * 搜索。★ 参数语义、可见性口径、以及「不走 ES 时也一样」的那些约定，
     * 见 {@code SearchController} 的 {@code @Operation} 描述。
     *
     * <p>★ <b>本方法【不抛业务异常】</b>：空关键词、越界页码都返回空分页体，
     * 不是 400 —— 调用方是首页顶部的搜索框，用户按了回车没输内容是正常操作。
     * （这条约定继承自 M4 之前的实现，见 {@code NovelServiceImpl#search}。）
     */
    public PageResult<NovelVO> search(String keyword, long page, long size) {
        /*
         * ★★ 判断 ①：空白关键词。必须在进 ES 之前短路。
         *
         * 不短路的后果（M4 之前那个实现里记着同一条）：不加条件就等于
         * 「列出全部已上架作品」—— 搜索框里什么都没输却返回一整页书，
         * 用户会以为搜索坏了。★ 而且这个错误在降级路径上长得一模一样，
         * 两条路一起错，更难发现。
         */
        if (!StringUtils.hasText(keyword)) {
            return PageResult.empty();
        }
        String kw = keyword.trim();

        /*
         * ★ 页码和页大小都要夹。★ 夹完再用 —— 下面算 from 用的是夹过的值，
         *   否则「size=100000」会算出一个荒谬的 from，而窗口判断又拦不住它。
         */
        long safePage = Math.max(page, 1L);
        long safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        /*
         * ★★ 判断 ②：翻页深度超过 ES 的 max_result_window（默认 10000）。
         *
         * ES 的 from + size 超过这个值会【直接返回 400】，而那个 400 会被
         * 下面的 catch 吞掉 → 静默降级 → 排序悄悄变回 view_count。
         * ★★ 那是 M4 最隐蔽的一种失败：它看起来完全正常。
         * 所以这里自己挡掉，不让它变成一次 400。
         *
         * ★ 返回空分页而不是报错：这个深度（size=10 时是第 1000 页）
         *   正常 UI 走不到，报错只会给爬虫和手工调参制造噪音。
         */
        long from = (safePage - 1) * safeSize;
        if (from + safeSize > maxResultWindow) {
            log.debug("翻页深度 {} 超过 max_result_window={}，直接返回空（不查 ES，避免 400 被降级吞掉）"
                    + " | keyword={}, page={}, size={}", from + safeSize, maxResultWindow, kw, safePage, safeSize);
            return PageResult.empty();
        }

        try {
            return searchByEs(kw, safePage, safeSize, from);
        } catch (Exception e) {
            /*
             * ★★ 只有走到这里才是「ES 这条路不通」。
             *
             * ★ 这里【必须】记 WARN：降级对用户透明，如果日志里也不出声，
             *   「ES 挂了三个月没人知道」是必然结果 —— 而用户那边一切正常。
             * ★ 只记异常类名 + 消息、不记完整栈：ES 挂掉时每一次搜索都会经过这里，
             *   打完整栈会把日志刷爆，反而让真正有用的行淹没。
             *   要栈的时候把本类的日志级别调到 debug 看 WIRE 日志（application-dev.yml 已开）。
             * ★ 带上 keyword/page/size：排障时要能重现这一次请求。
             */
            log.warn("ES 检索失败，降级到数据库 LIKE | keyword={}, page={}, size={} | {}: {}",
                    kw, safePage, safeSize, e.getClass().getSimpleName(), e.getMessage());
            return degradeToLike(kw, safePage, safeSize);
        }
    }

    /**
     * ES 路径：检索 → 装配 → 拼分页体。
     *
     * <p>★ 形参直接叫 {@code safePage} / {@code safeSize}：<b>能调到这里的一定是夹过的值</b>
     * （{@link #search} 是唯一的调用方），名字就是把那条约束写在签名上。
     *
     * <p>★ 抽成独立方法是为了让 {@link #search} 里的 {@code try} 块<b>一眼能看完</b> ——
     * 它只有一行。catch 的语义（「ES 这条路不通」）才是那个 try 的全部意义，
     * 把 20 行查询构造塞在里面会让人以为它也是被保护的对象之一。
     */
    private PageResult<NovelVO> searchByEs(String keyword, long safePage, long safeSize, long from) throws Exception {
        SearchResponse<NovelDocument> response = client.search(s -> s
                        .index(indexName)
                        .from((int) from)
                        .size((int) safeSize)
                        /*
                         * ★ ES 默认的 track_total_hits 只精确统计到 10000，超过就返回
                         *   {"value":10000,"relation":"gte"}。而 search/index.vue 把 total
                         *   直接渲染成「共 N 本」并绑给 el-pagination ——
                         *   ★ 于是「共 10000 本」和「只有 3 页」会同屏出现，看起来像分页坏了。
                         * ★ 这里强制精确统计。代价是多一次计数，在这个数据量下可以忽略。
                         */
                        .trackTotalHits(t -> t.enabled(true))
                        .query(q -> q.bool(b -> b
                                /*
                                 * ★★ 第二道闸门（判断 5）：status = 已上架，写在 filter 里。
                                 *
                                 * 为什么【必须】在 ES 侧也过滤一次，而不是只靠装配端：
                                 * 只在装配端过滤会让 total 虚高 + 页内空洞 ——
                                 * 两处症状叠加就是「共 1050 本」和「没有找到相关的作品」同屏。
                                 *
                                 * ★ 值和 mapping 一样是字符串形式：status 在 mapping 里是
                                 *   keyword（不分词、精确匹配），写成数字 ES 会做一次
                                 *   隐式转换 —— 能跑，但把「这是精确匹配」这件事藏起来了。
                                 *
                                 * ★ filter 而不是 must：filter 不打分、且能被 ES 缓存。
                                 */
                                .filter(f -> f.term(t -> t.field("status")
                                        .value(String.valueOf(NovelStatus.PUBLISHED.getCode()))))
                                /*
                                 * ★ 三个字段一起搜，权重递减。
                                 *   multi_match 默认就是 best_fields（取命中得分最高的那个字段），
                                 *   正是我们要的「书名命中 > 只有简介提到」。
                                 */
                                .must(m -> m.multiMatch(mm -> mm
                                        .query(keyword)
                                        .fields(FIELD_TITLE, FIELD_AUTHOR, FIELD_INTRO)))))
                        /*
                         * ★ 三段排序。★ 注意 _score 显式写在 sort 里之后，
                         *   ES 仍然会算分（它只是把分当成一个排序键）。
                         */
                        .sort(so -> so.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                        /*
                         * ★ missing: "_last" 是自文档化 —— ES 的默认行为就是缺失值排最后。
                         *   ★ 它属于 sort 子句，绝不能搬进 mapping（见类注释）。
                         */
                        .sort(so -> so.field(f -> f.field("lastUpdateTime")
                                .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
                                .missing(co.elastic.clients.elasticsearch._types.FieldValue.of("_last"))))
                        /* ★ id 末位兜底：同分文档的稳定顺序，翻页不重不漏（项目铁律） */
                        .sort(so -> so.field(f -> f.field("id")
                                .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                NovelDocument.class);

        /*
         * ★ 只取 id。★ source() 可能为 null（理论上不会，因为文档是我们自己灌的）——
         *   兜一下是因为 NPE 会掉进降级路径，而「因为一条脏文档就整页降级」
         *   比「少一条结果」更糟。
         */
        List<Long> ids = response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(NovelDocument::getId)
                .filter(Objects::nonNull)
                .toList();

        long total = response.hits().total() == null
                ? ids.size()
                : response.hits().total().value();

        /*
         * ★ 一个 id 都没有时【不调 content】—— 空 id 列表会让对端做一次无用的 IN 查询。
         */
        List<NovelVO> records = ids.isEmpty()
                ? List.of()
                : InternalResults.unwrap(contentClient.readerVos(ids), "检索装配");

        /*
         * ★ 用夹过的 safePage / safeSize，不用入参 —— 这和降级路径的口径一致
         *   （那边是 MyBatis-Plus 的 Page，它汇报的就是夹过之后的值）。
         * ★★ 用入参的话：传 ?page=0&size=0 时查询按 1/10 走、返回体却写着 current=0,size=0，
         *   前端 el-pagination 会按 0 渲染 —— 而且两条路给的还不一样。
         * ★ 总页数由 PageResults.of 自己算（和 MyBatis-Plus 的算法一致：向上取整，0 条 = 0 页）。
         */
        return PageResults.of(records, total, safePage, safeSize);
    }

    /**
     * 降级路径：原样转发给 content 的 MySQL {@code LIKE} 实现。
     *
     * <p>★ <b>一行逻辑都不在这里</b>：这里只负责「把请求转过去、把结果拿回来」。
     * 任何在这边补的排序/过滤都是<b>第三条实现</b>，而它不会有第二个人知道。
     *
     * <p>★ 对端返回的是同一个 {@code PageResult} 结构 —— 所以
     * {@code InternalResults.unwrap} 之后直接返回，形状天然一致。
     */
    private PageResult<NovelVO> degradeToLike(String keyword, long page, long size) {
        return InternalResults.unwrap(contentClient.legacySearch(keyword, page, size), "降级检索");
    }

}
