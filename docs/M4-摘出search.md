# M4 · 摘出 search —— 上 Elasticsearch，搜索从 `MySQL LIKE` 换成倒排检索

> **一句话**：把「一次全表扫描式的 LIKE 查询」换成「一次倒排检索 + 一次主键批量查」。
> 顺手证明了两件事：**大纲里那个 `group-content-stat` 消费组根本不该存在**，
> 以及**索引重建这条消息【不需要】事务消息** —— 因为另一条设计（`status` 过滤 + 第二道闸门）
> 已经把它的价值吸收掉了。

---

## 一、验收标准（大纲原文）

`项目大纲.md:1303`：

```
| **M4 摘出 search**（2~3 天） | 上 ES，搜索迁出，用 MQ 消费小说/章节变更重建索引 | 搜索走 ES，索引能自动同步 |
```

### 一句话目标

**读者搜「剑冢」能搜到那本书，而这件事在 M4 之前是做不到的** ——
因为 M4 之前只搜书名和作者昵称，不搜简介。这条既是验收，也是「M4 到底有没有真的改变行为」的判据。

### 开局状态（开工前核实，全部有据）

| 事实 | 证据 |
|---|---|
| 搜索是 **MySQL `LIKE`**，一个 Controller + 一个 Service 方法 | `ReaderSearchController.java`（76 行）→ `NovelServiceImpl.search:197-272` |
| 底层是 **LambdaQueryWrapper** 拼的，**没有任何手写 SQL / XML** | `NovelMapper` 是空接口；`novel-content/src/main/resources` 下 0 个 `.xml` |
| 匹配范围 = **书名 + 作者昵称**，**不搜简介** | `NovelServiceImpl:255-256`（`like(title)` OR `in(authorId)`） |
| 作者名要走 **一跳 Feign**，且是**全表扫描 + `LIMIT 100`** | `ContentUserLookup.userIdsByNickname:160-167` → auth `InternalUserQueryService:154-169` |
| 排序是 **`view_count DESC, id DESC`**，**不是相关性** | `NovelServiceImpl:269`；注释自己预言「M4 上 ES 之后换成相关性打分」 |
| 网关里 `/api/search` **寄生在 `content-misc`** | `application.yml`，`Path=/api/categories,/api/search,/api/ai,/api/ai/**`，**order: 5** |
| 白名单在 content 的 SecurityConfig | `ContentSecurityConfig` 里 `GET /api/search` → `permitAll` |
| **ES 的 Java 侧是彻底的白地** | 全仓 pom 无 ES 依赖；无 `novel-search` 模块；无 index/mapping/分词器；无全量灌数据的设计 |
| ES 容器定义**早就写好了，只是没起过** | `docs/docker/docker-compose.yml`，`elasticsearch:8.13.4` 单节点、关安全、1g 堆 |
| ★ **但官方镜像不含 IK，也没有任何插件安装的载体** | `docs/docker/` 只有 compose / `.env.example` / `broker.conf`，**无 Dockerfile** |
| M0 **刻意没起它** | `docs/M0-骨架搭建.md:33`「不碰 RocketMQ / ES（那是 M3 / M4）」 |
| 接缝早就留好了 | `ReaderSearchController` 的类注释：「二期 M4 搜索会上 ES，那时它会变成一个真正独立的服务（`novel-search`），现在单独一个类正好是那个接缝」 |

---

## 二、已拍板的四个决定（开工前定的，实施中没动摇）

| # | 决定 | 后果 |
|---|---|---|
| 1 | **中文分词：自建 Dockerfile 装 IK** | `docs/docker/Dockerfile-es` 新增；compose 的 `es` 段 `image:` → `build:`。★ **这打破了 M3 文档里「那份 compose 零改动」的事实** —— 见下面「一个必须先说的偏差」 |
| 2 | **搜索字段加上简介** | `title` + `authorName` + `intro`（权重递减）。★ 行为真的变了：搜「重生」会多出只在简介里提过的书 |
| 3 | **ES 挂了降级回 MySQL LIKE** | content **原样保留**今天那个 `search()`，作为 `/internal/search` 暴露。★ 降级路径不是重写，是**原件续用** |
| 4 | **索引消息靠定时全量重建兜底，用 `AfterCommit` 发普通消息** | ★ **推翻 M3 文档 §十三 的记载**（那里写的是 `CHAPTER_PUBLISHED` **事务消息** + ES 索引重建 → M4）。理由见判断 6 |

### ★★ 一个必须先说的偏差：`docs/docker/` 不再是「零改动」

M3 的文档里有一句「那份 compose 文件零改动，只是第一次被启动」。
**M4 打破了它** —— 决定 1 要在 ES 镜像里装 IK，就必须有 Dockerfile，
于是 `docker-compose.yml` 的 `es` 段从 `image: elasticsearch:8.13.4` 变成了 `build:`。

★ 这不是「顺手改的」，是决定 1 的直接后果，**必须在文档里明说**，
否则「compose 零改动」会变成一个跨期失效却没人更正的陈述。

---

## 三、目标结构

```
backend/
├── pom.xml                                   ← ★ 加 <module>novel-search</module>
├── novel-common/
│   ├── vo/NovelVO.java                       ← ★ 从 content 移来（字段不动）
│   ├── constant/MqConstant.java              ← ★ 加 TAG_NOVEL_INDEX / GROUP_SEARCH_INDEX
│   ├── result/PageResult.java                ← ★ 删掉两个引用 IPage 的工厂（见 S6）
│   ├── result/PageResults.java               ← ★ 新增，零依赖装配工厂
│   ├── result/MybatisPageResults.java        ← ★ 新增，IPage 适配器
│   └── config/{MybatisPlusConfig,MybatisMetaObjectHandler,UploadProperties,ImageStorage,WebMvcConfig}
│                                             ← ★ 加条件注解（判断 10）
├── novel-search/                             ← ★ 全新模块 :8084
│   ├── SearchApplication.java
│   ├── config/                               ← SearchSecurityConfig / ElasticsearchConfig
│   ├── controller/SearchController.java            （搬来的那个，路径一个字没改）
│   ├── controller/AdminSearchController.java       （POST /api/admin/search/reindex）
│   ├── document/NovelDocument.java           ← 6 个字段
│   ├── service/SearchService.java            ← ES 检索 + 组装 + ★降级
│   ├── service/IndexService.java             ← ensureIndex / 重建 / ★dropQuietly
│   ├── service/IndexRebuildService.java      ← 全量重建
│   ├── client/ContentClient.java             ← ★ 接口定义在消费方（铁律 1）
│   ├── client/FeignIdentityConfig.java       ← 注册代码的第 3 份 → M5 债（★ 见 §十二 订正）
│   └── mq/consumer/NovelIndexConsumer.java
├── novel-content/
│   ├── controller/ReaderSearchController.java  ← ★ 整个删除（搬去 search）
│   ├── config/ContentSecurityConfig.java       ← ★ 白名单做减法（删 /api/search）
│   ├── service/NovelVos.java                   ← ★ 新增装配点（判断 7）
│   ├── service/impl/NovelServiceImpl.java      ← search() 保留但改成内部端点；
│   │                                             4 个落点加 indexNotifier（判断 2）
│   ├── internal/controller/InternalContentController.java  ← ★ 加 3 个端点
│   └── mq/producer/NovelIndexNotifier.java     ← ★ 新增（AfterCommit）
└── novel-gateway/                              ← ★ 只改 application.yml 的路由表
```

```
docs/docker/
├── Dockerfile-es            ← ★ 新增（FROM 8.13.4 + elasticsearch-plugin install analysis-ik）
└── docker-compose.yml       ← ★ es 段 image: → build:
```

---

## 四、★★ 判断（都是被代码「逼」出来的，不是审美）

### ★★ 判断 1：大纲里那条 MQ 设计是错的，M4 只有一个消费方

大纲 §11.2 / §12.1 把这件事设计成**一个 Tag、两个消费组**：

```
Tag: CHAPTER_PUBLISHED
  ├── 消费组 group-content-stat   → 更新小说冗余字段（word_count / chapter_count / last_update_time）
  └── 消费组 group-search-index   → 重建 ES 索引
```

**前半条是错的，而且错得很隐蔽。** 大纲给的理由是「冗余字段更新 + ES 索引重建，
**跨服务了**，一个事务罩不住」—— 但这句话只对后半句成立：

> `syncNovelStat(Long)` 读的是 `chapter` 表、写的是 `novel` 表，
> **两张表在同一个库、同一个服务、而且它自己就带 `@Transactional(rollbackFor = Exception.class)`**，
> 调用方也都在事务里。
> ★ **它今天的原子性是满分。** 改成「发消息 → 自己消费 → 再写」= 把一个原子操作**降级**成最终一致，
> 还额外引入「消息丢了就永久不一致」的新故障模式。**纯粹变差，换不到任何东西。**

→ **M4 只有一个消费方：search。** `group-content-stat` 这个消费组**不建**。

★★ **这与 M3 的判断 A / D 是同一类发现**（大纲把「阅读量/进度」放进同一个 Tag 同一个消费组，
而那个组要跨写两个服务的表 → 拆成两个 Tag）。**症状对照也一样：它不报错，只是设计里多了一个不该存在的环节。**
**M3 是「拆开」，M4 是「删掉」—— 方向相反，判据相同：先问那个数据到底在哪张表、谁的事务里。**

---

### ★★ 判断 2：Tag 要改名 —— 触发源远不止「章节发布」

大纲叫 `CHAPTER_PUBLISHED`，但**只做章节发布的话，新书上架后搜不到**（新书没有章节发布事件）。
把「发消息」挂在 `syncNovelStat` 的调用点上也是错的 —— **那是四个调用点，不是触发点全集**：

| 参与检索/排序的列 | 被谁写 | 经不经 `syncNovelStat` |
|---|---|---|
| `title` / `intro` | `NovelServiceImpl.update()` | **✗ 不经** |
| `status` | `NovelServiceImpl.transitStatus()`（状态的**唯一**写入口） | **✗ 不经**（只有 `target == PUBLISHED` 那一支才调 `syncNovelStat`） |
| `word_count` / `chapter_count` / `last_update_time` / `last_chapter_id` | `syncNovelStat()` 的四个调用点 | ✓ |
| `deleted` | `NovelServiceImpl.delete()`（逻辑删除） | **✗ 不经** |
| `authorName` | **auth 的 `sys_user.nickname`** | ✗ **content 根本不知道** |

★ **这张表 5 行，`syncNovelStat` 只有 4 个调用点，两者不是同一个集合。**
挂在 `syncNovelStat` 上会永久漏发三条：**下架的书在索引里永远是「已上架」、改名后搜新名搜不到、
逻辑删除只对 MySQL 生效**（`@TableLogic` 是 MyBatis 的 WHERE 注入，ES 不知道 `deleted` 这回事）。

**落地方案：Tag 改名 `NOVEL_INDEX`，载荷只有 `novelId`**（+ `reason` 供观测），
语义是「**这本书的索引可能过期了**」——**推事实，不推数据**。

★★ 于是它落在铁律 7 框架下的**第五种形态**，而且是最省的一种：

| 消息 | 载荷 | 靠什么保证正确 |
|---|---|---|
| `VIEW_REPORT` | 增量 `+N` | 累加与顺序无关 |
| `PROGRESS_REPORT` | 绝对值 | ★ ORDERLY（全项目唯一） |
| `NOVEL_STAT` | 绝对值 | 时间戳单调写 |
| `AUDIT_RECORD` | 事实 | SETNX 幂等 |
| **`NOVEL_INDEX`** | **只有一个 id** | ★★ **幂等天然、顺序无关、不需要 SETNX、不需要时间戳** |

★ 为什么这么省：**载荷里没有数据，消费者读的是当时的真值**。两条乱序的消息各自触发一次重建，
两次都读同一份当前状态、写同一个文档 → **结果一样**。重复消费也一样。
**这是四条消息里唯一一条「幂等免费」的 —— 因为它推的是「去重算一次」这个指令，而不是算好的结果。**

---

### ★★★ 判断 3：ES 当「id 检索器」，不当数据源

ES 只存**参与检索/过滤/排序**的字段，命中后返回**有序 id 列表 + total**，
再 Feign 调 content 的装配端点拿 `NovelVO`。

**四个理由，按重要性排：**

**① ★★ 第二道闸门 —— 这是安全性的，不是性能的。**
被删掉的 `ReaderSearchController` 里有一句不能违反的话（原文留档，因为那个类已经不在仓库里了）：

> **这个类的每一条查询都必须自己带可见性条件。** 它在 `SecurityConfig` 里是匿名放行的，
> 没有登录态可以兜底 —— **条件漏了就是直接对外的内容泄露。**

现在这个判断**只有一处**（`NovelServiceImpl` 里的 `.eq(Novel::getStatus, PUBLISHED)`）。
拆服务之后，「ES 里是脏的」和「用户搜到不该搜的」之间必须**仍然隔着一道闸门** ——
而 content 的装配端点**天然知道 `status`**，search **连 `status` 是什么语义都不需要知道**。
→ 于是**即使索引里有一条陈旧的已下架文档，用户也搜不到它**。

★★ **这条直接决定了两件事**：索引陈旧的方向从「**内容泄露**」降级成「**搜不全**」（可接受、可自愈），
以及 `status` 字段**必须同时进索引参与 ES 侧过滤**（判断 5）——
两道闸门都在，第二道才有意义（只有一道时，「陈旧文档」这个信号永远不出现）。

**②** `view_count` 每 5 秒被 M3 的 `NovelViewAggregator` 刷一次，`collect_count` / `score` 也被 MQ 刷 ——
**把它们放进索引 = 每 5 秒批量更新文档**。装配时从 MySQL 读，这些字段**永远是新鲜的真值**。

**③** 索引 mapping 从 ~15 个字段降到 **6 个**，同步面大幅缩小。

**④** ★ **这不是「选了一个方案」，是「只有一个方案能成立」**：search 装配 `NovelVO` 需要
小说表（跨库，不许）+ 作者昵称（在 auth）+ 分类名（在 content）。**它做不了。**
`NovelVO` 自己的注释就写着「为什么 `categoryName` / `authorName` 放在 VO 里而不是前端自己查」。

#### ★ 代价（诚实记一笔）

ES 给了 10 个 id，content 过滤掉 2 个 → **这一页只有 8 条**。
用户可能看到「共 1050 本」+ 某页不满甚至空。处置：

