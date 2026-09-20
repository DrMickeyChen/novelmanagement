package com.novel.common.exception;

import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。所有异常在这里收口，转成统一的 {@link Result}。
 *
 * <p><b>它解决的问题：</b>没有它的话，Controller 里要么到处 try-catch，
 * 要么异常直接冒到 Tomcat，前端收到一坨 HTML 错误页 —— axios 解析失败，
 * 只能提示「网络错误」，用户完全不知道发生了什么。
 *
 * <p><b>兜底原则：</b>
 * <ul>
 *   <li>能明确告诉用户的（业务异常、参数错、404）→ 原样返回，warn 日志</li>
 *   <li>意料之外的（NPE、数据库挂了）→ 返回「服务器开小差了」，<b>error 日志带完整堆栈</b></li>
 * </ul>
 * 第二条很重要：对外不要暴露 {@code NullPointerException at NovelServiceImpl:87}
 * 这种信息，那既是安全问题（泄露内部结构），对用户也毫无意义。
 *
 * @author MickeyChen
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ========== 业务异常：最常见的，一天几万次，warn 级别 ========== */

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 | {} {} | code={} msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /* ========== 参数校验：@RequestBody 上的 @Valid 失败 ========== */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 | {}", message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /** 表单/查询参数绑定失败（非 @RequestBody 场景） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 | {}", message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /** 少传了必填的 query 参数，例：/api/novel/page 没传 current */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数：" + e.getParameterName();
        log.warn("{}", message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /** 请求体不是合法 JSON（多打了个逗号、字段类型不对等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "请求内容格式不正确");
    }

    /* ========== 路由 / 方法 ========== */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持 | {} {} 支持的方法={}",
                request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
        return Result.error(ResultCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在 | {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(ResultCode.NOT_FOUND);
    }

    /**
     * ★ Spring Boot 3.2 新增的异常，专门处理「访问了一个不存在的路径」。
     *
     * <p><b>不加这个 handler 会怎样：</b>访问不存在的接口会返回
     * {@code {"code":500,"message":"服务器开小差了"}} 而不是 404 ——
     * 因为 {@code NoResourceFoundException} 没被任何 handler 接住，
     * 掉进了最底下的 {@code Exception} 兜底。
     *
     * <p>后果不只是提示不对：<b>每个 404 都会打一条带完整堆栈的 ERROR 日志</b>。
     * 线上被扫描器扫一遍（这是每天都会发生的事），日志里就全是
     * 「系统异常」的假警报，真正的故障会被淹掉。所以这个 handler 既是
     * 接口语义问题，也是日志可用性问题。
     *
     * <p><b>为什么不是 NoHandlerFoundException：</b>
     * 那个是 {@code spring.mvc.throw-exception-if-no-handler-found=true} 时代的，
     * 而打开它必须同时关掉 {@code spring.web.resources.add-mappings}，
     * 那会把 Knife4j 的 {@code /doc.html} 一起关掉。
     * Spring Boot 3.2 之后用这个异常就足够了，上面那个 handler 留着兼容旧配置。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在 | {}", e.getResourcePath());
        return Result.error(ResultCode.NOT_FOUND);
    }

    /* ========== 兜底：意料之外的异常 ========== */

    /**
     * 所有没被上面接住的异常都落到这里。
     *
     * <p>日志级别是 <b>error 且带完整堆栈</b> —— 这类异常说明代码有 bug
     * 或者环境有问题，必须能在日志里一眼定位。对前端只说「服务器开小差了」。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 | {} {}", request.getMethod(), request.getRequestURI(), e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }

    /* ========== 私有工具 ========== */

    /** 把「字段名 + 校验注解上的 message」拼成一句人话，例：用户名长度需在 2~20 之间 */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getDefaultMessage();
    }
}
