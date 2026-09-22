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
 * <p><b>★ 失败时抛什么：{@code SYSTEM_ERROR}（HTTP 500），而不是新建一个 9xxx 码。</b>
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
}