- `status` 进索引 + ES 侧 `filter` → 正常情况下第二道闸门**一条都过滤不掉**，页永远满
- content 侧过滤数 **> 0 时记 WARN**（文案：「索引里有不可见的书」= 索引陈旧的信号）
- ★★ 铁律 8：这条 WARN **必须有阳性对照** —— 结果见 S8。

---

### ★ 判断 4：排序换成相关性

`_score DESC, lastUpdateTime DESC, id DESC`。
`should` + boost：`title^3` / `authorName^2` / `intro^1`。

★ 代码自己预言并批准了这次变更（`NovelServiceImpl` 里那句「二期 M4 上 ES 之后，
这里会换成真正的相关性打分」）。**但预言的执行方式变了** —— 见下面判断 11 与「原件的续用」。
★ `lastUpdateTime` 兜底 + `id` 末位兜底 —— 照项目铁律「按时间倒序的列表一律带 `id DESC` 兜底」。
★ `lastUpdateTime` **会回退**（已发布章节改正文 → 自动退回草稿），**那是真值不是 bug**，文档写明。

---

### ★ 判断 5：`status` 必须进索引，且必须参与 ES 查询

`bool { filter: [term(status = 2)], must: [...] }`。
★ 只在装配端过滤（判断 3）会让 `total` **虚高** + 页内空洞 —— 两处症状叠加：
`search/index.vue` 把 `total` 直接渲染成「共 N 本」并绑给 `el-pagination`，
而 list 是被过滤后的残页 → **「共 1050 本」和「没有找到相关的作品」同屏**。
**两道闸门都要在。**

---

### ★★ 判断 6：用 `AfterCommit`，不用事务消息（推翻 M3 §十三）

仓内**已经有一条现成判据**，就写在 `novel-common/mq/AfterCommit.java` 的类注释里：

> **有对账兜底的用它（收藏数），无对账的用事务消息（审核流水）。**

M4 会引入**定时全量重建**（= 对账）→ 按这条判据应当用 `AfterCommit`。三条理由都成立：

1. **判据直接适用**，而且遵守自己写下的规则比违反它更有说服力。
2. ★★ **事务消息买到的东西已经被判断 3 + 判断 5 吸收掉了。** 它防的核心是
   「消息先于 DB 提交对消费者可见 → 索引里出现一个不存在的状态」—— 而 `status` 过滤 + MySQL 第二道闸门
   恰恰让这个方向的后果**完全消失**（最坏是索引里多一条草稿文档，用户看不见它）。**剩下的只有「提交后崩溃」那个窗口。**
3. ★★ **成本是真的，而且仓内已经为完全相同的理由付过一次。**
   `MqConstant` 的 `PRODUCER_GROUP = "novel-producer"` 是 content 与 interaction **共用**的，
   而紧跟其后有一条注释：**「它必须独立，不能和 `PRODUCER_GROUP` 共用一个名字」** ——
   那说的就是 M3 的 `PRODUCER_GROUP_AUTH_TX`。
   ★ **M3 之所以要单开一个事务组，理由与 M4 要付的代价一模一样**：
   走事务消息就必须新开专用事务组（`novel-tx-index`）+ 多养一个 `RocketMQTemplate` bean。
   ★★ **而这次有个 M3 没有的额外麻烦**：`novel-tx-audit` 在 **auth**，它的进程里只有那一个监听器、
   回查必然命中；`novel-index` 若开在 **content**，而 content 与 interaction 同处一个 `novel-producer`
   的实例池 —— 半消息可能被投给**没有 `@RocketMQTransactionListener` 的进程**，
   回查返回不了真值、半消息悬着**且不报错**。
   ★ **用一条已有判据 + 一条已有先例就否掉它，比「多养一个事务组而已」这种含糊的成本说辞扎实得多。**

★ **明确记一笔**：M3 §十三 记的是「`CHAPTER_PUBLISHED` 事务消息 + ES 索引重建 → M4」，
**M4 只兑现了后半句，前半句被推翻了**，理由如上。
★ 顺带一句更好的面试答案：**「什么时候【不】用事务消息」比「我会用事务消息」更难答。**

---

### ★ 判断 7：`NovelVO` 下沉 `novel-common`

search 要返回 `PageResult<NovelVO>`（前端契约不变）。`novel-common` **已经有 `com/novel/common/vo/`**
（`NovelBriefVO` / `ChapterBriefVO`），方向是既有的。

★ **不要用 `NovelBriefVO` 绕过去**：它的类注释明确排除 `viewCount / wordCount / isFinish / status`，
而搜索卡片三个都用。

★ **唯一的机械风险是 `of()` / `fillFrom()` 的签名**——它收的是 content 的 `Novel` 实体，下沉后不能留。
处置：**不改成位置参数**（15 个字段里相邻同类型的错位**编译期不报错、运行期静默填错**），
而是在 content 侧留**唯一装配点** `NovelVos`，用具名 setter。

改造面很小：`NovelVO.of(...)` 全项目**只有 4 处调用、全在 `NovelServiceImpl` 一个文件里**；
`NovelDetailVO extends NovelVO` 仍在 content，`fillFrom` override 改为调 `NovelVos.fill(this, ...)`。

---

### ★ 判断 8：不引入服务级环

- `search → content`（Feign 读）**单向**
- `content → search` **只有 MQ** —— 按 M3 判断 C 用的定义（**服务级环 = 同步调用的环**），MQ 不算边

★★ **这个定义 M4 写进了 `CLAUDE.md` 变成承重条款**（M3 声称断掉环 #1 时用的正是它，但没写进约定）。
★ 唯一的前提是**消费方必须是 search**：若让 content 消费再调 search 推文档，就造出了
`content → search` 的**同步**边 → **环**。

★★★ **绝不新增 `auth → search` 的边。** 把作者名索引进 ES 之后，昵称改名会让索引陈旧，
自然的想法是「auth 改名时通知 search 重建」—— 但 **`auth` 现在的出站边是 0 条，那是 M3 好不容易挣来的**
（`grep -rn "novel-content\|ContentAuditClient" backend/novel-auth/src/` → 0 命中）。
**不为了一个极低频的场景把它加回来。** 收敛点 = 定时全量重建，收敛周期写进文档。

★ 同理**分类不进索引**（`categoryName` 是装配时从 MySQL 读的，永远新鲜）——
一旦为了「按分类搜」把它放进 mapping，**分类改名就立刻变成一个新触发点，而它在 content 的另一个 service 里**
→ 又多一个会漏的落点。
★ **索引里只有 6 个字段**：`id`（`index: false`，只存）/ `title` / `authorName` / `intro` / `status` / `lastUpdateTime`。

---

### ★ 判断 9：读的跳数从 1 变成 2（如实写，不粉饰）

```
今天：  gateway → content ──Feign──> auth                     （1 次跨服务读）
M4 后： gateway → search ──ES──>                              （本地）
                  └─HTTP─> content ──Feign──> auth             （2 次跨服务读）
```

★ 因为装配 `NovelVO` 时要 `userLookup.nicknamesOf(...)` 调 auth 取昵称。
★ **铁律 9 只管写不管读**，所以这不违规 —— 但「跳数不变」这句话是错的，
而它本来是「拆 search 不引入新风险」的论据。**如实写「写不变、读 +1」。**
换来的是：**作者名检索从一次全表扫描变成一次主键批量查，且消掉了 `LIMIT 100` 的召回上限。**

---

### ★★ 判断 10：会让 search 起不来的 common 类 —— ★★ 初稿只数了 2 个，实际是 **5 个**

★★★ **初稿这张表是「只看了 `config/` 目录」的产物，范围划错了。**
`novel-common` 里被 Spring 管理的类一共 **16 个**，`config/` 只是其中 7 个；
另外 9 个散在 `exception/` / `security/` / `upload/` 下。**把 16 个逐个判完之后，需要加条件注解的是 5 个。**

| 类 | 在 search 里为什么会炸 | 处置 |
|---|---|---|
| `MybatisPlusConfig` | 返回类型 `MybatisPlusInterceptor` 不在 classpath → 类加载失败 | `@ConditionalOnClass` |
| `MybatisMetaObjectHandler` | ★★ **它 `implements MetaObjectHandler`** —— JVM 加载类时要先解析接口，**扫描到它的一瞬间就 `NoClassDefFoundError`** | `@ConditionalOnClass` |
| ★ `UploadProperties` | `novel.upload.path` 没配 → `@PostConstruct` 抛 `IllegalStateException` | `@ConditionalOnProperty` |
| ★ `ImageStorage` | 构造器注入 `UploadProperties` | `@ConditionalOnProperty` |
| ★ `WebMvcConfig` | 构造器注入 `UploadProperties`；且会注册一条 search 收不到的 `/uploads/**` 映射 | `@ConditionalOnProperty` |

★★ **后三个（`upload/` 包 + `WebMvcConfig`）是初稿整个漏掉的** ——
因为初稿把范围划成了「`config/` 目录里有什么」。**这是划范围时犯的错，不是判断时犯的错。**

★★★ **初稿对 `MybatisMetaObjectHandler` 的判断写反了**：初稿写「只实现 `MetaObjectHandler`，
**大概率不炸**（不注入 DataSource）」。**判据错了** —— 它把「依赖什么」当成了判据，
而正确判据是「**这个类本身能不能被加载**」。一个 `implements` 了第三方库接口的类，
即使一个字段都没有，照样在类加载阶段就炸。
★ 推广：`implements` / `extends` 第三方类型 = 硬依赖，比字段注入**更早**失败。

**剩下的 11 个为什么没事**：`JacksonConfig` / `Knife4jConfig` / `RedisConfig` / `SchedulingConfig`
（只依赖 Spring + JDK）、`GlobalExceptionHandler`（★ 特意查过它**没有** `jakarta.validation.*`）、
`JwtUtil` / `TokenStore` / `RestAccessDeniedHandler` / `RestAuthenticationEntryPoint` /
`SecurityResponseWriter` / `HeaderAuthenticationFilter` ——
后六个都只依赖 Spring Security / Redis / jjwt，search 的依赖里都有。
★★ **它们的代价是 yml 要多写几段**（`novel.jwt.secret` / `expire-days` / `novel.internal.token`），
**而这正是 content 从 M1 起就在做的事**。
**判据：能靠「填值」解决的就填值（`JwtUtil` 无副作用），会「产生痕迹」的才加条件（`upload` 会建目录、注册路由）。**

**对三个现有服务是 no-op** —— `@ConditionalOnClass` 的两个条件它们天然满足；
`@ConditionalOnProperty` 的三个已核实：auth / content / interaction 的 `application-dev.yml` **都写了** `novel.upload.path`。
★★ **这是 M4 唯一一处「为了新服务去改 common」** —— 必须写进文档，且必须验证三个老服务没受影响。

★★★ **`SchedulingConfig` 的连带发现**：`@EnableScheduling` 一被扫到就**全局开启定时任务**。
search 依赖 `novel-common` → 白拿一个 `@EnableScheduling`。
★ 这**正好是 M4 想要的**（定时全量重建），但要说清：**不是 search 自己开的，是继承来的。**

★ 同样要核的还有 `WebMvcConfig`（它 `@RequiredArgsConstructor`，**注入了什么**决定了它在 search 里会不会炸）。
★★ **S2 的探针就是为这一整张表准备的**：读注解只完成一半，**另一半必须是「空壳服务真的能起来」**。

★ **search 要配 Redis**：全量重建是**计算型**定时任务，按 `SchedulingConfig` 自己的规矩**必须加分布式锁**
（`RedisKeyConstant.LOCK_PREFIX` + `SET NX EX`，照 `NovelStatReconcileJob` 的先例）。

★★★ **补记（S6 实测）：上面这 5 个是「Spring bean 起不来」那一类。还有第 6 个，是【类型】那一类，
而且它不在这个表里 —— `PageResult` 的方法签名引用了 `IPage`，害得 search 一调降级路径就 500。**
★ 两类要分开记：**这个表解决的是「服务能不能起来」，`PageResult` 那条解决的是「类能不能被反射/反序列化」——
后者能起服务、能编译、能过所有启动探针，只在第一次走那条路时炸。**

---

### ★★ 判断 11：全量重建 = `DELETE` 索引 + 重建 + bulk 灌，期间**自动降级**

已逻辑删除的书必须从索引里消失，而 upsert-only 的全量重建清不掉它们。
★ 最省且**自洽**的做法：全量重建时先 `DELETE /novel_index` → `PUT`（显式 mapping）→ bulk 灌。
**重建窗口内索引不存在 → 检索抛异常 → 自动走降级路径（判断 3 的 LIKE）** ——
**降级路径是现成的、正确的、已经用了三年的那条**，所以窗口内搜索**仍然是可用的**。

★★★ **【S8 实测订正 —— 初稿把最坏的情况说成了没事】**

初稿写的是「bulk 灌失败会让索引空着（**但降级一直可用**，且条数断言 + WARN 能发现）」。
★ **「降级一直可用」是错的。** `recreateIndex()` 先删后建，失败点有两个、后果完全不同：

| 失败点 | 留下的状态 | 检索会怎样 |
|---|---|---|
| `DELETE` 成功、`createIndex()` 失败 | 索引**不存在** | 抛异常 → **降级** → 结果完整、排序退化 |
| `createIndex()` 成功、`refill()` 失败 | 索引**存在且为空** | ★★ **不抛异常** → **静默返回 `total=0`** |

**实测**：索引为空时搜「剑」→ `HTTP 200 total=0`，而 LIKE 真值是 `2` 条。
★★ 而且 `indexed == 0` 那条 ERROR **拦不住它** —— 它在 `refill()` 返回之后才执行，这次 `refill()` 是抛出去的。
★★★ **判据：索引不存在（可降级）比索引存在但为空（静默骗人）【好】。**
已按此实施 `IndexService.dropQuietly()`（`rebuildAll()` 里 catch → 删索引 → rethrow），
**阳性对照已跑通**（详见 S8）。

★ **触发**：① 启动时索引不存在 → 自动灌（`ApplicationRunner`）；② `POST /api/admin/search/reindex` 手动；
③ 定时兜底（**6 小时**，加锁）。

---

## 五、逐条改造

### ① 建 `novel-search` 模块（S2）

依赖：`novel-common` + `spring-boot-starter-data-elasticsearch` + `spring-boot-starter-web`
+ `spring-boot-starter-security` + `spring-cloud-starter-openfeign`
+ `spring-cloud-starter-alibaba-nacos-discovery` + `rocketmq-spring-boot-starter` + knife4j + validation。
**不加** mybatis-plus / mysql-connector。

