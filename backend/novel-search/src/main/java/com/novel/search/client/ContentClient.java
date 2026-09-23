package com.novel.search.client;

import com.novel.common.internal.NovelIndexDoc;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.common.vo.NovelVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <b>novel-search 通往 novel-content 的唯一接缝（M4）。</b>
 *
 * <p><b>★★ 接口定义在【消费方】，不在提供方。</b>这是铁律 1 在 M1 之后的新表述：
 * 接口的<b>实现</b>住在消费方（就是「发一次 Feign 调用」这件事本身），
 * 提供方（content）只暴露 URL、<b>不暴露类型</b>。
 *
 * <p>★ 违反它的症状很隐蔽：content 那边会多出一个
 * <b>能编译、能被组件扫描、永远不会被调用</b>的死 bean ——
 * 它长得像「接缝的定义」，于是下一个人会去改它，然后发现改了没反应。
 * ★ 判据：<b>这个接口描述的是「search 需要 content 提供什么」，
 * 所以它属于 search。</b>换成「content 愿意提供什么」的写法就反了。
 *
 * <h3>★★ 本服务为什么【必须】有这条边 —— 这不是设计选择，是唯一解</h3>
 *
 * <p>search 要返回 {@code PageResult<NovelVO>}（前端契约不变，搜索页一个字不改）。
 * 而装配一个 {@code NovelVO} 需要三样东西：
 * <pre>
 *   小说表（title / intro / viewCount / wordCount ...）  → 在 content 的 MySQL 里
 *   作者昵称                                            → 在 auth，且要经过 content 的接缝
 *   分类名                                              → 在 content 的 MySQL 里
 * </pre>
 * <p><b>search 一样都拿不到</b> —— 它的 classpath 上没有 MyBatis-Plus、
 * 没有 MySQL 驱动，它的 yml 里没有 {@code spring.datasource.*}。
 * <p>★★ 所以 {@code NovelVO:26-29} 那句注释（「为什么 categoryName / authorName
 * 放在 VO 里而不是前端自己查」）在 M4 之后有了第二层含义：
 * <b>它同时也解释了「为什么 search 必须回调 content」</b>。
 *
 * <h3>★★ 这条边【不构成服务级环】—— 定义要看准</h3>
 *
 * <p>M4 之后 content 与 search 之间有两个方向：
 * <pre>
 *   search  → content    同步 HTTP（本接口）        ← 一条边
 *   content → search     ★ 只有 MQ，没有 HTTP      ← 不算边
 * </pre>
 * <p>★ 服务级环的定义是「<b>同步调用</b>的环」，MQ 不算 ——
 * M3 判断 C 用的就是这个定义（那一次是用来宣布环 #1 断掉的）。
 * ★★ 但这个定义有个前提，必须写死：<b>消费方必须是 search</b>。
 * 如果让 content 消费 {@code NOVEL_INDEX} 再反过来调 search 推文档，
 * 那就凭空造出了一条 content → search 的<b>同步</b>边 → <b>环成立</b>。
 * <p>所以「谁消费」这件事在这里是承重的，不是实现细节。
 *
 * <h3>★ 本接口的方法在 M4 的哪一步加</h3>
 *
 * <p>★ S2（本步）只建这个空接口 —— 它的作用是让 {@code @EnableFeignClients}
 * 有东西可扫、让探针能验证「Feign 装好了但一次都没调用」时服务能不能起。
 * 具体方法在后面的步骤里加，届时它们的返回类型才齐备：
 * <ul>
 *   <li>S5 完成 content 侧的三个内部端点；</li>
 *   <li>S4 把 {@code NovelVO} 下沉到 {@code novel-common}
 *       （★ 在那之前 search 引用不到这个类型，所以方法签名现在写不出来）；</li>
 *   <li>S6 补上方法并接进 {@code SearchService}。</li>
 * </ul>
 * <p>★ 一个<b>没有任何方法</b>的 {@code @FeignClient} 是完全合法的 ——
 * Feign 只在<b>调用时</b>才需要解析目标实例（走负载均衡器），
 * 所以 content 没注册到 Nacos 也不影响本服务启动。
 *
 * @author MickeyChen
 */
@FeignClient(name = "novel-content")
@Component
public interface ContentClient {

