package com.novel.interaction.client;

import com.novel.common.internal.FallbackSupport;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * <b>{@link AuthNicknameClient} 的降级工厂（M5）。</b>
 *
 * <p>★ 关于「为什么必须是 Spring bean」「为什么还要 application.yml 里那个开关」
 * 「S3 为什么只记日志、S5 才接上降级值」——完整论证写在 content 的
 * {@code AuthUserClientFallbackFactory} 类注释里（那是本项目的第一个），
 * 这里不重复。
 *
 * <p>★★ 顺带对照：content 那份同类的工厂里有<b>四个</b>方法，其中
 * {@code userIdsByNickname} <b>刻意不降级</b>（它返回的是搜索的筛选输入，
 * 降级会变成一句「没有找到相关作品」）。<b>那份是唯一一个在一个文件里
 * 走出两种结局的工厂</b> —— 这里只有一条接缝，所以只有一种结局。
 *
 * <h3>★ 这条接缝【可降级】，降级物是「一个破折号」</h3>
 *
 * <p>它只有一个方法：批量取评论作者昵称。挂掉时昵称位置显示
 * <b>{@code —}</b>，评论正文、时间、楼层全都还在 ——
 * 界面上是一眼可辨的缺失，而不是一句看起来正常的谎话。
 *
 * <p>★★ <b>关键在于它【不需要】在 JSON 里塞一个假值。</b>
 * 全局配了 {@code default-property-inclusion: non_null}，
 * 而且 {@code Map<Long, String>} 里<b>查不到的 key 直接不出现在 Map 里</b> ——
 * 于是前端拿到的就是「这个 id 没有昵称」，连 {@code null} 都不用造。
 * ★ 这正是 M5 判据 1 说的形状：<b>降级 = 让字段从 JSON 里消失，
 * 不是填 0 / false / ""</b>（那些「看起来正常」的假值恰恰是 M1 反对的东西）。
 *
 * <p>★ 反例在同一个服务里，而且是【刻意的】：{@code ContentClient}
 * 那条边（interaction → content）挂掉时<b>没有任何降级可言</b> ——
 * 作品名取不到，书架就是一排空壳。见它的 fallbackFactory。
 *
 * @author MickeyChen
 */
@Component
public class AuthNicknameClientFallbackFactory implements FallbackFactory<AuthNicknameClient> {

    private static final String CALL = "取评论作者昵称（interaction → auth）";

    /**
     * ★ S5：降级成<b>空 Map</b> —— 落到界面上就是那个破折号。
     *
     * <p>★ 调用方 {@code CommentServiceImpl#nicknamesOf} 早就写了
     * {@code result == null ? Map.of() : result}，并且注释里说明了理由
     * （「HTTP 这层多了一个『什么都没返回』的状态」）。所以这次降级
     * <b>复用的是一条已经存在、已经想清楚了的路径</b>。
     * <p>★ 注意降级的是<b>整批</b>昵称，不是某几个 —— 一页评论里的作者名
     * 会同时变成 {@code —}。这是对的：挂掉的是「批量取昵称」这一次调用，
     * 而不是某几个用户。★ 别为此改成「逐个重试」，那会把一次失败放大成
     * 20 次失败（N+1 的另一种形状）。
     */
    @Override
    public AuthNicknameClient create(Throwable cause) {
        /*
         * ★ 只有一个方法 → 用 lambda，不用匿名实现（对照 AuthUserClient 那份）。
         *   返回 Result.success(空 Map) 而不是裸 null，理由见
         *   FallbackSupport.logThenDegrade：裸 null 会被 unwrap 判成
         *   「对端返回了空 body」并抛 SYSTEM_ERROR。
         */
        return (userIds) -> FallbackSupport.logThenDegrade(CALL, cause, Collections.<Long, String>emptyMap());
    }
}