★ **`spring.elasticsearch.uris` 必须显式配** —— 不配会**静默 fallback 到 `localhost:9200`**
（只有一行 INFO，没有 WARN）。**S2 实测了这一条**。

### ② `ensureIndex()`（S3）

`ApplicationRunner`（**不是 `@PostConstruct`**）里 `HEAD /novel_index` → 404 就 `PUT` **显式 settings + mappings**。

★★ **绝不能靠 dynamic mapping**：`last_update_time` 为 null 的书（新书、一章未发）如果恰好是
**索引里唯一的文档**，该字段不会被建进 mapping → 此后任何按它排序的查询直接 **400**
`No mapping found for [last_update_time] in order to sort on`。

★★★ **初稿这里写「→ 搜索 500，是整个接口挂」—— 那句是错的，而且错的方向很危险。**
检索是 **try/catch 包着的**（为了 ES 挂了能降级走 LIKE），这个 400
**恰好会被那个 catch 吞掉** → 搜索 **200、有结果、不报错**，只是排序悄悄从相关性退回 `view_count`。
★ 也就是说：不写显式 mapping 的后果不是「接口挂掉」，而是
**「M4 做了半天，用户看到的东西和 M4 之前一模一样」** —— 这是最坏的一类失败。
★ 已实测（不是推的）：只含「无章节新书」的索引，动态 mapping 里确实没有 `lastUpdateTime`，
按它排序 **400**；同一份查询打到显式 mapping 的索引上 **200**。

`ensureIndex()` 内 try/catch 记 WARN **不外抛** —— 让 ES 挂着也能启动。

#### ★★ mapping 里的分析器：`analyzer: ik_max_word` + `search_analyzer: ik_smart`

**建索引切细、查询切粗**，两边都落在 `title` / `authorName` / `intro` 三个 `text` 字段上：

```json
"title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
```

| 方向 | 分析器 | 为什么 |
|---|---|---|
| **建索引** | `ik_max_word` | 切得**多** → 文档里 term 多 → **召回高** |
| **查询** | `ik_smart` | 切得**粗** → 查询词更整 → **精度高**，`_score` 不被碎词稀释 |

★★ **但这个理由在短查询上【实测看不出来】** —— M4 用「斗破苍穹」同时跑了两者，
**输出完全一样**。所以这段配置**不能拿那一次实测当依据**，
依据是 IK 官方推荐 + 长文本上 `ik_max_word` 更细（实测「重生之我在修仙世界当咸鱼」切出 8 个词）。
★ **如实写「这个组合在 M4 的短查询样本上看不出差异」**，不把一个没验到的收益说成验到了。
★ 真正**能验**的是判断 4 的排序验收：书名命中的排在只有简介命中的前面 —— 那验的是 boost，不是 analyzer。

★ 另：`status` 是 `keyword`（**不分词**，走 `term` 精确匹配），`id` 是 `long`，
`lastUpdateTime` 是 `date` —— **这三个都不该配 analyzer**。

#### ★★★ 订正：`missing` 不是 mapping 参数 —— 初稿写错，且后果是灾难性的

初稿写 `lastUpdateTime` 配 `"missing": "_last"`。**已实测：ES 直接 400 拒绝整个 mapping**：

```
400 mapper_parsing_exception
unknown parameter [missing] on mapper [lastUpdateTime] of type [date]
```

★ **`missing` 是【查询期】参数，写在 sort 子句里**：

```json
"sort": [ { "lastUpdateTime": { "order": "desc", "missing": "_last" } }, { "id": "desc" } ]
```

★★★ **它的后果被「`ensureIndex` 失败只记 WARN」放大成最坏的一种**：
建索引 400 → 索引**永远建不出来** → 每次检索都抛「索引不存在」→ 被那个 catch 吞掉 →
**静默地永远走降级路径，整个 M4 的 ES 部分一行都不生效，而日志里只有一行 WARN。**

★★ **两条合并成一条方法论**：**「能被 catch 吞掉的错误」必须用【正面验证】去验，
不能靠「没看到报错」下结论。**
★ 已实测补充：ES 对 sort 里缺失值的**默认就是 `_last`**，所以显式写它属于**自文档化**，
不是修复 —— 真正的修复是**显式 mapping**。两件事别混。

#### ★★ 订正：`number_of_replicas: 0` 必须显式写

初稿没写这一条。**本环境是单节点 ES，而副本数默认是 1** → 单节点放不下副本 →
集群健康变 **yellow**。★ 而 S0/S1 的验收判据写的就是 `status = green` ——
不写这一行的话，**`novel_index` 一被建出来，那条判据就开始失败**，且失败原因看起来像「ES 坏了」。
★ 已实测：写上之后建完索引健康仍是 `green`。

### ③ `NovelVO` 下沉 + `NovelVos` 装配点（S4）

纯机械重构，见判断 7。★ 改完立刻 `mvn package` 三端回归，确认没有任何接口的响应体变了。

### ④ content 的三个内部端点（S5）

| 端点 | 用途 | 语义 |
|---|---|---|
| `POST /internal/novels/index-docs` | **增量重建**（消息触发） | 给 id 列表 → 返回 6 个索引字段。★ **不看 `status`**（草稿也要在索引里，由 ES 的 filter 挡） |
| `GET /internal/novels/index-page?afterId=&size=` | **全量重建的数据源** | 游标分页（`size` 受 MyBatis-Plus 100 上限约束） |
| `GET /internal/search?keyword=&page=&size=` | **★ 降级路径** | **就是今天那个 `NovelServiceImpl.search` 原样搬过来**，一行逻辑不改 |

★★ **`index-docs` 的一个约定必须写死**：**响应里缺失的 id = 这本书不该在索引里 → search 执行 `delete`。**
（覆盖逻辑删除。）★ 前提是**请求 id 数量 ≤ 100 且响应必须完整、不分页** —— 由「消息触发永远只有一个 novelId」保证。
★ 这三个端点都要**调各自本来的那一个 Service 方法**，不复刻业务判断。

★ **`userIdsByNickname` 这条接缝不死** —— 降级路径继续用它。

### ⑤ search 的检索 + 组装 + 降级（S6）

```
SearchService.search(keyword, page, size)
  ├─ keyword 为空 → 直接 PageResult.empty()   ★ 短路在进 ES 之前，不进 try
  ├─ ★ from+size > 10000 → 不查了（见 R15）    ★ 同样在 try 之外
  │     —— 让它 400 的话会被下面的 catch 吞掉 → 静默降级 → 排序悄悄变回 view_count
  ├─ try:   ES 检索 → 有序 id 列表 + total
  │         → Feign POST content:/internal/novels/index-docs → 拿装配数据
  │         → ★ content 按传入 id 顺序重排（MySQL 的 IN 不保证顺序）
  │         → 拼 PageResult<NovelVO>
  └─ catch (Exception e):  ★ WARN「ES 检索失败，降级到数据库 LIKE」+ 异常类名
            → Feign GET content:/internal/search → 原样返回
```

★★ **降级路径对用户完全透明**（两条路的响应体形状一致，都是 content 造的 `PageResult<NovelVO>`），
**但日志里必须一眼看得出在降级**。
★ 排序在两条路上不同（相关性 vs `view_count DESC`）→ **对拍验收只能对【集合】，不能对顺序**，文档写明。

### ⑥ `NOVEL_INDEX` 消息 + 四个落点（S7）

- `MqConstant` 加 `TAG_NOVEL_INDEX = "NOVEL_INDEX"` / `GROUP_SEARCH_INDEX = "group-search-index"`
- content 新增 `NovelIndexNotifier`：`AfterCommit.run(() -> rocketMQTemplate.asyncSend(...))`，
  ★ **发送被 try/catch 包住 + 带 `SendCallback`**（`asyncSend` 会同步抛异常；不带 callback 的异步失败是静默的）
- 四个落点（**全部在 `NovelServiceImpl` 内**，显式调用，便于 grep 核对）：
  `transitStatus` 出口 / `update` 出口 / `delete` 出口 / `syncNovelStat` 出口
- ★ **上架时会重复发两次**（`transitStatus` → `syncNovelStat` 各一次）—— **无害，正是幂等设计的兑现**
- search 侧 `NovelIndexConsumer`：`consumeMode = CONCURRENTLY`、**显式 `maxReconsumeTimes = 3`**
- ★ 消费者里**永久失败绝不抛**（作品不存在 → 记日志 + 正常返回 = ACK），**只有基础设施异常才抛**

### ⑦ 网关路由迁移（S9）—— 必须和「删 Controller」同一个原子步骤

| 动作 | 细节 |
|---|---|
| ① 从 `content-misc` 的 Path 里**删掉** `/api/search` | 只留 `/api/categories,/api/ai,/api/ai/**` |
| ② 新增 `search-query`：`Path=/api/search,/api/search/**`，`uri: lb://novel-search`，**order: 3** | 裸路径 + `/**` **都要写**（`/api/ping,/api/ping/**` 的先例） |
| ③ 新增 `search-admin`：`Path=/api/admin/search,/api/admin/search/**`，**order: 3** | ★ **必须 < 5** —— content 的 `/api/admin,/api/admin/**` 在 order 5 |
| ④ 删 `ReaderSearchController` + `ContentSecurityConfig` 的白名单行 | |

★★★ **三种漏法症状完全相同（都是 404）**：漏①→老路由把请求转给 content，而类已经搬走；
漏③→被 order 5 的 content 抢走；只写 `/**`→裸路径掉进 order 20 的兜底（interaction）。

★★ **路由不变式扩写了**：原来那条只管 interaction 抢 content（`4 < 5`）。
M4 起改成「**任何与 content 的 `/api/novels/**` 或 `/api/admin/**` 重叠的【其它服务】路由，
order 必须严格小于 5**」—— `/api/admin/search/**` 是第二个适用者，将来还会有第三个。
★ search 用 **order 3 而不是 4**：同序号「碰巧没事」不是设计。

★ 最终形状（`order` 递增）：

```
【1】auth 具体前缀 →【3】search 抢占 →【4】interaction 抢占 →【5】content 具体前缀
                                        →【10】/uploads/** →【20】兜底
```

---

## 六、实施顺序（每步独立可验证）

| 步 | 内容 | 验证 |
|---|---|---|
| **S0** | 虚拟机起 ES（`vm.max_map_count`、`chown` 到 uid 1000、`docker.elastic.co` 单独重试）；★ **IK 探针** | ★ `_cluster/health` = **green**；★★ 判据见「S0 判据的订正」 |
| **S1** | `docs/docker/Dockerfile-es` + compose 的 `es` 段改 `build:`；重建容器 | `_analyze` 复验（带 standard 对照）；★ `docker compose ps` 里 `es` 是 `(healthy)` |
| **S2** | 建 `novel-search` 模块 + 父 POM 加 module；★ 按判断 10 给 common 的 5 个类加条件注解 | ★★ **探针步**：空壳服务**能起来**；★ `uris` 故意写错 → 启动不报错、第一次查询才炸；★ 五端**全部能起** |
| **S3** | `ensureIndex()`（显式 settings + mappings） | ★ 阳性对照：只含无章节新书的索引 → 排序 **400**，显式 mapping 后正常 |
| **S4** | `NovelVO` 下沉 + `NovelVos` 装配点 | 三端所有列表接口响应体**逐字节不变** |
| **S5** | content 三个内部端点 | 直连 `:8082` 带 `X-Internal-Token` 逐个打 |
| **S6** | search 的检索 + 组装 + 降级（**先不接 MQ**） | ES 在线 / 停 ES 两条路径都能返回正确结果 |
| **S7** | `NOVEL_INDEX` 消息：producer + 四个落点 + consumer | 改书名 / 下架 / 删作品 / 发章节 → 索引跟着变 |
| **S8** | 全量重建（定时 + 手动端点 + 启动时索引空自动） | 手工把某个文档改坏 → 一个周期内自愈 |
| **S9** | 网关迁移 + 白名单减法 + 删 `ReaderSearchController`（**同一步**） | ★ 停 `novel-search` → 搜索路径**非 2xx**、`/api/novels` **200** |
| **S10** | 全量回归 + 清理一次性对象 + 文档 + `CLAUDE.md` + 提交 `m4完成` | 照 README 从零跑一遍（**真跑**） |

★ **S3~S8 之间可以停** —— 每一步都是可交付的增量。

### ★★ S0 判据的订正 —— 初稿那条是个【永远不会失败】的护栏（铁律 8）

初稿写的是：

> ~~`_analyze` 返回**多于 1 个 token**（返回 1 个整串 = 插件没生效、退回 standard 了）~~

★ **这条永远为真，什么都验不到。** 因为没有 IK 时 `ik_max_word` 这个分析器名直接
**不存在**（`_analyze` 报 `failed to find analyzer`），而**真退回 standard 的话它返回 4 个 token**
（`斗/破/苍/穹`），**照样「多于 1 个」**。
★ 它错在把 standard 对中文的行为想成了「整串一个 token」—— 同一个错误信念，
在 Dockerfile 注释里也出现过一次。**两处同源，一起改。**

**正确的判据（三个层次，缺一不可）：**

| # | 检查 | 通过的样子 | 失败的样子 |
|---|---|---|---|
| ① | `curl localhost:9200/_cat/plugins?v` | 有一行 `analysis-ik 8.13.4` | 空 / 版本号不对 |
| ② | `_analyze` 用 **`ik_max_word`** | **分析器名被解析** | 404 `failed to find analyzer [ik_max_word]` |
| ③ | ★★ **与 `standard` 的差分** —— 这条才是真判据 | `ik_max_word`「斗破苍穹」→ `斗 / 破 / 苍穹`（**3 个 token，其中一个是 2 字词**）<br>`standard` 同句 → `斗 / 破 / 苍 / 穹`（**4 个单字**） | 两者**输出完全相同** = IK 没生效 |

★★ **这正是铁律 8 说的那件事**：一条护栏只要**它能通过的输入**和**它想拦的输入**长得像，它就等于没有。
**差分式判据（和对照组比）比阈值式判据（数个数）强**，因为前者的对照组是自动对齐的。

---

## 七、实施记录（S0 ~ S10，全部实测）

### S0 / S1 —— 差分判据通过

