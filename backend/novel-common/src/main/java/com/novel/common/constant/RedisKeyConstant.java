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

    /*
     * ★★ M3 删掉了两个常量：NOVEL_VIEWS（novel:novel:views:{id}）与
     *    NOVEL_VIEW_DIRTY（novel:novel:view_dirty）。
     *
     *    阅读量不再攒在 Redis 里了 —— 改成「请求路径发一条 MQ 消息、
     *    content 消费后在【进程内】批量聚合、每 5 秒落库」。
     *    删得有理由，不是「反正用不到了」：
     *      · 共享状态（Redis 脏集）换成了私有状态（各实例的堆内存），
     *        于是老实现里那套「先 SREM 认领、再 GETDEL 取数」的分布式认领
     *        整个不需要了 —— 一条消息只投给一个实例，
     *        而 view_count = view_count + ? 是可加的，各加各的正好对。
     *      · ★ 但代价也要记：老实现是【自愈】的（计数在 Redis 里累积，
     *        下次有人读会重新 INCR、GETDEL 把积压的一起捞出来），
     *        内存累加器没有这个性质。这一处失血窗口变大了，是有意换的，
     *        详见 NovelViewAggregator 的类注释。
     *
     *    ★ 那两段仍然成立的论证没有丢，各自搬到了它该在的地方：
     *      · 「INCR / SET NX 必须走 StringRedisTemplate」→ RedisConfig
     *        （它讲的是两个 Redis bean 的区别，和具体业务无关）；
     *      · 「怎么无锁地把累加值原子取走」→ NovelViewAggregator（
     *        从 SREM 认领 + GETDEL 换成了 ConcurrentHashMap.compute 的原子交换，
     *        解决的是同一类竞态）。
     */

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

    /* ==================== 消息队列（第二期 M3） ==================== */

    /**
     * 消费幂等：{@code novel:mq:idempotent:{messageKey}} → 值随便，存在即「这条消息处理过了」。
     *
     * <p><b>★ 写入只能用 {@code SET key 1 NX EX 86400} 一条命令</b>，
     * 和 {@link #COMMENT_LIMIT} 是同一个道理：拆成「先 GET 判断、再 SET」
     * 在并发重投下会双双通过；拆成「SETNX + EXPIRE」一旦中间断了，
     * 这把 key 就<b>永远不会过期</b>，那条消息对应的业务从此再也处理不了，且没有日志。
     *
     * <p>★ 只给 {@code AUDIT_RECORD} 用 —— 四条消息里只有它需要显式幂等，
     * 理由见 {@code AuditRecordMessage} 的类注释（另外三条重复消费都是无害的）。
     *
     * <p>★★ TTL 是 <b>24 小时</b>，比 Broker 的死信保留期（{@code fileReservedTime = 48}
     * 小时）<b>短</b>。这不是疏忽，是一个有意的取舍：
     * <ul>
     *   <li>幂等键要比「消息可能被重投的时间窗」长 —— 而重投只发生在重试阶梯
     *       和消费位点回退里，那几个窗口都在分钟级，24 小时绰绰有余；</li>
     *   <li>但如果<b>超过</b> 48 小时，那就是有人在手工重放一条两天前的死信,
     *       那时候「重新记一条流水」反而是可以接受的（总比静默丢掉强）。</li>
     * </ul>
     */
    public static final String MQ_IDEMPOTENT_PREFIX = PREFIX + "mq:idempotent:";

    /**
     * 统计值单调写的「已应用到的时间戳」：{@code novel:novel:stat_at:{novelId}} → Hash，
     * field 是列名（{@code COLLECT_COUNT} / {@code SCORE}），value 是 epoch 毫秒。
     *
     * <p><b>★★ 它解决的是「{@code NOVEL_STAT} 推绝对值、乱序会让值回退」这件事。</b>
     * 消费端只有「{@code incoming.at >= 已应用.at}」才写库，否则直接丢弃那条旧消息。
     * 完整论证见 {@code NovelStatMessage#at} 的注释和 {@code MqConstant} 的类注释 ——
     * 一句话是：<b>用应用层的不变量替代了传输层的顺序保证</b>。
     * ★ 理由（M3 实测后修正过一次）：原来写的是「顺序消费的失败语义
     * （永不放弃、永不进死信、队长死）代价太大」—— <b>那句是错的</b>。
     * 显式配了 {@code maxReconsumeTimes} 之后顺序消息照常进 DLQ、队列照常前进。
     * 真正的理由是<b>队头阻塞 + 固定 1 秒重试 + 整条队列单线程</b>，
     * 而修正乱序一个时间戳就够 —— 不值得为它付那些代价。
     * 实测记录在 {@code MqConstant} 的类注释里。
     *
     * <p><b>★ 为什么是 Hash 而不是两个平铺的 key：</b>
     * 一次性把这本书的<b>所有</b>列的已应用时间读回来（{@code HMGET}），
     * 消费端处理一条消息只要一次往返；平铺的话消息一多就是 N 次。
     * 顺带 RedisInsight 里展开 {@code novel:novel:stat_at:1001} 就能看到两列的时间，
     * 排查「这本书的收藏数为什么是旧的」时一眼就能比对。
     *
     * <p><b>★★ 比较与写入必须原子完成</b>（读-判断-写三步之间会有并发窗口，
     * 两个线程可能都判断通过、然后较旧的那个后写 —— 那就白防了）。
     * 所以消费端用的是一段 Lua 脚本，不是 {@code GET} 之后再 {@code HSET}。
     * <b>这不是优化，是正确性要求</b> —— 脚本和它的注释在
     * {@code content} 侧的单调写守卫里。
     *
     * <p>★ 不设 TTL：它跟着书走，书在就得在。一本书被删掉之后这把 key 会残留 ——
     * 和 {@code novel:novel:detail:*} 缓存同一个性质，属于「可接受的残留」，
     * 不值得为它加一套清理机制。
     */
    public static final String NOVEL_STAT_AT = PREFIX + "novel:stat_at:";

    /* ==================== 分布式锁 ==================== */

    /**
     * 第二期微服务用：防止多实例重复执行定时任务。
     *
     * <p>★ 用法：{@code SET novel:lock:{任务名} {实例标识} NX EX {秒数}}。
     * <b>不是所有定时任务都需要它</b> —— 见 {@code SchedulingConfig} 的类注释：
     * 「消费型」任务靠一次原子操作天然互斥（比如老的阅读量脏集用 {@code SREM} 认领），
     * 「计算型」任务没有天然互斥点，<b>必须自己加锁</b>。
     * <p>★ M3 起它的第一个真实使用者是 {@code interaction} 的
     * {@code NovelStatReconcileJob}（对账任务，计算型）。
     */
    public static final String LOCK_PREFIX = PREFIX + "lock:";
}
