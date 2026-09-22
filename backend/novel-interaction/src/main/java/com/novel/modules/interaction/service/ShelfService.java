package com.novel.modules.interaction.service;

import com.novel.common.result.PageResult;
import com.novel.modules.interaction.vo.ShelfItemVO;
import com.novel.modules.interaction.vo.ShelfStatusVO;

/**
 * 书架服务。<b>需要登录，userId 一律取自 {@code UserContext}。</b>
 *
 * <p><b>★★ 只有「加入」和「移出」两个方法，刻意【没有】toggle。</b>
 * 大纲里「加入/移出书架」是同一个接口，看着很适合做成一个 toggle，
 * 但 toggle 有两个致命性质：
 * <ol>
 *   <li><b>不幂等</b> —— 同一个请求发两次结果相反，而 HTTP 的 POST
 *       在客户端/网关/重试中间件眼里都是「发一次和发两次一样」的语义；</li>
 *   <li><b>不可重试</b> —— 用户点「加入书架」，服务端成功了但响应超时，
 *       客户端重试，第二次把书<b>移出</b>了，而两次响应都是 200。
 *       用户看到的是「我点了加入，它说成功，然后书不见了」。</li>
 * </ol>
 * 拆成 {@link #add} / {@link #remove} 之后，两个方法各自幂等：
 * 重复加入不报错也不重复计数，重复移出同样。这正是「加入书架」这个动作
 * 在用户心里的语义（我说的是「把它加进去」，不是「切换一下」）。
 *
 * @author MickeyChen
 */
public interface ShelfService {

    /**
     * 我的书架，按加入时间倒序。
     *
     * <p>★ 每行会带上「读到第几章」，来自 {@code read_history} ——
     * 书架页上光有书名没用，读者要的是<b>继续阅读</b>。
     */
    PageResult<ShelfItemVO> myPage(long page, long size);

    /**
     * 这本书在不在我的书架，以及它现在有多少人收藏。
     *
     * <p>给详情页的按钮提供初始态。★ <b>不复用 {@code GET /api/novels/{id}}
     * 的详情接口</b>：那个接口是<b>匿名</b>的（游客也要能看详情页），
     * 把自己的书架状态塞进一个匿名接口的响应里，等于给每个游客都算一遍
     * 「这个用户收没收藏」—— 而游客根本没有 userId。
     */
    ShelfStatusVO status(Long novelId);

    /**
     * 加入书架。<b>幂等</b>：本来就在书架里也返回成功，且不会重复计数。
     *
     * <p>★ <b>校验作品存在且已上架</b>（{@code 2001}）。这里和
     * {@link #remove} 的口径<b>刻意不同</b>，理由见那个方法。
     */
    ShelfStatusVO add(Long novelId);

    /**
     * 移出书架。<b>幂等</b>：本来就不在书架里也返回成功。
     *
     * <p><b>★★ 不校验作品上架与否，这是有意的，不要「补上」。</b>
     * 读者书架里完全可能有一本已经下架（甚至已被作者删除）的书 ——
     * 而它仍然是读者书架里的一行。如果这里也要求「作品已上架」，
     * 那一行就<b>永远移除不掉</b>：读者点「移除」拿到 {@code 2001}，
     * 书还在书架上，再点还是 {@code 2001}。既然后端返回列表时
     * 明确把这行显示成「已下架」占位卡，就必须同时给出清掉它的路。
     *
     * <p>不校验也不会被当成探针：这个接口<b>要登录</b>，
     * 而且它对任意 {@code novelId} 的响应完全一样（成功、删 0 行），
     * 区分不出「这本书存在吗」。
     */
    ShelfStatusVO remove(Long novelId);
}