| 检查 | 实测 |
|---|---|
| `_cluster/health` | `"status": "green"`，1 节点 |
| `_cat/plugins` | `analysis-ik 8.13.4` |
| ★★ **差分**：`ik_max_word`「斗破苍穹」 | `斗 / 破 / 苍穹` —— **3 个 token，含 1 个 `CN_WORD`** |
| ★★ **差分**：`standard`「斗破苍穹」 | `斗 / 破 / 苍 / 穹` —— **4 个 `IDEOGRAPHIC` 单字** |
| 长句 `ik_max_word` | 「重生之我在修仙世界当咸鱼」→ 8 个词 |

★ 两者**输出不同** → IK 确实生效。差分判据成立。

### S2 探针 —— 五条，全过

| # | 探针 | 实测结果 |
|---|---|---|
| 1 | 空壳 `novel-search` 起得来 | ✅ 8084，`Started SearchApplication in 19.9s` |
| 2 | ★★ `uris` 指向 `127.0.0.1:9999` 仍能启动 | ✅ 9.1s 起来，**日志里 0 条 ES 访问记录** —— 证实「**服务起来了」验不到 ES 通不通** |
| 3 | 三个老服务仍能起（common 改动是 no-op） | ✅ 全 reactor 打包后五个进程全部 `Started` |
| 4 | `@Scheduled` 空任务不报错 | ✅ 无任何相关 WARN |
| 5 | ★ ES 客户端 **8.10.4** ↔ 服务端 **8.13.4** | ✅ S3 的 `ensureIndex` 真实建索引成功（这就是那次握手） |

★ no-op 的**代码级**依据（两条都核过）：三个服务都有 mybatis-plus → `@ConditionalOnClass` 天然成立；
三个服务 `application-dev.yml` **都写了** `novel.upload.path` → `@ConditionalOnProperty` 天然成立。
★ 另：网关**不受影响**——它的 `@SpringBootApplication` 在 `com.novel.gateway`，
**从来不扫 `com.novel.common.*`**（这正是它四期来一直没被 `MybatisPlusConfig` 炸到的原因）。

### S3 —— `ensureIndex()` 建成，阳性对照全过

实测建出来的索引：6 个字段全在、三个 `text` 带 IK 双分析器、`status: keyword`、
`id: long + index:false`、`lastUpdateTime: date`、`max_result_window: 10000`、
`number_of_replicas: 0`，健康 **green**。

| 阳性对照（铁律 8） | 结果 |
|---|---|
| 动态 mapping + 只有 null 日期的文档 → 按 `lastUpdateTime` 排序 | **400** ✅ 能失败 |
| 同一份查询打到显式 mapping 的索引 | **200** ✅ |
| ES 不可达（`:9999`）时 `ensureIndex()` | **WARN 如期打出** + **服务照常启动** ✅ 护栏不是死代码 |

### ★★ 过程中发现的第 4 个「只有读了才知道」的事实：Boot 的 JsonpMapper 不带 JavaTimeModule

`javap` 核实 Spring Boot 3.2.12 的自动配置：

```java
@Bean
@ConditionalOnMissingBean
JacksonJsonpMapper jacksonJsonpMapper()      // ← ★ 没有参数，不收容器里的 ObjectMapper
```

★ 它自己 `new ObjectMapper()`，**没有 JavaTimeModule** → `LocalDateTime` 会被当成普通 POJO 序列化成
`{"year":2026,"month":"SEPTEMBER",...}` → ES 的 `date` 字段 **400**。
★★ 症状同样是**静默的**：只在写文档那一刻炸，且会被检索路径的 catch 吞掉。

**处置**：`NovelDocument.lastUpdateTime` 用 **`String`**（`uuuu-MM-dd'T'HH:mm:ss`），进 ES 前就格式化。
★ 序列化 String 不需要任何模块，**假设归零**。

### S4 —— `NovelVO` 下沉 + `NovelVos` 装配点，**18/18 接口逐字节不变**

★ 这次验收**碰上了运气**：改代码时 `:8082` 上跑的还是 S4 之前的 jar，
所以基线是**在改动生效前**抓的，比较是**真差分**而不是「改完自己跟自己比」。

| 步骤 | 结果 |
|---|---|
| **噪声标定**（同一份 jar 前后各抓一次） | ★★ **0 处差异，连易变字段都没动** —— 说明没人访问时 `viewCount` 不漂移，因此可以**用原始字节 diff** |
| **before vs after** | ✅ **0 / 18 有差异** —— 逐字节相同 |

**阳性对照（两条，缺一不可）**：

| # | 对照 | 结果 |
|---|---|---|
| ① | ★ 新 jar 真的在跑？ | ✅ `BOOT-INF/lib/novel-common-1.0.0.jar` 里**有** `NovelVO.class`（时间戳 = 本次构建）；★ 而 content 自己的 `BOOT-INF/classes/.../vo/` 下 **`NovelVO.class` 已不存在**。**两边同时成立才排除「拿旧 jar 跑出 0 差异」这个假阳性。** |
| ② | ★ diff 工具自己能失败吗？ | ✅ 把 `authorName` 改一个字 → diff 立刻报警。（**不做这条的话，「0 差异」和「脚本没跑」长得一模一样**） |

### S5 —— content 的三个内部端点

| 端点 | 实测 |
|---|---|
| `POST /internal/novels/index-docs` | ✅ 单个 / 批量 / **含不存在的 id（自动缺失）** |
| `GET /internal/novels/index-page?afterId=&size=` | ✅ 游标严格大于；溢出游标 → `[]` |
| `GET /internal/search?keyword=&page=&size=` | ✅ 与 M4 前基线逐条一致；`keyword` 空 → `records:[]` |

★★ **降级路径与 M4 前基线对拍，五个关键词全中**（这就是「原件续用」的验收）：

| keyword | 基线（M4 前） | `/internal/search` 实测 |
|---|---|---|
| 剑 | `total=2` `[1011,1001]` | ✅ 一致 |
| 测试 | `total=12` | ✅ 一致 |
| 修仙 | `0` | ✅ `0` |
| ★ 剑冢 | `0` | ✅ `0`（只在简介里 —— **决定 2 的阳性对照，M4 后应由 ES 路径给出 `[1001]`**） |
| ★ 避雨 | `0` | ✅ `0`（同上，ES 路径应给 `[1003]`） |

★★★ **⑨ 的阳性对照：第一版「不做 status 过滤」这条根本验不到，因为库里 12 本全是 `status=2`。**
「有过滤」和「没过滤」给出**完全相同的结果** —— 正是铁律 8 那个陷阱的形状。
于是造了一个带时间戳的一次性草稿当阳性对照：

| 检查 | 结果 |
|---|---|
| `index-page` 必须**含**草稿 | ✅ 13 本，`status` 分布 `{0: 1, 2: 12}` |
| `index-docs` 同样必须**含**草稿 | ✅ 返回了 `{"2002": {...,"status":0}}` |
| `+ /internal/search` 必须**搜不到**它 | ✅ `total=0` —— 第一道闸门没被 S5 碰坏 |
| 清理后种子完好 | ✅ 回到 **12 本、全 `status=2`** |

★ 顺带白捡一条真数据：**草稿的 `lastUpdateTime` 在 JSON 里直接消失**（`non_null`）——
这就是 S3 那个「一本无章节的新书 → 动态 mapping 里没有该字段 → 排序 400」的场景，
**它在真实数据里真的会发生**，不是假想。

★ 回归：S4 的 18 接口快照重跑一遍 → **仍 0/18 有差异**。

★★ **`/internal/**` 不带令牌的返回码，三个服务口径一致（S9 复验）**：
`:8084` 与 `:8082` 都是 **403 不是 401**。这条行为住在共享的 `HeaderAuthenticationFilter` 里，
**所以它本来也不可能各服务不一致** —— 当初把它列成待办，是把「注册样板有 4 份」误当成了「行为会有 4 份」。

### ★★★ S6 —— 检索 + 组装 + 降级：两条路都实测通过

#### ★★★ 拦路的那条 `NoClassDefFoundError` —— 判断 10 家族的第 4 个成员，而且是最隐蔽的一个

S6 的代码写完编译通过（第一次就过），但**一跑就 500**：

```
java.lang.NoClassDefFoundError: com/baomidou/mybatisplus/core/metadata/IPage
  at feign.ResponseHandler.handleResponse(...)
  at com.novel.search.service.SearchService.degradeToLike(SearchService.java:280)
```

**根因**：`PageResult` 里有两个静态工厂 `of(IPage<T>)` / `of(IPage<E>, Function)`。
`novel-search` 的 pom **刻意不加 MyBatis-Plus** → Jackson 反序列化 `Result<PageResult<NovelVO>>` 时
要枚举这个类声明了哪些方法，而 **`Class.getDeclaredMethods()` 必须把每个方法签名里的类型都解析出来**
→ 解析不到就抛 `NoClassDefFoundError`。
★★ **整个类就此不可用，跟那两个方法调没调用毫无关系**（降级路径一个字都没用到 `IPage`）。

★★★ **判据要扩写成一条，这是 M4 最有价值的一条发现**：

| 依赖形态 | 什么时候炸 | 显眼程度 |
|---|---|---|
| 字段注入第三方类型 | 起服务时 | 一眼看得出 |
| `implements` / `extends` 第三方类型 | 类加载阶段（比注入更早） | 看得出（判断 10 已记） |
| **方法签名里【出现】第三方的类型** | **反射枚举方法时**（`getDeclaredMethods()`），或**编译期**（重载决议） | ★★ 最不显眼 —— 长得像「一个永远不会被调用的重载」 |
| ★ **注解**里出现第三方类型 | **永远不炸** | —— |

★ 最后一行有实测依据：`NovelStatus` / `AuditAction` 等 5 个共享枚举都 `import com.baomidou...EnumValue`
并用了 `@EnumValue`，而 search 里 `NovelStatus.PUBLISHED.getCode()` **跑得好好的** ——
**反射遇到解析不了的注解会静默丢弃**。**对照着记：注解无害，参数类型致命。**

**处置（三处改，全部编译期可验）**：

| 类 | 变化 |
|---|---|
| `PageResult` | ★ **变回一根纯粹的 DTO**：删掉 `IPage` import 和那两个工厂，类注释里写明「本类不引用任何 ORM 类型」 |
| `PageResults`（新） | **零依赖**的装配工厂：`of(records, total, current, size)`，总页数自己算 |
| `MybatisPageResults`（新） | `IPage` 适配器，就是原来那两个方法，**体一字未改** |

★★ **`PageResults` 和 `MybatisPageResults` 必须分成【两个类】—— 这条也是实测的，不是分层洁癖**：
先把两个 ORM-free / ORM 版的重载放进**同一个类**，结果 **javac 直接报**
`无法访问 com.baomidou.mybatisplus.core.metadata.IPage` —— 因为**重载决议要把所有候选方法的参数类型都解析出来**。
★ 所以真正的判据不是「别把 `IPage` 放进 DTO」，而是 **「`IPage` 不许和任何人共处一个类」**。

**迁移代价**：`PageResult.of(` 全项目只有 **6 个真实调用点**（另 3 处是注释），纯机械替换。

★★ **为什么没走「给 search 也加上 mybatis-plus 依赖」那条更省的路**：
那会往一个**没有数据库**的服务里塞一个 ORM，从此谁都可以在 search 里注入 Mapper ——
而「search 不碰数据库」正是判断 3 的**结构性保证**，靠自觉守的约束不是约束。
★ 而且 `PageResult` 自己的类注释里就写着「`Page` 是 ORM 的类，**不该跨服务传递**」——
**这次是那个类自己违反了这句话**。★ 这条自相矛盾值得记：**它说明这个约束本来就存在于作者的脑子里，只是没落到类型上。**

#### ★ 顺带发现并修掉的一个静默不一致

`searchByEs` 原来返回 `new PageResult<>(records, total, page, size, pagesOf(...))` —— **用的是原始入参**，
而查询用的是夹过的 `safePage`/`safeSize`。于是 `?page=0&size=0` 会**按 1/10 查询、却汇报 `current=0,size=0`**，
前端 `el-pagination` 按 0 渲染；而且**降级路径（MyBatis-Plus 的 `Page`）汇报的是夹过的值**
→ **两条路给的还不一样**。★ 已统一到夹过的值，并把 `pagesOf` 删掉（总页数改由 `PageResults.of` 算，一个实现）。
★ 形参也改名成 `safePage` / `safeSize` —— **把「能调到这里的一定是夹过的值」写在签名上**。

#### 两条路径的实测（★ 三阶段，同一个进程 PID 全程不变）

★★ **测试方法值得单独记一笔**：ES 容器在虚拟机上，**没有免密 SSH**，停不了容器。
于是在本地起了一个**透明 TCP 代理（`127.0.0.1:19200` → `192.168.127.137:9200`）**，
用 `ES_URIS` 环境变量把 search 指过去（dev 配置是 `${ES_URIS:http://192.168.127.137:9200}`，
**无需改代码、无需重新打包**）。**杀掉代理 = ES 不可达；重启代理 = ES 恢复 —— 全程同一个 search 进程。**
★★ **这比「停容器」验得更多**：停容器只能验「挂了能降级」，
**「恢复后自动切回、无需重启」这条只有代理方案才验得到**。

| 阶段 | 操作 | 剑 | 剑冢 | 避雨 | 修仙 | 测试 | 斗 | HTTP |
|---|---|---|---|---|---|---|---|---|
| 0 | 直连 ES（基线） | `[1001,1011]` | **`[1001]`** | **`[1003]`** | `[]` | 全 12 本 | `[]` | 200 |
| 1 | 经代理（ES 在线） | 同上 | 同上 | 同上 | 同上 | 同上 | 同上 | 200 |
| 2 | ★ **杀代理（ES 不可达）** | `[1001,1011]` | **`[]`** | **`[]`** | `[]` | 全 12 本 | `[]` | ★ **200** |
| 3 | ★ **重启代理** | `[1001,1011]` | **`[1001]`** | **`[1003]`** | `[]` | 全 12 本 | 同上 | 200 |

- ★ **阶段 1 ≡ 阶段 0**（`diff` 无差异）→ 证明**代理本身没有改变行为**，阶段 2/3 的差异只能来自 ES 可达性。
- ★★ **阶段 2 ≡ 「content 的 `/internal/search` 直接调用结果」**（`diff` 无差异）
  → 这是**降级路径「原件续用」的判定性证据**，不是「看起来差不多」。
- ★★ **阶段 2 的 `剑冢`/`避雨` = 空** —— 这正是 `ContentClient` 注释里预告的**已知能力退化**
  （降级路径不搜简介）。**它必须为空**；若它俩在阶段 2 仍有结果，说明降级根本没生效、ES 还活着。
  **这是阶段 2 最关键的阳性对照。**
