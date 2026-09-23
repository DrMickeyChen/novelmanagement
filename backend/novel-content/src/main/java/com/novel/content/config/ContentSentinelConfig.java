package com.novel.content.config;

import com.novel.common.sentinel.SentinelRules;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * <b>novel-content 的熔断名单（M5）。</b>
 *
 * <p>规则的构造与阈值的理由都在 {@link SentinelRules}，这里只管<b>名单</b> ——
 * 而名单恰恰是最容易错、错了又不报错的那一半。
 *
 * <h3>★★ 这五条是怎么来的 —— 不是抄的，是从源码推出来、再用 {@code /tree} 对过的</h3>
 *
 * <p>资源名 = {@code 大写动词 + ":" + "http://" + name + cleanPath + 方法上的path}
 * （公式与出处见 {@link SentinelRules} 的类注释）。本服务的两个客户端是：
 *
 * <pre>
 *   AuthUserClient      name="novel-auth",        path="/internal"
 *   CommentBriefClient  name="novel-interaction", path="/internal"
 * </pre>
 *
 * <h3>★★★ 这五条里只有三条「有降级可言」—— 另外两条也配了，理由不一样</h3>
 *
 * <p>这是 M5 最容易被人问住的一处，所以写清楚：<b>熔断和降级是两件事</b>。
 * 熔断回答「还要不要发这个请求」，降级回答「发不出去时拿什么顶上」。
 * 判断「该不该配熔断」看的是<b>这条边会不会把本服务的线程拖死</b>，
 * 判断「该不该降级」看的是<b>缺失在界面上可不可见</b>（M5 判断 1）。
 * 两者只在「都值得做」时重合，<b>不是同一个问题的两种说法</b>。
 *
 * <table border="1">
 *   <caption>content 的五条出站边</caption>
 *   <tr><th>资源名里的方法</th><th>干什么</th><th>降级</th></tr>
 *   <tr><td>POST …/users/nicknames</td><td>批量取作者昵称（列表页、首页）</td>
 *       <td>✅ 作者名从 JSON 里消失 → 前端显示 {@code —}</td></tr>
 *   <tr><td>GET …/users/{id}/nickname</td><td>单个作者昵称（详情页）</td><td>✅ 同上</td></tr>
 *   <tr><td>POST …/author-applies/briefs</td><td>审核日志里的「作者申请 #5」摘要</td>
 *       <td>✅ 退化成只显示 id</td></tr>
 *   <tr><td>POST …/comment-briefs</td><td>审核日志里的评论正文（content 反读 interaction）</td>
 *       <td>✅ 退化成「评论 #5」</td></tr>
 *   <tr><td>★★ GET …/users/ids-by-nickname</td><td><b>按作者名搜索的筛选输入</b></td>
 *       <td>❌ <b>不降级</b>（M5 判断 1）—— 但它<b>照样配熔断</b>：
 *           降级成空集合会造出「没有找到相关作品」，而熔断只是让它<b>更快地</b>
 *           抛出那个 500。两者不矛盾</td></tr>
 * </table>
 *
 * <p>★ 最后一行值得再说一句：<b>熔断一条「不能降级的边」仍然有价值</b> ——
 * 它保护的是本服务的 Tomcat 线程，不是用户的观感。auth 慢的时候，
 * 这个请求会在 500ms 后就被本地判掉，而不是占着线程等满 5 秒。
 *
 * <h3>★ 怎么验这五条名字是对的</h3>
 *
 * <p>跑完全量回归后，在 content 的 transport 端口（<b>8719，但不保证</b>，
 * 见 {@code docs/环境配置.md}）上：
 * <pre>
 *   curl http://127.0.0.1:8719/tree                 → 走过的资源名，五条都该在
 *   curl http://127.0.0.1:8719/getRules?type=degrade → 五条规则都该在
 * </pre>
 * <p>★ <b>只有 {@code /getRules} 非空是不够的</b> —— 规则在 JVM 里、
 * 和规则<b>名字对得上</b>是两件事。两边一对照，落单的那个就是死规则。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class ContentSentinelConfig {

    /**
     * ★ {@code @PostConstruct} 而不是 {@code ApplicationRunner}：前者在
     * <b>bean 装配阶段</b>执行，早于 Servlet 容器开始接客（那是
     * {@code finishRefresh()} 的事）。用 Runner 的话，启动到最后一步之间
     * 进来的请求会跑在「无规则」状态下 —— 那个窗口很短，但它是真实存在的，
     * 而且没有任何日志能证明它发生过。
     */
    @PostConstruct
    public void loadRules() {
        SentinelRules.loadSlowCallRules("novel-content",
                // ↓ AuthUserClient（name="novel-auth", path="/internal"）
                "POST:http://novel-auth/internal/users/nicknames",
                "GET:http://novel-auth/internal/users/{id}/nickname",
                "GET:http://novel-auth/internal/users/ids-by-nickname",
                "POST:http://novel-auth/internal/author-applies/briefs",
                // ↓ CommentBriefClient（name="novel-interaction", path="/internal"）
                "POST:http://novel-interaction/internal/comment-briefs");
    }
}
