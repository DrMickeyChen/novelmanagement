package com.novel.common.internal;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * <b>一条「该进 ES 索引的」作品数据</b>（M4）。
 *
 * <p>方向：novel-search → novel-content（<b>读</b>）。
 * 端点是 {@code InternalContentController#indexDocs} / {@code #indexPage}，
 * 消费方是 {@code novel-search} 的 {@code ContentClient} / {@code IndexService}。
 *
 * <h3>★★ 为什么它是「索引文档」而不是又一个 VO</h3>
 *
 * <p>它<b>不是</b>给页面看的，是<b>给倒排索引看的</b>。两者的字段集合刻意不同，
 * 而这个差别就是 M4 的核心设计（判断 3）：
 * <ul>
 *   <li>只有 <b>6 个</b>字段，而 {@code NovelVO} 有 15 个。索引里<b>只放
 *       「参与检索 / 过滤 / 排序」的列</b>。</li>
 *   <li>★★ <b>刻意不含 {@code viewCount} / {@code collectCount} / {@code score}</b> ——
 *       这三个由 M3 的 {@code NovelViewAggregator} 每 5 秒 / MQ 持续刷新。
 *       放进索引就等于<b>每 5 秒批量重写文档</b>；而它们本来也不参与检索，
 *       只是搜索结果卡片上要显示。让它们在装配时从 MySQL 读，<b>永远是新鲜的真值</b>。</li>
 *   <li>★ <b>刻意不含 {@code categoryName}</b>（分类名）：它是装配时从 MySQL 读的，
 *       一旦为了「按分类搜」把它放进索引，<b>分类改名就立刻变成一个新的索引触发点</b>，
 *       而那个写入口在 content 的另一个 service 里 —— 又多一个会漏的落点。
 *       见 {@code NovelServiceImpl} 里四个落点那张表。</li>
 *   <li>★ <b>也不含 {@code deleted}</b>：逻辑删除靠「全量重建时不出现」来收敛，
 *       而不是靠索引里存一个标志位（ES 不知道 {@code @TableLogic} 这回事）。</li>
 * </ul>
 *
 * <h3>★★★ 为什么是 {@code @Data} 类，不是 {@code record} —— 和隔壁 DTO 不一样</h3>
 *
 * <p>{@link NovelCollectCountDTO} 是 {@code record}，它 2 个字段。
 * <b>本类 6 个字段，其中 {@code title} / {@code authorName} / {@code intro}
 * 是三个相邻的 {@code String}</b> —— 位置参数写法下传错<b>编译期一个字都不报</b>，
 * 后果是「搜到了书，但卡片上显示的是另一本的简介」。
 * ★ 判据不是「字段多少」，是「<b>相邻同类型字段有几个</b>」：
 * 2 个不同类型的字段用 record 很安全，3 个同类型的就不安全了。
 * （同一条判据见 {@code NovelVos} 的类注释，那里是 15 个字段里 7 个相邻同类型。）
 *
 * <h3>★ {@code status} 是数字码，不是 {@code NovelStatus} 枚举</h3>
 *
 * <p>{@code NovelVO} 用的是枚举（带 {@code @JsonValue}），但那是因为它只<b>出</b>到前端、
 * 从不被另一个服务反序列化。本类<b>要坐一趟 Feign 来回</b>，用数字码：
 * <ul>
 *   <li>索引侧要的就是这个码（{@code keyword} 字段上做 {@code term} 过滤），
 *       枚举在那边<b>没有任何用处</b>；</li>
 *   <li>省掉一次「枚举 ↔ 数字」的编解码假设 —— 那要依赖 {@code NovelStatus}
 *       上有 {@code @JsonCreator}，是一个<b>多出来且没人会记得的耦合</b>。</li>
 * </ul>
 *
 * <h3>★ {@code lastUpdateTime} 是 {@code LocalDateTime}，不是 String</h3>
 *
 * <p>索引里那个字段确实是 ISO 字符串 —— 但<b>转成 ISO 是 search 的事，不是 content 的事</b>。
 * 理由：{@code LocalDateTime} 会被 Jackson 的 JavaTimeModule 按项目统一格式
 * （{@code yyyy-MM-dd HH:mm:ss}，见 {@code JacksonConfig}）<b>序列化也能反序列化</b>，
 * 这条链路是双向通的；而「ES 要 ISO 的 {@code T} 分隔」纯粹是<b>索引侧的怪癖</b>
 * （ES 的 {@code strict_date_optional_time} <b>不接受空格分隔</b>）。
 * <b>让 content 为了 ES 的格式去改自己的日期输出，等于把索引细节漏进了业务服务。</b>
 * 转换落在 {@code NovelDocument#from} 里，那边也是唯一知道 JsonpMapper 怪癖的地方。
 *
 * @author MickeyChen
 */
@Data
public class NovelIndexDoc {

    /** 作品 id。<b>索引里 {@code index: false}，只存不检索</b> —— 它是主键，不是搜索词。 */
    private Long id;

    /** 书名。★ 权重最高的检索字段（{@code title^3}）。 */
    private String title;

    /**
     * 作者昵称。<b>由 content 现查 {@code ContentUserLookup#nicknamesOf} 补上</b>，
     * {@code novel} 表里只有 {@code authorId}。
     *
     * <p>★★ <b>这个字段正是「绝不能新增 {@code auth → search} 边」的原因</b>：
     * 作者在 auth 改昵称，索引里那一份就陈旧了。自然的想法是「auth 改名时通知 search 重建」，
     * 但 auth 现在的出站边是 <b>0 条</b>（M3 好不容易挣来的），
     * <b>不为了一个极低频的场景把它加回来</b> —— 收敛点是定时全量重建。
     */
    private String authorName;

    /** 简介。★ M4 新纳入检索范围（决定 2）—— 搜「剑冢」这类只在简介里出现的词从此有结果。 */
    private String intro;

    /** 作品状态码，语义见 {@code NovelStatus}：0 草稿 / 1 待审 / 2 已上架 / 3 已下架 / 4 已驳回。 */
    private Integer status;

    /**
     * 最后更新时间（<b>最新已发布章节</b>的时间，由 {@code syncNovelStat} 维护）。
     *
     * <p>★ <b>可为 {@code null}</b> —— 一本一章都没发布的新书就是 null。
     * 它同时是索引的<b>兜底排序字段</b>，所以 null 必须排到最后
     * （ES 的默认行为就是 {@code _last}，见 {@code IndexService} 的 mapping 注释）。
     *
     * <p>★ <b>它的值会「回退」</b>（已发布章节被改回草稿时），
     * 那是真值不是 bug —— 文档写明。
     */
    private LocalDateTime lastUpdateTime;
}