- ★ 单字关键词：`剑` 两条路都给 2 条 → **单字没有退化**。
- ★ `斗` = 0 **两条路都是 0** → 种子数据里根本没有含「斗」的书，**不是回归**。

**日志侧的阳性对照**：
- ★ 阶段 2 期间 `ES 检索失败，降级到数据库 LIKE | ... | ConnectException: Connection refused` **如期出现**；
- ★★ **阶段 2 期间 0 条 `系统异常`**，而 **12:58 那次（降级路径坏在 `IPage` 上）同一个接口返回的是 500** ——
  **那次事故本身就成了「降级代码若坏掉会怎样」的天然阳性对照**，不必再去注释代码。

### ★★★ S7 —— `NOVEL_INDEX` 消息：producer + 四个落点 + consumer

#### 落点清单（判断 2 那张表的落地）

| 落点 | 方法 | 覆盖的表列 | 为什么不能少 |
|---|---|---|---|
| ① | `transitStatus` 出口 | `status` | ★ 上架漏发 → **新书搜不到**；下架漏发 → **下架的书仍搜得到** |
| ② | `update` 出口 | `title` / `intro` | ★ 只挂 `syncNovelStat` 会**永久漏发**（改名根本不经过它） |
| ③ | `delete` 出口 | `deleted`（逻辑删除） | ★ `@TableLogic` 是 MyBatis 往 WHERE 里注入 `deleted = 0`，**ES 不知道有这一列** |
| ④ | `syncNovelStat` 出口 | `chapter_count` / `word_count` / `last_update_time` | ★ 它一次覆盖**四个调用点**，但**反过来不成立** |

★★ **四个落点全部显式写在 `NovelServiceImpl` 里、不抽公共方法** ——
这样 `grep -n "indexNotifier"` 就能一次核对全。
★ 判据写在 `NovelIndexNotifier` 的 javadoc 上：**「参与检索的列被写的地方」**。

★★ **`create` 刻意【不是】第五个落点**：新建的作品 `status` 恒为 `DRAFT`，会被 ES 侧的 `term(status=2)` 挡掉；
它第一次进索引靠的是**上架时的落点 ①**。
★ 并留了一句警告：**若将来 `create` 允许直接建成已上架，这里必须补一个落点** ——
否则症状又是「新书搜不到」，而且是那种查半天查不出来的。
★ **S10 实测复验了这条**：`create` 之后 ES 文档 **404**、搜标题为空（见九、验收证据）。

#### 覆盖性证明（不是「我觉得够了」，是 grep 出来的）

`novel` 表在全项目只有 **8 处写入口**，逐个判完：

| 写入口 | 涉及的列 | 是落点吗 |
|---|---|---|
| `insert`（`create`） | 全列，但 `status` 恒 DRAFT | ✗ **刻意** |
| `updateById`（`update`） | `title` / `intro` / … | ✓ ② |
| `deleteById`（`delete`） | `deleted` | ✓ ③ |
| `update(null, …)`（`syncNovelStat`） | `chapter_count` / `word_count` / `last_update_time` | ✓ ④ |
| `update(novel, …)`（`transitStatus`） | `status` | ✓ ① |
| `refreshCollectCount` | **只有 `collectCount`** | ✗ 不参与检索/排序 |
| `refreshScore` | **只有 `score`** | ✗ 同上 |
| `NovelViewAggregator` | **只有 `viewCount`** | ✗ 同上 |

★ 后三处已逐个读过源码确认**只 set 了那一个字段**。
**所以「四个落点覆盖全部」是成立的** —— 这是一个可以复查的结论，不是印象。

#### 实测：一次性对象（`M4S7...`，全程一个对象，结尾逻辑删除清理）

| 步骤 | 观测 |
|---|---|
| create（DRAFT） | ES `found:false` ✅ 草稿不进索引 |
| submit | 文档出现，`status:1` |
| **audit pass** | `status:2`；按书名搜 → 命中；★ **只存在于简介的词也能搜到** → **决定 2 在增量路径上同样兑现** |
| offline（status 3） | 文档 `status:3`；搜索 → `0` |
| ★ **rename（必须先下架）** | 索引里 title 变了 |
| **republish** | ★ 旧名 `0` / 新名 `1` → **判断 2 那个失败模式被演示成已修复** |
| chapter create+submit+audit | `lastUpdateTime` `null` → 有时间戳 |
| chapter withdraw+delete | `lastUpdateTime` → `null` |
| novel delete | ES `found:false`；两个关键词都 `0` |

★★ **上架时确实发了两次**（`SYNC_STAT` + `TRANSIT_STATUS`）—— **无害，而且正是幂等设计的兑现**。**不修**。

#### ★★ 铁律 8：两条「永久失败」分支的阳性对照（用一次性 `MqProbe` 投毒）

`NovelIndexConsumer` 有两支**永远不该重试**的路径，它们**平时一条都不会跑到** ——
这正是「不会失败的护栏」的形状，所以必须造阳性对照：

| 投毒 | 期望 | 实测 |
|---|---|---|
| `novelId = 999999`（不存在） | 走 delete 分支 → **ACK，不重试** | ✅ 日志 `索引文档已删除（作品不存在或已逻辑删除）`，**0** 条重试 |
| `novelId = null` | 走校验分支 → **WARN + ACK** | ✅ 日志 `索引消息缺少 novelId，已丢弃`，**0** 条重试 |

★ 事后统计（整个 S7 窗口）：`consume message failed` / `reconsume` / `sendMessageBack` **各 0 条**；
`%RETRY%group-search-index` / `%DLQ%group-search-index` **各 0 次**。

#### ★★★ 过程发现：**消费者注册与 `producer.group` 无关** —— 一条防御性配置靠一次实测证否

M4 初稿给 search 的 yml 留了一段防御性的 `rocketmq.producer.group`，理由写得很小心：
照 content 的记载，2.3.6 的 producer bean 条件是
`@ConditionalOnProperty(prefix="rocketmq", value={"name-server","producer.group"})`，
**而消费者那边是不是同一个条件，当时没核实过**。
万一监听器注册也挂在这个条件下，少写 `producer.group` 就会**消费者根本不注册**，
症状正是铁律 7 记的那类：**消息发出来了、一条都没收到、而且没有任何报错**。

**探针做法**：删掉这段 → 全量打包 → 重启。**没有改任何别的代码。** 消费者照常注册、照常消费。
★ **结论：那个 `@ConditionalOnProperty` 只管 `DefaultMQProducer`，管不到监听器容器。**

★★ **那为什么一定要删、留着不是更保险吗** —— 因为留着它有**实际的害处**：
它让一个纯消费的服务凭空多出一个 `DefaultMQProducer` bean，
于是 search 和 content / interaction **共用了同一个生产者组** ——
而 search 的 pom 里那条注释写着「本服务【不发】任何消息」。
★ 一份「说了不用、但配置里配着」的东西，下一个人只会有两种误判。

★★★ **判据（值得推广）：防御性配置只有在「删了会静默出事」时才值得留；
能靠一次实测证否的，就该实测之后删掉，把结论写进注释。**
★ 配置删除后已复验：doc 在幂等重写之后**逐字节不变**。

#### ★ 一条留给自己的教训：写完之后立刻读，会得到假阴性

`POST .../submit` 之后**立刻** curl ES，拿到 `{"found":false` —— 当时看起来像**落点 ① 没触发**。
实际是 `asyncSend` + MQ + Feign 这条链还没走完（秒级）。**2 秒后重查，文档就在那儿。**

★★ **判据：「写之后立刻读」不是证据 —— 这条链路是异步的，
`found:false` 只说明「还没到」，不说明「不会到」。**
★ 所以 S10 的验收脚本里所有断言都写成了**轮询到谓词成立**（`wait_for`），不再是「写完立刻读」。

#### 编译期/机械性核对

- ★ `grep -rn "MqConstant.TAG_NOVEL_INDEX"` → producer 一处、consumer 一处，无第三处
- ★ `GROUP_SEARCH_INDEX` 全项目**只出现一次**（消费者注解里）—— 不存在「两个 Tag 共用一个消费组」那个坑
- ★ `maxReconsumeTimes = 3` 已显式写在注解上（四个消费者口径一致）

### ★★★ S8 —— 全量重建：三个触发源全部实测，并逼出一个【静默返回 0】的破坏性失败模式

#### 三个触发源

| 触发源 | 怎么验的 | 结果 |
|---|---|---|
| ① 启动时索引为空 → 自动灌 | 删索引 → 重启 search | ✅ 走 `IndexInitializer.isEmpty()` → `rebuildAll()` |
| ② 管理端点 | `POST /api/admin/search/reindex` | ✅ `skipped:false, indexed:13` |
| ③ **定时兜底** | ★ 临时把 cron 改成 `0 */2 * * * *`，等两跳 | ✅ 见下表 |

★★★ **③ 验的其实不是「重建能不能跑」，是「继承来的 `@EnableScheduling` 到底会不会触发」** ——
S2 的探针只验了**反向**（没有 `@Scheduled` 方法时不报错），正向他一次都没验过。

| 时刻 | 分支 | 日志 |
|---|---|---|
| 13:52:01 | **真重建** | `索引重建完成 \| index=novel_index 灌入=13 条 耗时=1732ms` |
| 13:54:00 | **拿不到锁 → 跳过** | `索引重建跳过：没拿到锁（另一个实例在重建，或上一轮还没到期）` |

★ 两跳的线程名都是 `novel-sched-1`。
★★ 第二跳**是先预测再看的**：13:53:48 用 `RedisProbe` 读到锁与 TTL，据此断言「13:54 那跳必然 skipped」——
观测与预测一致，这一次不是事后解释。★ 验完 cron 已还原成 `0 0 */6 * * *`。

#### ① 自愈：手工改坏一个文档 → 一个重建周期内自愈

| 阶段 | BROKEN（改坏的词） | HEAL（正确的词） | INTRO（只在简介里的词） |
|---|---|---|---|
| 改坏之后 | `1` | `0` | `1` |
| `POST reindex` 之后 | `0` | `1` | `1` |

★★ 中间出现过一次**全 0** —— 那不是自愈失败，是 **bulk 的 refresh 窗口**：ES 默认 1 秒才可见。
**这是「写完之后立刻读会得到假阴性」的同一个坑，只是换了个触发方式**（那次是 MQ 异步，这次是 ES refresh）。

#### ② 两道闸门的阳性对照 —— ★ 计划里那条期望对 `status=3` 写错了

计划原文写的是「手工往 ES 塞一条已下架的书 → 用户搜不到它，且 content 侧出现 WARN」。
**实测下来，`status=3` 和 `status=2` 走的是两条不同的路，必须分开说：**

| 塞进去的文档 | 检索结果 | content 被调用了吗 | 结论 |
|---|---|---|---|
| `status=3`（已下架，但书在库里） | `total=0` | ★ **完全没调用** | ★★ **ES 侧的 `filter` 就挡住了** —— 第一道闸门单独生效，第二道闸门根本没机会上场 |
| `status=2` 但**库里不存在** | `total=1` 而 `records=[]` | ✅ 调了，WARN `ids=[...]` | ★ 这才是**第二道闸门**的阳性对照 |

★★ **判定性证据是日志的差分**：前者 content 侧**一行 Feign 都没有**，后者**恰好一行、恰好一个 id**。
★ 也就是说那句「且有 WARN」只对**第二种**成立。**写文档时不能合并成一条** ——
合并的后果是：下一个人按 `status=3` 去验 WARN，验不到，然后误判成「第二道闸门坏了」。

#### ③④ 幂等与顺序无关：判据是**逐字节相等**

| 投毒 | 期望 | 实测 |
|---|---|---|
| 同一条合法消息投两遍 | 两次同步，`_source` 不变 | ✅ `_source` **逐字节相同** |
| 两条同一 `novelId`、**因果顺序颠倒**的消息 | 最终文档正确 | ✅ 与顺序投放 `_source` **逐字节相同** |

★ 用 `_source` 的字节比对而不是字段比对 —— 字段比对会漏掉「多了一个字段」这类漂移。

#### ⑥ 重建窗口内搜索仍可用：**30/30 全 200**

重建期间连打 30 次检索：**30 次全 `200`**，1 条降级 WARN，**0 条 `系统异常`**。
★ 窗口内用户可感知的只有排序变化（相关性 → 阅读量），**不是错误**。

★★ **但这条差点得出反向结论**：同时刻另一次测量里 content 的 `/api/novels` 出现 297s、ES health 5.9s 的尖刺，
一度被读成「重建把系统打挂了」。**同一实验、相反结果 → 差异在环境、不在代码。**
★ 判据：**当两次同样的实验给出相反的结论时，先怀疑环境，别急着改代码。**

#### ★★★ 判断 11 的订正 —— 本次最大的发现：一个【静默返回 0】的破坏性失败模式

**实测证据（原样留档）**：

```
=== 索引为空时搜「剑」（ES 路径成功 → 静默 0 条）===
  search  HTTP=200 0.089501s  total=0 ids=[]
  LIKE 真值: total=2 ids=[1011, 1001]
```

★★★ **两种失败态的严重程度是反直觉的**：

```
  索引不存在   → 抛异常 → 降级到 MySQL LIKE → 结果【是全集】，只是排序退化  ← 好
  索引存在但空 → 无异常 → 返回 0 条         → 结果【是空的】，且无人报错    ← 坏
```

★ 为什么 `indexed == 0` 那条 ERROR 拦不住它：**它在 `refill()` 返回之后才执行，而这次 `refill()` 是抛出去的** ——
控制流根本走不到那行。★ 这是一个「护栏写在错误的控制流位置上」的例子：
**它自己没问题，只是它在的那条路上永远不会经过出事的那种情况。**
★ 而且这个窗口**不会自己关上**：索引一直是空的，直到下一个周期（最长 6 小时）或有人手工再点一次。

**处置**：`IndexRebuildService.rebuildAll()` 里把 `refill()` 包进 try/catch，
失败时调 `IndexService.dropQuietly()`（新增，best-effort 删索引、**绝不外抛**）再 rethrow。
★ 让系统停在「索引不存在」这个**可降级**的状态，而不是「索引为空」这个**静默骗人**的状态。

**阳性对照（铁律 8，实测）** —— 停掉 content `:8082` 再触发重建，让 `refill()` 必然失败：

