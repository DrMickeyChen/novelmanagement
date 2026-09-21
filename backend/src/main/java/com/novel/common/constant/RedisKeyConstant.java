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
     * 登录态：{@code novel:auth:token:{userId}:{jti}} → 存 {@code LoginUser} 对象
     *
     * <p>★ 为什么不只靠 JWT 自己带的过期时间，还要在 Redis 存一份：
     * JWT 一旦签发就无法作废，用户「退出登录」或管理员「封禁账号」时，
     * 只有 Redis 里删掉这条记录才能真正让 token 立刻失效。
     *
     * <p>★ 为什么 key 里要有 {@code jti}（JWT 的唯一编号）：
     * 这是<b>多端并存</b>的关键。同一个账号在手机和电脑上登录会拿到
     * 两个不同的 jti，于是 Redis 里是两把互不干扰的 key：
     * <pre>
     *   novel:auth:token:1:a3f9c2...   ← 手机
     *   novel:auth:token:1:8c21b7...   ← 电脑
     * </pre>
     * 手机点「退出登录」只删得掉手机那把，电脑继续用。
     * 如果 key 停留在 {@code {userId}} 一层，后登录的会覆盖先登录的 ——
     * 手机一登录，电脑就被挤下线了。
     *
     * <p>顺带一个坑：按前缀匹配时<b>结尾的冒号不能省</b>。
     * {@code novel:auth:token:1:*} 是精确的；但如果写成
     * {@code novel:auth:token:1*}，就会连 userId=11、12、100 的一起匹配上，
     * 「踢掉 1 号用户」变成「踢掉一片用户」。
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

    /**
     * 作品浏览量：{@code novel:novel:views:{novelId}} → 用 {@code INCR} 累加，定时落库。
     *
     * <p>★ 存的是<b>自增的纯数字字符串</b>，不是对象，所以读写都必须走
     * {@code StringRedisTemplate}。用 {@code RedisTemplate<String,Object>}
     * 会让 value 序列化成 {@code {"@class":"java.lang.Integer","value":1}}，
     * 对它执行 INCR 会直接报 {@code ERR value is not an integer or out of range}。
     *
     * <p>★ 这个 key 是「消费型」的：定时任务用 {@code GETDEL} 把它整个取走，
     * 读走就没了。所以它<b>不需要过期时间</b> —— 没有读者点击的书根本不会
     * 产生这个 key，而产生了的 key 一定会被下一轮任务收走。
     */
    public static final String NOVEL_VIEWS = PREFIX + "novel:views:";

    /**
     * 待落库的浏览量脏集合（Set）：{@code novel:novel:view_dirty} → 成员是 novelId。
     *
     * <p>为什么不遍历 {@code novel:novel:views:*} 而要额外记一个集合：
     * {@code KEYS} / {@code SCAN} 是<b>全库扫描</b>，key 一多就是生产事故；
     * 而且它没法知道「这个 key 是这一轮新产生的、还是上一轮漏掉的」。
     * 用一个集合明确记下「谁有增量」，定时任务只处理集合里的成员，
     * 复杂度从「全库 key 数」降到「这一分钟被读过的书数」。
     *
     * <p>★ 它是<b>唯一需要人工关注的 key</b>：正常情况下每轮结束都会空掉。
     * 如果它长期不空，说明有 key 一直落库失败（见 {@code NovelViewCounter}
     * 里 {@code finally} 中那句 SREM 的注释）。
     */
    public static final String NOVEL_VIEW_DIRTY = PREFIX + "novel:view_dirty";

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

    /**
     * 评论防刷：{@code novel:comment:limit:{userId}}。
     *
     * <p>值存「上一次发表的时间戳」，10 秒后自动过期（{@code CommonConstant.COMMENT_INTERVAL_SECONDS}）。
     * <p><b>★ 写入只能用 {@code SET key value NX EX 10} 一条命令</b>，
     * 不能写成「先 GET 判断、再 SET」或者「先 SETNX 再 EXPIRE」——
     * 前者连点两下会同时通过，后者一旦在两条命令之间断了，
     * 这个 key 就永远不会过期，那个用户从此发不出评论且没有任何日志。
     * 见 {@code CommentServiceImpl#requireNotTooFast}。
     */
    public static final String COMMENT_LIMIT = PREFIX + "comment:limit:";

    /**
     * <b>评论</b>点赞的用户集合（Set）：{@code novel:like:user:{commentId}}，
     * 成员是 {@code userId}。
     *
     * <p>存在的唯一理由是<b>防重复点赞</b>：全库 22 张表里<b>没有
     * {@code comment_like} 表</b>（已核实），所以「谁点过」这件事没有地方落库，
     * 只能靠 Redis 的 {@code SADD} —— 它返回 1 表示第一次点，0 表示点过了。
     * 单条命令，原子。
     *
     * <p><b>★★ 注意这里和 P1 定这个常量时的写法【不一样】，是有意改的：</b>
     * 原来的注释写的是「用户点赞的<b>作品</b>集合，{@code novel:like:user:{userId}}」
     * —— 后缀是 userId、成员是 novelId。但项目里从来没有「作品点赞」这个功能
     * （{@code novel} 上只有 {@code collect_count} 收藏数，那是 P6 书架的事），
     * 而 P5 的点赞是<b>评论</b>点赞，方向正好相反：后缀是 commentId、成员是 userId。
     * 键字符串没变（从没被写入过，改了也没有历史数据要迁），
     * 但<b>谁的 id 在名字里必须写清楚</b> —— 反了的话，
     * 「A 点赞了 B 的评论」会被记成「B 点赞了 A 的评论」，而且完全看不出来。
     *
     * <p><b>★ 点赞总数【不在这里】，在 {@code novel_comment.like_count} 列上。</b>
     * P5 一开始留过一个 {@code LIKE_COUNT = novel:like:count:{novelId}}，
     * 已删除：评论列表每行都要显示点赞数，走 Redis 就是每行一次往返（N+1），
     * 而这一列本来就存在、且 {@code like_count = like_count + 1} 由数据库保证原子。
     * 把一个「库里有列」的数字再在 Redis 里存一份，只会多一个会对不上的地方。
     */
    public static final String LIKE_USER = PREFIX + "like:user:";

    /* ==================== AI 模块 ==================== */

    /** AI 每日额度：{@code novel:ai:quota:{userId}:{yyyyMMdd}} */
    public static final String AI_QUOTA = PREFIX + "ai:quota:";

    /** AI 生成结果缓存：{@code novel:ai:cache:{参数hash}}，避免相同请求重复烧钱 */
    public static final String AI_CACHE = PREFIX + "ai:cache:";

    /* ==================== 分布式锁 ==================== */

    /** 第二期微服务用：防止多实例重复执行定时任务 */
    public static final String LOCK_PREFIX = PREFIX + "lock:";
}
