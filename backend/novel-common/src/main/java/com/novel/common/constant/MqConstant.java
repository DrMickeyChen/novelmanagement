package com.novel.common.constant;

/**
 * RocketMQ 的 Topic / Tag / 消费组 / 生产者组（第二期 M3 新增）。
 *
 * <p><b>为什么要统一在这里定义：</b>和 {@link RedisKeyConstant} 同一个理由，但更狠 ——
 * 一个 Tag 名字打错一个字母，<b>生产者照样发成功、消费者照样启动成功</b>，
 * 只是消息永远送不到（Broker 端没有任何订阅匹配）。
 * 没有异常、没有日志、没有积压 —— 表现和「这个功能我还没写」一模一样。
 * <b>写成常量，至少能让拼错变成编译错误。</b>
 *
 * <h3>★★★ 本项目最重要的一条 MQ 约定：一个 (服务, Tag) 一个消费组</h3>
 *
 * <p>RocketMQ 的<b>订阅关系是按【消费组】存在 Broker 上的</b>，不是按消费者实例。
 * 同一个组内的所有消费者，订阅必须<b>完全一致</b>（Topic + Tag + 消费模式）；
 * 不一致时后注册的那个会<b>覆盖</b>前一个 ——
 * 于是<b>某个 Tag 的消息再也收不到，而且不报任何错</b>。
 *
 * <p>所以下面四个消费组是<b>一一对应</b>地列出来的，不是「按服务归类」：
 * <pre>
 *   group-content-view          ← content  ⟷ VIEW_REPORT
 *   group-interaction-progress  ← interaction ⟷ PROGRESS_REPORT
 *   group-content-novelstat     ← content  ⟷ NOVEL_STAT
 *   group-content-audit         ← content  ⟷ AUDIT_RECORD
 * </pre>
 * ★ <b>content 有三个消费组，不是「一个服务一个组」</b> —— 这正是本节要防的事。
 * 假设把 {@code VIEW_REPORT} 和 {@code NOVEL_STAT} 合到同一个组里：
 * 那两个消费者的 selectorExpression 不同（{@code VIEW_REPORT} / {@code NOVEL_STAT}），
 * 后启动的那个会把订阅<b>整个覆盖掉</b> —— 于是先启动那条 Tag 的消息
 * <b>再也收不到，而且不报任何错</b>。
 *
 * <p><b>★★ 这个坑的【形状】和 M2 那条网关路由的 {@code 4 &lt; 5} 不变式一模一样</b>
 * （见 {@code CLAUDE.md}）：配错了不报错，只表现为「消息没到」，
 * 看着完全像「这个 Tag 没实现」。<b>症状对照：Tag 收不到消息 → 先查有没有两个 Tag 共用一个消费组。</b>
 *
 * <h3>★ 四个 Tag 的消费模式与可靠等级（刻意不一样）</h3>
 *
 * <pre>
 *   Tag                载荷      消费模式        防乱序靠什么              消费方
 *   VIEW_REPORT        增量 +N   CONCURRENTLY    不用防（累加与顺序无关）   content
 *   PROGRESS_REPORT    绝对值    ORDERLY         hashKey = userId           interaction
 *   NOVEL_STAT         绝对值    CONCURRENTLY    载荷时间戳 + 单调写        content
 *   AUDIT_RECORD       事实      CONCURRENTLY    SETNX 幂等                 content
 * </pre>
 *
 * <p><b>★ 一句话规则：推「增量」的可以乱序；推「绝对值」的，要么顺序、要么带时间戳做单调写。</b>
 * <p><b>★★ 全项目只有 【一条】 ORDERLY</b>（{@code PROGRESS_REPORT}），这是有意的取舍。
 * 顺序消费失败走的是 {@code SUSPEND_CURRENT_QUEUE_A_MOMENT} ——
 * 不是 Broker 往返重试，而是<b>消费者线程内按固定 1 秒间隔的本地重试</b>，
 * 且 {@code maxReconsumeTimes} 在顺序消费下<b>默认是 {@code Integer.MAX_VALUE}</b>。
 *
 * <p>★★★ <b>注意「默认是」这三个字 —— M3 实施时这里写错过一次，值得留档。</b>
 * 本类注释、以及下面 {@link #DLQ_TOPIC_PREFIX} 的注释，原来都断言
 * 「顺序消息<b>永不进</b> {@code %DLQ%}、一条毒消息把队列<b>永久</b>卡死」。
 * <b>M3 实测证明那句话是错的</b>，错在把「默认值」当成了「必然」：
 * <pre>
 *   实测（PROGRESS_REPORT 投一条 progress:"abc" 的毒消息）
 *     23:19:55.016  consume message failed ... reconsumeTimes:0   ← 第 1 次
 *     23:19:56.115  consume message failed ... reconsumeTimes:1   ← +1.099s
 *     23:19:57.121  consume message failed ... reconsumeTimes:2   ← +1.006s
 *     23:19:58.129  consume message failed ... reconsumeTimes:3   ← +1.008s
 *     23:20:02      队列恢复，后续控制消息全部消费成功
 *   drain %DLQ%group-interaction-progress
 *     GOT tag=PROGRESS_REPORT reconsume=4 body={"progress":"abc", ...}
 * </pre>
 * ★ <b>原因很简单：我们【显式】给四个消费者都配了
 * {@code maxReconsumeTimes = 3}</b>（见各 {@code @RocketMQMessageListener}）。
 * 顺序消费的 {@code Integer.MAX_VALUE} 只是<b>不写时的默认值</b>，
 * 显式写了就按写的算 —— 重试 3 次之后照常进 DLQ，队列照常前进。
 * <b>「永不放弃」这个可怕的性质从来没有生效过。</b>
 *
 * <p>★ 那么保留唯一的 ORDERLY 还剩什么代价？<b>三条，都是真的，但都不是「不可恢复」：</b>
 * <ol>
 *   <li><b>队头阻塞</b>：这 4 秒里该 MessageQueue 上的消息<b>一条都过不去</b>
 *       （实测 23:19:55 ~ 23:19:58，之后才恢复）。半径是 {@code 1/队列数} = 1/4，
 *       因为粒度是「单条队列内部有序」。</li>
 *   <li><b>失败重试固定在 1 秒</b>，没有并发消费那种 10s/30s/60s 的延迟阶梯 ——
 *       毒消息会以更高的频率刷日志。</li>
 *   <li><b>吞吐</b>：整条队列交给<b>一个</b>消费线程
 *       （日志里的 {@code ConsumeMessageThread_group-interaction-progress_3..6} 是顺序轮流用的），
 *       不像并发消费可以横向铺开。</li>
 * </ol>
 *
 * <p>★★ <b>所以「为什么 {@code NOVEL_STAT} 不用 ORDERLY」的答案要重新表述。</b>
 * 原来写的是「顺序消息不可恢复」——<b>那是错的</b>。真正的理由弱一档、但依然成立：
 * 它推的是绝对值，而<b>修正乱序本来就不需要整条队列为它排队</b> ——
 * 一个时间戳比较就够了。<b>能用一条应用层不变量换掉的东西，
 * 就不该让整条队列承担队头阻塞和 1 秒重试。</b>
 * <p>★ 换句话说：原来的论证是「因为 ORDERLY 会死，所以用单调写」，
 * 现在改成「因为单调写更便宜且已足够，所以不用 ORDERLY」。<b>结论没变，前提变了。</b>
 * （这正是 CLAUDE.md 里说的那类最难发现的注释：结论还对、前提已经变了。）
 *
 * @author MickeyChen
 */