| 步骤 | 观测 |
|---|---|
| `POST /api/admin/search/reindex`（content 已停） | **HTTP 500** |
| ★★ ES 里 `novel_index` 还在吗 | **HTTP 404 —— 被删掉了** |
| 日志 | `13:55:35.522 重建：旧索引已删除` → `.854 索引创建成功` → ★ `.916 WARN 重建失败，已把【空的/半成品】索引删掉，检索将回落到降级路径（MySQL LIKE）` |
| 起回 content 后搜「剑」 | **`total=2 ids=[1011, 1001]`** ← ★ 结果完整 |
| 起回 content 后搜「剑冢」 | `0` ← ★ 降级路径的标记（LIKE 不搜简介） |
| 清锁后再重建 | `skipped:false, indexed:13` → `剑冢` 回到 `[1001]` |
| ★ **`dropQuietly` 全程触发次数** | **1 次** —— 失败那次触发、成功那次不触发 |

★★ 最后一行是这条护栏的**另一个臂**：一条「每次重建都删索引」的护栏会和没有护栏一样糟。
★ 上面那张表里最关键的是**「剑」在索引被删掉后仍然是 2 条** ——
**用户搜得到那两本书**，只是简介命中的能力暂时退化。旧行为下这里是 **0**，书明明在库里。

★★★ **一个副产品：M5 的「临时索引 + alias 原子切换」不再是「更稳一点」了。**
它现在有了一个**可复现的破坏性失败模式**作为立项理由。**这是本次实测最有价值的一条。**

★★ **`indexed == 0` 这一支【刻意不】跟着删索引**，理由已写进代码注释：
判据是不变式「**索引存在 ⟹ 索引里的内容是正确的（空也算正确）**」——
干净库的空索引是对的；契约漂移的空索引是错的，但 **search 分辨不出这两者**。
**能自动处置的自动处置，分不清的宁可留着现场让人来看。**

#### ★★ 顺带答掉了 M3 的 R3：「broker 必须先于消费方服务」从「建议、未实测」升级为「实测、必须」

| 中间件 | 挂掉之后 search/content 能不能起 |
|---|---|
| **ES**（`ES_URIS` 指向死地址） | ✅ **能起**（9.1s，0 条 ES 访问记录） |
| **RocketMQ**（`ROCKETMQ_NAME_SERVER` 指向死地址） | ★★ **起不来** —— `RemotingConnectException: connect to null failed`，抛在 `DefaultMQPushConsumerImpl.start` |

★★ **这个不对称是刻意保留的，不是 bug**：ES 挂在**请求路径**上（可以降级），
MQ 挂在**注册路径**上（消费组必须注册成功才谈得上消费）。
★ 结论：**「broker 先于消费方服务」现在必须写进启动顺序**，M3 文档里那句「建议」要改成「必须」。

★★★ **但必须连这条 caveat 一起写**：测的是 **namesrv 不可达**，**不是「namesrv 在、broker 挂了」**。
★★ 而且它顺带暴露了一个**诊断陷阱**：content 那次启动失败后 **JVM 没退出**（PID 还活着，而端口没在监听）——
失败的 Spring Boot 启动会留下非守护的 Netty/RocketMQ 线程吊住进程。
★ 判据：**「进程在」不等于「服务在」** —— 排错要看端口在不在监听，不是看 PID 在不在。

#### ⑤（停 broker → 改书名 → 仍 200）**本次无法验**，如实记

两个原因，都不是「懒得验」：
1. **没有到虚拟机的免密 SSH** → 停不了 broker 容器；
2. **S6 那个透明 TCP 代理的办法对 RocketMQ 不成立** ——
   客户端是先从 namesrv 学到 broker 的**通告地址**、再**直连那个地址**发消息的，
   在本地架代理只能骗到 namesrv 那一跳。
★ 所以 `NovelIndexNotifier` 的失败隔离**目前只有结构性证据**（`AfterCommit` 契约 + try/catch + `SendCallback`），
**没有实测**。★ 按规矩：**不许把没验过的说成验过了。**

#### ★★ 另一个 MQ 侧的坑：非法 `reason` 会让消息进死信，而且报错在【框架里】不在我们的代码里

S8 最初想用自定义 `reason`（`M4S8_IDEMPOTENT`）来投毒验幂等 ——
**投出去的消息一条都没被消费，直接进了 DLQ**。症状是 6 条 `convert failed`（2 条消息 × 3 次重试）。

★★ 根因：`NovelIndexMessage.Reason` 是个**枚举**，Jackson 反序列化失败的异常抛在
**`rocketmq-spring` 的消息转换阶段**，而那一层在 `@RocketMQMessageListener` 的方法**之前** ——
**我们的 `try/catch` 根本没机会执行**，所以既不会记我们那条「非法载荷」日志，也不会走我们的 ACK 分支，
而是直接交给框架的重试阶梯 → 3 次 → DLQ。

★ 推广（这条比它本身重要）：**「我的消费者代码永远不会抛异常」这个保证的范围，
只覆盖【方法体之内】。方法签名上的类型转换失败发生在那之前。**
★★ 以及一条实操警告：**将来若给 `Reason` 改名或删值，在途消息会【全部】静默进 DLQ** ——
枚举的兼容性不是编译期问题，是运行时问题。

★ 这次往 `%DLQ%group-search-index` 留了 2 条消息。**那是死信该待的地方，不去清。**

#### ★ 环境侧的一条：上一个实例崩掉时留下的锁会挡住重试

`dropQuietly` 那次失败后立刻再点「重建索引」，拿到的是 `{"skipped":true,"indexed":0}` ——
因为**锁不主动释放**，TTL 还有 29 分钟。
★ 这不是 bug（30 分钟 TTL、任务 6 小时一轮），但它是「**不主动释放锁**」这个取舍**第一次有实测症状**。
★★ 顺带证明了 `RebuildReport` 把 `skipped` 单列一个字段是对的：
**「没做」和「做了 0 条」在响应里长得不一样**，管理员不会把跳过误读成成功。

### ★★ S9 —— 网关迁移 + 白名单减法 + 删 `ReaderSearchController`（同一个原子步骤）

#### ★★★ 判定性验证：计划里写的「必须 503」是错的，实测是 **500**

停掉 `novel-search` 进程之后，走网关打四条，**全部 500**：

```
GET  /api/search?keyword=…    500        POST /api/admin/search/reindex  500
GET  /api/search              500        ---- 同时刻的对照 ----
GET  /api/search/             500        GET  /api/novels        200
GET  /api/search?keyword=     500        GET  /api/categories    200
```

网关日志给的根因一句话：

```
io.netty.channel.AbstractChannel$AnnotatedConnectException:
    Connection refused: getsockopt: /127.0.0.1:8084
```

★★ **不是 503，是因为 Nacos 摘除滞后**：进程没了，但实例还挂在注册表里 → 网关照样拿到实例去连 →
连接被拒 → `AbstractErrorWebExceptionHandler` 兜成 **500**。隔一分钟再打仍是 500。
★★★ **这与网关 yml 开头早就写好的一条辨析是同一件事**（「实例在 Nacos 里【有】但连不上 → 500；
实例【没有】→ 503」）。**503 的语义是「一个实例都没有」，停一个进程到不了那个状态。**
★ 所以判据要写成「**非 2xx**」，不能写死 503 ——
**写死 503 的后果是：下一个人跑出 500，会去查「是不是路由没配好」，方向直接跑偏。**
★ 已据此改掉 gateway yml 里那句错断言（并留下实测证据）。

★★ 判据里真正有信息量的那半是**对照**：四条 search 路径**同时**失败，而相邻路由全部 200 ——
**故障被精确定位在 search 这一条路由上，网关整体是好的。**

#### ★ 顺带闭掉了第 ③ 条漏法：`/api/search/` 与裸路径**同生共死**

带斜杠那条**跟着一起 500** —— 若它掉进了 `order: 20` 的兜底（interaction 活着），
停 `novel-search` 对它**不应该有任何影响**。它跟着变，说明 **`Path=/api/search/**` 确实吃掉了带斜杠的形状**。
★★ 这同时**消解了中途那个 401 的歧义**（当时 `GET /api/search/` → 401，两种解释都通）。
**只有「停一个服务看谁跟着死」能分开这两者**，靠状态码本身分不开。

#### 白名单减法的证据 —— ★ 以及一个**没能提供信息**的对照组（如实记）

| 证据 | 内容 |
|---|---|
| **代码** | `ContentSecurityConfig` 里那条 `permitAll` 已删；`ReaderSearchController.java` 已删（`git status` 里是 `D`） |
| **行为** | 直连 `:8082/api/search` → **401**（不是 404） |
| **推理** | 那行若还在 → 授权通过 → 进 handler mapping → 控制器已删 → **必然 404**。拿到 401 ⇒ 授权没过 ⇒ 那行确实不在了 |

★★ **没做到的对照组，说清楚**：理想对照是「白名单仍在 + 控制器已删 → 404」，
但这个组合**在代码里已经不存在了**，造不出来。
★ 另外试过一条日志判据 —— 白名单若在，Spring 会打 `No mapping for GET /api/search`（`PageNotFound`）。
**实测前后都是 0 条**（content 那侧这条 WARN 根本不打），**这条探针是空结果，区分不了**。
★ 支撑 401 是「非白名单路径的通用答复」的旁证：`:8082/api/nonexistent-xyz` → 401、
`/api/definitely-not-here` → 401。
★ **结论按推理强度如实写：行为与代码一致，且 404 是唯一可能的反例。**

#### 三态 + 伪造身份头：★ 401 与 403 的差别本身就是一个可用的判据

`POST /api/admin/search/reindex` 走网关（铁律 3）：

| 身份 | 实测 |
|---|---|
| 匿名 | **401** ✅ |
| READER | **403** ✅ |
| AUTHOR | **403** ✅ |
| ADMIN | **200** ✅ |
| 匿名 `GET /api/search` | **200** ✅（读者端搜索本来就放行） |

直连 `:8084` **逐项补头**，得到一个干净的阶梯：

| 请求头 | 实测 | 说明 |
|---|---|---|
| 只带 `X-Internal-Token` | 401 | 没有身份 → 匿名 |
| ＋ `X-User-Id` | 401 | ★ **身份头不完整**（`HeaderAuthenticationFilter` 明写：令牌有效但缺 `X-User-Id` **或 `X-Jti`**） |
| ＋ `X-Jti` | ★ **403** | ★★ **身份被采纳了** —— 这个 401→403 的跳变只可能发生在「过滤器真的建出了 LoginUser」之后 |
| ＋ `X-Roles: ADMIN` | **200** | 权限也对上了 |
| 全都带 + **令牌是错的** | **401** | ★★ **共享密钥那道锁是真的**：头造得再全，令牌不对就退回匿名 |

★★★ **可推广的判据：在这条链路上，401 = 身份没被采纳（当匿名），403 = 采纳了但权限不够。**
把两者混起来看，就分不清「令牌/头配错了」和「角色不够」——
而这两种故障的修法完全不同（一个是配置，一个是赋权）。

### ★★★ S10 —— 全量验收：**相关性 boost 阳性对照通过**，并逼出两个自己脚本里的错

全量验收跑在一个带时间戳的一次性对象上（`M4S10-20260923-140724`，id **2005**），
**完整证据表在第九节**，这里只记 S10 独有的三件事：

#### ① ★★★ 相关性排序的**判定性证据**（判断 4）—— 种子数据给不出这一条

种子里「剑」只出现在 **1001 / 1011 的书名**里，所以光看它们**排不出 boost 的差别**。
于是让一次性对象的**简介**里含「剑」、书名不含 —— 三方同场：

```
搜「剑」 total=3
  rank1 id=1011    ← 书名命中（boost 3）
  rank2 id=1001    ← 书名命中（boost 3）
  rank3 id=2005    ← ★ 只有简介命中（boost 1）
  PASS
```

★★ **只有「书名命中的排在只有简介命中的前面」这一条同时成立，才证明 boost 真的生效**；
**只看 `total=3` 的话，「随便排」也能排出 3 条。**
★ 这与「剑冢 / 避雨」是同一类判据：**要验一个新行为，必须造一个旧行为给不出同样结果的输入。**

#### ② ★★ 第一版验收脚本第 5 步超时 —— **错在我的顺序，不是代码**

脚本按 `create → submit → audit → rename` 走，改名那步**超时**。查 content 日志：

```
业务异常 | PUT /api/author/novels/2005 | code=2003
msg=「已上架」状态下不能修改作品信息（要改请先改审核结果，或把它下架）
```

★ **这是 P4 的既有规则，代码完全正确**（S7 当时走的正是「先下架 → 改名 → 重新上架」）。
★ 教训：**一个「失败」的第一嫌疑对象应该是「我刚才做的那件事的顺序」，而不是被测系统** ——
尤其是这个系统刚刚通过了 18/18 逐字节回归的时候。详见坑 5。

#### ③ 降级专项（决定 3）三阶段 —— 方法复用 S6 的透明代理，**同一个进程全程未重启**

| 阶段 | 剑 | ★ 剑冢 | ★ 避雨 | HTTP |
|---|---|---|---|---|
| 1 · 经代理（ES 在线） | `[1011,1001]` | `[1001]` | `[1003]` | 200 |
| 2 · **杀代理（ES 不可达）** | `[1011,1001]` | ★★ **`[]`** | ★★ **`[]`** | ★ **200** |
| 3 · **重启代理** | `[1011,1001]` | `[1001]` | `[1003]` | 200 |

★★ **阶段 2 ≡ content `/internal/search` 的逐条输出（含顺序）** —— 这是「降级 = LIKE 原件续用」的判定性证据。
★★★ **阶段 2 的 `剑冢`/`避雨` 必须为空** —— 它们只存在于种子的**简介**里，
而降级路径不搜简介。**若这两行在阶段 2 仍有结果，说明降级根本没生效、ES 还活着**，
那么「降级可用」这个结论就是假的。**这是整个专项最关键的一格。**
★ 收尾已确认代理端口关闭、search 回到默认 `ES_URIS`（`剑冢` 重新变回 `[1001]`）。

#### ④ 收尾：种子与索引全净

| 对象 | 结果 |
|---|---|
| 菜单 `READER / AUTHOR / ADMIN` | ✅ **0 / 2 / 15**，`sys_role_menu` **17 行**，账号 1~4 |
| `novel` | ✅ 未删 **12 本、全 `status=2`、id `1001~1012`**；`id >= 2000 AND deleted = 0` → **0 条** |
| ES `novel_index` | ✅ **12 本、正好 `1001~1012`** |
| 12 个锁定文件 md5 | ✅ 三端 `request.js` 全为 `2047681401d85ed69cebbd069f4ee7dd` |

