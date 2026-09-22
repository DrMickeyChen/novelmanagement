# M3 · 引入 RocketMQ —— 四条写路径改走消息

> 与 `docs/M0-骨架搭建.md` / `docs/M1-摘出auth.md` / `docs/M2-摘出content.md` 并列。
> **本文记录的是「当时的事实」**，包括**一处被实测推翻、事后改正的设计判断**（§四 判断 B′）——
> 那一节请连着我的错一起读，它比结论本身有价值。

---

## 一、验收标准（大纲原文）

`项目大纲.md:1302`：

```
| **M3 摘出 interaction**（2~3 天） | 评论 / 书架 / 历史迁出；**此时引入 MQ 处理阅读量上报** | 写热点走 MQ 削峰，互动服务独立部署 |
```

★★ **先说一件必须说清楚的事：这一行的前半句和后半句，M2 已经做完了。**
`novel-interaction` 在 M2 就是独立进程（`:8083`），评论 / 书架 / 历史也已经在里面。
所以 **M3 的实质只剩一件事：MQ**。
这不是「M3 没活干」，而是「M3 的活被大纲写在了两行里，一行是拆分、一行是 MQ，拆分那半已经兑现」。

**M3 的验收标准**（其余全是手段）：**写热点走 MQ 削峰** —— 而且不只是「跑通」，
要能回答大纲 §12 那五个必问问题（丢消息 / 重复消费 / 顺序 / 积压 / 事务消息）。

### 一句话目标

装上 RocketMQ，把**四条写路径**从「同步直写」改成「发消息 + 消费」：

| # | 路径 | 方向 | 消费模式 | 防乱序靠什么 |
|---|---|---|---|---|
| ① | **阅读量** | content 自发自消 | CONCURRENTLY | 不用防（推的是增量） |
| ② | **阅读进度** | interaction 自发自消 | ★ **ORDERLY**（全项目唯一） | `hashKey = userId` |
| ③ | **收藏数 / 评分** | interaction → content | CONCURRENTLY | 载荷时间戳 + 单调写 |
| ④ | **审核流水** | auth → content | CONCURRENTLY | SETNX 幂等 |

**而业务行为对前端逐字节不变**：两个埋点接口今天就是 `silent: true` + 不看返回值。

### 开局状态（开工前核实，全部有据）

| 事实 | 证据 |
|---|---|
| ★★ **Broker 容器早就写好了，只是没起过** | `docs/docker/docker-compose.yml` 已有 `rmqnamesrv`(9876) + `rmqbroker`(10909/10911/10912)，镜像 `apache/rocketmq:5.3.1` |
| ★ `broker.conf` 也已写好 | `docs/docker/broker.conf` —— `brokerIP1 = 192.168.127.137` 已填、`autoCreateTopicEnable = true` |
| M0 **刻意没起它** | `docs/M0-骨架搭建.md`「不碰 RocketMQ / ES（那是 M3 / M4）」 |
| **MQ 的 Java 侧是零** | 五个 `pom.xml` 里 `rocketmq` 0 命中；无 `@Async` / `@EventListener` / `ApplicationEventPublisher` |
| 父 POM **没有** RocketMQ 的版本管理 | 只 import 了 `spring-cloud-dependencies` 与 `spring-cloud-alibaba-dependencies` 两个 BOM |
| ★ `@EnableScheduling` 在 `novel-common` | `SchedulingConfig` → **四个服务全都在跑定时任务**，只是目前一个 `@Scheduled` 都没有了 |
| ★ 网关是**只有 4 个文件**的 WebFlux 应用 | `GatewayApplication` / `JwtAuthGlobalFilter` / `application.yml` / `application-dev.yml` |

### ★★ 一个必须先说的偏差：`docs/docker/` 只改了一行

`docker-compose.yml` **零改动** —— 容器定义早就写好了，M3 只是**启动**它。
唯一改的是 `broker.conf`：**补上 `defaultTopicQueueNums = 4`**（理由见 §六）。
★ 这一点要在文档里说清楚，否则后人会以为 M3 加了中间件。

---

## 二、已拍板的四个决定（开工前定的，实施中没动摇）

| # | 决定 | 后果 |
|---|---|---|
| 1 | **M3 范围 = 阅读量 + 两笔债 + 去环** | 除了大纲点名的阅读量，还做 R8 / R9（M2 记的两个跨服务派生值缺陷）与环 #1 → **M3 之后服务级环从 2 个降到 1 个** |
| 2 | **阅读量：content 自发自消，MQ 取代 Redis 脏集** | 请求路径只发一条异步消息；**删掉 `NovelViewCounter` 与那个 30 秒定时回写**。单一写路径 |
| 3 | **进度上报也走 MQ，用来演示顺序消息** | 全项目唯一一条 ORDERLY |
| 4 | **网关不引 MQ 依赖** | 见 §五「为什么削峰不发生在网关上」 |

---

## 三、目标结构

```
backend/
├── pom.xml                                  ← ★ 加 <rocketmq-spring.version> + dependencyManagement
├── novel-common/                            ← ★ 一个 rocketmq 依赖都不加（关键）
│   └── com/novel/common/
│       ├── constant/MqConstant.java              ★ 新增
│       ├── constant/RedisKeyConstant.java        ← 加 MQ_IDEMPOTENT_PREFIX / NOVEL_STAT_AT；删 NOVEL_VIEWS / NOVEL_VIEW_DIRTY
│       ├── mq/AfterCommit.java                   ★ 新增（纯 Spring，无 MQ 类型）
│       ├── mq/MqErrors.java                      ★ 新增
│       ├── mq/ViewReportMessage.java             ★ 新增
│       ├── mq/ProgressReportMessage.java         ★ 新增
│       ├── mq/NovelStatMessage.java              ★ 新增
│       ├── mq/AuditRecordMessage.java            ★ 新增
│       ├── enums/NovelStatField.java             ★ 新增
│       └── internal/NovelCollectCountDTO.java    ★ 新增（对账用的入参）
├── novel-content/
│   └── com/novel/content/mq/
│       ├── producer/ViewReportProducer.java      ★ 新增
│       ├── consumer/ViewReportConsumer.java      ★ 新增（并发，只投递）
│       ├── consumer/NovelViewAggregator.java     ★ 新增（进程内聚合 + 5 秒 flush）
│       ├── consumer/NovelStatConsumer.java       ★ 新增（单调写）
│       ├── consumer/NovelStatWriteGuard.java     ★ 新增（Lua 原子的单调写守卫）
│       ├── consumer/AuditRecordConsumer.java     ★ 新增（SETNX 幂等）
│       └── service/NovelViewCounter.java         ← ★ 整个删除
├── novel-interaction/
│   └── com/novel/interaction/mq/
│       ├── producer/ProgressReportProducer.java  ★ 新增（syncSendOrderly）
│       ├── producer/NovelStatProducer.java       ★ 新增（提交后同步发）
│       ├── producer/CommentAuditProducer.java    ★ 新增（提交后发）
│       ├── consumer/ProgressReportConsumer.java  ★ 新增（★ 唯一 ORDERLY）
│       └── job/NovelStatReconcileJob.java        ★ 新增（对账，带锁）
└── novel-gateway/                            ← ★ 代码与依赖一行不改
                                                （只有 pom 里的**护栏注释**加宽了，见 §九 判断 F）
```

★ **消息载荷为什么放 `novel-common`**：它既是生产方的出参、又是消费方的入参 ——
和 `CommentBriefDTO` / `AuthorApplyAuditDTO` 一样，放 common 是**唯一不产生依赖的方向**。
（对照铁律 1 的 M1 表述：那条讲的是**业务语义接口**住在消费方；消息载荷是**数据契约**，不是接口。）

★★ **但 `novel-common` 的 `pom.xml` 一个 rocketmq 依赖都不加。**
载荷是纯 POJO、`MqConstant` 是纯字符串、`AfterCommit` 只用 Spring 的
`TransactionSynchronizationManager` —— 都不需要 RocketMQ 类型。
★ 这不是洁癖：**common 被网关依赖着**，往里加任何东西都是往网关上搬。
不加，网关的 classpath 就**结构上**不可能因为 M3 而变化（决定 4 的硬保证）。

### ★ 与大纲 §四 目录结构的已知偏差

| 大纲写的 | 实际 | 为什么 |
|---|---|---|
| 消费组 `group-interaction-stat` 同时写 `novel.view_count` 和 `read_history` | 拆成两个 Tag、两个消费组，各自只写自己服务的表 | 大纲 §三 关键约束 6 自己写着「服务之间不共享数据库表」，两条自相矛盾（见 §四 判断 A） |
| `Tag: VIEW_REPORT` 涵盖「阅读量 / 进度上报」 | 拆成 `VIEW_REPORT` + `PROGRESS_REPORT` | 同上：一个 Tag 一个消费组，而两个消费方是两个服务 |

