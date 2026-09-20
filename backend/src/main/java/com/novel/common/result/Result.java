package com.novel.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。所有接口都返回这个结构，前端只写一套拦截器。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": { ... }
 * }
 * </pre>
 *
 * <p><b>★★ body 里的 {@code code} 和 HTTP 状态码是什么关系：</b>
 * <pre>
 *   code 在 400 ~ 599（401/403/404/405/500…）→ HTTP 状态码 = code
 *   其余业务码（1002 用户名或密码错误、2001 小说不存在…）→ HTTP 200
 * </pre>
 * 判断逻辑只有一处，在 {@link ResultHttpStatus}；两边都调它，
 * 所以「同一个错误走不同路径返回不同状态码」这种事不会发生。
 *
 * <p><b>为什么业务错误仍然是 HTTP 200：</b>
 * 「用户名已存在」是<b>一个合法请求的正常结果</b>，不是系统故障。
 * 给它 4xx 会让 APM、告警、网关熔断统计把它当异常流量 ——
 * 用户输错一次密码就报一次警。而且前端 {@code request.js} 靠
 * {@code code === 401} 判断「该踢回登录页了」，业务码走的是
 * 「弹提示、留在原地」，这两种处理必须分得开。
 *
 * <p>真正的系统异常走 HTTP 500，由
 * {@link com.novel.common.exception.GlobalExceptionHandler} 兜底。
 *
 * @param <T> 业务数据类型
 * @author MickeyChen
 */
@Data
@NoArgsConstructor
@Schema(description = "统一响应体")
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "业务状态码，200 表示成功", example = "200")
    private Integer code;

    @Schema(description = "提示信息，可直接弹给用户看", example = "操作成功")
    private String message;

    @Schema(description = "业务数据，失败时为 null")
    private T data;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /* ==================== 成功 ==================== */

    /** 成功，不带数据（用于「删除成功」这类只需知道成败的接口） */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功，带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 成功，自定义提示语 */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /* ==================== 失败 ==================== */

    /** 失败，用预定义错误码 */
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /** 失败，用预定义错误码但换一句提示（比如把「参数校验失败」换成具体哪个字段不对） */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /** 失败，完全自定义 */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败，<b>并且带数据</b>。
     *
     * <p>失败还给数据听起来矛盾，但对<b>排查类接口</b>是必需的 ——
     * {@code /api/ping/db} 失败时，{@code message} 只能说「MySQL 连接失败」，
     * 真正有用的东西（根因、该检查哪个配置）在 {@code data} 里。
     * 没有这个重载的话，那两个接口就只能返回一句笼统的提示，等于白查。
     *
     * <p>业务接口一般用不到它 —— 失败就是失败，没什么可说的。
     */
    public static <T> Result<T> error(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }

    /* ==================== 便捷判断 ==================== */

    /**
     * 是否成功。给「拿到 Result 后要继续做判断」的场景用，
     * 比如第二期跨服务调用：{@code if (!result.isSuccess()) { 走兜底逻辑 }}
     *
     * <p><b>★ 这里的 {@code @JsonIgnore} 不能省。</b>
     * Jackson 会把所有 {@code isXxx()} 形式的方法当成名为 {@code xxx} 的
     * 布尔属性序列化出去，所以少了这个注解，接口返回会变成：
     * <pre>
     * { "code":200, "message":"操作成功", "data":{...}, "success":true }
     *                                        ↑ 多出来的这个
     * </pre>
     * 响应体就不再是 §6.1 约定的三字段结构了。
     * 注意它只影响序列化，Java 代码里照样能调 {@code isSuccess()}。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSuccess() {
        return ResultCode.SUCCESS.getCode().equals(this.code);
    }
}
