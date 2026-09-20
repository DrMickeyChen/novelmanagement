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
 *   6001 ~     分类模块
 *   9001 ~     系统/中间件（第二期用）
 * </pre>
 *
 * <p><b>为什么要分段而不是随便给号：</b>前端可以只判断区段就决定怎么处理 ——
 * 比如 {@code 401} 直接踢回登录页、{@code 1xxx} 提示「账号相关」、
 * {@code 2xxx} 提示「作品相关」，不用为每个错误码写一个 if。
 *
 * <p><b>★★ 这个分段还有一个用途：决定 HTTP 状态码。</b>
 * 前两段（400~599）和 HTTP 语义<b>一一对应</b>，所以它们会被原样
 * 写进 HTTP 状态码；{@code 1001+} 是纯业务语义，HTTP 里没有对应物，
 * 一律返 200，错误码只放 body。
 * <pre>
 *   ResultCode.PARAM_ERROR(400)      → HTTP 400
 *   ResultCode.UNAUTHORIZED(401)     → HTTP 401
 *   ResultCode.USER_PASSWORD_ERROR(1002) → HTTP 200（正常业务结果，不是故障）
 * </pre>
 * ★ <b>所以新增错误码之前想清楚它该放哪个区段</b> ——
 * 「未登录/无权限/资源不存在」这类必须用 401/403/404，
 * 一旦放成 1001+ 就失去了监控可见性，而且和已有的 401 语义打架。
 * 翻译规则在 {@link ResultHttpStatus}。
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

    /*
     * 大纲树（P4）。★ 和章节共用 3xxx 段而不是另起一段：
     * 大纲树是「章节的上游」—— 它是章节的写作计划，不是独立业务对象。
     * 而且它和章节一样只在创作中心一个地方出现，前端拿到 3xxx
     * 就知道该在当前页面提示，不用再学一段新号码。
     */
    OUTLINE_NODE_NOT_FOUND(3006, "大纲节点不存在"),
    OUTLINE_NODE_NOT_YOURS(3007, "该大纲节点不属于这本作品"),
    OUTLINE_ROOT_EXISTS(3008, "这本作品已经有根节点了"),
    /*
     * ★ 3009 和 3010 的区别是【责任方】：
     *   3009 = 用户做了非法操作（把父节点拖进自己的子孙里）→ 提示他改操作
     *   3010 = 库里的数据本来就是坏的（已经存在环）→ 提示他刷新，且必须 log.error
     * 合成一个码的话，数据坏掉时会提示「不能移动到自己的子节点下」，
     * 而用户根本没做这件事 —— 他会反复重试，而日志上一条异常都没有。
     */
    OUTLINE_PARENT_ILLEGAL(3009, "不能把节点移动到它自己的子节点下"),
    OUTLINE_TREE_BROKEN(3010, "大纲结构异常，请刷新后重试"),
    OUTLINE_TOO_DEEP(3011, "大纲层级过深（最多 20 层）"),

    /* ---------- 评论模块 4001 ~ ---------- */
    COMMENT_NOT_FOUND(4001, "评论不存在"),
    COMMENT_NOT_YOURS(4002, "只能删除自己的评论"),
    COMMENT_ALREADY_DELETED(4003, "评论已被删除"),
    COMMENT_TOO_FAST(4004, "评论太频繁了，休息一下吧"),

    /* ---------- AI 模块 5001 ~ ---------- */
    AI_DISABLED(5001, "AI 功能未启用"),
    AI_API_KEY_MISSING(5002, "尚未配置 AI 的 API Key"),
    AI_CALL_FAILED(5003, "AI 服务调用失败，请稍后重试"),
    AI_QUOTA_EXCEEDED(5004, "今日 AI 额度已用完"),

    /* ---------- 分类模块 6001 ~ ---------- */
    /*
     * 分类单独占一段（而不是塞进上面的「小说模块 2001~」）：
     * 分段解决的是「前端拿到错误码后该在哪个页面提示」——
     * 分类的错误全部发生在分类管理页，和小说/章节的错误不在一个页面上。
     *
     * ★ 注意 6001 不在 400~599 里，所以它返的是 HTTP 200（见 ResultHttpStatus）——
     * 「分类不存在」是正常的业务结果，不是协议层故障，不该在监控上报警。
     */
    CATEGORY_NOT_FOUND(6001, "分类不存在"),
    CATEGORY_NAME_EXISTS(6002, "分类名称已存在"),
    CATEGORY_HAS_NOVELS(6003, "该分类下还有作品，无法删除");

    /** 错误码 */
    private final Integer code;

    /** 给用户看的提示语（不要写技术细节，直接能弹到前端） */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