public final class MqConstant {

    /** 工具类，禁止实例化 */
    private MqConstant() {
    }

    /* ==================== Topic ==================== */

    /**
     * 全项目共用的唯一 Topic。
     *
     * <p>★ 四条业务消息全在它下面，靠 <b>Tag 区分</b>，不是靠 Topic ——
     * 这是大纲 §12.1 定的，优点写在那边：一个 Tag 混两种消息、
     * 两个消费组都得在消费者里再判一次类型，就白丢了 Broker 端的 Tag 过滤。
     *
     * <p>★★ 队列数：{@code docs/docker/broker.conf} 里显式写了
     * {@code defaultTopicQueueNums = 4}，所以这个 Topic 是 <b>4 条队列</b>。
     * 这个数字对 {@code PROGRESS_REPORT} 有意义 ——
     * 顺序消息<b>只在单条队列内部</b>有序，{@code hashKey = userId}
     * 把用户分散到 4 条队列上，所以「每用户内部有序」成立，
     * 而队头阻塞的影响半径是整条 Tag 的 1/4。
     * <p>★ 之所以要把它写进配置文件而不是靠默认值：默认值也是 4，
     * 但那是个<b>隐式的</b> 4 —— 「顺序消息」这个话题每次都要说清队列数，
     * 而它不该藏在 RocketMQ 的默认值里。
     */
    public static final String TOPIC = "novel-topic";