★ 顺带说明为什么这次能干净收尾：**验收期间创建的 2005 / 2006 两个对象都走了作者端的 `DELETE`**
（不是直接改库）—— 于是它们**顺手把落点 ③ 又验了一遍**（ES 文档 404）。

---

## 八、踩坑记录

### 坑 1 ★★★ 最贵的一个：`PageResult` 让 search 一调降级路径就 500

见 S6 那一节。**它不是「哪个类没加注解」，是一个共享 DTO 里躺着两个第三方类型的工厂方法。**
★ 记这一条的原因是：**它能在编译期通过、能过所有启动探针、能过所有「服务起没起来」的检查，
只在第一次真的走那条路时炸** —— 而那条路（降级）**平时根本不走**。

### 坑 2 ★★ 「写完之后立刻读」在 M4 里有**三个不同的触发源**

| 触发源 | 现象 | 正确做法 |
|---|---|---|
| **MQ 异步** | `submit` 后立刻查 ES → `found:false` | 给它秒级窗口 |
| **ES refresh** | bulk 灌完立刻搜 → 全 0 | 默认 1 秒才可见 |
| **跨 HTTP 测量** | `total` 与 `records` 是两次请求 | 断言前先等窗口，别把两次快照当成一次 |

★★ **判据：「写之后立刻读」不是证据** —— `found:false` 只说明「还没到」，不说明「不会到」。
★ M4 的验收脚本因此**全部改成轮询到谓词成立**（`wait_for`），不再有「写完立刻读」。

### 坑 3 ★★ 中文关键词走 bash argv 会被 ANSI 码页打坏（**查询串版**）

M3 记过 body 版（`curl -d` 传中文 → `Invalid UTF-8 start byte`），M4 撞到的是**查询串版**：

```bash
curl "http://127.0.0.1:8084/api/search?keyword=剑"     # → 服务端收到 keyword=%EF%BF%BD%EF%BF%BD
```

★★ 症状极具误导性：**一个本该有结果的关键词返回 0 条，看起来像「ES 查询写错了」**。
★ 处置：把百分号编码**算好再传**（`urllib.parse.quote`，中文只以 ASCII 形式进 argv）。
★ 编码表留档：`剑=%E5%89%91`、`剑冢=%E5%89%91%E5%86%A2`、`避雨=%E9%81%BF%E9%9B%A8`、`测试=%E6%B5%8B%E8%AF%95`。
★★ 同一族还有一个**只坑 Python 的**：`python` 是 Windows 原生解释器，**不认 Git Bash 的 `/tmp`** ——
**读 stdin 没事**（所以登录取 token 那步一直是好的），但**给它文件路径就会 `FileNotFoundError`**。
M4 里踩到的是**写**：`io.open('/tmp/m4s8/tok_reader.txt','w')` 抛异常 → token 文件没生成 →
那句「READER 打 reindex」用了一个**空 token** → 拿到 **401 而不是 403**，差点被记成「READER 越权失败」。
★ 处置：用 `cygpath -w /tmp/m4s8` 拿到 Windows 形态的路径再传给 Python。

### 坑 4 ★★ `mvn dependency:tree -q` 与「看起来也对、其实什么都验不到」的 grep（M3 记过，M4 又见一个）

M3 记的是网关那条 `dependency:tree | grep` 的两个假阳性写法。**M4 又撞见一个同族的**：
结构回归里那条 `grep -rni "elasticsearch" backend/novel-content backend/novel-gateway → 0 命中`，
**源码目录确实 0 命中，但 `target/*.jar` 命中了两条** —— 因为
**`spring-boot-autoconfigure` 里含有所有技术的自动配置类名**（`.../autoconfigure/data/elasticsearch/*.class`），
`@ConditionalOnClass` 让它们在库不在 classpath 时完全惰性。

★★ **也就是说：这条检查按原样写，在【任何】Boot 应用上都会命中，它区分不了任何东西。**
★ 正确的判据是看 **`BOOT-INF/lib`**：
`unzip -l novel-content.jar | grep -i elastic` → **空**；
而 `unzip -l novel-search.jar | grep -i elastic` → **`elasticsearch-java-8.10.4.jar` 等三个**。
**对照是天然的 —— 「该有的有、不该有的没有」两边同时成立才有信息量。**

### 坑 5 ★★ 上架状态下不能改名 —— 而且这条是**我的验收脚本错了**，不是代码错了

S10 的第一版脚本按 `create → submit → audit → rename → offline` 的顺序跑，
第 5 步「改名后搜新名」**超时**。查日志：

```
业务异常 | PUT /api/author/novels/2005 | code=2003
msg=「已上架」状态下不能修改作品信息（要改请先改审核结果，或把它下架）
```

★ **这是 P4 的既有规则，代码完全正确**；错的是脚本顺序。
S7 当时走的正是**先下架 → 改名 → 重新上架**（回看 S7 的 reason 序列就能看出来：
`TRANSIT_STATUS ← offline` / `UPDATE ← rename` / `SYNC_STAT + TRANSIT_STATUS ← republish`）。
★★ 两条可推广的：
- **一个「失败」的第一嫌疑对象应该是【我刚才做的那件事的顺序】，而不是被测系统** ——
  尤其当这个系统刚刚通过了 18/18 逐字节回归的时候；
- **脚本里把响应体丢掉（`>/dev/null`）的代价是：真因要多花一次往返才查得到。**
  这次是**日志里有**才没走丢，下一次未必。

### 坑 6 ★ 一个 `wait_for` 写法的边界：整条命令被 `$( )` 包住时，函数内部的 `echo` 会混进结果

`ids=[$(wait_for "$NID" ids $T1)]` 打出来的是 `ids=[OK(第1跳)]` ——
**「命中了」这个结论是对的，但打印出来的「id」是那个 OK 字样**。
★ 不影响判定（只要它非空、且 `wait_for` 返回 0 就说明拿到了期望值），
但**读输出的人会被误导**。★ 教训：**把「判定」和「取值」混在同一个函数里，
下一次一定会有人误读** —— 应该让 `wait_for` 只返回码，取值另走一次调用。

### 坑 7 ★ `total` 与 `records` 是两次 HTTP —— 别把它们当成一次快照

S10 第一版脚本第 8 步打出 `搜剑 total=3` 而 `ids=[1011,1001]`（只有 2 个），
看起来像「`total` 算错了」。**实际是删除刚发生，`total` 那次请求落在 ES 的 refresh 窗口里。**
★ 这与坑 2 是同一族，但**触发方式不同**：那次是「写后读同一个接口」，这次是
**「同一个接口的两个字段分两次请求取」** —— 后者更隐蔽，因为**两次单看都正常**。
★ 处置：`total` 与 `records` **必须来自同一次响应**（这一点代码本来就是对的，
错的是我的验收脚本分两次 curl）。订正后 `total=2 ids=[1011,1001]` 立刻一致。

### 坑 8 ★ 我自己的轮询循环把「已经好了」显示成了「等了 10 秒」

```sh
for i in $(seq 1 20); do code=$(...); [ "$code" = 200 ] && break; sleep 10; done
echo "up after ${i}0s"
```
**先判后睡** —— 第 1 跳就成功时 `i=1`，打印「after 10s」，而真实等待是 **0 秒**。
★ 无害，但它让日志里那句「等了 10 秒」**永远是假的**。
★ 判据：**任何带计数的循环，打印的时间必须是实测出来的，不能是 `i × 间隔` 推出来的** ——
否则第一次就成功的那些（也就是最该被信任的那些）报出来的数字全是错的。

---

## 九、验收证据（实测汇总）

### 主链路（大纲 M4 验收原文）

| 场景 | 期望 | 实测 |
|---|---|---|
| 搜书名 | 命中 + 相关性排序 | ✅ `剑 → total=2 [1011,1001]` |
| ★ **相关性排序（判定性）** | 书名命中的排在只有简介命中的前面 | ✅ `剑 → total=3`，rank1 `1011`、rank2 `1001`（书名 boost 3）、rank3 **`2005`**（只有简介 boost 1）**PASS** |
| 搜作者名 | 命中，且没有 100 个作者的召回上限 | ✅ （`LIMIT 100` 已随全表扫描一起消失，见判断 9） |
| ★ 搜简介里的词 | 命中（决定 2 的新行为） | ✅ `剑冢 → [1001]`、`避雨 → [1003]`，**而 LIKE 路径对两者都是 0** |
| 单字关键词 | 有结果 | ✅ `斗` 两条路都是 0（**种子数据里没有含「斗」的书**，不是退化 —— S6 已核实） |
| 未上架 / 已下架 / 待审核 | 搜不到 | ✅ 见下表「四态」 |
| `keyword` 为空 | 空列表（不是 400、不是全部） | ✅ |
| ★ 新书上架 → 搜书名 | 秒级可搜到 | ✅ 2005 审核通过后 `wait_for` 第 1 跳命中 |
| ★ 作品下架 → 搜书名 | 秒级搜不到 | ✅ 第 0 跳（`sleep 2` 后即空） |
| ★ 改名 → 搜新名 | 秒级可搜到 | ✅ 见「落点 ②」 |
| ★ 逻辑删除 → 搜书名 | 秒级搜不到 | ✅ 第 0 跳 |

### 一次性对象 `2005` 全程四态（`M4S10-20260923-140724`）

| 动作 | 库里的 `status` | ES 文档 | 搜得到吗 |
|---|---|---|---|
| `create` | 0 草稿 | ★ **404**（create 刻意的非落点） | ✗ |
| `submit` | 1 待审核 | 200（文档在） | ★ ✗ —— **ES 侧 `filter(status=2)` 挡住了** |
| `audit pass` | 2 已上架 | 200 | ✅ |
| `offline` | 3 已下架 | 200（文档仍在） | ✗ |
| `delete` | — `deleted=1` | ★ **404**（落点 ③ 删掉了） | ✗ |

★★ **前三行合起来是判断 3 + 判断 5「两道闸门」的完整证据**：
`submit` 之后**文档确实进了索引**（说明增量落点在工作），但**用户搜不到**（说明 `status` 过滤在工作）。
**少了任何一行，这个结论都不成立** —— 只看「搜不到」的话，「消息没发出去」和「过滤生效了」长得一模一样。

### 落点 ②（`update` / 改名）—— 顺序错了会验出一个假故障

对象 `2006`（`M4S10R-20260923-140923`）：

| 步骤 | 结果 |
|---|---|
| 上架后搜旧名 | ✅ 命中（第 0 跳） |
| 下架（`status=3`） | — |
| **改名** | ✅ `PUT` → `{"code":200,"message":"保存成功"}`（第一版脚本在这里拿到 `2003`，见坑 5） |
| ★ 索引里 `title` 变了吗 | ✅ **YES** |
| 重新上架 → 搜新名 | ✅ 命中（第 1 跳） |
| ★ 搜旧名 | ✅ **落空** —— 旧名不会残留 |
| 删除 → ES 文档 | ✅ **404** |
| 收尾：搜剑 | ✅ `total=2 ids=[1011,1001]`、ES 文档数 **12** |

### 降级专项（决定 3）—— 三阶段，**同一个 search 进程（PID 33504，启动于 14:10:58）全程未重启**

| keyword | 阶段 1 · 经代理 | 阶段 2 · **杀代理** | 阶段 3 · 重启代理 | 收尾 · 默认 `ES_URIS` |
|---|---|---|---|---|
| 剑 | `[1011,1001]` | `[1011,1001]` | `[1011,1001]` | `[1011,1001]` |
| ★ **剑冢** | `[1001]` | ★★ **`[]`** | `[1001]` | `[1001]` |
| ★ **避雨** | `[1003]` | ★★ **`[]`** | `[1003]` | `[1003]` |
| 修仙 | `[]` | `[]` | `[]` | `[]` |
| 测试 | ES 序 | ★ **≡ LIKE 序** | ES 序 | ES 序 |
| HTTP | 200 | ★ **200** | 200 | 200 |

- ★★ **阶段 2 ≡ content `/internal/search` 的逐条输出**（含 `测试` 的**顺序**）→
  **这是「降级路径 = LIKE 原件续用」的判定性证据**，不是「看起来差不多」。
- ★★★ **阶段 2 的 `剑冢`/`避雨` 为空 —— 这是整个专项里最关键的一格。**
  它们是「ES 真的没在用」的**阳性对照**：若阶段 2 这两行仍有结果，说明降级根本没生效、ES 还活着，
  **那么「降级可用」这个结论就是假的**。
- ★ 阶段 1 ≡ 阶段 3 ≡ 收尾 → **恢复后自动切回 ES，无需重启**（这正是透明代理方案比「停容器」多验到的那一条）。
- ★ 日志：阶段 2 期间 `ES 检索失败，降级到数据库 LIKE | ... | ConnectException: Connection refused` **6 条**
  （每个关键词一条）；`14:10~14:12` 窗口内 **`系统异常` 0 条**。

★ 方法复用自 S6：`ES_URIS=http://127.0.0.1:19200` 指向本地透明代理，
**杀代理 = ES 不可达，重启代理 = ES 恢复**（dev 配置是 `${ES_URIS:http://192.168.127.137:9200}`，
**无需改代码、无需重新打包**）。收尾已确认 `:19200` 关闭、search 回到默认 `ES_URIS`。

### 索引同步专项

| # | 场景 | 实测 |
|---|---|---|
| ① | 手工改坏一个文档 → 一个重建周期内自愈 | ✅ 见 S8 |
| ② | 手工塞一条已下架的书 | ✅ **ES 侧 `filter` 就挡住了**（content 一行 Feign 都没调）；`status=2` 但库里不存在的那条才是**第二道闸门**的对照（见 S8 ②） |
| ③ | 重复投递同一条 `NOVEL_INDEX` | ✅ `_source` **逐字节相同** |
| ④ | 乱序投递两条同一 `novelId` | ✅ `_source` **逐字节相同** |
| ⑤ | 停 broker → 改书名 | ★★ **本次无法实测**，如实记（无免密 SSH 停不了容器；TCP 代理对 RocketMQ 不成立）—— 只有结构性证据 |
| ⑥ | 全量重建进行中搜一次 | ✅ **30/30 全 200**，1 条降级 WARN，0 条 `系统异常` |

### 三态与越权（★ 铁律 3）

