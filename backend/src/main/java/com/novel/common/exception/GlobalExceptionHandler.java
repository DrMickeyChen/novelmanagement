package com.novel.common.exception;

import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.common.result.ResultHttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
 * <p><b>★★ HTTP 状态码：为什么这里返回 {@code ResponseEntity} 而不是直接返回 {@link Result}</b>
 *
 * <p>返回类型不是 {@code Result<Void>} 而是 {@code ResponseEntity<Result<Void>>}，
 * 是为了能<b>同时</b>控制状态码和响应体。规则见 {@link ResultHttpStatus}：
 * <pre>
 *   code 在 400~599（401/403/404/405/500…）→ HTTP 状态码 = code
 *   其余业务码（1002/1004/2001…）         → HTTP 200，错误码只在 body 里
 * </pre>
 *
 * <p>曾经这里一律返回 HTTP 200，代价是 <b>APM / 监控上完全看不出鉴权失败和 5xx</b> ——
 * 所有响应长一个样。现在协议层的错误恢复成真实状态码，
 * Nginx、网关、SkyWalking、告警规则这些标准工具不用任何配置就能用。
 *
 * <p><b>★ 为什么不用 {@code @ResponseStatus} 注解：</b>
 * 它是<b>写死</b>的常量，没法跟着 {@code code} 走。而本类里
 * {@link #handleBusinessException} 拿到的 code 是运行时才知道的
 * （{@code BusinessException} 可以带任意 {@link ResultCode}，包括 401），
 * 所以只能用 {@code ResponseEntity} 动态构造。
 * 既然必须有一处用动态方式，就<b>全都用同一种机制</b> ——
 * 一半注解一半 ResponseEntity，以后改规则必然漏掉一半。
 *
 * @author MickeyChen
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ========== 业务异常：最常见的，一天几万次，warn 级别 ========== */

    /**
     * 业务异常。
     *
     * <p>★ 这一条的 HTTP 状态码是<b>动态</b>的：{@code BusinessException} 里带的
     * code 落在 400~599 就返真实状态码（比如 {@code UserContext.requireUserId()}
     * 抛的是 {@code UNAUTHORIZED(401)}，那就真的是 401），
     * 落在业务区段（1001+）就返 200。判断逻辑在 {@link ResultHttpStatus}。
     *
     * <p>日志用 warn：业务异常说明「这个请求被正常拒绝了」，
     * 不是「系统坏了」，不该进 error 级别。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 | {} {} | code={} msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return respond(e.getCode(), e.getMessage());
    }

    /* ========== 参数校验：@RequestBody 上的 @Valid 失败 ========== */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 | {}", message);
        return respond(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /** 表单/查询参数绑定失败（非 @RequestBody 场景） */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 | {}", message);
        return respond(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /** 少传了必填的 query 参数，例：/api/novel/page 没传 current */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数：" + e.getParameterName();
        log.warn("{}", message);
        return respond(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * <b>query 参数 / 路径参数的类型转换失败</b>，例：{@code ?page=abc}、
     * {@code ?size=1.5}、{@code /api/novels/xyz}。
     *
     * <p><b>★★ 这个 handler 是补上的一个洞，而且是个很典型的洞：</b>
     * 没有它的时候，{@code MethodArgumentTypeMismatchException} 不被任何 handler 接住，
     * 掉进最底下的 {@code Exception.class} 兜底 —— 于是
     * <b>调用方把 {@code page} 拼错成字母，返回的是 HTTP 500「服务器开小差了」，
     * 日志里还留一条带完整堆栈的 ERROR</b>。
     *
     * <p>后果和 {@link #handleNoResourceFound} 那条是同一类：
     * <ul>
     *   <li>对外：前端拿到 500 会当成「后端挂了」，不会去检查自己拼的参数</li>
     *   <li>对内：一条正常的分支被记成系统故障。
     *       扫描器每天都会拿 {@code ?id=1'"}、{@code ?page=../../} 试一圈，
     *       这些请求全部会变成 ERROR 堆栈 —— 真正的故障会被淹掉</li>
     * </ul>
     *
     * <p><b>它和 {@link #handleNotReadable} 是一对：</b>那条管「请求体里的字段类型不对」，
     * 这条管「URL 上的参数类型不对」。两条都在，参数错误才在协议层完整地表达为 400。
     *
     * <p>★ {@code e.getName()} 是参数名（{@code page}），{@code e.getValue()} 是原始值。
     * 把它们放进 message 里 —— 前端只需要把这句话直接弹出来就能定位问题，
     * 不用去猜是哪个参数。回显用户传的原始值在这里是安全的：
     * 它只是个字符串，会被 Jackson 转义后放进 JSON body，
     * <b>不会被拼进 SQL、日志格式串或 HTML</b>。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                           HttpServletRequest request) {
        String message = "参数「" + e.getName() + "」的值不合法：" + e.getValue();
        log.warn("参数类型转换失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), message);
        return respond(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /** 请求体不是合法 JSON（多打了个逗号、字段类型不对等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return respond(ResultCode.PARAM_ERROR.getCode(), "请求内容格式不正确");
    }

    /* ========== 鉴权失败（方法级） ========== */

    /**
     * <b>{@code @PreAuthorize} 拒绝时抛的异常 —— 少了这个 handler 会变成 500。</b>
     *
     * <p><b>★★ 鉴权失败有两条路，这里只堵了其中一条，另一条在别处：</b>
     * <pre>
     *   ① URL 级失败 —— 在 filter chain 里就被拦下，请求根本没进
     *      DispatcherServlet，所以本类接不到。
     *      由 {@code RestAuthenticationEntryPoint}（401）
     *      和 {@code RestAccessDeniedHandler}（403）负责。
     *
     *   ② 方法级失败 —— {@code @PreAuthorize} 是在 Controller / Service
     *      的方法上生效的，那时请求【已经进了】DispatcherServlet，
     *      异常会走 {@code @RestControllerAdvice}，也就是这里。
     * </pre>
     * <b>两条都要覆盖，少一条就是一个洞。</b>
     * ★ 而且两条路返回的 HTTP 状态码必须<b>一致</b>（都是 403）——
     * 否则同一个「权限不足」，走 URL 拦截和走 {@code @PreAuthorize}
     * 会给出两种 HTTP 状态，监控上要写两条规则才能统计全。
     * 一致性由两边都调用 {@link ResultHttpStatus} 保证，不靠人肉对齐。
     *
     * <p><b>不写这个 handler 会怎样：</b>
     * {@code AccessDeniedException} 没被任何 handler 接住，于是掉进最底下的
     * {@code Exception.class} 兜底，返回
     * {@code {"code":500,"message":"服务器开小差了"}} ——
     * <ul>
     *   <li>前端会把它当成「后端挂了」而不是「你没权限」，
     *       不会给出正确引导（比如提示去申请作者权限）</li>
     *   <li>日志里是 <b>error 级别带完整堆栈</b>，把一个正常的业务分支
     *       记成了系统故障。权限被拒在正常使用中是很常见的
     *       （作者点了管理端入口），日志很快就会被这种假报警淹掉</li>
     * </ul>
     *
     * <p>日志用 warn 不用 error：它说明的是「这个用户不该干这事」，
     * 不是「系统坏了」。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        log.warn("鉴权失败（方法级） | {} {} | {}", request.getMethod(), request.getRequestURI(),
                e.getMessage());
        return respond(ResultCode.FORBIDDEN);
    }

    /* ========== 路由 / 方法 ========== */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                 HttpServletRequest request) {
        log.warn("请求方法不支持 | {} {} 支持的方法={}",
                request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
        return respond(ResultCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在 | {} {}", e.getHttpMethod(), e.getRequestURL());
        return respond(ResultCode.NOT_FOUND);
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
     * <p>★ 改完状态码之后它多了一层意义：404 现在是<b>真的 404</b>，
     * 监控上能一眼看出「有人在扫接口」，而不需要去 grep 日志。
     *
     * <p><b>为什么不是 NoHandlerFoundException：</b>
     * 那个是 {@code spring.mvc.throw-exception-if-no-handler-found=true} 时代的，
     * 而打开它必须同时关掉 {@code spring.web.resources.add-mappings}，
     * 那会把 Knife4j 的 {@code /doc.html} 一起关掉。
     * Spring Boot 3.2 之后用这个异常就足够了，上面那个 handler 留着兼容旧配置。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在 | {}", e.getResourcePath());
        return respond(ResultCode.NOT_FOUND);
    }

    /* ========== 兜底：意料之外的异常 ========== */

    /**
     * 所有没被上面接住的异常都落到这里。
     *
     * <p>日志级别是 <b>error 且带完整堆栈</b> —— 这类异常说明代码有 bug
     * 或者环境有问题，必须能在日志里一眼定位。对前端只说「服务器开小差了」。
     *
     * <p>★ 返回的 HTTP 状态是 <b>500</b>（{@code SYSTEM_ERROR} 的码就是 500）。
     * 这是这次改动里收益最大的一条：以前后端挂了也返 200，
     * 监控上和服务正常时长得一模一样，只能靠人工看日志发现。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 | {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ResultCode.SYSTEM_ERROR);
    }

    /* ========== 私有工具 ========== */

    /**
     * 统一的出口：把「业务码 + 提示语」组装成带正确 HTTP 状态码的响应。
     *
     * <p>所有 handler 都必须经过它 —— 这样「状态码怎么定」这个问题
     * 全项目只有一个答案（{@link ResultHttpStatus}），
     * 不会有某个 handler 漏改。
     */
    private ResponseEntity<Result<Void>> respond(ResultCode resultCode) {
        return respond(resultCode.getCode(), resultCode.getMessage());
    }

    private ResponseEntity<Result<Void>> respond(Integer code, String message) {
        return ResponseEntity.status(ResultHttpStatus.of(code))
                .body(Result.error(code, message));
    }

    /** 把「字段名 + 校验注解上的 message」拼成一句人话，例：用户名长度需在 2~20 之间 */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getDefaultMessage();
    }
}