    /**
     * {@code RocketMQTemplate} 的 destination 写法：<b>{@code topic:tag}</b>。
     *
     * <p>★★ <b>这个方法存在的唯一理由是防一个静默错误：漏写 Tag。</b>
     * {@code RocketMQTemplate} 的 destination 是 {@code "topic:tag"} 这样拼出来的，
     * 而 {@code asyncSend(TOPIC, ...)}（只给 Topic、不带 Tag）<b>不会报错</b> ——
     * 它发的是一条<b>没有 Tag 的消息</b>。而消费端的 {@code selectorExpression}
     * 是具体的 Tag 名，于是<b>没有任何订阅能匹配上它</b>：
     * 发送成功（有 SendResult，msgId 都拿到了）、消费端一切正常、没有积压、没有死信，
     * 消息就这么消失了 —— 和「这个 Tag 我还没写消费者」长得一模一样。
     *
     * <p>★ 收进一个方法里，Tag 就只能来自上面的常量，拼错是编译错误。
     * <b>四处发送都必须走它，不要手写 {@code TOPIC + ":" + tag}。</b>
     */
    public static String destinationOf(String tag) {
        return TOPIC + ":" + tag;
    }

    /* ==================== Tag ==================== */

    /**
     * 阅读量上报：阅读量 <b>增量</b>（{@code +N}）。
     *
     * <p>★ 生产方和消费方<b>都是 novel-content</b>（{@code novel.view_count} 是它的列）。
     * 这条是「自发自消」，所以它不需要跨服务契约 —— 但仍然要写在这里，
     * 因为 {@code novel-common} 是这个项目里唯一一个所有服务都依赖的地方。
     *
     * <p>★★ 它必须保持「增量」语义，一旦改成推绝对值，多实例立刻算错：
     * 每个 content 实例只 flush 自己进程内那份累加器，
     * 而 {@code view_count = view_count + ?} 是可加的 —— 各加各的，加起来正好对。
     * 推绝对值就变成「后写的盖掉先写的」，实例越多丢得越多。
     */
    public static final String TAG_VIEW_REPORT = "VIEW_REPORT";

