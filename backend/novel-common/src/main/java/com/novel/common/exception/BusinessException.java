package com.novel.common.exception;

import com.novel.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p><b>什么时候抛它：</b>「业务规则不允许」的情况 ——
 * 用户名已被注册、作品状态不允许上架、不是你的评论不能删……
 * 这些都不是 bug，是正常的业务分支，抛出来让
 * {@link GlobalExceptionHandler} 统一转成给用户看的提示。
 *
 * <pre>
 * if (userMapper.existsByUsername(username)) {
 *     throw new BusinessException(ResultCode.USERNAME_EXISTS);
 * }
 * </pre>
 *
 * <p><b>什么时候不要抛它：</b>参数格式错误交给 {@code @Valid} 处理，
 * 数据库连不上这类系统故障交给全局兜底（它会返回 500 并记 error 日志）。
 *
 * <p><b>为什么继承 {@code RuntimeException} 而不是 {@code Exception}：</b>
 * 受检异常会污染每一层方法签名，Service 里遍地 {@code throws BusinessException}。
 * 而且 Spring 的 {@code @Transactional} 默认只对 RuntimeException 回滚 ——
 * 如果抛受检异常，事务不会回滚，这是个很隐蔽的坑。
 *
 * <h3>★★★ E1 起多了一个可选字段：{@link #httpStatus} —— 它【只影响 HTTP 层，不动 code】</h3>
 *
 * <p>默认（{@code null}）时状态码由 {@code ResultHttpStatus} 按 {@code code} 推：
 * 400~599 段返真实状态码，业务码（1001+）返 200。这条规矩一个字没变。
 *
 * <p>★ 那为什么还需要显式指定？因为有一类失败<b>「业务码落在业务区段、但 HTTP 层必须说实话」</b>——
 * E1 的 AI 前置闸门就是它：额度用完（5004）如果按老规矩返 {@code 200}，
 * 前端 {@code sse.js} 会走「2xx 但 Content-Type 不是事件流」那条出口，
 * 把「额度用完」渲染成<b>「响应不是事件流，请检查代理配置」</b>——
 * <b>不是一句含糊的话，是把人指向一个完全无关的系统</b>。
 * 详见 {@code AiPreflight} 的类注释。
 *
 * <p>★★ 判据：<b>「能不能在创建 SSE emitter 之前判定」决定失败走哪条出口</b>，
 * 而不是「这个错误重不重要」。理由见那个类。
 *
 * <p>★ 刻意做成<b>可选</b>而不是给每个 {@code ResultCode} 加一列状态码：
 * 前者只影响主动声明的那几处，后者会一次性改变全项目的响应行为 ——
 * 而绝大多数业务码返 200 是<b>对的</b>（见 {@code ResultHttpStatus} 的类注释）。
 *
 * @author MickeyChen
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码，直接透传给前端 */
    private final Integer code;

    /**
     * 显式指定的 HTTP 状态码。<b>{@code null} = 交给 {@code ResultHttpStatus} 按 {@code code} 推。</b>
     *
     * <p>★ 它<b>不参与</b> body 里那个 {@code code} 的构造 —— 两者是两个问题：
     * <b>HTTP 状态决定用户看到什么，body 的 code 决定程序怎么判断。</b>
     */
    private final Integer httpStatus;

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 复用错误码但换一句更具体的提示。
     * 例：{@code new BusinessException(ResultCode.PARAM_ERROR, "章节序号必须大于 0")}
     */
    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message, null);
    }

    /** 完全自定义（少用，尽量走 ResultCode，方便前端统一处理） */
    public BusinessException(Integer code, String message) {
        this(code, message, null);
    }

    /**
     * 带显式 HTTP 状态码的那一个。
     *
     * <p>★★ <b>别直接调这个构造器</b>（三个参数里有两个是数字型的语义、容易写反），
     * 走 {@link #withStatus(int)} —— 它从已有异常复制 code 与 message，
     * 只换状态码，正是闸门要的那件事。
     */
    public BusinessException(Integer code, String message, Integer httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 复制一个「同样的 code、同样的 message，但指定 HTTP 状态码」的异常。
     *
     * <p>★ 闸门里 catch 一个 {@code NovelAccessGuard} 抛出来的异常再重新抛出时用它：
     * 那个 guard <b>刻意</b>给「动了别人的东西」返 {@code 200 + 2002}
     * （它的类注释论证过为什么），而那条设计在 SSE 端点上会退化成一句
     * 「请检查代理配置」—— 所以这里只换状态码，<b>一个字都不改它的 code 与文案</b>。
     */
    public BusinessException withStatus(int httpStatus) {
        return new BusinessException(code, getMessage(), httpStatus);
    }

    /**
     * 业务异常不需要堆栈。
     *
     * <p>它一天可能抛几万次（用户输错密码也算），每次 fillInStackTrace
     * 都要遍历整个调用栈，是个纯粹的浪费；真正需要堆栈的是 NullPointerException
     * 那类「意料之外」的异常。关掉之后日志更干净，一眼能看真正的 error。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
