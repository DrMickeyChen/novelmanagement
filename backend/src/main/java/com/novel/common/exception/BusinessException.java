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
 * @author MickeyChen
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码，直接透传给前端 */
    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 复用错误码但换一句更具体的提示。
     * 例：{@code new BusinessException(ResultCode.PARAM_ERROR, "章节序号必须大于 0")}
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /** 完全自定义（少用，尽量走 ResultCode，方便前端统一处理） */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
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