    /**
     * 阅读进度上报：{@code (userId, novelId, chapterId, progress)} <b>绝对值</b>。
     *
     * <p>★★ <b>全项目唯一一条顺序消息</b>，{@code hashKey = userId}。
     * 乱序会让进度<b>回退</b>（先收到第 5 章、再收到第 3 章，读者下次打开就回到了第 3 章）。
     *
     * <p>★ 它之所以敢用 ORDERLY，是因为<b>它的消费者只写 interaction 自己的表、
     * 不调任何远程</b>（{@code read_history} 就在 interaction 的库里）。
     * ★ 理由<b>不再</b>是原来写的「content 一挂、顺序队列就永久卡死」——
     * 那是错的（见类注释的实测：显式配了 {@code maxReconsumeTimes} 就会进 DLQ）。
     * 真正的理由是<b>队头阻塞的半径</b>：这条链路没有 Feign，
     * 所以一次失败最多阻塞 4 秒（3 次本地重试）就进 DLQ 放行；
     * 若换成一条要发 Feign（read-timeout 5s）的消息，
     * 每次失败都要先等 5 秒超时，阻塞时间放大一个数量级。
     * <b>★ 结论仍然是「换成任何一条要发 Feign 的消息，都不许用 ORDERLY」，
     * 但依据是延迟而不是不可恢复性。</b>
     *
     * <p>★ 生产方和消费方都是 novel-interaction（自发自消）。
     */
    public static final String TAG_PROGRESS_REPORT = "PROGRESS_REPORT";

    /**
     * 作品统计值：{@code collect_count} / {@code score}，<b>绝对值</b>。
     *
     * <p>★ 方向：<b>novel-interaction → novel-content</b>。
     * 它替代的是 M2 留下的那两笔债里的写回部分 ——
     * {@code ShelfServiceImpl} 的收藏数、{@code CommentServiceImpl} 的评分，
     * 原来是同步 Feign 写回（一笔在事务外、一笔在事务内，各有各的坏法）。
     *
     * <p>★★ 消费模式是 {@code CONCURRENTLY}，防乱序靠<b>应用层单调写</b>：
     * 载荷带生产时间戳，消费端只有「新时间戳 ≥ 已应用的时间戳」才写。
     * 理由见类注释里那段 ORDERLY 的代价 —— 这条消息的旧值被丢弃就够了，
     * 不需要整条队列为它排队。
     */
    public static final String TAG_NOVEL_STAT = "NOVEL_STAT";

    /**
     * 审核流水：一次审核动作的事实。
     *
     * <p>★ 方向：<b>novel-auth → novel-content</b>（{@code audit_log} 表在 content 那边）。
     * ★★ <b>这是 M3 断掉服务级环 #1（{@code novel-auth ⇄ novel-content}）的那条消息</b> ——
     * 它取代了 M2 的 {@code ContentAuditClient} 那次 Feign 写。
     *
     * <p>★★ <b>这个 Tag 有两个生产者，而它们用的发送方式【故意不一样】</b>：
     * <pre>
     *   novel-auth       审作者申请 → AuthorApplyAuditProducer  事务消息
     *   novel-interaction 审/删评论 → CommentAuditProducer      普通消息（提交后发）
     * </pre>
     * ★ 差别不在「谁更重要」，而在<b>发送方在不在一个事务里</b>：
     * auth 那次写在 {@code @Transactional} 的 {@code audit()} 内，
     * 只能用事务消息把「本地事务提交」和「消息可见」绑在一起
     * （Feign 不参与调用方事务，所以 auth 一回滚，content 里就留下一条
     * <b>不会有人删的孤儿流水</b>）；
     * interaction 那次用 {@code AfterCommit} 就够（它的事务很短，
     * 且漏发的后果弱一档）。<b>完整对照写在两个 Producer 的类注释里。</b>
     * <b>★ 不要因为这条消息「是流水」就以为它一律走事务消息。</b>
     *
     * <p>★ 消费端靠 {@code SETNX} 幂等（载荷带 {@code messageKey}）——
     * {@code audit_log} 没有唯一键，而这张表的全部价值就是
     * 「一条流水对应一次真实发生过的操作」，重复写一条等于记了一次没发生的事。
     *
     * <p>★★ <b>订正（E1）</b>：这里原写「它是四条里<b>唯一</b>需要显式幂等的」。
     * {@link #TAG_AI_AUDIT} 也需要显式幂等，但两者的<b>幂等键不是一回事</b> ——
     * 本条用<b>消息键</b>（挡的是「同一次发送被重投」），
     * {@code AI_AUDIT} 用<b>内容指纹</b>（消息键挡不住「内容没变却又被审了一遍」）。
     * ★ <b>别因为「都是幂等」就把两处的键统一</b>：那是把两条不同的问题
     * 塞进一个机制里，两边都会各错一半。
     */
    public static final String TAG_AUDIT_RECORD = "AUDIT_RECORD";