| 场景 | 期望 | 实测 |
|---|---|---|
| 匿名 `GET /api/search` | 200 | ✅ **200** |
| 匿名 `POST /api/admin/search/reindex` | 401 | ✅ **401** |
| READER 打 reindex | 403 | ✅ **403** |
| AUTHOR 打 reindex | 403 | ✅ **403** |
| ADMIN 打 reindex | 200 | ✅ **200**（`indexed:13`） |
| 直连 `:8084` 伪造 `X-User-Id`（不带内部令牌） | 401 | ✅ **401**（共享密钥锁生效） |
| 伪造头：不带 token 附 `X-User-Id: 1` 打 `GET /api/search` | 401 | ★ **200** —— **`/api/search` 本来就是 `permitAll`**，头被忽略 → 当匿名 → 200。**判据要用在「匿名 ≠ 已登录」的端点上**（reindex 打对了，401 ✅） |

### 结构回归

| # | 检查 | 实测 |
|---|---|---|
| 1 | 12 个锁定文件 md5 | ✅ 三端 `request.js` 全为 `2047681401d85ed69cebbd069f4ee7dd`（前端**零改动**） |
| 2 | 三端 `npm run build` | ✅ |
| 3 | `grep -rln ReaderSearchController backend/` | ✅ 只剩**四处 javadoc 引用**（content 两处、search 两处），**无实体类** |
| 4 | 网关 yml 的 `/api/search` | ✅ **只活在 `search-query` 路由（`order: 3`）和注释里**；`content-misc` 已删 |
| 5 | 网关依赖树（加宽版） | ✅ 见 M1/M3 那条护栏（M4 未改网关 Java） |
| 6 | ★ **`elasticsearch` 不许漏进别的服务** | ✅ **判据改成看 `BOOT-INF/lib`**：content **0**、gateway **0**、search 有 `spring-data-elasticsearch-5.2.12` + `elasticsearch-java-8.10.4` + `elasticsearch-rest-client-8.10.4`。★ 另：search 的 `BOOT-INF/lib` 里 **mybatis 命中 0** —— 判断 3 的结构性保证成立 |
| 7 | 种子基线 | ✅ 菜单 `READER 0 / AUTHOR 2 / ADMIN 15`、`sys_role_menu` **17 行**、账号 1~4 |
| 8 | `novel.internal.token` | ✅ **五个 `application-dev.yml` 各一处**（网关 + 四个服务）+ 手工 curl = **六处** |
| 9 | ★ 一次性对象残留 | ✅ `novel WHERE id >= 2000 AND deleted = 0` → **0 条**；`novel` 未删 **12 本、全 `status=2`、id `1001~1012`**；ES **12 本、正好 `1001~1012`** |

### 搜索基线对拍（★ 决定 2 的判定性证据）

| keyword | M4 前（MySQL LIKE） | M4 后（ES） | 说明 |
|---|---|---|---|
| 剑 | `total=2 [1011,1001]` | `total=2`，集合同 | 顺序可变（相关性） |
| 测试 | `total=12` | `total=12` | 全库命中 |
| 修仙 | `0` | `0` | ★ **不变的对照** —— 若这里变了，说明哪里出了大问题 |
| ★ **剑冢** | **`0`** | **`[1001]`** | 只在 1001 的**简介**里 |
| ★ **避雨** | **`0`** | **`[1003]`** | 只在 1003 的**简介**里 |

★★ **最后两行是「M4 到底有没有真的改变行为」的唯一判据 —— 不验这两条，M4 可能什么都没做。**
（已用 SQL 核实这两个词**既不在任何书名里、也不在任何作者昵称里**。）

---

## 十、回滚

**M4 是单 commit。** 回滚 = `git revert`（或 `git reset --hard 4546d59`，即 M3 的提交）。

干净程度**不如 M3**，必须说清：

1. ★★ **有一个外部状态：ES 容器与它的 `data/es` 卷。** 回滚代码之后容器还在跑、索引还在 ——
   **不影响旧代码**（旧代码不知道 ES 存在），所以**不需要清**。ES 容器可以留着也可以停，两者都不影响。
2. ★★ **`Dockerfile-es` / `docker-compose.yml` 的改动是唯一一处「改了 M0/M3 的既有文件」** ——
   回滚会一并还原成 `image:`。★ 这也正是 M4 打破 `docs/docker/` 「零改动」那句话的地方。
3. **没有 DDL、没有数据迁移。** 索引是纯派生数据，丢了只是搜不到。
4. **前端 0 改动** → 回滚不需要动前端。
5. 步骤：停**五个**进程 → `git reset --hard 4546d59` → `mvn -f backend/pom.xml package` → 起四个进程。**全程 < 5 分钟。**

★ 一个回滚时容易漏的点：**`ContentSecurityConfig` 的白名单减法与网关路由是同一次提交里的**，
所以 `reset` 之后两者是**一起**回到旧状态的 —— 不会出现「路由回旧、白名单已删」那种半截状态。
**这正是把它们放进同一个原子步骤（S9）的理由。**

---

## 十一、明确不改

| 不动的 | 理由 |
|---|---|
| **12 个锁定文件** | ★ 铁律 2。搜索接口路径没变（`/api/search`），前端**零改动** |
| **`novel-gateway` 的 Java 代码** | 只改了 `application.yml` 的路由表 + 注释 |
| **`docs/P1`~`docs/P6`、`docs/M0`、`docs/M1`、`docs/M2`** | 它们记录的是**当时的事实** |
| ★ **`docs/M3-引入MQ.md`** | 同上 —— **M4 的文档负责写对照（哪条被推翻）**，不去改历史记录 |
| `modules/**` 里除改动点之外的任何业务逻辑 | 只允许「换接缝类型」，不许顺手重构 |
| ★ **`novel-common` 的其余部分** | **只动了 5 个类的条件注解**（判断 10）+ `PageResult` 拆成三个类（S6）、`NovelVO` 下沉（S4）。其余一行不碰 |
| **管理端评论列表的 IN 过长债**（`CommentServiceImpl`） | ★ **与检索层无关，继续延期** —— 写在这里是为了让它**不会从计划表上莫名消失** |
| **服务级环 #2**（`novel-content ⇄ novel-interaction`） | M3 解不掉（需要评论正文快照 DDL），**M4 也不碰** |
| **`ReaderSearchController` 的那条安全规矩本身** | ★ **这条规矩随代码一起搬走了，一个字没改** —— 见 `SearchController` 的类注释与 `NovelService:583` 的引用。**搬走的类也要带着它的规矩走**，否则「匿名放行的接口必须自带可见性条件」这句话会在新服务里失传 |

---

## 十二、记一笔（M5 / 后续债，M4 只记录不处理）

- ★★ **服务级环 #2 `novel-content ⇄ novel-interaction` 仍在** —— 要解需要「审核时把评论正文快照写进 `audit_log`」（DDL）→ M5
- **`FeignIdentityConfig` 从三份副本变成四份**（新增 search 那份）→ M5 治理
  - ★★ **M5 订正：这个数字是错的，实测是「两份变三份」。**
    `find backend -name FeignIdentityConfig.java`（M5 开工前）→ **3 个**：content / interaction / search。
    **auth 那份在 M3 就随整条边一起删掉了** —— M4 那句话把**已经删掉的那一份算了进来**。
    ★ 差别的来源：M4 是**照 M2 的结论往后推**的（M2 时确实是三份），
    而 M3 删 auth 的 Feign 依赖时**没有回头改 M2 那句话**。
    ★ 教训与铁律 8 同源：**「份数」这种判据要现数，不要从前一期的文档里继承** ——
    它会在中间某一期被别的改动顺手改掉，而**没有任何东西会报错**。
  - ★ M5 之后这 3 份合并成了 `novel-common` 里的**单源 1 份**（见 `docs/M5-治理.md` §8.2）。
- ★★ **`HeaderAuthenticationFilter` 本身【不是】副本** —— 它全项目只有一份，住在
  `novel-common/.../common/security/filter/`。重复的是**每个服务 `SecurityConfig` 里注册它的那几行**
  （`addFilterBefore` + `FilterRegistrationBean`）。★ 写成「过滤器有 4 份副本」是错的，
  而且这个错误会让「M5 治理」显得比实际简单（真要治理的是**注册样板**，不是过滤器）
- **网关的同步 Redis 调用**（`JwtAuthGlobalFilter`）→ M5；M4 **只改路由表，不动 Java**
- `/internal/**` 的强鉴权仍是共享密钥头 → M5
- 上传目录跨进程共享 → M5 对象存储
- dev `ASYNC_FLUSH`+`ASYNC_MASTER` vs prod `SYNC_FLUSH`+`SYNC_MASTER` → M5（M3 文档已记）
- ★★ **ES 的 `xpack.security.enabled: false`** —— 容器定义里自己写了「生产环境绝对不能这么配」。
  **M4 沿用 dev 配置，但这里必须点出这个 dev/prod 差异**，否则面试时会被追问
- ★★★ **全量重建的 alias 原子切换** —— ★ **它不再是「更稳一点」，而是「修掉一个已实测的破坏性失败模式」**：
  索引为空且不报错 → 静默返回 `total=0`（判断 11 / S8）。**这是它现在排在最前面的理由**
- ★ **搜索高亮**：`NovelCard` 是首页/分类/榜单/搜索**四处共用**的组件，加高亮会影响另外三处 → 需要先拆组件，M5 再说
- ★ **`POST /api/admin/search/reindex` 现在是同步的**（大库上会顶到网关超时）→ 改成「提交任务 + 轮询进度」，M5
- **`novel-search` 的 `:8084` 在生产上必须只对内网开放**（同 `:8081`/`:8082`/`:8083`）
- ★ **`SearchController` 的翻页深度上限**（`max_result_window = 10000`）现在是「超了就不查」（R15），
  对用户表现为「翻到第 N 页突然空了」→ 更好的处置是前端限制页码，M5

---

## 十三、逐条回应 M3 对 M4 的预测

| 预测 | 出处 | 判定 |
|---|---|---|
| 「不碰 RocketMQ / ES（那是 M3 / M4）」 | `docs/M0-骨架搭建.md:33` | **兑现** |
| 「`SchedulingConfig` 类本身保留（**M4 的章节发布对账要用**）」 | `docs/M3-引入MQ.md:965` | ★ **推翻**：判断 1 把 `syncNovelStat` 留在本地同事务 → **没有对账需求**。但类**仍要用** —— 用途换成「定时全量重建」，且按类注释它属于**计算型 → 必须加锁**。★ M4 给那条规则补了一个新范例（`IndexRebuildJob`，`SET NX EX` + 不主动释放） |
| 「`CHAPTER_PUBLISHED` **事务消息** + ES 索引重建 → M4」 | `docs/M3-引入MQ.md:993` | ★ **部分推翻**：索引重建**兑现**；Tag 名要改（判断 2）、**事务消息改成 `AfterCommit`**（判断 6） |
| 「interaction → content 的边从 8 条降到 4 条左右」 | `docs/M3-引入MQ.md:987` | ★ **M3 文档本身就不准**：核实下来是 **6 条**。**本文件在此更正这个数字** |
| 「管理端评论列表的 IN 过长债，M4 上 ES 时随检索层一起重做」 | `docs/M2:373,763` | ★ **继续延期** —— 与检索层无关 |
| 「真正的相关性要等二期接入 ES」 | `NovelServiceImpl:258-268`、`ReaderSearchController:58-60` | **兑现** → 这两处注释按自己说的「那时就该删掉」处理 ✅ |
| 「M4 搜索会上 ES，那时它会变成 `novel-search`」 | `ReaderSearchController:31-32` | **兑现** → 「单独一个类正好是那个接缝」**被证明是对的** ✅ |
| 「`ContentUserLookup:153` / `InternalUserQueryService:130`：M4 上 ES 解决这个全表扫描」 | 代码注释 | ★ **部分兑现**：主路径解决了；**但降级路径仍然用它**（决定 3），所以**接缝不能删**，注释已改写成「主路径不再经过它，降级路径仍然依赖」 |

★ 另有两条 M3 遗留的**未实测项被 M4 顺手闭掉**：

| M3 遗留 | M4 的处置 |
|---|---|
| **R3**「broker 挂着时服务能不能起」标着未实测 | ★★ **闭掉，并把结论从「建议」升级为「必须」**：namesrv 不可达时 search **起不来**（`RemotingConnectException` 抛在 `DefaultMQPushConsumerImpl.start`）。★ **必须连 caveat 一起引用**：测的是 **namesrv 不可达**，不是「namesrv 在、broker 挂了」 |
| **§十三** 那条事务消息的路线 | 如上，判断 6 已推翻并给出理由 |

---

## 十四、这一期最该记住的三句话

**① 一条「索引可能过期了」的消息，比一条「把新数据推过去」的消息省一整个可靠性机制。**
`NOVEL_INDEX` 的载荷只有一个 id —— 消费者读的是**当时的真值**，
于是**幂等天然、顺序无关、不需要 SETNX、不需要时间戳**（判断 2）。
★ 更一般的说法：**推「去重算一次」这个指令，永远比推「算好的结果」好维护。**

**② 那些「能被 catch 吞掉的错误」，必须用【正面验证】去验，不能靠「没看到报错」下结论。**
M4 里这类错误出现了三次，一次比一次隐蔽：
`missing` 写进 mapping（建索引 400 → 永远走降级 → **M4 一行都不生效，日志里只有一行 WARN**）、
`PageResult` 里的 `IPage`（降级路径一调就 500）、
`createIndex` 成功但 `refill` 失败（**索引存在且为空 → 静默返回 0 条**）。
★★ **三者共同点：接口返回 200、没有异常、看起来一切正常。**
★ 所以 M4 的验收里，「**剑冢 / 避雨**」这两个词的地位比其它所有关键词加起来都高 ——
**它们是唯一能证明「ES 真的在干活」的两个词**（LIKE 路径对它们恒为 0）。

**③ 一条不会失败的护栏比没有护栏更糟 —— 而这个「不会失败」有【两种】形状。**
一种是 M3 记的**阈值式**（`_analyze`「多于 1 个 token」，IK 的 3 个和 standard 的 4 个都通过）；
M4 又撞见另一种：**判据本身永远为真**（`grep -rn elasticsearch` —— 每个 Boot jar 里都有自动配置类名，
**在任何 Boot 应用上都会命中，区分不了任何东西**）。
★★ 两种的解药是同一个：**换成差分式判据（和对照组比）**——
`ik_max_word` 对 `standard`、`BOOT-INF/lib` 对源码目录、**降级后的 `剑冢` 对 ES 在线时的 `剑冢`**。
★ 因为**对照组的对齐是自动的**，而阈值是你自己拍的。