---

## 四、★★ 四条被代码「逼」出来的设计判断（不是审美）

### 判断 A：大纲把「阅读量 / 进度」放在同一个 Tag、同一个消费组下 —— **这条实现不了**

大纲 §12.1 写的是：

```
├── Tag: VIEW_REPORT                阅读量 / 进度上报
│     └── 消费组 group-interaction-stat → 批量聚合后落库
```

★ 那个消费组要同时写 **content 的 `novel.view_count`** 和 **interaction 的 `read_history`** ——
而大纲自己写着「服务之间不共享数据库表」。

**落地：拆成两个 Tag、两个消费组，各自只写自己服务的表。**
★ Topic 仍是一个 `novel-topic`，**Tag 过滤在 Broker 端完成** —— 这正是大纲自己讲的优点：
一个 Tag 混两种消息，两个消费组都得在消费者里再判一次类型，就白丢了 Tag 过滤。

### ★★ 判断 D：**一个消费组只能有一个订阅 —— 我原来的表是错的**

我最初把 `VIEW_REPORT`（并发）和 `NOVEL_STAT`（顺序）都挂在 `group-content-stat` 上。
**这是错的，而且是静默错。**

RocketMQ 的**订阅关系是按消费组存在 Broker 上的**：同一个组内所有消费者的订阅必须**完全一致**，
否则后注册的那个会**覆盖**前一个 —— 表现是**某个 Tag 的消息再也收不到，且不报任何错**。

→ **每个 (服务, Tag) 一个独立消费组**：

```
group-content-view          ← content  ⟷ VIEW_REPORT
group-interaction-progress  ← interaction ⟷ PROGRESS_REPORT
group-content-novelstat     ← content  ⟷ NOVEL_STAT
group-content-audit         ← content  ⟷ AUDIT_RECORD
```

★ **content 有三个消费组，不是「一个服务一个组」** —— 这正是要防的事。

★★ 这个坑的形状**和 M2 那条 `4 < 5` 的网关路由不变式一模一样**：配错了不报错、只表现为「消息没到」。
**症状对照：Tag 收不到消息 → 先查有没有两个 Tag 共用一个消费组。**

### ★★ 判断 B：**「增量」与「绝对值」决定了要不要顺序消息 —— 这是本期的设计主线**

```
Tag                载荷      消费模式        防乱序靠什么              消费方
VIEW_REPORT        增量 +N   CONCURRENTLY    不用防（累加与顺序无关）   content
PROGRESS_REPORT    绝对值    ORDERLY         hashKey = userId           interaction
NOVEL_STAT         绝对值    CONCURRENTLY    载荷时间戳 + 单调写        content
AUDIT_RECORD       事实      CONCURRENTLY    SETNX 幂等                 content
```

★★ **一句话规则：推「增量」的可以乱序；推「绝对值」的，要么顺序、要么带时间戳做单调写。**
这条规则比大纲「同一用户进度不能乱序」的说法更根本 —— 它同时解释了为什么 `NOVEL_STAT` 也要防乱序，
而大纲 §11.2 的表里根本没提这条边。

### ★★★ 判断 B′（**已被实测推翻一半，请看完整的过程**）

**这是本期最该被记住的一节，因为它记的是我的错。**

**第一版推论**：「推绝对值 ⇒ 必须 ORDERLY」。评审推翻了它，依据是一条当时被认为是硬事实的性质：

> **ORDERLY 消费失败时不会重试到死信队列。** 它走的是 `SUSPEND_CURRENT_QUEUE_A_MOMENT` ——
> 消费者线程内按固定 1 秒间隔的本地重试；而 `maxReconsumeTimes` 在顺序消费下
> **默认是 `Integer.MAX_VALUE`**。也就是说它**实际上永不放弃**，
> `ProcessQueue` 永远不前进，**整个 MessageQueue 被一条毒消息永久卡死**，
> 积压只涨不掉，**而且永远进不了 `%DLQ%`**。

基于这条，方案改成：全项目**只留 1 条 ORDERLY**，`NOVEL_STAT` 改**应用层单调写**
（`UPDATE ... WHERE incoming.at >= 已存.at`）。理由包装得很漂亮：

> 「单调写比顺序消息更强：它把『传输层必须有序』降级成了『应用层不接旧值』这个不变量。」

★★★ **M3 实施时把这条性质实测了一遍，结果是：那句话把「默认值」当成了「必然」。**

```
实测（给 PROGRESS_REPORT 投一条 progress:"abc" 的毒消息）

  23:19:55.016  consume message failed ... reconsumeTimes:0
  23:19:56.115  consume message failed ... reconsumeTimes:1     ← +1.099s
  23:19:57.121  consume message failed ... reconsumeTimes:2     ← +1.006s
  23:19:58.129  consume message failed ... reconsumeTimes:3     ← +1.008s
  23:20:02      队列恢复，后续控制消息全部消费成功
  （日志里的消费线程名 ConsumeMessageThread_group-interaction-progress_3 → _4 → _5 → _6，
    证明重试确实是【本地】的，没有 Broker 往返）

  drain %DLQ%group-interaction-progress   →  MqProbe drain
    GOT tag=PROGRESS_REPORT reconsume=4 body={"userId":4,"novelId":2001,"chapterId":1,"progress":"abc",...}
    DRAINED total=1
```

**★ 那条毒消息现在就在 `%DLQ%group-interaction-progress` 里躺着，`reconsume=4`。**

**为什么？** 因为我们**显式**给四个消费者都配了 `maxReconsumeTimes = 3`。
顺序消费的 `Integer.MAX_VALUE` 只是**不写时的默认值**，显式写了就按写的算 ——
重试 3 次之后照常进 DLQ，队列照常前进。
**「永不放弃、永久卡死」这个可怕的性质，在本项目里从来没有生效过。**

#### 那么保留唯一的 ORDERLY 还剩什么代价？

**三条，都是真的，但都不是「不可恢复」：**

1. **队头阻塞** —— 这 4 秒里该 MessageQueue 上的消息**一条都过不去**（实测 55.016 → 58.129）。
   半径是 `1/队列数` = 1/4，因为有序的粒度是**单条队列内部**。
2. **失败重试固定在 1 秒**，没有并发消费那种 10s/30s/60s 的延迟阶梯 ——
   毒消息刷日志的频率更高。
3. **吞吐** —— 整条队列交给**一个**消费线程，不像并发消费可以横向铺开。

#### ★★ 结论怎么改？**结论没变，依据换了。**

- **原来**：「因为 ORDERLY 会永久卡死，所以 `NOVEL_STAT` 用单调写。」
- **现在**：「因为单调写**更便宜且已足够**，所以 `NOVEL_STAT` 不用 ORDERLY。」

`NOVEL_STAT` 仍然是 `CONCURRENTLY` + 单调写，`PROGRESS_REPORT` 仍然保留唯一的 ORDERLY。
**如果一个设计只有靠一条假的事实才站得住，那不是设计，是巧合。** 好在这次它站得住。

★★ **代码里 8 处写了那句假话，全部改掉了**（`MqConstant` / `NovelStatMessage` /
`RedisKeyConstant` / `NovelStatConsumer` / `NovelViewAggregator` / `ViewReportConsumer` /
`ProgressReportConsumer` / `application-dev.yml`）。
★ 这属于 CLAUDE.md 说的那类最难发现的注释：**结论还对、前提已经变了**。
★ 更值得记的是它**为什么能骗过我**：`maxReconsumeTimes` 的默认值这件事是真的，
「默认是 MAX_VALUE」在**不写的时候**确实等于永不放弃 ——
**我把「默认危险」直接推成了「必然危险」，跳过了「我们已经显式写了」这一步。**

### 判断 E：**ORDERLY 消费者里「永久失败」绝不能抛异常**

| 失败类型 | 例子 | 处置 |
|---|---|---|
| **永久失败**（重试一万次也一样） | `2001` 作品不存在、`3001` 章节不存在、载荷字段是 `null` | ★ **记 WARN，正常返回（= ACK）** |
| **瞬时失败**（基础设施） | DB 连不上、`DataAccessException` | **抛出去**（本地重试是对的） |

★ 直觉是「失败了就该重试」，于是把永久失败也抛出去 ——
**结果是把一条坏消息变成一条 4 秒的队头阻塞**（判断 B′ 修正后的说法；原来是「变成一条死队列」）。
它重试一万次也不会变好，只是白占着队头。

★ **必须显式配 `maxReconsumeTimes = 3`**，四个消费者都写了。
★ 顺带记一笔：**今天没有任何东西看 `%DLQ%`** —— 这是 M5 的监控债。
★ **生产者 / 消费者要配对**：`syncSendOrderly` + hashKey 发的 Tag，必须配 `consumeMode = ORDERLY`。
发的时候是并发的、消费却声明 ORDERLY —— **一点顺序都没有，而且静默**。