    /**
     * 小说索引重建（M4）：<b>「这本书的索引可能过期了」</b>。
     *
     * <p>★ 方向：<b>novel-content → novel-search</b>。
     *
     * <h3>★★ 为什么叫 {@code NOVEL_INDEX} 而不是大纲里的 {@code CHAPTER_PUBLISHED}</h3>
     *
     * <p>大纲把这条设计成「章节发布」触发。★ 但只做章节发布的话，<b>新书上架后搜不到</b> ——
     * 新书还没有任何章节发布事件。而把发送点挂在 {@code syncNovelStat} 的调用点上也是错的：
     * <b>那是 4 个调用点，不是触发点全集</b>。参与检索的列有 5 组，
     * 而 {@code title} / {@code intro}（改书名）、{@code status}（上下架）、{@code deleted}（逻辑删除）
     * <b>都不经过它</b>。
     * ★★ 挂错的后果是三条<b>永久不会自愈</b>的错：下架的书在索引里永远是「已上架」、
     * 改名后搜新名搜不到、逻辑删除只对 MySQL 生效
     * （{@code @TableLogic} 是 MyBatis 往 WHERE 里注入条件，<b>ES 完全不知道有 {@code deleted} 这回事</b>）。
     * <p>所以 Tag 的语义是「<b>这本书的索引可能过期了</b>」——
     * <b>推事实，不推数据</b>。判据：<b>参与检索的列被写的地方</b>，而不是「哪个方法看着像入口」。
     *
     * <h3>★★★ 铁律 7 框架下的【第五种形态】—— 而且是唯一一条「幂等免费」的</h3>
     *
     * <table border="1">
     *   <caption>四条 M3 消息 + 这一条，各自的载荷与正确性来源</caption>
     *   <tr><th>消息</th><th>载荷</th><th>靠什么保证正确</th></tr>
     *   <tr><td>{@code VIEW_REPORT}</td><td>增量 {@code +N}</td><td>累加与顺序无关</td></tr>
     *   <tr><td>{@code PROGRESS_REPORT}</td><td>绝对值</td><td>★ ORDERLY（全项目唯一）</td></tr>
     *   <tr><td>{@code NOVEL_STAT}</td><td>绝对值</td><td>时间戳单调写</td></tr>
     *   <tr><td>{@code AUDIT_RECORD}</td><td>事实</td><td>{@code SETNX} 幂等</td></tr>
     *   <tr><td><b>{@code NOVEL_INDEX}</b></td><td><b>只有一个 id</b></td>
     *       <td>★★ <b>幂等天然、顺序无关</b> —— 不需要 SETNX、不需要时间戳</td></tr>
     * </table>
     *
     * <p>★ 为什么这么省：<b>载荷里没有数据，消费者读的是【当时的真值】。</b>
     * 两条乱序的消息各自触发一次重建，两次都读同一份当前状态、写同一个文档 → 结果一样；
     * 重复消费也一样。<b>它推的是「去重算一次」这个指令，而不是算好的结果。</b>
     * <pre>
     *   ★ 顺带一个反例（记下来免得将来有人「优化」）：如果哪天为了省一次查询，
     *   把 title/intro 直接塞进载荷 —— 这条消息立刻退化成「绝对值」，
     *   于是必须补上时间戳或顺序消费。**省一次查询，换来一整套防乱序机制。**
     * </pre>
     */
    public static final String TAG_NOVEL_INDEX = "NOVEL_INDEX";