    /**
     * 【检索装配】给 ES 命中的 id，拿回读者可见的作品，<b>按传入顺序</b>。
     *
     * <p><b>★★ 这是「用户搜索」这条路上的第二跳。</b>
     * 完整链路：{@code ES 检索 → 本方法 → 拼 PageResult}。
     *
     * <p>★★ <b>返回条数可能少于请求条数</b>，这是<b>刻意允许</b>的，不是错误 ——
     * 它意味着索引里有 content 这边不可见的书（已下架 / 已删 / 状态改过而消息丢了）。
     * ★ 对端会记 WARN，本服务<b>不重复告警</b>（同一件事报两遍只会稀释信号）。
     * <p>★ 代价如实写：ES 给 10 个 id、这边回 8 个 → 这一页只有 8 条。
     * 正常运行时两道闸门都在，一条都不会被过滤掉。
     *
     * <p>★ 顺序<b>已经是对端重排好的</b>（MySQL 的 {@code IN} 不保证顺序，
     * 对端按传入 id 的顺序重排）—— 本服务拿到之后<b>直接渲染，不要再排一次</b>。
     */
    @PostMapping("/internal/novels/reader-vos")
    Result<List<NovelVO>> readerVos(@RequestBody Collection<Long> ids);

    /**
     * 【增量重建】给一组 id，拿回它们该进索引的样子。
     *
     * <p>★★ <b>返回的 Map 里没有某个 id = 这本书不该在索引里，请删掉它。</b>
     * 三种成因（不存在 / 已逻辑删除 / 从未进过索引）不必分辨，处置一样。
     * ★ 其中「已逻辑删除」是这条约定存在的真正理由 —— {@code @TableLogic}
     * 是 MyBatis 往 WHERE 里注入条件，<b>ES 完全不知道有这回事</b>。
     * <p>★ 目前唯一的调用点是 {@code NOVEL_INDEX} 消息的消费者，一次只传一个 id。
     */
    @PostMapping("/internal/novels/index-docs")
    Result<Map<Long, NovelIndexDoc>> indexDocs(@RequestBody Collection<Long> ids);

    /**
     * 【全量重建的数据源】按 id 升序取一页索引数据。游标分页，扫到底返回空列表。
     *
     * <p>★★ <b>调用方必须知道页大小上限是 100</b>，且「返回条数 &lt; 请求条数」
     * 是判断扫到底的依据 —— 请求 {@code size > 100} 会被对端静默截断到 100，
     * 于是循环<b>提前结束、漏掉后面的书</b>，而那些书会因为重建「先删索引再灌」
     * <b>从索引里消失</b>。所以页大小在 {@code IndexService} 里写死成 100。
     *
     * <p>★ 已逻辑删除的作品不会出现，这正是全量重建要的效果。
     */
    @GetMapping("/internal/novels/index-page")
    Result<List<NovelIndexDoc>> indexPage(@RequestParam("afterId") Long afterId,
                                          @RequestParam("size") Integer size);

    /**
     * <b>降级检索</b> —— M4 之前那个 MySQL {@code LIKE} 实现，对端一行逻辑没改。
     *
     * <p>★★ <b>这是「ES 挂了怎么办」的全部答案</b>（决定 3）。
     * {@code SearchService} 的检索是 try/catch 包着的，catch 里调本方法、
     * 把结果<b>原样</b>返回给前端 —— 对用户完全透明，
     * 只是排序从「相关性」退回「阅读量降序」。
     *
     * <p>★ <b>响应体形状与 ES 路径完全一致</b>（都是 content 造的
     * {@code PageResult<NovelVO>}）—— 所以前端不需要知道自己在走哪条路。
     * ★★ <b>但集合可能不同、顺序一定不同</b>：所以对拍验收<b>只能对集合，不能对顺序</b>。
     *
     * <p>★ 它<b>不搜简介</b>（M4 之前的口径），所以「剑冢」这类只在简介里的词
     * 在降级路径下仍然搜不到 —— 那是<b>已知且可接受</b>的能力退化，
     * 不是 bug。见 docs/M4-摘出search.md 的降级专项。
     */
    @GetMapping("/internal/search")
    Result<PageResult<NovelVO>> legacySearch(@RequestParam("keyword") String keyword,
                                             @RequestParam("page") long page,
                                             @RequestParam("size") long size);
}