### 判断 C：**环 #1 的解法正好是「事务消息」，而且它顺带修掉一个真实存在的缺陷**

`AuditLogService.record` **刻意不加 `@Transactional`**，理由是「一条不真实的流水比没有流水更糟」——
它要加入调用方的事务。★ 但 M2 把这条接缝改成了 **Feign 调用**，而 **Feign 不参与调用方的事务**。
那句话的前提**静默失效**了：

> `AuthorApplyServiceImpl.audit()` 是 `@Transactional` 的 →
> 里面的 Feign 调用让 content **立刻提交**一条 `audit_log` →
> 如果 auth 这边随后回滚 → **content 里留下一条不会有人删的孤儿流水**。

**所以这不是「为了用 MQ 而用 MQ」**：RocketMQ 的**事务消息**是这条接缝唯一能恢复原子性的机制 ——
半消息先行、本地事务提交后才对消费者可见、生产端宕机由 Broker 回查。
它同时把 `novel-auth` 对 `novel-content` 的**唯一**一条出站边删掉 → **环 #1 断开**。

★ 附带好处：`CommentServiceImpl.audit` 事务里的两次 Feign 写全部移出 →
**两个 `@Transactional` 方法里远程调用归零**，R8 从「接受」变成「不存在」。

### ★★ 判断 C′：**事务消息的「教科书写法」在 Spring 事务里是有洞的**

最初的写法是「在 `@Transactional` 的 `audit()` 里调 `sendMessageInTransaction`，
在 `executeLocalTransaction` 里返回 `COMMIT`」。**这是错的，而且错得很隐蔽：**

```
sendMessageInTransaction 的顺序是：先发半消息 → 再执行 executeLocalTransaction → 返回 COMMIT 才可见
                  ★ 但 executeLocalTransaction 是在【外层 Spring 事务还开着、还没提交】的时候跑的
                  → 在这里返回 COMMIT，消息【可能先于 DB 提交】对消费者可见
                  → auth 随后回滚 → content 里留下一条「审批通过了」的流水，而审批根本没发生
```

★ 这正是「**一条不真实的流水比没有流水更糟**」要防的东西 ——
**用事务消息的本意是修掉孤儿流水，写成这样反而把孤儿流水变成了「内容错误」的流水，更坏。**

**采用的写法：**

1. `executeLocalTransaction` **一律返回 `UNKNOWN`**（不赌）。
2. 真正的 COMMIT 由 **`TransactionSynchronization.afterCommit`** 驱动 ——
   即「DB 真的提交了」那一刻才让半消息可见。
3. **`checkLocalTransaction` 真查库**（按 `applyId` 查 `author_apply` 的 `status` / `audit_user_id`）。

### ★ S7 的两个 spike 项：只能跑出来，读文档读不出来

**① `@RocketMQTransactionListener` 的绑定属性变了。**
这一版改成按**模板 bean 名**绑定（`rocketMQTemplateBeanName()`，默认 `"rocketMQTemplate"`），
**不再是 `txProducerGroup`**。

**② `TransactionSendResult` 会丢掉 `offsetMsgId` —— 返回值的类型本身就是个坑。**

这是本期花时间最多的一处。现象是 `afterCommit` 里发 `endTransaction` 时报：

```
error=port out of range:2103716004
```

★ **`2103716004` 不是端口号，是 `7D6428A4` 这个十六进制串被当成十进制整数解析的结果。**
它是 `offsetMsgId` 的**后半段**，而 `offsetMsgId` 的完整形状是：

```
C0A801BD7D6428A418FC70DE9F1D0000
└──┬───┘└──┬───┘└──────┬───────┘
   │       │           └── UNIQ_KEY（客户端生成的唯一键）
   │       └── 存储偏移（十六进制）
   └── Broker 地址 C0A801BD = 192.168.1.189
```

**根因**：`sendMessageInTransaction` 返回的 `TransactionSendResult` **丢掉了 `offsetMsgId`**
（它只有 `msgId` = UNIQ_KEY）。客户端自己的 `endTransaction` 之所以能工作，
是因为它**还持有 native 的 `SendResult`**；我们只拿到包装过的那个，于是 `endTransaction`
拿 UNIQ_KEY 去当 `offsetMsgId` 解析 → 报「端口越界」。

**修法**：**手动发半消息**，让 native 的 `SendResult` 活到 `afterCommit`。
★ 副作用：`executeLocalTransaction` 现在是**死代码**，永远返回 `UNKNOWN` ——
这不是疏漏，是这条路径唯一正确的形态（判断 C′ 第 1 条）。**代码注释里写清了，别当成可以清理的残留。**

**③ 两条不对称分支**（刻意不对称，不是不一致）：

| 失败 | 处置 | 为什么 |
|---|---|---|
| 半消息发送失败 | **抛出去**（回滚事务，返 500） | 消息根本没发出去，本地事务不该提交 |
| `endTransaction` 失败 | **吞掉 + WARN** | 半消息在那儿，Broker 回查会自愈 —— 抛出去反而让一个已提交的事务报 500 |

★ 实测：`23:35:04.375 半消息裁决已发出 | state=COMMIT_MESSAGE`，**没有** `半消息裁决发送失败` WARN →
`port out of range` 那个修法生效了。

### 判断 F：**网关那条护栏 grep 抓不到 RocketMQ —— 必须把它加宽**

网关 pom 里原有的回归命令是：

```bash
mvn -f backend/novel-gateway/pom.xml dependency:tree | grep -E "spring-boot-starter-web:jar|spring-webmvc|tomcat-embed-core"
```

★★ **这条 grep 对 RocketMQ 完全免疫** —— 它只认 webmvc 三件套。
而 RocketMQ 带给网关的**真正危险不是 webmvc，是 `netty-all`**：
`rocketmq-client` 会把 `io.netty:netty-all` 拖进来，而网关跑的是 Boot 托管的**逐个 `netty-*` 模块**。
两者混用是 `NoSuchMethodError` 的经典来源 —— ★ 而且**是运行时炸、不是启动时炸**。

★★★ **但 M3 实测发现：这条护栏有【两种】「看起来也对、其实什么都验不到」的写法，都被写进注释了：**

| 反例 | 为什么验不到 |
|---|---|
| `mvn -f backend/novel-gateway/pom.xml dependency:tree`（**M1~M2 就是这么写的**） | `novel-common` 从没 install 进本地仓库，Maven 只打一句 `The POM for com.novel:novel-common:jar:1.0.0 is missing` 就把它当**叶子节点**列出来 —— 子树一个字都不打印。**退出码 0、BUILD SUCCESS、零命中。** 把 pom 里那条 `<exclusions>` 删掉它照样「通过」 |
| 跑聚合 POM 但不做**段落切分** | 那三个 jar 在 `novel-common` 的子树里**确实存在**（实测 **3 命中**），它们正是被 `<exclusions>` 挡在外面的 |

★ **一条不会失败的护栏比没有护栏更糟** —— 它给人「已经验过了」的错觉。
★ 而反例二那 **3 命中就是这条护栏的阳性对照**：它证明这条管道有能力失败，而不是只会打印零。
★ 修正后的命令（依赖 reactor 顺序，`sed` 那句也把这个前提写在注释里了）：

```bash
mvn -f backend/pom.xml dependency:tree > tree.log
sed -n '/@ novel-gateway /,/@ novel-auth /p' tree.log \
  | grep -E "spring-boot-starter-web:jar|spring-webmvc|tomcat-embed-core|rocketmq|netty-all"
```

★★ 另有一条必须说清的：`RocketMQTemplate` 需要的 `spring-messaging` **不会**拖 `spring-webmvc` /
`tomcat-embed-core`，所以**别把「Spring MVC found on classpath」那句报错当成 RocketMQ 的安全网** ——
它不会响。

★ **网关这一期唯一的改动就是这个 pom 里的护栏注释**（代码、依赖、`application.yml` 一行未动）。
这本身是个可以说出口的设计判断，而不是「忘了网关」。

### 判断 G：**削峰为什么不在网关上**（决定 4 的展开）

大纲 §11.2 把阅读量的生产者写成 **`gateway/content → interaction`**，留了两种可能。我们选 **content**：

1. ★★ **网关是 Netty event loop**。`JwtAuthGlobalFilter` 已经因为「同步调 Redis」自记为 M5 债。
   一次 MQ 发送是**到 Broker 的网络往返（几十毫秒）**，比那次 Redis 调用还贵一个数量级。
2. ★ **网关会变成业务感知**。要它认 `POST /api/novels/*/view` 就得在 `GlobalFilter` 里写业务路径匹配 ——
   与 M1 定下的「网关只做路由 + 认证」直接冲突。
