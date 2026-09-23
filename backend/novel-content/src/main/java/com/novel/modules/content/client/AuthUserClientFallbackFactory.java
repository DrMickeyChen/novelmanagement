package com.novel.modules.content.client;

import com.novel.common.internal.AuthorApplyBriefDTO;
import com.novel.common.internal.FallbackSupport;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <b>{@link AuthUserClient} 的降级工厂（M5）。</b>
 *
 * <p><b>★★★ 这个类【必须】是一个 Spring bean，不能只是 {@code new} 出来的对象。</b>
 * SCA 的 {@code SentinelFeign} 是按类型从容器里取它的
 * （{@code getFromContext(..., FallbackFactory.class)}），取不到就抛。
 * ★ 这个失败是【大声的】（启动/首次调用就炸），和「开关没开」那种
 * 静默失效正好相反 —— 后者见下面那段。
 *
 * <p><b>★★ 光有 {@code @FeignClient(fallbackFactory = ...)} 是不够的</b>，
 * 还要 application.yml 里的 {@code feign.sentinel.enabled: true}。
 * 少了它，SCA 走的是 {@code DefaultTargeter}，<b>这个类永远不会被创建</b>，
 * 而应用照常启动、一条报错都没有。两个条件缺任何一个，
 * 本类都只是一段不会被执行的代码。
 *
 * <h3>★★★ 本类是【唯一一个在一个文件里走出两种结局】的降级工厂（S5）</h3>
 *
 * <p>其余四个工厂各自只有一种处置（要么全降级、要么全抛），
 * 而这个客户端有四个方法，其中<b>两个降级、两个不降级</b> ——
 * 因为降级的判据不是「服务重不重要」，是<b>「消费方能不能诚实地把这个缺失渲染出来」</b>
 * （M5 文档判断 1）。所以 {@code create()} 返回的是一个<b>匿名实现</b>，
 * 四个方法各自写各自的处置，<b>没有一行是共用的</b>。
 *
 * <p>★ 写成一个匿名类而不是四个小工厂：这个差异是<b>同一个客户端内部</b>的，
 * 拆出去会让人以为它们是四条独立的接缝。差异要留在能一眼看见的地方。
 *
 * <h3>S3 → S5 的演变</h3>
 *
 * <p>★ S3 引入 Sentinel 时，这个类<b>只记日志不改行为</b>（一律原样抛），
 * 为的是让那一步能用「32 端点快照 0 差异」证明「引入 Sentinel 没弄坏任何东西」。
 * <b>S5 才把降级值接上</b>——也就是下面这张表的右列。
 *
 * <p><b>★★ 这个类里有两条接缝，它们的结局【刻意不同】</b>（见 M5 文档判断 1）：
 *
 * <table border="1">
 *   <caption>同一个客户端的四个方法</caption>
 *   <tr><th>方法</th><th>降级成什么</th><th>界面上看到</th></tr>
 *   <tr>
 *     <td>{@code nicknameOf}</td><td>{@code Result.success(null)}</td>
 *     <td>详情页作者名 <b>{@code 佚名}</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code nicknamesOf}</td><td>{@code Result.success(空 Map)}</td>
 *     <td>每张卡片的作者名 <b>{@code 佚名}</b>；审核日志的操作人 <b>{@code 用户 #1}</b></td>
 *   </tr>
 *   <tr>
 *     <td>★★ {@code briefsOf}</td>
 *     <td><b>仍然抛</b>（★ S5 中途改错过，见 {@code create()} 里的记录）</td>
 *     <td>★ 审核日志那一行会渲染成「<b>作者申请 #5（已删除）</b>」—— 一句假话</td>
 *   </tr>
 *   <tr>
 *     <td>★★ {@code userIdsByNickname}</td>
 *     <td><b>仍然抛</b>（S5 一行不改）</td>
 *     <td>★ 按作者名搜索<b>整个 500</b>，而不是「没有找到相关作品」</td>
 *   </tr>
 * </table>
 *
 * <p>★★ <b>前两行的共同点：降级 = 字段从 JSON 里消失</b>
 * （全局 {@code default-property-inclusion: non_null} + 包装类型），
 * 于是前端拿到的就是「这个字段没有」—— <b>不是 {@code 0} / {@code false} / {@code ""}</b>。
 * 那些「看起来正常」的假值恰恰是 M1 反对的东西（{@code ShelfStatusVO#onShelf} 有完整论证）。
 * ★ 所以本类的四个返回值<b>没有一个是基础类型</b>：{@code String} / {@code Map} / {@code List}，
 * 空值天然可表达。假如哪天要给 {@code AuthUserClient} 加一个返回 {@code int} 的方法，
 * <b>它不在这里的降级范围里</b>——{@code int} 表达不了「未知」，降级它只会把 {@code 0} 送出去。
 *
 * <h3>★★★ 分界线：「佚名 / 用户 #1」和「（已删除）」不是一回事</h3>
 *
 * <p>三个兜底文案看起来都是「缺了就说句别的」，但按同一个判据量下去会分成两堆 ——
 * <b>看它说的是「我们知道什么」，还是「数据怎么了」</b>：
 *
 * <table border="1">
 *   <caption>兜底文案在断言什么</caption>
 *   <tr><th>兜底文案</th><th>它在说的是</th><th>判</th></tr>
 *   <tr><td>{@code authorName || '佚名'}</td>
 *       <td>作者名<b>不详</b> —— 关于「我们知道什么」</td>
 *       <td>✅ <b>真话</b></td></tr>
 *   <tr><td>{@code operatorName || '用户 #' + id}</td>
 *       <td>这是 1 号用户，<b>只是没拿到名字</b></td>
 *       <td>✅ <b>真话</b></td></tr>
 *   <tr><td>{@code targetTitle || '（已删除）'}</td>
 *       <td>这条数据<b>被删了</b> —— 关于「数据怎么了」的<b>具体断言</b></td>
 *       <td>❌ <b>假话</b></td></tr>
 * </table>
 *
 * <p>★ 「佚名」之所以成立，不是因为它含糊，是因为它<b>恰好就是真的</b>：
 * 名字确实没拿到。而「（已删除）」凭空说出了一个<b>没有发生过的事件</b>，
 * 还是在审核日志这种<b>用来追责</b>的页面上。
 * ★★ 所以判的不是「缺得明不明显」，是<b>那句兜底文案有没有撒谎</b>。
 *
 * <p>★ 最后一行是这张表的全部价值：{@code userIdsByNickname} 返回的是
 * <b>喂给 {@code in(authorId)} 的筛选输入</b>，它降级成空集合的后果是结果集为 0 条，
 * 而页面上写的是「<b>没有找到相关作品</b>」—— 和「这个作者真的没有作品」一模一样。
 * 那正是 M1 那句「宁可 500，也不要一个看起来正常但内容是错的页面」。
 * <b>拆错方向的症状只是「按作者名搜索突然搜不到了」，不会有任何报错。</b>
 *
 * @author MickeyChen
 */
@Component
public class AuthUserClientFallbackFactory implements FallbackFactory<AuthUserClient> {

    /**
     * 这次调用是干什么的。
     *
     * <p>★ 写成「content → auth」这个<b>方向</b>而不是只写「取昵称」：
     * 排查的人要能一眼看出是哪条边挂了。本服务有两条出站边
     * （auth / interaction），只写业务名会分不清。
     *
     * <p>★ 三条日志文案各自对应<b>不同的处置</b>（两条降级记 WARN、两条抛记 ERROR），
     * 所以不能共用一个常量 —— 看到日志的人要能立刻分出
     * 「这是设计好的降级」还是「这是刻意让它 500」。
     */
    private static final String CALL = "取作者昵称（content → auth）";

    /**
     * ★ 第二条<b>不降级</b>的调用，单独一个名字。
     *
     * <p>它返回的是「这些作者申请分别叫什么」，喂给审核日志的
     * {@code targetTitle}。★ 而 {@code targetTitle} 的兜底文案是
     * 「<b>（已删除）</b>」—— 空值等于<b>凭空断言这些申请被删了</b>。
     * 详见 {@code create()} 里那段实测记录。
     */
    private static final String CALL_APPLY = "取作者申请摘要（content → auth，★ 这条刻意不降级："
            + "审核日志把空值渲染成「（已删除）」，而它们没有被删）";

    /**
     * ★ 这一条<b>不降级</b>的那次调用，单独一个名字。
     *
     * <p>它和上一条走的是<b>不同的方法</b>（{@code logThenRethrow} 而不是
     * {@code logThenDegrade}），所以日志级别也不同（error / warn）。
     * ★ 而这一行字是排查时唯一能一眼看出「这里为什么是 500 而不是破折号」
     * 的地方 —— 没有它，看到「按作者名搜索 500」的人会去查 auth 是不是挂了，
     * 而正确答案是<b>「auth 确实挂了，但这条接缝【故意】不降级」</b>。
     */
    private static final String CALL_FILTER =
            "按昵称反查用户（content → auth，★ 这条刻意不降级：它是搜索的筛选输入，"
                    + "降级会变成一句「没有找到相关作品」）";

    @Override
    public AuthUserClient create(Throwable cause) {
        /*
         * ★★★ 四个方法【没有一行共用】—— 两个降级、两个抛。理由见类注释那张表。
         *     写成匿名实现而不是在 create() 里就抛（S3 的写法），正是为了这个差异。
         *
         * ★ 每个方法传的降级值都是 null 或【空集合】，绝不能是 0 / false / ""。
         *   那些「看起来正常」的假值会让「服务挂了」和「真的没数据」在界面上
         *   长得一模一样 —— 那正是 M1 说的「看起来正常但是错的页面」。
         *
         * ★★ 返回的是 Result.success(...) 而不是裸值：内部接口一律带信封，
         *    调用方拿 InternalResults.unwrap 拆 —— 裸 null 会被判成「对端返回了
         *    空 body」并抛 SYSTEM_ERROR，降级白做还多一条误导性的 ERROR。
         *    FallbackSupport.logThenDegrade 的签名把这件事兜住了。
         */
        return new AuthUserClient() {

            /**
             * 详情页的作者名。降级 → 该字段从 JSON 里消失，前端渲染
             * {@code authorName || '佚名'} —— <b>「作者名不详」是句真话</b>。
             */
            @Override
            public Result<String> nicknameOf(Long id) {
                return FallbackSupport.logThenDegrade(CALL, cause, null);
            }

            /**
             * 列表页的作者名（批量）。降级 → 空 Map，每个 id 都查不到名字。
             *
             * <p>★ 这个方法<b>有两个消费者</b>，两个的兜底文案都成立：
             * {@code NovelVO.authorName} → {@code '佚名'}，
             * {@code AuditLogVO.operatorName} → {@code '用户 #' + operatorId}。
             * <b>两个都在说「我们只知道这么多」，没有一个是假话。</b>
             * ★ 换一个消费者之前要重新判一次 —— 同一个空值落到别处可能是「（已删除）」。
             */
            @Override
            public Result<Map<Long, String>> nicknamesOf(List<Long> userIds) {
                return FallbackSupport.logThenDegrade(CALL, cause, Collections.emptyMap());
            }

            /**
             * ★★ 不降级之一：它返回的是「哪些用户符合这个昵称」，
             * 调用方拿它去 {@code in(authorId)} —— 空集合 == 筛选结果为 0 条，
             * 而界面上会写「没有找到相关作品」。<b>宁可 500。</b>
             */
            @Override
            public Result<List<Long>> userIdsByNickname(String keyword) {
                return FallbackSupport.logThenRethrow(CALL_FILTER, cause);
            }

            /**
             * ★★ 不降级之二 —— <b>S5 中途改错过一次，这里留着证据。</b>
             *
             * <p>它喂的是审核日志的 {@code targetTitle}。按「有编号、没标题，
             * 一眼可辨」判它是可降级的，于是 S5 一度把它改成了
             * {@code logThenDegrade(CALL, cause, 空 List)}。
             *
             * <p>★★★ <b>而空 List 会让 {@code applyTitles} 变成空 Map</b>
             * （{@code ContentUserLookup#authorApplyTitlesOf} 拿到空 applies
             * 就直接 {@code Collections.emptyMap()}），于是
             * {@code AuditLogServiceImpl} 的 {@code case AUTHOR_APPLY} 取到 {@code null}，
             * 前端 {@code targetLabel()} 渲染成
             *
             * <pre>    作者申请 #5（已删除）</pre>
             *
             * <p>★ 而那些申请<b>根本没有被删</b>。
             * {@code ContentUserLookup#authorApplyTitlesOf} 的注释逐字写着
             * 「查不到的 id 不会出现在 Map 里 … 日志页显示成『作者申请 #5（已删除）』」——
             * <b>降级会让这句话里的因果关系变成假的</b>：现在还有第二个原因能走到 null。
             *
             * <p>★★ 和 {@code CommentBriefClientFallbackFactory} 是<b>同一个陷阱的两个入口</b>：
             * 那条接缝在 {@code targetType=3}（评论）上，这条在 {@code targetType=4}（作者申请）上，
             * <b>喂的是同一列 {@code targetTitle}、落进同一个 {@code targetLabel()}</b>。
             * 两条一起判、一起改，只改一条会留下一个不对不对称的洞。
             * ★ 纠正它们靠的都是【把渲染那条路读到底】—— 铁律 8。
             */
            @Override
            public Result<List<AuthorApplyBriefDTO>> briefsOf(List<Long> applyIds) {
                return FallbackSupport.logThenRethrow(CALL_APPLY, cause);
            }
        };
    }
}