    /**
     * 违禁审核（E1）：<b>「这条内容该过一遍 AI 审核」</b>。
     *
     * <p>★ 方向：<b>novel-content → novel-content</b>（章节 submit）、
     * <b>novel-interaction → novel-content</b>（评论入库）。两个生产者、一个消费方 ——
     * 与 {@link #TAG_AUDIT_RECORD} 完全同形。
     * ★ 方向那一条值得单独说：interaction 发的那半边是 <b>MQ</b>，而
     * <b>MQ 不算服务级边</b>（M3 判断 C 的定义，M4 起成为承重条款）——
     * 所以它<b>不加重</b> {@code content ⇄ interaction} 那个环。
     *
     * <h3>★★★ 与 {@link #TAG_AUDIT_RECORD} 是两回事，命名必须隔开</h3>
     *
     * <pre>
     *   AUDIT_RECORD（M3） = 【人】审完了  → 写审核流水
     *   AI_AUDIT   （E1） = 【机器】该审了 → 产出 AI 判定，等【人】来处置
     * </pre>
     * ★★ 两者在本文里相隔几十行、互相引用，是刻意的：它们的消费组名
     * {@code group-content-audit} 与 {@code group-content-ai-audit} <b>只差三个字符</b>，
     * 而抄错的症状是<b>某个 Tag 收不到消息且不报错</b>
     * （同一消费组上后注册的订阅会覆盖前一个 —— 见类注释开头）。
     *
     * <h3>★★★ 它【不是】「幂等免费」的 —— 铁律 7 的判据在这里被收紧</h3>
     *
     * <p>它的载荷形状与 {@link #TAG_NOVEL_INDEX} <b>一模一样</b>（只有一个 id），
     * 但它<b>不幂等</b>：<b>调一次 AI 是非纯函数</b>（温度 &gt; 0），
     * 同一个 {@code {targetType:3, targetId:94}} 消费两次会得到<b>两份不同的判定</b>。
     *
     * <p>★★ <b>由此把铁律 7 的判据说精确</b>：{@code NOVEL_INDEX} 幂等免费，
     * <b>不是因为「载荷里没有数据」，而是因为「读真值之后做的那件事是纯函数」</b>。
     * 「读真值」只有在<b>读完之后那件事也是确定的</b>时，才是幂等的充分条件。
     *
     * <p>★ 所以这条需要显式幂等，而幂等键是<b>内容指纹</b>（消费端算 {@code hash(读到的正文)}），
     * <b>不是消息键</b>：消息键只能挡「同一次发送被重投」，挡不住「内容没变却又被审了一遍」；
     * 反过来它还会<b>误伤</b>「改了内容重新提交」—— 那<b>应该</b>重审。
     * 粒度是<b>内容版本</b>，详见 {@code AiAuditConsumer}。
     *
     * <p>★★ <b>载荷里不许放枚举</b>：{@code rocketmq-spring} 的类型转换失败发生在
     * 方法体被调用<b>之前</b>，我们的 {@code try/catch} 根本没机会执行 ——
     * 载荷里的枚举改名/删值会让在途消息<b>全部静默进 DLQ</b>。
     * 所以 {@code targetType} 用 {@code Integer}，与 {@code ai_audit_result.target_type}
     * 的裸 {@code TINYINT} 对齐。
     * ★ 顺带一条结构性结论：<b>给 {@code AiScene} 加 {@code AUDIT(5)} 不可能影响在途消息</b> ——
     * {@code AiScene} 住在 {@code novel-content}，载荷类住在 {@code novel-common}，
     * 而 {@code novel-common} <b>不依赖 content</b>，结构上不可能被载荷引用。
     * （判据钉在包依赖上，不钉在「grep 某个词」上 —— 铁律 8 的第三种形状。）
     */
    public static final String TAG_AI_AUDIT = "AI_AUDIT";

    /* ==================== 消费组 ==================== */
    /* ★ 一个 (服务, Tag) 一个组 —— 理由见类注释开头。六个组不要合并。 */