3. ★ **网关的 pom 有一条不能碰的红线**（见判断 F），为一个埋点去赌一个新依赖的传递树性价比是负的。
4. ★ 消费方是 **content**（`novel.view_count` 是 content 的表），生产者放网关只是把一跳变成两跳。

---

## 五、逐条改造

### ① 阅读量

**改造前**（`NovelViewCounter`）：请求路径 `INCR novel:novel:views:{id}` + `SADD novel:novel:view_dirty`；
`@Scheduled(fixedDelayString="PT30S")` 里 `SREM 认领` → `GETDEL 取数` → `UPDATE novel SET view_count = view_count + ?`。

**M3**：请求路径只发一条**异步消息**；content 侧 `ViewReportConsumer` 并发消费，
进程内 `ConcurrentHashMap<Long, LongAdder>` 累加，`@Scheduled` 每 5 秒 flush 一次。

★★ **三条必须做对、做错就是静默 bug 的地方：**

1. **发送必须被 `try/catch(Exception)` 整个包住，并且带 `SendCallback`。**
   `asyncSend` **会同步抛异常**（producer 没启动、队列满了），而 `ReaderNovelController`
   只 catch `BusinessException` —— 裸调会让这个「永远返回 200」的接口**多出一条 500 路径**。
   ★ 另一半：**不带 callback 的异步失败是静默的**，增量悄悄消失、连日志都没有。
2. **flush 必须用「原子交换」而不是「求和再删」**：
   ```java
   // ✗ 错的：sum() 与 remove() 之间落进来的 add() 会加到一个已经被丢弃的 adder 上，永久丢失
   // ✓ 对的：compute() 在 map 的每键锁内原子完成「取走旧的、装一个新的」
   map.compute(id, (k, old) -> { if (old != null) swapped.add(old); return new LongAdder(); });
   ```
   ★ 这和 `NovelViewCounter` 当年用 `SREM 认领` + `GETDEL` 解决的是**同一类竞态** ——
   老代码在这个点上是对的，别在改写时把它写丢。
3. ★★ **这样反而不需要分布式锁**：每个实例只 flush **自己进程内**那份 map，
   `group-content-view` 是单组（一条消息只投给一个实例），而 `view_count = view_count + ?` 是**可加的** ——
   多实例各加各的，加起来正好对。**比原来那套（计数在共享 Redis 里、必须 SREM 认领）还简单。**
   ★ 反过来说：**这就是阅读量必须保持「增量」、而收藏数必须保持「绝对值」的原因。**

★★ **诚实记一笔（这一期唯一一处「退步」）**：内存聚合意味着**进程崩溃会丢掉最多 5 秒的增量**；
改造前的 Redis 脏集不会。而且评审补的一刀更准：**老设计是自愈的** ——
计数在 Redis 里是累积的，「这本书下次有人读时会重新 `INCR`，`GETDEL` 会把积压的一起捞出来」。
**内存 `LongAdder` 没有这个性质**：每条浏览都是独立的一次 `+1`，没有「待补的余额」可以再捞一次。

**处置**：加 `@PreDestroy` **关停排空**，把丢失窗口从「随时最多 5 秒」压到「`kill -9` 才有」。
★ 换回来的是：改造前 **DB 一挂，flush 那一批就永久丢了**（`GETDEL` 已经把值取走，只记一条 WARN），
而 MQ 有重试与死信。**两边的失血点不一样。**

★★ **注释要重写，不是删掉。** `NovelViewCounter.record` 的兜底理由是
「只用 Redis，**不碰数据库** —— 这是这个接口能匿名开放的前提（不落库、不产生数据、不记录调用者）」。
**走 MQ 之后这个前提是假的了**：接口确实产生了持久数据（一条消息，在 Broker 上留 48 小时）。
理由换成「**匿名接口只允许产生可丢弃的、不含调用者信息的消息**」—— 结论不变，前提变了。
★ 同类的一处漏网之鱼在 `ReaderHistoryController:29`（不在原计划的清单里，是收尾时扫出来的）：
那句「只 INCR 一个 Redis 计数器」也变成了假话。**这是本期唯一一处「计划外」的注释修正。**

### ② 阅读进度（唯一的顺序消息）

**改造前**：`HistoryServiceImpl.report` = `requirePublished`（Feign 读）+ `requireChapterStatus`（Feign 读） + 本地 upsert。

★★ **修正：校验留在生产者，只有 upsert 搬到消费者。** 两条理由：

1. **那两次校验是有信息量的错误码，不是纯粹的内部检查。** `2001` / `3001`
   是**抗探测**设计：「上报成功 / 失败」的差别本身就是一把探针，能反推出一本未上架的书里有哪些章节 id。
   全变成 200 之后，**这把探针连合法读者一起废掉了**：读者拿过期链接点进草稿章节，
   原来拿到 3001，现在拿到 200、进度被静默丢弃、历史列表里没有这本书 ——
   客服听到的是「阅读历史没记住」，**而服务端日志里什么都没有**。
2. ★ **时间戳会漂。** 消费者里 `upsert(..., LocalDateTime.now())` 拿到的是**消费时刻**，
   而 `idx_user_time` 正是阅读历史的排序依据。一有积压，列表顺序就和事实不符。
   **必须在生产者盖时间戳、随载荷带走。**

★ 所以「请求路径三跳归零」要改成更准的一句：
**请求路径上的【写】从「两次远程读 + 一次本地写」变成「一次投递」**，两次读保留。
**削峰削的是写热点，不是读。**

★ 安全边界跟着移动：MQ 之后 userId 来自载荷 —— **可以接受，但前提是生产者是唯一的写入者、
且绝不接受请求体里的 userId**。这条写进了生产者代码的注释。

### ③ 收藏数 / 评分（修 R8 / R9）

**改造前**：
- `ShelfServiceImpl.add/remove` → 本地 `COUNT(*)` 重算 + Feign 写回 content。**不在事务里** → R9
- `CommentServiceImpl.delete` / `audit`（都 `@Transactional`）→ `AVG` 重算 + Feign 写回。**在事务里** → R8

**M3**：两处都改成发 `NOVEL_STAT`，消费者在 content 侧写 `collect_count` / `score`。
- R8：两个事务方法里的远程调用归零
- R9：失败进重试 → 死信，不再静默停在旧值
- ★ 幂等**天然**：推的是**绝对值**

★★ **必须配套的两件事（缺一个就是新的静默 bug）：**

1. **给 `ShelfServiceImpl.add/remove` 加 `@Transactional`** —— 改造前**没有**。
   坏处：Feign 写失败时书架行**已经提交**了，调用方却拿到 500 ——「报错」和「没做成」说的是两件相反的事。
2. ★★ **发送必须放在「提交之后」，helper 要处理「没有事务」的分支**：
   ```java
   if (!TransactionSynchronizationManager.isSynchronizationActive()) send();
   else registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { send(); }
        });
   ```
   ★ **这个 if/else 写反了，两个分支里必有一个【永远不发消息】，而且不报错。**
   helper 是 `novel-common` 的 `AfterCommit`，签名收一个 `Runnable` ——
   **纯 Spring，不带任何 RocketMQ 类型**，这样 common 的 pom 依然一个 rocketmq 依赖都不用加。

★ `remove` 继续跳过 `requirePublished`（下架的书也要能移除）—— 这是刻意的，别顺手「修」。
★ `ShelfStatusVO` 里的收藏数现在变成 **interaction 的本地值**，同步到 content 是异步的 ——
**字段形状没变，新鲜度变了。**

### ★★ 一处刻意的不对称：`CommentAuditProducer` 走普通消息，`AuthorApplyAuditProducer` 走事务消息

它们发的是**同一个 Tag**（`AUDIT_RECORD`），但发送方式**故意不一样**：

| 发送方 | 方式 | 为什么 |
|---|---|---|
| `AuthorApplyAuditProducer`（auth） | **事务消息** | 它在 `@Transactional` 的 `audit()` 里，只能用事务消息把「本地事务提交」和「消息可见」绑在一起 |
| `CommentAuditProducer`（interaction） | **`AfterCommit` + 普通消息** | 它的事务很短，且漏发的后果弱一档（一条审计缺失 vs 一条内容错误的流水） |

**★ 不要因为「这条消息是流水」就以为它一律走事务消息。** 差别不在「谁更重要」，
而在**发送方在不在一个事务里**。完整对照写在两个 Producer 的类注释里。

### ④ 审核流水（事务消息，断环 #1）

**改造前**：`AuthorApplyServiceImpl.audit()`（`@Transactional`）→ `AuthorApplyAuditRecorder`
→ `ContentAuditClient`（`@FeignClient(name="novel-content")`）→ content 写 `audit_log`。

**M3**：auth 侧改成发**事务消息**；**删除 `ContentAuditClient`**。
- ★ 验证环断掉的机械证据见 §十 结构回归 3。

