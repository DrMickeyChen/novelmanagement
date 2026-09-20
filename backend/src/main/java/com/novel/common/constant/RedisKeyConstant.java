package com.novel.common.constant;

/**
 * Redis key 前缀。
 *
 * <p><b>为什么要统一在这里定义、并且都用冒号分隔：</b>
 * 用 {@code keys *} 看 Redis 的时候，冒号会自动在 RedisInsight 这类工具里
 * 显示成树形目录 —— {@code novel:auth:*} 一展开就知道有哪些登录态。
 * 如果各写各的（有 {@code token_} 有 {@code TOKEN-}），线上排查会非常痛苦。
 *
 * <p>约定：{@code 项目名:业务模块:用途:标识}
 *
 * @author MickeyChen
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    /** 全局前缀 */
    private static final String PREFIX = "novel:";

    /* ==================== 认证模块 ==================== */

    /**
     * 登录态：{@code novel:auth:token:{userId}} → 存 JWT 或用户信息
     * <p>★ 为什么不只靠 JWT 自己带的过期时间，还要在 Redis 存一份：
     * JWT 一旦签发就无法作废，用户「退出登录」或管理员「封禁账号」时，
     * 只有 Redis 里删掉这条记录才能真正让 token 立刻失效。
     */
    public static final String AUTH_TOKEN = PREFIX + "auth:token:";

    /** 刷新令牌：{@code novel:auth:refresh:{userId}} */
    public static final String AUTH_REFRESH = PREFIX + "auth:refresh:";

    /** 图形验证码：{@code novel:auth:captcha:{uuid}}，有效期 5 分钟 */
    public static final String AUTH_CAPTCHA = PREFIX + "auth:captcha:";

    /** 登录失败次数：{@code novel:auth:login_fail:{username}}，超过 5 次锁 15 分钟 */
    public static final String AUTH_LOGIN_FAIL = PREFIX + "auth:login_fail:";

    /* ==================== 作品模块 ==================== */

    /** 作品详情缓存：{@code novel:novel:detail:{novelId}} */
    public static final String NOVEL_DETAIL = PREFIX + "novel:detail:";

    /** 作品浏览量：{@code novel:novel:views:{novelId}} → 用 HINCRBY 累加，定时落库 */
    public static final String NOVEL_VIEWS = PREFIX + "novel:views:";

    /** 热门榜（有序集合）：{@code novel:novel:hot_rank} */
    public static final String NOVEL_HOT_RANK = PREFIX + "novel:hot_rank";

    /** 分类列表缓存：{@code novel:novel:category} */
    public static final String NOVEL_CATEGORY = PREFIX + "novel:category";

    /* ==================== 章节模块 ==================== */

    /** 章节正文缓存：{@code novel:chapter:content:{chapterId}} */
    public static final String CHAPTER_CONTENT = PREFIX + "chapter:content:";

    /** 章节阅读进度：{@code novel:chapter:progress:{userId}:{novelId}} */
    public static final String CHAPTER_PROGRESS = PREFIX + "chapter:progress:";

    /* ==================== 互动模块 ==================== */

    /** 评论防刷：{@code novel:comment:limit:{userId}} */
    public static final String COMMENT_LIMIT = PREFIX + "comment:limit:";

    /** 用户点赞的作品集合（Set）：{@code novel:like:user:{userId}} */
    public static final String LIKE_USER = PREFIX + "like:user:";

    /** 作品的点赞总数：{@code novel:like:count:{novelId}} */
    public static final String LIKE_COUNT = PREFIX + "like:count:";

    /* ==================== AI 模块 ==================== */

    /** AI 每日额度：{@code novel:ai:quota:{userId}:{yyyyMMdd}} */
    public static final String AI_QUOTA = PREFIX + "ai:quota:";

    /** AI 生成结果缓存：{@code novel:ai:cache:{参数hash}}，避免相同请求重复烧钱 */
    public static final String AI_CACHE = PREFIX + "ai:cache:";

    /* ==================== 分布式锁 ==================== */

    /** 第二期微服务用：防止多实例重复执行定时任务 */
    public static final String LOCK_PREFIX = PREFIX + "lock:";
}
