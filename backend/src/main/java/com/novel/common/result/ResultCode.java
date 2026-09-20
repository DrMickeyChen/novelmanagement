package com.novel.common.result;

import lombok.Getter;

/**
 * 统一错误码。
 *
 * <p>分段规则见 {@code 项目大纲.md §6.2}：
 * <pre>
 *   200        成功
 *   400 ~ 500  通用 HTTP 语义错误
 *   1001 ~     用户模块
 *   2001 ~     小说模块
 *   3001 ~     章节模块
 *   4001 ~     评论模块
 *   5001 ~     AI 模块
 *   9001 ~     系统/中间件（第二期用）
 * </pre>
 *
 * <p><b>为什么要分段而不是随便给号：</b>前端可以只判断区段就决定怎么处理 ——
 * 比如 {@code 401} 直接踢回登录页、{@code 1xxx} 提示「账号相关」、
 * {@code 2xxx} 提示「作品相关」，不用为每个错误码写一个 if。
 *
 * @author MickeyChen
 */
@Getter
public enum ResultCode {

    /* ---------- 通用 ---------- */
    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    SYSTEM_ERROR(500, "服务器开小差了，请稍后再试"),

    /* ---------- 用户模块 1001 ~ ---------- */
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "用户名或密码错误"),
    USER_DISABLED(1003, "账号已被禁用"),
    USERNAME_EXISTS(1004, "用户名已被注册"),
    EMAIL_EXISTS(1005, "邮箱已被注册"),
    TOKEN_INVALID(1006, "登录凭证无效"),
    TOKEN_EXPIRED(1007, "登录已过期，请重新登录"),
    OLD_PASSWORD_ERROR(1008, "原密码不正确"),
    AUTHOR_APPLY_PENDING(1009, "作者申请审核中，请耐心等待"),
    NOT_AUTHOR(1010, "你还不是作者，请先申请成为作者"),

    /* ---------- 小说模块 2001 ~ ---------- */
    NOVEL_NOT_FOUND(2001, "作品不存在"),
    NOVEL_NOT_YOURS(2002, "这不是你的作品"),
    NOVEL_STATUS_ILLEGAL(2003, "当前作品状态不允许该操作"),
    NOVEL_NOT_PUBLISHED(2004, "作品尚未上架"),
    NOVEL_HAS_CHAPTERS(2005, "作品下已有章节，无法删除"),

    /* ---------- 章节模块 3001 ~ ---------- */
    CHAPTER_NOT_FOUND(3001, "章节不存在"),
    CHAPTER_NOT_YOURS(3002, "这不是你作品的章节"),
    CHAPTER_STATUS_ILLEGAL(3003, "当前章节状态不允许该操作"),
    CHAPTER_NUMBER_EXISTS(3004, "该章节序号已存在"),
    CHAPTER_CONTENT_EMPTY(3005, "章节正文不能为空"),

    /* ---------- 评论模块 4001 ~ ---------- */
    COMMENT_NOT_FOUND(4001, "评论不存在"),
    COMMENT_NOT_YOURS(4002, "只能删除自己的评论"),
    COMMENT_ALREADY_DELETED(4003, "评论已被删除"),
    COMMENT_TOO_FAST(4004, "评论太频繁了，休息一下吧"),

    /* ---------- AI 模块 5001 ~ ---------- */
    AI_DISABLED(5001, "AI 功能未启用"),
    AI_API_KEY_MISSING(5002, "尚未配置 AI 的 API Key"),
    AI_CALL_FAILED(5003, "AI 服务调用失败，请稍后重试"),
    AI_QUOTA_EXCEEDED(5004, "今日 AI 额度已用完");

    /** 错误码 */
    private final Integer code;

    /** 给用户看的提示语（不要写技术细节，直接能弹到前端） */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