★★ **坑 1：消费者会「每一条消息都抛异常」—— 而且绝不能靠伪造 `UserContext` 解决。**

`AuditLogServiceImpl` 是 `entity.setOperatorId(UserContext.requireUserId())`，
而 `requireUserId()` 在 `UserContext` 为空时**抛 `BusinessException(UNAUTHORIZED)`**。
消费者线程上**永远没有** `UserContext`。

**做法（★ 不要 `UserContext.set(...)`）：**
- 载荷带 `operatorId`；**给 `AuditLogService` 加 `record(..., Long operatorId)` 显式重载**。
- ★★ **不要伪造一个 `LoginUser` 塞进 `UserContext`。** 消费者线程池是**复用的** ——
  `set()` 不配 `clear()` 就是「张三登录着，李四的请求以张三身份执行」那个 bug 的原样复现。
  更要命的是：伪造之后 `requireUserId()` 会**开始「正常工作」**，
  把「这里根本没有调用方」这件事藏起来 —— **那比抛异常更坏。**

★ **`AuthorApplyAuditDTO` / `CommentAuditLogDTO` 的类注释花了大篇幅论证**
「加一个 `operatorId` 字段会把『操作人不可伪造』这条不变量变成一个纯靠调用方自觉的约定」。
**那个论证对 HTTP 跳是对的，对 MQ 跳是空的**（它依赖存在第二条不可伪造的通道）。
**没删那段注释** —— 改成写清「约束的载体从『能不能调 `/internal/**`』换成了
『能不能往 `novel-topic` 发消息』，两者都已经在信任边界之内，
所以不变量没有变弱，只是执行机制变了」。这两个 DTO 本身随端点一起删了，
论证迁到了 `AuditRecordMessage` 的注释里。

★★ **坑 2：幂等。** `audit_log` 没有唯一键，而 `AuditLogService` 写着「一条不真实的流水比没有流水更糟」——
这是四条消息里**唯一**需要显式幂等的：消费前 `SETNX mq:idempotent:{messageKey}` EX 24h。

★★ **坑 3：删 `ContentAuditClient` 的爆炸半径比「删一个 client」大。**

| 要动的 | 为什么 |
|---|---|
| `AuthorApplyAuditRecorder` **接口本身** | ★ 它在**铁律 1 里被点名是一个「被认可的特例」**。删掉它 = **改了一条写在 CLAUDE.md 和文档里的架构不变量** → **CLAUDE.md 与相关 docs 必须同步改** |
| `POST /internal/author-apply-audit-logs` 端点 | 没有调用方了 |
| `CommentAuditLogDTO` + `POST /internal/comment-audit-logs` + `InternalAuditLogController` | ★ **评论那条也必须一起走 MQ**，否则会留下**两条并存的审计路径** |

★ **但 `/internal/**` 没有整体删掉**：`InternalUserController` / `InternalContentController` /
`InternalCommentController` / `InternalAuthorApplyController` 都还在服役。
→ **`ContentSecurityConfig` 里 `/internal/**` 的白名单、以及过滤器里的 `/internal` 403 闸门，都保留了。**

### ⑤ 对账补偿任务（`NovelStatReconcileJob`）

大纲 §11.3 坑 2 要求「MQ + 最终一致性 + **定时对账补偿任务**」。特殊约束是：
`user_shelf` **没有 `update_time` 列**，且删除是**物理 DELETE** ——
所以「找最近变动的 novel」这条路走不通，必须走**绝对真值**。

**做法**：每 10 分钟 —— interaction 本地 `SELECT novel_id, COUNT(*) FROM user_shelf GROUP BY novel_id`
取真值，再**读 content 当前存的非零统计**（内部端点，分页 + 上限 + WARN），两边做**并集**，
不一致的重推绝对值。

★★ **为什么「并集」能覆盖「物理删除」这个坑（这一点不显然）：**
一次移除在 interaction 自己的表里**不留任何痕迹** —— 按「找最近变动的 novel」去扫是**扫不到的**，
而 R9 要修的恰恰就是「移出时忘了减」。
★ 成立的依据是：**对账的锚点不是「谁最近动过」，而是两边的【绝对值】本身**。
interaction 侧 `GROUP BY` 在最后一行被删掉后就没有这个 novel 了（真值 0），
而 content 侧的 `collect_count` 还是旧的非零值 → **并集把这个 novel 捞出来 → 重推 0**。
**关键在于读了 content 那一侧的「非零集合」** —— 少了这一步，就真的只能看见新增、看不见移除。

★★ **两处按项目自己的规矩必须做的：**
- **加锁**：`SchedulingConfig` 写着「**计算型**没有天然互斥点，**必须自己加分布式锁**」。
  这个对账任务是计算型 → `RedisKeyConstant.LOCK_PREFIX` + `SET NX EX`。
- **绝不能调 `ShelfService`**：它每个公开方法第一行都是 `UserContext.requireUserId()`，
  定时任务线程上没有 `UserContext`，**一调就抛**。**走 mapper。**

★ `%DLQ%` / 死信 / 一次性对象清理见 §十一。

---

## 六、`broker.conf` 的唯一改动：把队列数写死

`broker.conf` 原来开了 `autoCreateTopicEnable` 与 `autoCreateSubscriptionGroup`，
但**没有 `defaultTopicQueueNums`** → `novel-topic` 会拿 RocketMQ 的**默认 4 个队列**。

★ 对 ORDERLY 的 `PROGRESS_REPORT` 来说，4 个队列意味着「每用户内部有序」成立，
而队头阻塞的影响半径是 1/4。**这个数目本身没问题，问题是它是隐式的** ——
「顺序消息」的讨论**每次都要说清队列数**，而它不该藏在默认值里。

→ **显式写 `defaultTopicQueueNums = 4`，并把「4」写进 `MqConstant` 的注释。**
★ 顺带记：关掉两个 auto-create 开关之后，**队列数就必须在建 Topic 时显式指定**，
而 ORDERLY 的设计依赖「队列数是已知的」。

---

## 七、RocketMQ 的依赖与版本（已查证，不是推测）

### 选型：`org.apache.rocketmq:rocketmq-spring-boot-starter`，版本 **2.3.6**

`spring-cloud-alibaba-dependencies:2023.0.3.2` **确实管着 RocketMQ**（四条），
**但它【不】管 `rocketmq-spring-boot-starter`**，而那才是 `RocketMQTemplate` /
`@RocketMQMessageListener` 的出处。更关键的是：**SCA 那条路（Spring Cloud Stream binder）
【不支持事务消息】** —— 而断环 #1 靠的**正是**事务消息。所以**必须自己管版本号**。

| 事实 | 结论 |
|---|---|
| 本机镜像仓库最新 release | **2.3.6** |
| Spring Boot 3 支持下限 | **2.2.3**（2.2.3 起才改用 `jakarta.*`） |
| ★★ Boot 3.2 + JDK17+ 的已知 bug | `NoClassDefFoundError: .../protocol/heartbeat/MessageModel`，**2.3.2 起才修** |

### ★★ 一个必须避开的命名坑：属性名**不能**叫 `rocketmq.version`

SCA BOM 内部**已经有一个同名属性** `rocketmq.version = 5.3.1`。
Maven 的规矩是「**import 一个 BOM 只带版本、不带它的 `<properties>`**」，
所以写 `rocketmq.version = 2.3.6` **不会**改变 `rocketmq-client` 的实际版本 ——
**但会严重误导读者**，而且下一个人照着这个名字往 `dependencyManagement` 里写一条
`${rocketmq.version}` 去锁 `rocketmq-client`，就会**静默地把客户端锁到一个不存在的 2.3.6**。

→ 属性名用 **`rocketmq-spring.version`**，注释里写清了上面这段。

★★ **一个白拿到的事实**：`rocketmq-client` 的实际版本 = **5.3.1**，
正好等于 `docker-compose` 里 broker 镜像的版本 ——
因为**我们 import 的 SCA BOM 优先级高于 starter 传递进来的版本**。
★ 所以**不要**手动声明 `rocketmq-client` 依赖，让 BOM 去管。

### 留下的两个真实代价（写进文档，不藏）

- **`rocketmq-rocksdb`**：client 5.x 会传递带进一个 RocksDB 原生依赖。
  starter 的 parent 在 `dependencyManagement` 里写了排除，**但 `<exclusions>` 在
  `dependencyManagement` 里是不生效的** —— 它会实打实进 fat jar。
  ★ **如果打包体积意外变大，第一嫌疑就是它。**
- **`spring-boot-starter-validation`** 是这个 starter 的**非 optional** 依赖。
  ★ 这一条**已查证是无害的**：auth / content / interaction 三个 pom **本来就各自显式声明了它**。
  所以它不是新增能力、**不会让任何原本被忽略的校验注解突然开始生效**
  （那会是一次静默的接口行为变更 —— 加依赖之前必须先查这个，查完才能说「无害」）。

