package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * 小说管理系统 —— 搜索服务启动类（第二期 M4 起）
 *
 * <p><b>本服务装着：</b>Elasticsearch 全文检索 + 索引同步（{@code com.novel.search}）。
 *
 * <h3>★★ 它和另外三个服务有一个本质区别</h3>
 *
 * <p>{@code novel-auth} / {@code novel-content} / {@code novel-interaction}
 * 是从第一期的单体里<b>搬</b>出来的 —— 业务代码一行没改，只是换了个进程跑。
 * <b>本服务是【新写】的。</b>第一期那个 {@code ReaderSearchController}
 * 只是个壳（一个 Controller + 一个 Service 方法，实现是 MySQL {@code LIKE}），
 * 真正的搜索 M4 才有。
 *
 * <p>★ 所以这个类上的注解和前三个<b>不一样，而且少了一个</b>，两处都要能解释：
 *
 * <table border="1">
 *   <caption>四个服务启动类的注解对照</caption>
 *   <tr><th></th><th>auth</th><th>content</th><th>interaction</th><th>search</th></tr>
 *   <tr><td>{@code @SpringBootApplication}</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>{@code @EnableFeignClients}</td><td>✗</td><td>✅</td><td>✅</td><td><b>✅</b></td></tr>
 *   <tr><td>{@code @MapperScan}</td><td>✅</td><td>✅</td><td>✅</td><td><b>✗ 没有</b></td></tr>
 * </table>
 *
 * <p><b>★ 为什么【没有】{@code @MapperScan}：</b>本服务不连数据库。
 * 加了它反而有害 —— 它会去扫 {@code com.novel.modules.**.mapper}，
 * 而那个包在本服务的 classpath 上<b>不存在</b>，于是 MyBatis 会注册
 * 一个空的 MapperScannerConfigurer，并且强制要求一个 DataSource，
 * 而本服务的 yml 里没有任何 {@code spring.datasource.*} → <b>启动失败</b>，
 * 报的是「找不到数据源」。
 * ★★ 所以这里「少一个注解」不是漏了，是<b>必须少</b>：
 * 它是 {@code @MapperScan} 的第一次缺席，而这个缺席本身就是
 * 「本服务不连数据库」这句话的执行方式。
 *
 * <p><b>★ 为什么【需要】{@code @EnableFeignClients}：</b>本服务只有一个对端
 * （{@code novel-content}），但有两个用途，都定义在
 * {@link com.novel.search.client.ContentClient}：
 * <ul>
 *   <li>拿装配数据 —— ES 只返回 id，正文 / 作者名 / 分类名都在 MySQL 里；</li>
 *   <li>★ 降级路径 —— ES 不可用时调 content 的 {@code /internal/search} 走 LIKE。</li>
 * </ul>
 * <p>★ 它的默认扫描范围是启动类所在包（{@code com.novel}），
 * 客户端所在的 {@code com.novel.search.client} 在范围内。
 *
 * <h3>★★ 关于 {@code @EnableScheduling}：本服务靠它跑定时全量重建，但【不是自己开的】</h3>
 *
 * <p>索引自愈靠一个定时全量重建任务兜底（防止 MQ 消息丢失造成永久不一致）。
 * 按直觉那个注解应该挂在这里 —— <b>但它不在这儿，而在
 * {@code com.novel.common.config.SchedulingConfig} 上</b>，
 * 那是个会被<b>每个服务</b>扫到的 {@code @Configuration}。
 *
 * <p>★ 所以本服务【不要】再写一遍 {@code @EnableScheduling}。
 * 写了不会报错（这个注解是幂等的），但会让人以为「是这行在起作用」，
 * 而真正起作用的那一行在 common 里。
 *
 * <p>★★ M4 之前，「四个服务全都白拿一个 {@code @EnableScheduling}」
 * 这件事是<b>一个隐患</b>（见 {@code SchedulingConfig} 的长注释）；
 * M4 之后它变成了<b>一个被用上的能力</b> —— 但要说清：
 * <b>不是 search 自己开的，是继承来的。</b>
 * ★ 这条区别在将来「把 search 拆成独立仓库」时会立刻变成真的问题：
 * 那个注解不在本模块里，搬走它就跑不起来定时重建，而症状是
 * 「索引再也不自愈了」—— 不报错、不崩溃，只是慢慢变旧。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
public class SearchApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(SearchApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8084");
        String profiles = String.join(",", env.getActiveProfiles());

        /*
         * ★ 这个横幅照 content 的抄，但【不是】复制粘贴改端口就完事：
         *   ① 服务名换了（内容服务 → 搜索服务）
         *   ② 端口 8082 → 8084
         *   ③ ★ 最后几行列的是【兄弟服务的位置】，M4 起是五个进程 ——
         *      少列一个不会有任何报错，只会在排障时误导人。
         *
         * ★ 为什么横幅里的地址必须是对的：默认值只在 application.yml
         *   被改坏时才生效，而那时打印一个错的端口是纯粹的误导。
         */
        log.info("""

                        ----------------------------------------------------------
                          小说管理系统 · 搜索服务启动成功
                        ----------------------------------------------------------
                          当前环境 : {}
                          接口地址 : http://localhost:{}/api
                          接口文档 : http://localhost:{}/doc.html
                        ----------------------------------------------------------
                          ★ 生产环境这个端口【不对外】，唯一入口是网关 :8080
                          ★ 登录 / 用户 / 公告 / ping 在 novel-auth        :8081
                          ★ 作品 / 章节 / 分类 / 审核在 novel-content      :8082
                          ★ 评论 / 书架 / 历史在 novel-interaction         :8083
                          ★ 本服务依赖 Elasticsearch（在虚拟机上，:9200）
                            与 novel-content（装配数据 + 降级路径）
                          ★★ 注意：ES 地址写错了【不影响启动】—— 客户端懒连接，
                             要到第一次搜索才炸，而且错误信息里写着 ES 的地址，
                             看着像「ES 没起来」。启动成功验不到 ES 通不通。""",
                profiles.isEmpty() ? "default" : profiles,
                port, port);
    }
}
