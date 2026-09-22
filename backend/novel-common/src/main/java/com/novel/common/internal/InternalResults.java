package com.novel.common.internal;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 跨服务调用（Feign）拿回来的 {@link Result} 怎么解包 —— 全项目只此一份。
 *
 * <p><b>★★ 为什么必须有这么一个东西：</b>
 * 内部接口返回的是全项目统一的 {@code Result} 信封（和业务接口一样），
 * 所以「HTTP 200」<b>完全不等于</b>「调用成功」——
 * 这是铁律 3 的直接推论，也是这个项目里最容易反复踩的坑。
 * <pre>
 *   HTTP 200 + code=200  → 真的成功
 *   HTTP 200 + code=1001 → 业务失败（对端说「用户不存在」）
 *   HTTP 500             → Feign 直接抛异常，根本走不到这里
 * </pre>
 * 如果调用方拿到 {@code Result} 就 {@code getData()} 用，那第二种情况
 * 会拿到一个 {@code null}，然后以一个「数据是空的」的样子继续往下跑 ——
 * <b>调用失败被伪装成了「查无此人」</b>，而这两个结论在业务上的处理
 * 完全相反（一个该报错，一个该显示「已删除」）。
 *
 * <p><b>★★ 本类有两个解包方法，选错哪一个都是真 bug</b>（M2 补的第二个）：
 * <ul>
 *   <li>{@link #unwrap} —— 失败一律抛 {@code SYSTEM_ERROR}（500）。
 *       用在「对端替我查一次数据」的场景。</li>
 *   <li>{@link #unwrapBusiness} —— 失败时<b>把对端的业务码原样抛回</b>。
 *       用在「对端替我执行一次校验」的场景（2001 / 3001 必须原样到达前端）。</li>
 * </ul>
 * 判据和踩错之后的症状都写在 {@code unwrapBusiness} 的注释里，<b>用之前先读它</b>。
 *
 * <p><b>★ {@code unwrap} 失败时抛什么：{@code SYSTEM_ERROR}（HTTP 500），而不是新建一个 9xxx 码。</b>
 * <p>（下面这一整段说的是 {@code unwrap}，{@code unwrapBusiness} 走的是另一条路。）
 *
 * <p>原因是 {@code ResultHttpStatus} 的规则：<b>只有 400~599 才会变成真实的
 * HTTP 状态码，其余一律返 200</b>。而「兄弟服务调不通」是一次
 * <b>真正的系统故障</b>，它必须在监控上可见（APM 靠状态码统计 5xx）。
 * 如果给它一个 9001，结果是 HTTP 200 —— 监控上看起来一片祥和，
 * 只有用户在那里看到空数据。
 * <p>★ 哪天真想要一个可区分的码，<b>必须同时改 {@code ResultHttpStatus}</b>
 * 让它把 9xxx 映射成 500，否则就是上面那个陷阱。
 *
 * <p><b>★ 为什么不在这里做「降级」（失败就返回空集合）：</b>
 * 见 {@code ContentUserLookup} 的类注释 —— 降级的症状是
 * 「首页所有书的作者名变成空白」，它看起来像数据坏了，
 * 排查方向会一路跑到数据库上去，而真正的原因是认证服务不可用。
 * <b>宁可 500，也不要一个看起来正常但内容是错的页面。</b>
 *
 * @author MickeyChen
 */
@Slf4j
public final class InternalResults {

    private InternalResults() {
    }

    /**
     * 解包一个内部调用的结果。
     *
     * @param result 对端返回的信封，可以为 {@code null}（对端返回了空 body）
     * @param what   这次调用的用途，只用于日志 —— 排错时要一眼看出
     *               「是哪一次跨服务调用挂了」，而不是只知道「有一次挂了」
     * @return 信封里的 data
     * @throws BusinessException 对端返回了非成功码，或者根本没返回东西
     */
    public static <T> T unwrap(Result<T> result, String what) {
        if (result == null) {
            log.error("跨服务调用没有拿到响应体 | {}", what);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试");
        }
        if (!result.isSuccess()) {
            /*
             * ★ 把对端的错误码和消息原样记进日志，但【不要】把它透给前端。
             *   对端的业务码（比如 1001 用户不存在）在这个上下文里没有意义
             *   —— 调用方问的问题和那个码回答的问题不是同一个，
             *   透出去只会让前端拿到一个语义错位的提示。
             */
            log.error("跨服务调用返回失败 | {} | code={} message={}",
                    what, result.getCode(), result.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试");
        }
        return result.getData();
    }

    /**
     * 解包一个内部调用的结果，<b>失败时把【对端的业务码】原样抛回业务层</b>。
     *
     * <p><b>★★ 和 {@link #unwrap} 的区别只有一行，但选错哪一个都是真 bug。</b>
     * 判据是同一个问题：<b>「对端失败」这件事，在调用方的上下文里是什么？</b>
     *
     * <table border="1">
     *   <caption>两个方法的判据</caption>
     *   <tr><th></th><th>{@link #unwrap}</th><th>{@code unwrapBusiness}</th></tr>
     *   <tr>
     *     <td>对端在做什么</td>
     *     <td>替我做一次<b>查询</b><br>（取昵称、取申请摘要）</td>
     *     <td>替我做一次<b>校验 / 动作</b><br>（作品在不在架上、这章属于这本书吗）</td>
     *   </tr>
     *   <tr>
     *     <td>对端失败＝</td>
     *     <td>我<b>没拿到</b>本来要用的数据</td>
     *     <td>调用方这次操作<b>本来就不该成功</b></td>
     *   </tr>
     *   <tr>
     *     <td>该抛什么</td>
     *     <td>{@code SYSTEM_ERROR}（500）</td>
     *     <td><b>对端那个码</b>（2001 / 3001 …）</td>
     *   </tr>
     *   <tr>
     *     <td>抛错的样子</td>
     *     <td>「服务暂时不可用」</td>
     *     <td>「作品不存在或未上架」</td>
     *   </tr>
     * </table>
     *
     * <p><b>★ 为什么这个区分是必需的 —— 一个具体的例子：</b>
     * {@code NovelService#requirePublished} 对「未上架」返回 2001，
     * 前端据此显示「作品不存在或未上架」。如果调用方用 {@link #unwrap} 解包，
     * 这个 2001 会被改写成 500，用户看到的是「服务器开小差了」——
     * <b>一次正常的业务拒绝被伪装成了系统故障</b>，而且监控上会多出一批
     * 假的 5xx（每一次「有人点了一本下架的书」都会计一次）。
     *
     * <p><b>★★ 反过来的错误同样严重，这是这个项目最容易踩的坑：</b>
     * 因为业务码一律返 HTTP 200（铁律 3），<b>Feign 不会因为 2001 抛异常</b> ——
     * 它安安静静地返回一个 {@code code: 2001} 的信封。调用方要是
     * 「拿到结果就当成功」（哪怕是<b>根本不看返回值</b>），
     * 校验就<b>静默失效</b>了：给未上架的书发评论、给不存在的书加书架，
     * 全部会成功。<b>没有任何报错，只有数据慢慢变脏。</b>
     *
     * <p><b>★ 用它的前提：对端那个码在【本服务的上下文里】语义相同。</b>
     * 这在同源的 {@code ResultCode}（住在 novel-common，两边共用一份）
     * 下天然成立 —— 比如 2001 的定义就是「作品不存在或未上架」，
     * 不管它在哪个进程里被抛出来，意思都一样。
     * <p>★ 但对「对端自己发明的、我这边不存在的概念」就不能用（比如 auth 的
     * 1001「用户不存在」在 content 取昵称的上下文里没有意义）—— 那种情况用
     * {@link #unwrap}，这条区别就是 {@code ContentUserLookup} 那边选 unwrap 的原因。
     *
     * @param result 对端返回的信封，可以为 {@code null}（对端返回了空 body）
     * @param what   这次调用的用途，只用于日志
     * @return 信封里的 data
     * @throws BusinessException 对端返回了非成功码（<b>带的是对端那个码</b>），
     *                           或者根本没返回东西（这一种仍然是 500 ——
     *                           拿不到信封是通信故障，不是业务结论）
     */
    public static <T> T unwrapBusiness(Result<T> result, String what) {
        if (result == null) {
            // ★ 这一种【不能】沿用对端的码：连信封都没有，说明是通信故障。
            //   把「我没收到回复」说成「作品不存在」会让故障彻底隐形。
            log.error("跨服务调用没有拿到响应体 | {}", what);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试");
        }
        if (!result.isSuccess()) {
            /*
             * ★ 和 unwrap 的唯一区别：不翻译成 SYSTEM_ERROR，原样抛对端的码。
             *   ★ 这里记 warn 而不是 error —— 它是一次【业务拒绝】，
             *     和「参数校验失败」同级，不是系统故障。记 error 会让日志里
             *     真正的故障被淹掉（一天几万次「有人点了下架的书」）。
             */
            log.warn("跨服务校验未通过 | {} | code={} message={}",
                    what, result.getCode(), result.getMessage());
            throw new BusinessException(result.getCode(), result.getMessage());
        }
        return result.getData();
    }
}