### ★ S1 是一次「探针」，不是一次「改 pom」

`rocketmq-spring-boot` 的编译基线是 **Spring Boot 2.7.18 / Spring 5.3.27**。
传递依赖会被我们的 Boot 3.2.12 拉回 6.1.x，但**运行时有没有残留的 Boot 2 假设，
无法从 POM 读出来**。

→ **S1 的完成定义是**：起一个服务 → 发一条消息 → **消费者真的收到并落库**。
★ 实测：通过（四端全部起得来，消息收到并落库）。

---

## 八、实施顺序（每步独立可验证）

| 步 | 内容 | 结果 |
|---|---|---|
| **S0** | 起 `rmqnamesrv` + `rmqbroker`；`broker.conf` 补 `defaultTopicQueueNums` | ✅ `grep "boot success" broker.log` 通过（`docker compose ps` 显示 `Up` **不算数**） |
| **S1** | 父 POM 加版本管理；三个模块加依赖；网关与 common 不加 | ✅ 探针：消息收到并落库；四端全起得来；网关依赖树加宽版 grep **0 命中** |
| **S2** | `MqConstant` + 四个消息 DTO 进 common；`RedisKeyConstant` 加前缀 | ✅ 编译绿 |
| **S3** | **阅读量**：producer + consumer + aggregator + **删 `NovelViewCounter`** | ✅ 秒级可见；停 broker 仍 200 |
| **S4** | **进度**：producer（顺序）+ consumer | ✅ 乱序注入不覆盖新值 |
| **S5** | **收藏数**（R9） | ✅ 并发 add/remove → 最终 = 真值 |
| **S6** | **评分 + 评论审核流水**（R8） | ✅ 两个 `@Transactional` 方法体里远程调用为 0 |
| **S7** | **环 #1**：auth 事务消息 + content 消费者 + 删 `ContentAuditClient` | ✅ `grep` → 0 命中；正向链通，194/178ms 级 |
| **S8** | 对账任务 + content 内部统计端点 | ✅ 手工改错 → 一个周期内自愈（实测见 §十一） |
| **S9** | 全量回归 + 清理 + 压测 | ✅ 见 §十一 |
| **S10** | 文档 + 提交 `m3完成` | ✅ |

### ★ S9 的一处**计划外**发现

收尾扫「阅读量 + 30 秒 / 定时任务 / 计数器」时全仓 5 命中，4 处是合法的
（`AiController` 讲的是 AI 配额、两处是**刻意引用旧措辞来说明它现在是假的**、`项目大纲.md` 是规格），
**1 处是真的过时**：`ReaderHistoryController:29`。已改（见 §五 ①）。

---

## 九、踩坑记录

### 坑 1 ★★ 最贵的坑：`mvn dependency:tree -q` 把输出整个吞了

`-q` 在 `dependency:tree` 上**不是「安静一点」，是「什么都不打印」**。
于是**网关的护栏 grep 和 interaction 的阳性对照【同时】返回 0** ——
一个假阴性，和「命令写错了」长得一模一样。

★ 这正是项目自己的注释警告过的事：「没有这个对照，『0 命中』和『命令写错了』看起来是一模一样的」。
**修法**：不带 `-q` 重跑。结果：网关 108 行 / 0 命中；interaction **11 命中**
（`rocketmq-spring-boot-starter:2.3.6 → rocketmq-spring-boot:2.3.6 → rocketmq-client:5.3.1`）。

### 坑 2 ★★ 中间件虚拟机是「饿」的 —— 这条决定了后面所有的容错写法

实测特征：

| 指标 | 实测值 |
|---|---|
| Nacos 就绪 | **3.1 s** |
| MySQL `SELECT 1` 往返 | **194 ~ 295 ms** |
| `asyncSend` 失败原因 | `RemotingTooMuchRequestException (invokeAsync call the addr[192.168.127.137:10911] timeout)` |
| 同步 `send` 单次成功率 | **~50%** |
| `sendOneway` 吞吐 | **~4000/s** |

★ 那个 `RemotingTooMuchRequestException` **不是 Broker 拒绝，是客户端自己在
「获取 async 信号量」这一步超时放弃了**。这一点必须分清 ——
它意味着「消息丢了」和「Broker 过载」不是一回事。

### 坑 3 ★★ `asyncSend` 的异常消息**全是壳，真因在 `cause` 里**

对着 `MQClientAPIImpl$1` 的字节码读出来的映射：

| 底层异常 | 包装后的 `getMessage()` |
|---|---|
| `RemotingConnectException` | `"send request failed"` |
| `RemotingTimeoutException` | `"wait response timeout, cost=…"` |
| **其它全部** | `"unknown reason"` **+ 一段 FAQ 链接** |

★ 所以 `e.getMessage()` 基本没有信息量 —— 这就是 `MqErrors.describe()` 存在的原因
（它按 `cause` 链往下走，`MAX_DEPTH 5`，带自引用保护）。

### 坑 4 ★★ ④ 积压实验：2000 条消息 → 21 次 UPDATE（1 : 95）

数 DB 写入次数要**给 content 加 stdout 重定向再重启**（★ SQL 日志在 stdout 不在 `backend/logs/`），
然后 `grep -c "Preparing: UPDATE novel SET view_count"`。**不数出来就是编的。**

| 场景 | UPDATE 次数 | 证据 |
|---|---|---|
| 1000 条 / 1 个 novelId | **1** | `Parameters: 1000(Long), 2001(Long)`；`view_count` 4317 → 5317 = **恰好 +1000** |
| 1000 条 / 20 个 novelId | **20** | 每条 `Parameters: 50(Long), 91xx(Long)` |
| 合计 | **21**（基线 6 → 27） | 2000 / 21 ≈ **1 : 95** |

★★ **顺带发现（`blast` 压出来的）：`NovelViewAggregator` 用 `affectedRows` 当存在性判断。**
所以一个**不存在的 novelId** 仍然要花一次 DB 往返，日志是
`阅读量落库：目标作品不存在，本次增量丢弃`。
★ 那条 `落库完成` 的 INFO **只在至少有一次写入成功时才出现**；
`阅读量落库：无增量` 是 DEBUG —— 演示时要按这个说。

### 坑 5 ★★ `onException` 会把「丢消息」说大

压测里看到 **3982 次投递失败回调**，而 **2000/2000 个请求全部返回 HTTP 200**。
★ 两个数字都对，但它们说的不是同一件事：**前者是回调计数，后者是请求结果**。
把两者混着讲，就会把「埋点降级成功」说成「丢了 3982 条」。
★ 这条也是大纲 §12.2① 「丢消息」那个问题的实测答案：
**投递失败 ≠ 读者看到错误** —— 埋点接口的契约是「永远 200」。

### 坑 6 ★★ 两套日志文件名（Windows 陷阱）

M2 时期的进程写的是 `novel-<name>-stdout.log`，M3 的进程写的是 `<name>-stdout.log`。
glob `*-stdout.log` **会同时匹配到两套** —— 排查时会读到上一次运行留下的旧日志，
看起来像「刚改的代码没生效」。

### 坑 7 ★★ 直连与日志的两个既有陷阱（M1/M2 记过，本期又踩到）

- **`-WorkingDirectory` 必须是 `backend/` 不是 `backend/novel-xxx/`** ——
  `logging.file.name` 是相对路径，跟着工作目录走，**改了就静默换目录**。
- **`curl` 到 `localhost:8080` 曾两次静默没产出文件**，导致请求没带 token → 401。
  改用 Python `urllib` 的脚本（登录 + 审计在同一进程里）后正常。
- **审计端点是 `@PutMapping("/{id}/audit")` 不是 POST** —— 第一次 POST 拿到 405。

### 坑 8 ★ 假设错了用户 id

我假定 reader01 = id 3；实际是 **admin=1, reader01=2, reader02=3, author01=4**。
无害（两个 reader 同密码），一次性的那个用户拿到了 id **218**。

### 坑 9 ★★ 一个**不是** bug 的现象：对账任务把我注入的错值改回来了

我在 S8 把 `collect_count` 手工改成 50，之后它变回了 0 —— 一度看起来像单调写守卫失效。
**实际是 S8 的对账在正确工作**：

```
23:10:02.576  对账发现收藏数漂移，已重推 | novelId=2001 真值=0 content上报=50
```

单调写的结论不受影响（23:05:11 的日志记录了 `value=50 落库`）。
★ 教训：**在一个有自愈机制的系统里做「注入错值」的实验，必须知道自愈的周期**，
否则会把自己的成功当成失败。

### 坑 10 ★ `-q` 之外，还有一个「Maven 块缓冲」的老坑

Maven 的输出是块缓冲的，挂在后台时日志长时间不出来，看起来像启动卡死。
★ 这本身就是**不让 `mvn package` 走管道**的理由（`package` 完再跑 jar）。

