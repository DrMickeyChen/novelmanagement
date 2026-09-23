package com.novel.interaction.config;

import com.novel.common.sentinel.SentinelRules;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * <b>novel-interaction 的熔断名单（M5）。</b>
 *
 * <p>规则的构造与阈值的理由都在 {@link SentinelRules}，这里只管名单。
 * 本服务有两个客户端：
 *
 * <pre>
 *   AuthNicknameClient  name="novel-auth",    path="/internal"
 *   ContentClient       name="novel-content", path="/internal"
 * </pre>
 *
 * <h3>★★★ 这七条【全部】不降级 —— 它是 M5 里最干净的一个「没有降级可言」样本</h3>
 *
 * <p>按 M5 判断 1 的判据（<b>缺失在界面上可不可见</b>），interaction 的七条边
 * 一条都不该降：
 *
 * <table border="1">
 *   <caption>为什么这七条降不了</caption>
 *   <tr><th>边</th><th>降级会变成什么</th></tr>
 *   <tr><td>{@code POST …/users/nicknames}（auth）</td>
 *       <td>★ 评论列表里<b>每条评论的作者名都空着</b>。列表还在、条数还对，
 *           只是「谁写的」全没了 —— 这正是 M1 说的
 *           「看起来正常但内容是错的页面」</td></tr>
 *   <tr><td>{@code POST …/novels/briefs}、{@code POST …/chapters/briefs}</td>
 *       <td>★★ <b>书架 / 阅读历史整页变成一排空壳</b> —— 书名和章节名都取不到，
 *           只剩下 id。那不是降级，那是把功能删掉</td></tr>
 *   <tr><td>{@code GET …/novels/{id}/published}、{@code …/chapters/{id}/status}</td>
 *       <td>★ 这两个是<b>校验</b>（能不能发评论）。降级成「通过」= 给未上架的书
 *           放行评论；降级成「不通过」= 正常用户发不出评论。<b>两个方向都是错的</b>，
 *           所以它们连「返回一个默认值」这个选项都没有</td></tr>
 *   <tr><td>{@code GET …/novels/{id}/chapter-ids}</td>
 *       <td>管理端按作品筛评论。降级成空列表 = <b>筛选结果为空但界面不说为什么</b>；
 *           而 {@code IN ()} 是语法错误，多跑一步就是 500</td></tr>
 *   <tr><td>{@code GET …/novels/collect-counts}</td>
 *       <td>对账任务。<b>降级成不扫 = 账永远对不平，而且没人知道</b></td></tr>
 * </table>
 *
 * <p><b>★ 所以 interaction 在 M5 里加的是熔断，不是降级。</b>
 * content 挂掉时本服务的正确表现是<b>快速失败 + 前端说「暂时拿不到」</b>
 * （那是 S2 的前端工作），而<b>不是</b>编一个空书架出来。
 *
 * <h3>★★ 一个容易漏掉的细节：这里的资源名和 search 的会【长得一模一样】</h3>
 *
 * <p>本服务写的是 {@code path="/internal"}，方法上是 {@code @PostMapping("/novels/briefs")}；
 * 而 search 的 {@code ContentClient} <b>没写 path</b>，方法上写的是
 * {@code @PostMapping("/internal/novels/reader-vos")}。
 * 两段字符串拼出来<b>形状相同</b>（都是 {@code http://novel-content/internal/...}），
 * 但<b>来源不同</b>：一个是类的 path，一个是方法的 path。
 *
 * <p>★ 这不是问题 —— 规则是<b>按 JVM 各自索引</b>的，两个进程里同名资源互不影响。
 * 写在这里是因为「照抄另一个服务的那一行」时，你抄对的概率和抄错的概率一样大，
 * 而错了不会报错。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class InteractionSentinelConfig {

    @PostConstruct
    public void loadRules() {
        SentinelRules.loadSlowCallRules("novel-interaction",
                // ↓ AuthNicknameClient（name="novel-auth", path="/internal"）
                "POST:http://novel-auth/internal/users/nicknames",
                // ↓ ContentClient（name="novel-content", path="/internal"）
                "POST:http://novel-content/internal/novels/briefs",
                "POST:http://novel-content/internal/chapters/briefs",
                "GET:http://novel-content/internal/novels/{id}/published",
                "GET:http://novel-content/internal/novels/{novelId}/chapters/{chapterId}/status",
                "GET:http://novel-content/internal/novels/{id}/chapter-ids",
                "GET:http://novel-content/internal/novels/collect-counts");
    }
}
