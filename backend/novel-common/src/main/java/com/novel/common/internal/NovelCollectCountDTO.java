package com.novel.common.internal;

/**
 * 「content 这边这本书的收藏数现在是几」—— <b>对账任务读回来的一行</b>（M3）。
 *
 * <p>方向：novel-interaction → novel-content（<b>读</b>）。
 * 端点在 {@code InternalContentController#collectCounts}，
 * 消费方在 {@code ContentClient} / {@code ContentGateway} /
 * {@code NovelStatReconcileJob}（对账任务）。
 *
 * <h3>★★ 为什么它住 {@code internal} 包，而不是 {@code mq} 包</h3>
 *
 * <p>M3 把 {@code com.novel.common} 分成了两半，分界线是<b>失败语义</b>：
 * <ul>
 *   <li>{@code internal} = <b>同步读</b>的信封（本类、{@code CommentBriefDTO}）——
 *       调用失败要<b>当场</b>让调用方知道，因为调用方正等着这个值继续往下算；</li>
 *   <li>{@code mq} = <b>异步写</b>的载荷（{@code NovelStatMessage} 等）——
 *       发出去就不管了，失败由重试、死信、对账兜着。</li>
 * </ul>
 * 这条分界写在 {@code CommentBriefDTO} 的类注释里（M3 挪走两个 DTO 时记的），
 * 本类正好是「分界之后新增的第一个」——<b>它落在哪一边本身就是要判断的事</b>：
 * 对账任务拿到这个值之后要立刻做比对，拿不到就没法继续，
 * 所以它是同步读，不是消息。
 *
 * <h3>★ 为什么不复用 {@code NovelBriefVO}</h3>
 *
 * <p>那个 VO 的用途是「渲染一张书卡」（书名 / 封面 / 作者 / 分类 / 最新章节 …），
 * 取数是 {@code NovelService#briefsOf}：<b>收一串 id、只返回已上架的</b>。
 * 本类要的恰好是它的反面：
 * <ol>
 *   <li><b>没有 id 列表</b> —— 对账要的是「你把你知道的全给我」，
 *       而不是「这几个 id 是什么情况」。硬凑成 id 列表的话，
 *       对账就得先知道有哪些书，而它恰恰不知道（这正是要问的）。</li>
 *   <li><b>不看上架状态</b> —— 一本下架的书，书架上还留着读者，
 *       它的 {@code collect_count} 照样要准。{@code briefsOf} 那个
 *       「只返回已上架」的口径在这里是<b>错的过滤器</b>，会把下架的书
 *       从对账范围里漏掉，于是那本书的收藏数永远修不回来。</li>
 * </ol>
 * <p>★ 代价是「收藏数」这个字段的意义在两个类型里出现两次 ——
 * 但它们的<b>口径本来就不同</b>（一个是渲染给用户看的，一个是比对用的），
 * 强行合并会让其中一边的规则被另一边污染。同 {@code NovelBriefVO} 拒绝
 * 复用 {@code NovelVO} 的那条论证。
 *
 * <h3>★ 两个字段的类型</h3>
 *
 * <p>{@code novelId} 是 {@code Long}（{@code novel.id} 是 BIGINT）、
 * {@code collectCount} 是 {@code Integer}（{@code novel.collect_count} 是 INT）。
 * <b>不必也不该统一成 {@code long}</b>：类型跟着列走，读的人一眼知道
 * 这边是表上原生的一列，而不是某种加工过的派生值。
 *
 * @param novelId      作品 id
 * @param collectCount 该作品当前的收藏数。<b>只会 ≥ 1</b> ——
 *                     调用方的查询条件是「非零」，0 的那些书不会出现在结果里，
 *                     它们由「不在返回集合里」表达（对账任务据此判断该不该重推 0）。
 *
 * @author MickeyChen
 */
public record NovelCollectCountDTO(Long novelId, Integer collectCount) {
}