---

## 十、验收证据（实测）

### ① 主链路

| 场景 | 期望 | 结果 |
|---|---|---|
| `POST /api/novels/2001/view` × 1000 | 秒级可见 | ✅ `23:25:33.647 阅读量落库完成 \| 作品数=1 增量合计=1000`；`view_count` **恰好 +1000** |
| 读者离开章节 → `POST /api/reader/history` | 200 | ✅ |
| 收藏 / 取消收藏快速交替 | `collect_count` = 真值 | ✅ 见 ③ |
| 点评审核通过 / 删除点评 | `novel.score` 正确 | ✅ |
| 作者申请审核通过 | `audit_log` 多一条，操作人**有值** | ✅ 见 ⑤ |

### ② 大纲 §12 的五个必问问题（逐条实测）

#### ① 丢消息

**停掉 broker → `POST /view` 仍 200。**
★ 更硬的证据是坑 5 那组数字：**3982 次投递失败回调，2000/2000 个请求全部 HTTP 200**。

#### ② 重复消费

同一条 `messageKey` 投两次：

```
23:03:09.061  审核流水已落库   | key=probe-dup-1790089377 ...
23:03:12.783  流水消息已处理过，跳过 | key=probe-dup-1790089377
```

→ `audit_log` **只多 1 行**。SETNX 幂等生效。

#### ③ 顺序 / 单调写

按**时间戳**乱序注入同一个 `novelId` 的三条 `NOVEL_STAT`：

| 注入顺序 | `at` | 结果 |
|---|---|---|
| 第 1 条 | 现在 − 300s，值 **99** | 落库 |
| 第 2 条 | 现在 + 120s，值 **50** | 落库 |
| 第 3 条 | 现在 − 600s，值 **7** | **被拒**（`统计值消息过旧，已丢弃`），再来一次仍被拒 |

→ 最终 `collect_count = 50`，**不是 7**。✔
★ 另有一条 `收藏数不是整数，已丢弃 value=3.7` —— 永久失败分支（判断 E）。

#### ④ 积压

见坑 4：**2000 条 → 21 次 UPDATE（1 : 95）**，零丢失。

#### ⑤ 事务消息

**正向**（审核通过）：

```
audit http=200 code=200                    整体 0.709 s
audit_log  id=391  operator_id=1（★ 非 null）target_type=4 target_id=30 action=2
author_apply.status = 1
sys_user_role: 218 → role 2
auth    23:35:04.375  半消息裁决已发出 | state=COMMIT_MESSAGE   ← 无「裁决发送失败」WARN
content 23:35:04.553  审核流水已落库
```

★ **「裁决 → 落库」178 ms。**
★ 对比：**修 `port out of range` 之前，靠 Broker 回查要 ~14 秒** —— 这就是那个修法的价值。
★ `operator_id=1` 非 null，把 R4 那条风险（消费者线程上 `UserContext` 为空）**处置掉了**。

**★★ 反向（回滚）—— 如实记录：这一方向【没有跑成】。**

不是「验了没通过」，是**在现有代码下无法诱发**：
`publishAuthorApplyAudit(...)` 是 `audit()` 方法的**最后一条语句**，
而在它之前的所有失败路径（`remark` 超长、`grantAuthorRole` 重复、乐观锁 `rows == 0`）
都发生在**半消息发出之前**。
→ 要有「发布之后才回滚」，必须**改代码**（在 publish 后面插一句抛异常），
那改的是被测对象本身，测出来的东西没有意义。

**所以这一条只能作为推理论证写出**：事务消息保证「本地事务提交 ⟺ 消息可见」，
回滚时 `afterCompletion(STATUS_ROLLED_BACK)` 发 ROLLBACK，半消息被删，消费者永远看不到。
★ **实施记录里明确标注它是「论证」而不是「实测」——不把没跑过的事说成跑过了。**

#### ★ 死信（并发专项）

`AUDIT_RECORD` 投一条 `operatorId:"abc"` 的毒消息 → 重试按 **10s / 30s / 60s 的延迟阶梯**
（`23:21:12.675 / :23.039 / :53.202 / 23:22:53.456`）→ 进 `%DLQ%group-content-audit`。
★ 保留期 **48 小时**（`broker.conf` 的 `fileReservedTime = 48`），不是网上常说的 3 天。

#### ★★ 毒消息（ORDERLY 专项）—— 本期最重要的实测

**实测结果与设计时的预期相反**，见 §四 判断 B′：**顺序消息重试 3 次后进 DLQ，队列恢复**。

| | 并发（`AUDIT_RECORD`） | 顺序（`PROGRESS_REPORT`） |
|---|---|---|
| 重试间隔 | **10s / 30s / 60s** 延迟阶梯 | **固定 1 秒** |
| 重试位置 | Broker `%RETRY%` Topic 往返 | **消费者线程内本地**（线程名 `_3→_4→_5→_6`） |
| 进 DLQ | ✔ `%DLQ%group-content-audit` | ✔ `%DLQ%group-interaction-progress`（`reconsume=4`） |
| 阻塞半径 | 无（并发消费不阻塞其他消息） | **该 MessageQueue 约 4 秒** |

★ 演示时按这张表讲 —— **它把「顺序消息为什么贵」从「不可恢复」修正成了「队头阻塞 × 3 条代价」，
后者才是真的，而且经得起追问。**

#### ★★ 消费组隔离（判断 D 专项）

四个消费组并存，四条 Tag 各自收得到（S3~S8 全程）。
★ 反证没做（要改代码把两个组改成同名），但**症状对照写进了 `MqConstant` 和 `CLAUDE.md`**。

#### ★ 顺序配对

`syncSendOrderly` + `consumeMode = ORDERLY` 两侧都配了，③ 的结果即为证据。

### ③ 三态与越权（★ 铁律 3）

| 场景 | 期望 | 结果 |
|---|---|---|
| 匿名 `POST /api/novels/1/view` | **200** | ✅ |
| 匿名 `POST /api/reader/history` | **401** | ✅ |
| 匿名 `GET /api/novels/1`、`/api/novels`、`/api/novels/rank` | **200** | ✅ |
| READER 打 `/api/admin/**` | **403** | ✅ |
| 业务失败（登录密码错） | **200 + code=1002** | ✅ |
| 伪造头：不带 token 附 `X-User-Id: 1` | **401** | ✅（M1 不变） |
| 直连 `:8082` / `:8083` 伪造 `X-User-Id`（不带内部令牌） | **401** | ✅ |

### ④ S8 对账的自愈（**意外拿到的证据**）

```
23:00:05 / 23:10:02 / 23:20:01        ← 10 分钟周期
23:10:02.576  对账发现收藏数漂移，已重推 | novelId=2001 真值=0 content上报=50    重推=1
```

★ 本来只是想验证「注入错值能自愈」，结果**顺手证明了 S8 的对账任务是活的**。

### ⑤ 结构回归

| # | 检查 | 结果 |
|---|---|---|
| 1 | 12 个锁定文件 md5 | ✅ 三端逐字节一致，`request.js` = `2047681401d85ed69cebbd069f4ee7dd` |
| 2 | 三端 `npm run build` | ✅ 三个 `dist/` 全部刷新，退出码 0 |
| 3 | `grep -rn "novel-content\|ContentAuditClient" backend/novel-auth/src/` | ✅ **0 命中**（环 #1 断链的机械证据） |
| 4 | interaction 里 `refreshCollectCount` / `refreshScore` / `recordCommentAudit` | ✅ 只剩生产者 |
| 5 | 网关依赖树（**加宽版**） | ✅ `spring-boot-starter-web:jar` / `spring-webmvc` / `tomcat-embed-core` / `rocketmq` / `netty-all` **全 0 命中** |
| 6 | `NovelViewCounter` 类与两个 key 常量 | ✅ 类文件与常量声明**已删**（见下面的偏差说明） |
| 7 | 种子基线 | ✅ 菜单 `READER 0 / AUTHOR 2 / ADMIN 15`，`sys_role_menu` **17 行**，账号 **1~4** 未动 |
| 8 | staged diff | ✅ 无密钥/密码；`novel.internal.token` 五处一致 |

★ **回归 6 的一处诚实偏差**：`grep NovelViewCounter` **不是 0 命中** ——
还有 11 处，全部是**注释里说明「那个类已经删掉了、论证搬到哪里」的迁移记录**
（`ContentApplication` / `SchedulingConfig` / `RedisConfig` / `NovelViewAggregator` /
`ViewReportProducer` / `RedisKeyConstant` / `CommentServiceImpl` / `novel-content/pom.xml`）。
★ 按 CLAUDE.md 的注释规矩，那是**搬迁**而不是残留 ——
**「留着死常量配着活注释」才是要避免的，这里是「删了常量、留下迁移说明」。**
★ 两条真正的机械判据（都通过）：**类文件不存在**、**常量声明不存在**。

