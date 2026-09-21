package com.novel.common.constant;

import java.util.Set;

/**
 * 全项目通用常量。
 *
 * <p><b>为什么要单独弄个常量类：</b>像「请求头里 token 的字段名叫什么」
 * 这种事，前端、后端拦截器、网关（第二期）三处都要用同一个值。
 * 只要有一处手打成 {@code "Authorizaton"}，就是一个查一下午的 bug。
 *
 * @author MickeyChen
 */
public final class CommonConstant {

    /** 工具类，禁止实例化 */
    private CommonConstant() {
    }

    /* ==================== 认证相关 ==================== */

    /** 携带 token 的请求头名 */
    public static final String TOKEN_HEADER = "Authorization";

    /** token 前缀，注意后面有个空格：{@code Authorization: Bearer eyJhbGci...} */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * 第二期微服务用：网关解析完 token 后，把用户信息塞进这两个头往下游传，
     * 下游服务不用再解析一遍 JWT。
     */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /* ==================== 角色 ==================== */

    public static final String ROLE_READER = "READER";
    public static final String ROLE_AUTHOR = "AUTHOR";
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 内置角色码 —— 由 {@code docs/sql/seed.sql} 灌进去的那三个。
     *
     * <p><b>★ 这组角色不允许删除、停用、改标识</b>（S13 的 D-6 防护，
     * 见 {@code ResultCode.ROLE_BUILTIN} 上的长注释）。
     *
     * <p>判断「是不是内置」用的是 <b>角色码</b>而不是 {@code id}：
     * 自增 id 是数据库的实现细节，换一套环境、重新灌一次种子数据就可能变；
     * 而 {@code ADMIN} 这三个字是代码里到处在用的业务常量
     * （{@code SecurityConfig} 的 {@code hasRole("ADMIN")}、
     * {@code SysUserMapper} 注册时授予 READER），它才是稳定标识。
     *
     * <p>放在这个类里而不是 {@code AdminRoleServiceImpl} 的私有常量：
     * 「哪几个角色是内置的」是项目级的公共事实，登录、注册、鉴权、
     * 管理端四处都依赖它。分散在各处的话，将来加一个内置角色
     * 就会漏改其中一两处 —— 而漏掉的后果是那个角色可以被删掉。
     */
    public static final Set<String> BUILTIN_ROLE_CODES =
            Set.of(ROLE_ADMIN, ROLE_AUTHOR, ROLE_READER);

    /* ==================== 通用状态 ==================== */

    /** 启用 / 正常 */
    public static final Integer STATUS_ENABLED = 1;

    /** 禁用 */
    public static final Integer STATUS_DISABLED = 0;

    /** 逻辑删除：未删除 */
    public static final Integer NOT_DELETED = 0;

    /** 逻辑删除：已删除 */
    public static final Integer DELETED = 1;

    /* ==================== 分页 ==================== */

    /** 默认页码 */
    public static final long DEFAULT_PAGE_CURRENT = 1L;

    /** 默认每页条数 */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    /**
     * 每页最大条数。
     * <p>★ 一定要有上限：否则有人传 {@code size=999999} 就能把整张表拖出来，
     * 数据库和内存一起爆掉。这是个很容易被忽略的安全问题。
     */
    public static final long MAX_PAGE_SIZE = 100L;

    /* ==================== 业务限制 ==================== */

    /** 小说标题最大长度 */
    public static final int NOVEL_TITLE_MAX_LENGTH = 100;

    /** 章节正文最大长度（按字符算，约 5 万字） */
    public static final int CHAPTER_CONTENT_MAX_LENGTH = 50000;

    /** 评论最大长度 */
    public static final int COMMENT_MAX_LENGTH = 500;

    /** 同一用户对同一作品的评论间隔（秒），防刷 */
    public static final int COMMENT_INTERVAL_SECONDS = 10;
}
