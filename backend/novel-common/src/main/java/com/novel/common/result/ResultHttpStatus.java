package com.novel.common.result;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 把响应体里的 {@code code} 翻译成 HTTP 状态码。
 *
 * <p><b>规则只有一条：</b>
 * <pre>
 *   code 在 400 ~ 599   →  HTTP 状态码 = code
 *   其余（1001+ 业务码）→  HTTP 200
 * </pre>
 *
 * <p><b>这条规则不是这里发明的</b>，而是 {@link ResultCode} 自己的分段方式
 * 本来就长这样 —— 看它的类注释：
 * <pre>
 *   400 ~ 500  通用 HTTP 语义错误     ← 这几类和 HTTP 语义【一一对应】
 *   1001 ~     用户模块              ← 这些是【业务语义】，HTTP 里没有对应物
 *   2001 ~     小说模块
 *   ...
 * </pre>
 * 所以「400~599 用真实状态码、1001+ 用 200」不是在打补丁，
 * 只是把枚举里已经写好的分类落实到 HTTP 层。
 *
 * <h3>为什么不干脆「所有错误都返真实状态码」</h3>
 * 因为业务错误（{@code 1002 用户名或密码错误}）是<b>一个合法请求的正常结果</b>，
 * 不是系统故障。如果它也返 4xx，那么：
 * <ul>
 *   <li>APM / 告警会把它当异常统计 —— 用户输错一次密码就报一次警，
 *       真正的故障会被这种噪音淹掉</li>
 *   <li>前端 {@code request.js} 的分支也会乱：它靠 {@code code === 401}
 *       判断「该踢回登录页了」，而 1002 走的是「弹提示、留在原地」——
 *       这两种处理方式【必须】区分开，混在一起就会出现
 *       「用户输错密码，结果被踢去登录页」</li>
 * </ul>
 * 一句话：<b>HTTP 状态码回答「这次请求的传输/协议层怎么样了」，
 * body 里的 code 回答「业务上成不成功」。两者不该互相冒充。</b>
 *
 * <h3>对监控的意义（这个类存在的直接原因）</h3>
 * 改之前所有响应都是 HTTP 200，于是 APM 上<b>看不出鉴权失败、也看不出 5xx</b> ——
 * 想统计「今天有多少人被权限拦住」「有没有接口在报错」只能去翻应用日志。
 * 改完之后这些在监控上就是普通的状态码，任何标准工具都能直接看。
 *
 * @author MickeyChen
 */
public final class ResultHttpStatus {

    /** HTTP 语义错误的区间下界（含） */
    private static final int HTTP_SEMANTIC_MIN = 400;

    /** HTTP 语义错误的区间上界（含）。5xx 用不到 599，但留出余量 */
    private static final int HTTP_SEMANTIC_MAX = 599;

    private ResultHttpStatus() {
    }

    /**
     * @param code 响应体里的业务码，可为 null（按 200 处理）
     * @return 该用哪个 HTTP 状态码
     */
    public static HttpStatusCode of(Integer code) {
        if (code == null || code < HTTP_SEMANTIC_MIN || code > HTTP_SEMANTIC_MAX) {
            // 业务码（1001+）或没给码：HTTP 层是成功的，语义在 body 里
            return HttpStatus.OK;
        }
        /*
         * ★ 用 valueOf 而不是 HttpStatus.resolve()。
         *
         * resolve() 只认标准码，遇到 499 这种非标准码会返回 null，
         * 再拿 null 去 setStatus 就是 NPE —— 而这个类的容错态度应该是
         * 「不认识也照样发出去」，不该因为一个没见过的码把请求打成 500。
         * valueOf(499) 会造一个自定义状态码，正好是这个语义。
         */
        return HttpStatusCode.valueOf(code);
    }
}