★ 验收/回归一律跑在带时间戳的一次性对象上，结尾断言种子完好；
清理用**字面 id 的 `IN (...)`**，绝不用 `LIKE`（★ 铁律 5）。

### ⑥ 一次性对象的清理

实测断言：`seed_novels_12 = 12`、`users = 4`（1~4）、`role_menu_17 = 17`、
`audit_log = 0`、`author_apply = 0`、`READER 0 / AUTHOR 2 / ADMIN 15`。

★ **刻意留下、没有删的**：`audit:AUTHOR_APPLY:27/28` 与 `stat_at:1074/1075` 这两组 Redis key ——
是**活的业务状态 / 其它会话的残留**，约 22 小时后自然过期。**删它们才是越界。**

★★ **一件消不掉的残留，如实记录**：`%DLQ%group-content-audit` 与
`%DLQ%group-interaction-progress` 里各有一条毒消息，**没有 `mqadmin` 删不掉**。
它们在 48 小时后自动过期。★ **不藏** —— 这两条正是 §十 那两处实测的证据，
文档里点名说明比悄悄清掉更有价值。

---

## 十一、回滚

**M3 是单 commit。** 回滚 = `git revert`（或 `git reset --hard 5b40439`，即 M2 的提交）。

这套回滚**干净**，理由：

1. **没有任何 DDL、没有任何数据迁移。** 四条消息改的都是**已有的冗余列**
   （`novel.view_count` / `collect_count` / `score`）与**已有的行**（`read_history` / `audit_log`）。
2. **Redis 的 key 少了两个**（`NOVEL_VIEWS` / `NOVEL_VIEW_DIRTY`），但没有新增 ——
   回滚后旧代码会重新开始写它们，**不需要数据恢复**。
3. ★★ **回滚期间会丢一段阅读量**：MQ 里未消费的消息随 broker 保留 48 小时，
   但回滚后的旧代码不认识它们。**这是唯一一处不可逆**，且只影响阅读量（近似值）。
4. **前端 0 改动** → 回滚不需要动前端（那两处注释顺手改回即可）。
5. 步骤：停四个进程 → `git reset --hard 5b40439` → `mvn -f backend/pom.xml package`
   → 起四个进程 + 停 broker 容器。

---

## 十二、明确**没有**改的东西

| 不动的 | 理由 |
|---|---|
| **12 个锁定文件** | ★ 铁律 2 |
| **`novel-gateway` 的代码 / 依赖 / `application.yml`** | 决定 4。★ 唯一改动是 pom 里的**护栏注释**（判断 F） |
| **`docs/P1` ~ `docs/P6`、`docs/M0`、`docs/M1`、`docs/M2`** | 它们记录的是**当时的事实** |
| **`docs/docker/docker-compose.yml`** | ★ **零改动** —— 容器定义早就写好了，M3 只是**启动**它 |
| `SchedulingConfig` **类本身** | 保留（M4 的章节发布对账要用），只改注释 |
| `modules/**` 里其它业务逻辑 | 只允许「换接缝类型」，没有顺手重构 |
| `项目大纲.md:721`（阅读量 → Redis + 5 分钟批量） | **它是 M3 之前的规格，刻意留作「规格的当时形态」**，M3 的对照写在本文 |

### 需要改的文档与配置（M3 的「收拾」清单）

| 文件 | 改什么 |
|---|---|
| `CLAUDE.md` | ① 阶段行加 M3；② 环境段加 broker 的启动与验证；③ ★ 铁律补一条**消息可靠性分级**；④ 端口表加 9876 / 10911；⑤ 加 Tag / 消费组不变式与它的症状 |
| `README.md` | 进度表加 M3 行；中间件表把 RocketMQ 从「第二期」改成「已启用」 |
| `docs/环境配置.md` | 补「起 RocketMQ」的实际步骤与**三条坑**（uid 3000 / brokerIP1 / `boot success`） |
| `docs/部署.md` | 生产要加 `rocketmq.name-server`；★ **broker 必须先于服务启动**（或按 R3 的结论写） |
| `docs/mq-notes.md` | 加一节「M3 落地情况」：四个 Tag 的实际切分、与 §八 落位表的**偏差**（阅读量的消费方是 content 不是 interaction）、实测数字。**不重写**这份读物 |
| `reader-web/src/api/novel.js`、`ReaderNovelController` | 那两处「30 秒」已经变成假话，必须改（见 §五 ①） |

---

## 十三、记一笔（M4 / M5 债，M3 只记录不处理）

- ★★ **服务级环 #2 `novel-content ⇄ novel-interaction` —— M3 【没有】解掉。**
  「管理端审核日志要显示评论正文」是一次**同步读**，要解只能改成
  「审核时把正文快照写进 `audit_log`」（那是 DDL）→ **M5**。
  ★ M3 之后 interaction → content 的边从 **8 条降到 4 条左右**（删掉 3 条写回 + 1 条审核流水，保留读），
  但**环还在**。**别以为 M3 顺手解掉了。**
- ★ **`ShelfServiceImpl.add` 里仍然有一次 Feign 调用**（`requirePublished`），
  而 M3 刚给它加了 `@Transactional` —— 等于**新增了一次「事务里做远程调用」**。
  ★ 这是**明知而为**的：那是一次读，且它的错误码是有信息量的（同 §五 ② 的理由）。
  但**它把 R8 的形状从「写回」搬到了「前置校验」上** → **记为 M5 债**（要么加缓存，要么改成异步校验）。
- **`CHAPTER_PUBLISHED` 事务消息 + ES 索引重建** → **M4**
- **`COMMENT_SUBMIT` 异步敏感词审核** → E1
- **`AUDIT_RESULT` 延时消息（站内信）** → 需要先有站内信功能，暂无归属
- **`FeignIdentityConfig` 三份副本** → M5 治理
- **网关的同步 Redis 调用** → M5；**M3 没动网关**，这个债原样留着
- **没有任何东西在看 `%DLQ%`** → M5 监控债
- **`NovelStatWriteGuard` 的残留窗口**：单调写只能保证「不接旧值」，
  **不能保证「最新值一定到」** —— 最后一条消息丢了就是丢了，靠对账兜底。**记一笔。**
- ★★ **dev / prod 的 broker 配置差异**：`broker.conf` 现在是
  `ASYNC_FLUSH` + `ASYNC_MASTER`（换吞吐），而讲可靠性时生产要 `SYNC_FLUSH` + 主从同步复制。
  **面试被追问「你配的是同步刷盘吗」时要能直接答**：dev 是异步，生产要改。
- **`NovelStatReconcileJob` 的 `SCAN_PAGE_SIZE ≤ 100`**、**并集只能覆盖 `collect_count`**、
  **评分没有真值来源所以不做对账** —— 三条边界都写在那个类里了。
- ★★ **R3【未验】：broker 挂着时服务能不能启动，M3 没有实测。**
  M3 只验了「服务在跑时停掉 broker → 埋点仍 200」（§十 ②①）。
  「配了 `rocketmq.name-server` 而 broker 不可达 → autoconfiguration 会不会让服务起不来」
  **只能跑出来**，而这一期没跑。
  ★ **不要再把它当成已验证的事实引用** —— `CLAUDE.md` 与 `docs/部署.md` 里的措辞都已改成「建议」。
  ★ 补测方法：停 broker 容器 → 起 `novel-content` → 看是启动失败还是照常起。

---

## 十四、这一期最该记住的三句话

1. **★ 「默认值很危险」和「我们没配那个默认值」是两件事。**
   整个 ORDERLY 的设计论证建立在一条**没有生效的**性质上（§四 判断 B′）——
   而它之所以能骗过我，是因为**那半句是真的**：`maxReconsumeTimes` 在顺序消费下确实默认
   `Integer.MAX_VALUE`。我把「默认危险」直接推成了「必然危险」。
   **一条只有靠假事实才站得住的设计，必须被重新论证一遍。**

2. **★ 一条不会失败的护栏比没有护栏更糟。**
   `dependency:tree` 那条 grep 有两个「看起来也对、其实什么都验不到」的写法（§九 判断 F），
   其中一个**在仓库里存在了两期**。护栏的价值全在「它是机械的」——
   **所以加护栏的时候，必须同时准备一个【能证明它会失败】的阳性对照。**

3. **★ 削峰削的是【写】，不是读。**
   「请求路径三次跨服务调用归零」这句话是错的（校验的两次读是有信息量的错误码，必须留）。
   准确的说法是「请求路径上的**写**从『两次远程读 + 一次本地写』变成『一次投递』」。
   ★ 而这个项目里唯一「反正是近似值、丢了没关系」的数据只有**阅读量** ——
   其余三条都必须有重试、幂等或对账兜底。**能把可靠性分级的依据说出来，比四条都配最高可靠更可信。**
