package com.novel.interaction.client;

import com.novel.common.internal.FallbackSupport;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * <b>{@link ContentClient} 的降级工厂（M5）。</b>
 *
 * <p>★ 通用论证（为什么必须是 bean / 为什么还要那个开关 / 为什么 S3 只记日志）
 * 见 content 的 {@code AuthUserClientFallbackFactory}。
 *
 * <h3>★★★ 这条边上【没有降级可言】—— 而这是结论，不是偷懒</h3>
 *
 * <p>本服务对 content 的 6 个调用回答的都是同一类问题：
 * <b>「这本书叫什么、这一章属于哪本书、还在架上吗」</b>。
 * content 是<b>主数据源</b>，它挂了没有任何「降级数据」可以显示 ——
 * 唯一的选项是显示一个空列表，而<b>空书架和「你收藏的书都没了」长得一模一样</b>。
 * ★ 那正是 M1 那句「宁可 500，也不要一个看起来正常但内容是错的页面」。
 *
 * <p><b>所以 M5 对这条边的正确处置是「熔断 + 快速失败 + 前端显示『暂时拿不到』」，
 * 而不是「编一个降级值出来」。</b> 停 content 时本服务的接口返回 500 是
 * <b>预期行为</b>。
 *
 * <h3>★★ 而且这 6 个方法里有一个【绝对不能】降级，类型都不一样</h3>
 *
 * <p>{@code requirePublished} 和另外 5 个不是一回事：
 *
 * <table border="1">
 *   <caption>查询 vs 校验</caption>
 *   <tr><th></th><th>另外 5 个</th><th>{@code requirePublished}</th></tr>
 *   <tr><td>在做什么</td><td>对端替我做一次<b>查询</b></td><td>对端替我做一次<b>校验</b></td></tr>
 *   <tr><td>失败意味着</td><td>「没查到」</td><td>「<b>不知道</b>这本书该不该给读者看」</td></tr>
 *   <tr><td>降级成「成功」会怎样</td><td>—</td><td>★★★ <b>未上架的作品对读者变得可读</b> —— 一次越权</td></tr>
 * </table>
 *
 * <p>★ 它是 {@code Result<Void>}，「成功」和「降级成功」在类型上<b>完全一样</b>，
 * 没有任何东西能区分它们 —— 所以这里<b>不存在「降级得小心一点」这个选项</b>，
 * 只能是「不降级」。{@code InternalResults} 为此专门分了
 * {@code unwrap} / {@code unwrapBusiness} 两个方法，判据写在那个类的注释里。
 *
 * @author MickeyChen
 */
@Component
public class ContentClientFallbackFactory implements FallbackFactory<ContentClient> {

    private static final String CALL = "取作品/章节主数据（interaction → content）";

    @Override
    public ContentClient create(Throwable cause) {
        /*
         * ★★ S5 把四个 fallbackFactory 的返回值改成了降级值，【唯独没有动这一行】。
         *   「记一笔 + 原样抛」就是这条边的最终形态 —— 不是还没做，
         *   是这条边上没有安全的降级值可取（见类注释）。
         *
         * ★ 判据不是「content 更重要」，是那个老问题：
         *   降级之后用户看到的东西，和「真的一本书都没有」区分得开吗？
         *   这里区分不开 —— 空书架就是空书架。而在 auth 那条边上，
         *   一个「—」和真名字永远区分得开。
         */
        return FallbackSupport.logThenRethrow(CALL, cause);
    }
}
