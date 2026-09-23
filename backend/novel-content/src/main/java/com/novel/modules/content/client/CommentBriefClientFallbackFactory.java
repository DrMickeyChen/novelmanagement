package com.novel.modules.content.client;

import com.novel.common.internal.FallbackSupport;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * <b>{@link CommentBriefClient} 的降级工厂（M5）。</b>
 *
 * <p>★ 关于「为什么必须是 Spring bean」「为什么还要 application.yml 里那个开关」
 * 「S3 为什么只记日志、S5 才接上降级值」——完整论证写在
 * {@link AuthUserClientFallbackFactory} 的类注释里，这里不重复。
 *
 * <h3>★★★ 这条接缝【不降级】—— 而它看起来最像该降级的那一条</h3>
 *
 * <p>它只服务一个场景：<b>管理端审核日志要显示评论正文</b>。挂掉之后
 * 「有编号、没正文」看起来正是一眼可辨的缺失，判它可降级是自然的。
 * ★★ 而<b>实测推翻了这个直觉</b>：这一格在页面上只有两个渲染分支，
 * 而空值落进的那一个写的是「<b>已删除</b>」。详见 {@code create()} 里的实测记录。
 *
 * <p>★★ 而它正是那条<b>服务级环</b>（{@code content ⇄ interaction}）的回程。
 * 这个环 MQ 解不掉，因为要的是「页面就在等的那个值」，异步化没有意义；
 * 要根治只能改成「审核时把正文快照写进 {@code audit_log}」（那是 DDL）。
 * ★ M5 <b>仍然不解决它</b>，也<b>不再试图降级它</b> —— 别把这两件事混起来说。
 *
 * @author MickeyChen
 */
@Component
public class CommentBriefClientFallbackFactory implements FallbackFactory<CommentBriefClient> {

    private static final String CALL = "取评论正文（content → interaction，管理端审核日志用）";

    @Override
    public CommentBriefClient create(Throwable cause) {
        /*
         * ★★ 这条接缝【不降级】—— S5 中途改错过一次，这里留着证据。
         *
         *   原本按「有编号、没正文，一眼可辨」判它是可降级的，于是 S5 把它
         *   改成了 `logThenDegrade(CALL, cause, 空 Map)`。★ 实测结果是：
         *   停掉 interaction 后这个接口【HTTP 200】，而审核日志那一行渲染成
         *
         *       「评论 #94（已删除）」
         *
         *   （复刻 admin-web/views/log/index.vue 的 targetLabel()：
         *   targetTitle 为空就拼「（已删除）」，见那个函数的文件头注释。）
         *
         *   ★★★ 而那条评论根本没有被删。**降级把「拿不到」说成了一条
         *   关于数据的具体假话**，还是在审核日志这种用来追责的页面上。
         *
         * ★★ 正确的判据不是「缺失看不看得见」，而是更靠前的一步：
         *   【消费方能不能诚实地把这个缺失渲染出来】。
         *   评论正文这一格只有一个渲染分支 —— 「有标题」或「已删除」，
         *   没有第三个状态能容纳「暂时拿不到」。所以这里没有安全的降级值。
         *
         * ★ 这不是新判断：CommentTextLookupImpl 的类注释（M1 写的）和
         *   CommentTextLookup#briefsOf 的注释（P6 写的）都逐字写了这个症状，
         *   结论都是「宁可 500」。S5 的方案表把这条标成「可降级」是错的，
         *   而纠正它靠的是【停掉 interaction 实测一次】—— 铁律 8。
         *
         * ★★ 同一个陷阱有【第二个入口】，在隔壁：AuthUserClientFallbackFactory
         *   的 briefsOf（作者申请摘要）。它喂的是同一列 targetTitle、
         *   落进同一个 targetLabel()，只是 targetType 是 4 不是 3。
         *   两条一起判、一起改 —— 只改一条会留下一个不对称的洞。
         */
        return FallbackSupport.logThenRethrow(CALL, cause);
    }
}