    /** content 消费 {@link #TAG_VIEW_REPORT}（阅读量，并发 + 进程内批量聚合） */
    public static final String GROUP_CONTENT_VIEW = "group-content-view";

    /** interaction 消费 {@link #TAG_PROGRESS_REPORT}（阅读进度，★ 顺序） */
    public static final String GROUP_INTERACTION_PROGRESS = "group-interaction-progress";

    /** content 消费 {@link #TAG_NOVEL_STAT}（收藏数 / 评分，并发 + 单调写） */
    public static final String GROUP_CONTENT_NOVELSTAT = "group-content-novelstat";

    /** content 消费 {@link #TAG_AUDIT_RECORD}（审核流水，并发 + SETNX 幂等） */
    public static final String GROUP_CONTENT_AUDIT = "group-content-audit";

    /**
     * ★★ <b>search 消费 {@link #TAG_NOVEL_INDEX}（索引重建，并发 + 幂等天然）。</b>
     *
     * <p>★★ <b>这是全项目【唯一】一个由非 content/interaction/auth 的服务持有的消费组</b> ——
     * 它属于 {@code novel-search}。
     *
     * <p>★★★ <b>「谁消费」在这里是承重的设计决定，不是实现细节</b>：
     * 消费方必须是 search。如果反过来让 content 消费再调 search 推文档，
     * 就凭空造出一条 <b>content → search 的【同步】边</b>，
     * 而 {@code search → content} 的同步边本来就存在（检索要回 content 装配）——
     * <b>两条合起来就是服务级环 #3</b>。
     * ★ 按 M3 判断 C 用的定义（<b>服务级环 = 同步调用的环，MQ 不算边</b>），
     * 现在这条 {@code content →(MQ) search} <b>不构成环</b>。
     *
     * <p>★ 名字按大纲 §12.1 的写法（{@code group-search-index}）保留。
     * ★ 大纲里跟它并列的那个 {@code group-content-stat} <b>M4 没有建</b> ——
     * 它要干的事（{@code word_count} / {@code chapter_count} 冗余字段更新）
     * 本来就是 content 自己一个事务里的本地写，改成「发消息 → 自己消费 → 再写」
     * 是把一个原子操作<b>降级</b>成最终一致。<b>那是大纲里一条不该存在的设计。</b>
     */
    public static final String GROUP_SEARCH_INDEX = "group-search-index";

    /**
     * ★★ <b>content 消费 {@link #TAG_AI_AUDIT}（AI 违禁审核，并发 + 内容指纹幂等）。</b>
     *
     * <p>★★★ <b>这个名字必须与 {@link #GROUP_CONTENT_AUDIT} 分清</b> ——
     * 两者只差 {@code -ai-} 三个字符，而抄错的结果是
     * <b>某个 Tag 收不到消息、且不报错</b>。
     * ★ 另有一条现成的坑：<b>大纲 §12.1 里的 {@code group-comment-audit 根本不存在</b>，
     * 而 {@code group-content-audit} 已被 {@link #TAG_AUDIT_RECORD}（人工审核流水）占用 ——
     * E1 新增消费组时<b>不要照抄大纲</b>（订正 4）。
     *
     * <p>★ 消费方是 <b>content</b>，不是 interaction：interaction 只负责把
     * 「有评论要审」这件事投出来，判定与 {@code ai_audit_result} 的读写全部留在 content ——
     * <b>单库单事务</b>。★ 而且这个选择是承重的：反过来让 interaction 去调 AI，
     * 就等于在 interaction 里凭空多出一条到 AI 上游的依赖，
     * 还多出一个「评论正文要跨服务传过去」的接口。
     */
    public static final String GROUP_CONTENT_AI_AUDIT = "group-content-ai-audit";

    /* ==================== 生产者组 ==================== */

