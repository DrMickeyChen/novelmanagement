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
 * <p><b>为什么 HTTP 状态码恒为 200、错误信息放 body 里：</b>
 * 业务错误（比如「用户名已存在」）不是 HTTP 层面的错误，
 * 用 4xx/5xx 表达会让 axios 拦截器、网关、监控面板都把它当异常流量。
 * 真正的系统异常（比如数据库连不上）才走 HTTP 500，由
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
