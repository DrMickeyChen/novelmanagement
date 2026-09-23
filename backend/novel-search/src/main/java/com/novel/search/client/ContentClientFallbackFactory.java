package com.novel.search.client;

import com.novel.common.internal.FallbackSupport;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * <b>{@link ContentClient} 的降级工厂（M5）。</b>
 *
 * <p>★ 通用论证（为什么必须是 bean / 为什么还要那个开关 / 为什么 S3 只记日志）
 * 见 content 的 {@code AuthUserClientFallbackFactory}。
 *
 * <h3>★★ 本服务只有这一条出站边，而它挂了【没有降级可言】</h3>
 *
 * <p>search 没有自己的数据库 —— ES 只当「id 检索器」，<b>装配
 * {@code NovelVO} 必须 Feign 回 content</b>。所以 content 挂掉的后果是
 * 「搜到了 id，但拼不出任何一本书」。
 *
 * <p><b>★★★ 而这里有一个特别容易搞错的地方：M4 的「降级路径」也调 content。</b>
 *
 * <pre>
 *   try {
 *       return searchByEs(...);      // ← 这里面【包含】一次 Feign 调 content
 *   } catch (Exception e) {
 *       log.warn("ES 检索失败，降级到数据库 LIKE | ...");
 *       return degradeToLike(...);   // ← 这一次 Feign 【没有 catch】→ 直接冒泡 500
 *   }
 * </pre>
 *
 * <p>于是 content 挂掉时的真实行为是两跳：<b>第一次调用被上面那个
 * {@code catch (Exception)} 吞掉，还被归因成「ES 检索失败」</b>，
 * 第二次调用才抛出来。★ <b>那行日志的归因是错的</b> ——
 * 它会把「content 挂了」说成「ES 挂了」，把排查方向直接带到
 * ES 容器上去。
 *
 * <p>★★ M5 <b>不重写那段</b>（{@code try} 的语义被刻意限定为
 * 「ES 这条路不通」，那是 M4 的既有设计）。M5 的做法是<b>在本类
 * 补一条归因正确的日志</b>：同一个异常会在这里被
 * {@link FallbackSupport} 认出「下游不可达或响应超时」，
 * 两行日志放在一起看，就能分辨到底是谁挂了。
 *
 * <p>★ 所以：<b>「停 content 时搜索返回 500」是预期行为，不是 M5 要修的 bug。</b>
 * M5 对这条边的处置是熔断 + 快速失败，不是编一个空结果出来。
 *
 * @author MickeyChen
 */
@Component
public class ContentClientFallbackFactory implements FallbackFactory<ContentClient> {

    private static final String CALL = "装配检索结果 / 全量重建取文档（search → content）";

    @Override
    public ContentClient create(Throwable cause) {
        /*
         * ★ 同 interaction 的 ContentClientFallbackFactory：S5 改了四个工厂的
         *   返回值，【唯独没有动这一行】——「没有 content 就没有书」，
         *   没有安全的降级值。保持「记一笔 + 原样抛」是最终形态，不是没做完。
         *
         * ★ 判据是「降级之后和真的没数据区分得开吗」。这里区分不开：
         *   一个降级出来的空结果，在搜索页上就是「没有找到相关作品」——
         *   而那正是本章要消灭的那句谎话（见 doSearch 的类注释）。
         */
        return FallbackSupport.logThenRethrow(CALL, cause);
    }
}