    /**
     * 普通消息的生产者组（content 与 interaction 用）。
     *
     * <p>★ 普通消息的生产者组在 RocketMQ 5.x 里基本只是个标记 ——
     * 它<b>不参与</b>「一条消息投给谁」的决策（那是消费组的事）。
     * 所以两个服务共用一个名字是安全的。
     */
    public static final String PRODUCER_GROUP = "novel-producer";

    /**
     * ★★ 事务消息的生产者组，<b>只给 novel-auth 用</b>。
     *
     * <p><b>它必须独立，不能和 {@link #PRODUCER_GROUP} 共用一个名字。</b>
     * 原因是事务消息的生产者组不是标记，而是<b>事务组的标识</b> ——
     * Broker 在回查（{@code checkLocalTransaction}）时，
     * 是拿这个组名去反查「该问哪个生产者实例」的。
     * 如果 content / interaction 也用同一个名字注册了普通生产者，
     * Broker 的组名字典里就混进了两个不能回查的实例，
     * <b>回查请求可能被投给一个根本不知道这条半消息的进程</b> ——
     * 表现是半消息永远悬着、流水静默不出现。
     *
     * <p>★ auth 的 {@code application.yml} 里 {@code rocketmq.producer.group}
     * 就写这个值（auth 只发这一条消息，所以它整个服务的生产者组就是事务组）。
     */
    public static final String PRODUCER_GROUP_AUTH_TX = "novel-tx-audit";

    /* ==================== Broker 系统 Topic 的命名规则 ==================== */

    /**
     * 重试 Topic 的前缀：Broker 自动建 {@code %RETRY%{消费组}}。
     *
     * <p>★ 只有<b>并发</b>消费失败才会进这里（延迟阶梯重试）。
     * 顺序消费失败是<b>线程内本地重试</b>，不经过这个 Topic。
     */
    public static final String RETRY_TOPIC_PREFIX = "%RETRY%";

    /**
     * 死信 Topic 的前缀：Broker 自动建 {@code %DLQ%{消费组}}。
     *
     * <p>★★ <b>它的保留期在本项目是 48 小时，不是网上常说的 3 天</b> ——
     * {@code docs/docker/broker.conf} 里 {@code fileReservedTime = 48}。
     * 演示「死信可被看到」时要按 48 小时说。
     *
     * <p>★★ 而且<b>顺序消息【也】会进这里</b> —— 只要 {@code maxReconsumeTimes} 是显式配的
     * （本项目六个消费者都写了 {@code 3}）。
     * ★ 这一点在本注释里原来写反了（写成「顺序消息永远进不了这里、
     * {@code %DLQ%group-interaction-progress} 是一个永远不存在的 Topic」），
     * M3 实测把那条断言推翻了，详见类注释里那段实测记录 ——
     * 那条毒消息<b>现在就在这个 Topic 里</b>躺着，{@code reconsume=4}。
     * <p>★ 排查「进度消息去哪了」时的正确顺序因此是：
     * <b>先看消费端日志有没有在重试 → 再看这个 DLQ</b>，
     * 而不是像原来那样直接排除 DLQ。
     */
    public static final String DLQ_TOPIC_PREFIX = "%DLQ%";

    /** 某个消费组的重试 Topic 名，例如 {@code %RETRY%group-content-audit} */
    public static String retryTopicOf(String consumerGroup) {
        return RETRY_TOPIC_PREFIX + consumerGroup;
    }

    /**
     * 某个消费组的死信 Topic 名，例如 {@code %DLQ%group-content-audit}。
     *
     * <p>★ 写成方法而不是让各处自己拼字符串：这串东西只在
     * 「排查积压」和「演示死信」两处用得到，而且两处都要拼对才能查到东西，
     * 拼错了返回的是「这个 Topic 不存在」，和「里面没消息」长得一样。
     */
    public static String dlqTopicOf(String consumerGroup) {
        return DLQ_TOPIC_PREFIX + consumerGroup;
    }
}
