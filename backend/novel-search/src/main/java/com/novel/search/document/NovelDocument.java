package com.novel.search.document;

import com.novel.common.internal.NovelIndexDoc;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 小说索引文档 —— Elasticsearch 里 {@code novel_index} 的一条记录。
 *
 * <h3>★ 判断 3：ES 当「id 检索器」，不当数据源</h3>
 *
 * <p>这个类<b>只有 6 个字段</b>，而 {@code novel} 表有十几个。
 * 少掉的那些不是偷懒，是刻意的：ES 里<b>只放参与「检索 / 过滤 / 排序」的字段</b>，
 * 命中之后由 search 拿 id 列表回 content 装配完整的
 * {@code NovelVO}（正文、分类名、阅读量……）。
 *
 * <p>★★ 最该说清楚的是<b>为什么 {@code viewCount} / {@code collectCount} / {@code score}
 * 不进索引</b>：
 * 它们每 5 秒就被 M3 的 {@code NovelViewAggregator} 刷一次。
 * 放进索引 = 每 5 秒批量更新一次文档，而换来的只是「一个稍旧的值」——
 * 装配时从 MySQL 读，这几个字段<b>永远是新鲜的真值</b>，而且一个字节都不用同步。
 *
 * <p>★★ 而 {@code status} 进索引是<b>安全性</b>要求，不是性能取舍，见下方字段说明。
 *
 * <h3>★★★ 为什么 {@code lastUpdateTime} 是 {@code String} 而不是 {@code LocalDateTime}</h3>
 *
 * <p><b>这是一个实测出来的结论，不是风格偏好。</b>
 *
 * <p>本服务用的是 {@code spring-boot-starter-data-elasticsearch} 自动配置出来的
 * {@code ElasticsearchClient}，它序列化文档用的是 {@code JacksonJsonpMapper}。
 * 而 Spring Boot 3.2.12 里那个 mapper 的定义是（已用 {@code javap} 核实）：
 *
 * <pre>
 *   &#64;Bean
 *   &#64;ConditionalOnMissingBean
 *   JacksonJsonpMapper jacksonJsonpMapper()      // ← ★ 注意：没有参数
 * </pre>
 *
 * <p>★ <b>它不接收容器里的 {@code ObjectMapper}</b>，而是自己 new 一个<b>裸的</b>
 * {@code new ObjectMapper()} —— 那个 ObjectMapper <b>没有注册 JavaTimeModule</b>。
 * 于是 {@code LocalDateTime} 落到 Jackson 手里会被当成一个普通 POJO 序列化，
 * 产出这种形状：
 *
 * <pre>
 *   {"year":2026,"month":"SEPTEMBER","dayOfMonth":20,"hour":7,...}
 * </pre>
 *
 * <p>★★ 而 ES 的 {@code date} 字段收到这个对象会直接 <b>400</b>，
 * 报文是 {@code failed to parse date field}。
 * <b>这个错不会在编译期出现，也不会在启动期出现</b> ——
 * 它只在这一条文档被写进索引的那一刻炸，
 * 而如果那时候正好在跑全量重建，症状就是「灌到某一条突然失败」。
 *
 * <p>★★ 处置有两条路，这里选的是第二条：
 * <ol>
 *   <li><b>自己定义一个 {@code JsonpMapper} bean</b> ——
 *       ★ 这条路是<b>可行</b>的（上面那个 {@code @ConditionalOnMissingBean} 已核实，
 *       我们定义的会覆盖它）；但它要求注入的 {@code ObjectMapper} 恰好带 JavaTimeModule，
 *       而那取决于 {@code JacksonConfig} 是怎么改的 —— <b>多一个要维护的假设</b>。</li>
 *   <li>★ <b>本类的做法：用 {@code String}，在进 ES 之前就把它格式化成 ISO-8601。</b>
 *       序列化一个 String 不需要任何模块，<b>哪种 JsonpMapper 都能work</b>，
 *       假设归零。★ 顺带一个好处：{@code curl} 出来的 {@code _source} 里时间是给人看的
 *       （{@code "2026-09-20T07:36:25"}），不是一坨整数。</li>
 * </ol>
 *
 * <p>★ 格式用的是 {@link #TIME_FORMATTER}，<b>显式写出秒</b>。
 * 不用 {@code LocalDateTime.toString()} 是因为它在秒为 0 时会省略秒位
 * （输出 {@code 2026-09-20T07:36}）—— 虽然 ES 的默认 format
 * （{@code strict_date_optional_time||epoch_millis}）两种都认，
 * ★ 但「有时候带秒有时候不带」会让按时间范围查的手工调试变得很难解释。
 *
 * <p>★ {@code lastUpdateTime} 允许为 {@code null}（新书一章未发）。
 * 写进去是 JSON 的 {@code null}，ES 把它当<b>该字段缺失</b>处理 ——
 * 排序时靠 sort 子句里的 {@code missing: "_last"} 兜底（见 SearchService），
 * ★ <b>不是</b> mapping 里的参数：{@code missing} <b>不是合法的 mapping 参数</b>，
 * 写进 mapping 会让<b>建索引本身就 400</b>（已实测，见 IndexService 的长注释）。
 *
 * @author MickeyChen
 */
@Data
public class NovelDocument {

    /**
     * ★ 时间格式：显式 ISO-8601，秒位一定在。
     * <p>ES 那边 {@code lastUpdateTime} 声明成 {@code date}，
     * 默认 format 是 {@code strict_date_optional_time||epoch_millis}，
     * 这个形状（{@code 2026-09-20T07:36:25}）正好落在前者里 —— 已实测。
     */
    public static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    /**
     * 小说 id。★ 与 ES 文档的 {@code _id} 是<b>同一个值</b>
     * （这样「重建某一本」就是一次 {@code PUT /novel_index/_doc/{id}}，天然幂等）。
     *
     * <p>★★ 它在 mapping 里是 <b>{@code index: false}</b>：只存不索引。
     * 因为没有任何查询会「按 id 检索」—— 我们检索的是书名/作者/简介，
     * id 是用来回 content 装配的载荷。★ 关掉它能省一份倒排表。
     * （★ 注意 {@code index: false} 不影响 {@code doc_values}，
     * 所以按 id 排序仍然可以，S6 的 id 末位兜底就靠它。）
     */
    private Long id;

    /** 书名。★ 权重最高（{@code title^3}），见 S6 的 should 子句。 */
    private String title;

    /**
     * 作者昵称。
     * <p>★★ 它<b>不属于 content</b> —— 它是 auth 的 {@code sys_user.nickname}，
     * 由 content 装配时经 {@code ContentUserLookup} 取到再喂给索引。
     * ★ 这正是判断 8 那条约束的由来：<b>绝不新增 {@code auth → search} 的边</b>，
     * 昵称改名造成的索引陈旧<b>靠定时全量重建收敛</b>，不靠实时通知。
     */
    private String authorName;

    /**
     * 简介。★ <b>决定 2 的新增字段</b>：加上它之后，
     * 只在简介里提过的词也能搜到（今天 MySQL 的 {@code LIKE} 只搜书名 + 作者昵称）。
     * <p>★ 权重最低（{@code intro^1}）—— 它最长、最容易「碰巧命中」。
     */
    private String intro;

    /**
     * 作品状态（2 = 已上架）。
     *
     * <p>★★★ 这个字段进索引是<b>安全要求</b>，不是可选优化 —— 见判断 3 / 判断 5。
     *
     * <p>Java 侧它是个 {@code Integer}，但 mapping 里声明成 <b>{@code keyword}</b>：
     * 它只用来做 {@code term} <b>精确匹配</b>，不该被分词。
     * ★ 若配成 {@code text}，{@code 2} 会被分析器处理，
     * 而 {@code term} 查询恰好也能命中（单字符）—— <b>能跑，但是错的</b>，
     * 将来任何多位数状态值都会静默失配。
     *
     * <p>★★ 它<b>同时参与 ES 侧 {@code filter}</b>，和 content 装配端的
     * {@code status = PUBLISHED} 构成<b>两道闸门</b>：
     * 索引陈旧（比如一本书已下架但文档还在）时，
     * <b>用户搜不到它</b> —— 于是「索引陈旧」的后果从
     * <b>内容泄露</b>降级成<b>搜不全</b>（可接受、且被定时全量重建自愈）。
     * ★★ 只有一道闸门时，「陈旧文档」这个信号永远不会出现，
     * 第二道闸门也就无从谈起 —— 两道必须都在。
     */
    private Integer status;

    /**
     * 最后更新时间（★ 见类注释：这里存的是 <b>String</b>，不是 {@code LocalDateTime}）。
     * <p>排序用：{@code _score DESC, lastUpdateTime DESC, id DESC}。
     * <p>★ 它<b>会回退</b>（已发布章节被改回草稿时 {@code syncNovelStat} 会把它改小）——
     * <b>那是真值，不是 bug</b>，排序跟着变是正确的。
     */
    private String lastUpdateTime;

    /**
     * 把 {@code LocalDateTime} 转成本类的字符串形状。
     *
     * <p>★ 单独抽一个静态方法，是为了让「格式化」这件事<b>只有一个实现</b> ——
     * 增量重建（一条）和全量重建（bulk 一批）都要用它，
     * 两处各写一遍的话，改格式时必然漏一处，
     * 而漏一处的症状是<b>同一批文档里时间形状不一致</b>，极难查。
     *
     * @param time 可为 {@code null}（新书无章节）
     * @return ISO-8601 字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    /**
     * 从 content 给的索引数据转成一条 ES 文档。<b>全项目唯一一处这个映射。</b>
     *
     * <p>★★ <b>为什么要有这个方法、而不是直接把 {@code NovelIndexDoc} 索引进去：</b>
     * <ul>
     *   <li>★★★ {@code NovelIndexDoc.lastUpdateTime} 是 {@code LocalDateTime}，
     *       而本类的同名字段必须是 {@code String}（理由见类注释里那段 JsonpMapper）。
     *       <b>转换就发生在这里，也只会发生在这里。</b></li>
     *   <li>两个类<b>刻意不合并成一个</b>：{@code NovelIndexDoc} 是<b>跨服务契约</b>
     *       （它长什么样由 content 和 search 共同约定，改它要同时改两边），
     *       而本类是<b>ES 的映射模型</b>（改它只影响这一个服务的索引）。
     *       ★ 合并的后果是「ES 的字段类型」变成「跨服务契约」的一部分，
     *       以后想给某个字段换个 ES 类型（比如 {@code text} → {@code keyword}），
     *       就变成一次<b>跨服务的不兼容变更</b>。</li>
     * </ul>
     *
     * <p>★ 字段是一对一搬的：{@code NovelIndexDoc} 那 6 个和本类这 6 个<b>必须同名同义</b>
     * —— 因为检索时装配那一跳要靠 {@code id} 回 content 取数，
     * 而 {@code status} / {@code lastUpdateTime} 是 ES 侧的过滤与排序字段。
     * ★ 加字段时两个类都要动，这是「6 个字段」这个设计<b>刻意付出的</b>代价：
     * 索引面小到「改它的时候一定会想起来还有另一处」。
     *
     * @param doc 对端返回的一行，不可为 {@code null}
     * @return 可直接写进 ES 的文档
     */
    public static NovelDocument from(NovelIndexDoc doc) {
        NovelDocument document = new NovelDocument();
        document.setId(doc.getId());
        document.setTitle(doc.getTitle());
        document.setAuthorName(doc.getAuthorName());
        document.setIntro(doc.getIntro());
        document.setStatus(doc.getStatus());
        // ★ 就是这一行：LocalDateTime → ISO-8601 字符串（见类注释）
        document.setLastUpdateTime(formatTime(doc.getLastUpdateTime()));
        return document;
    }
}
