# M2 · 摘出 content + 跨服务边全部改走 Feign

> 第二期第三个里程碑。上一个是 `docs/M1-摘出auth.md`（网关接管 JWT、auth 独立）。
>
> **M2 做的事一句话**：把 `modules/content` + `modules/ai` 从单体里搬进独立进程
> **`novel-content:8082`**，把 `novel-backend` 改名 **`novel-interaction`** 退到 **`:8083`**，
> 两个服务之间的数据边**全部改走 Feign**。

---

## 一、验收标准（大纲原文）

`项目大纲.md:1301`：

```
| **M2 摘出 content**（2~3 天） | 小说 / 章节 / 分类 / 审核迁到 content 服务，auth 与 content 之间用 Feign
                              | 详情页跨服务取到作者信息，业务无回归 |
```

拆开是三件事，**第三件才是验收标准**：

1. 小说 / 章节 / 分类 / 审核（+ AI）迁到 content 服务
2. auth 与 content 之间用 Feign
3. ★ **详情页跨服务取到作者信息，业务无回归** —— `:8080` 仍是唯一入口，
   三态（401 / 403 / 200+code）、匿名只读、P6 全写路径，**业务行为逐字节不变**

★ 沿用 M1 那条贯穿全文的取舍原则：**宁可多写一层搬运，也不改业务语义。**
凡遇到「这样更优雅但会改变可观察行为」和「这样笨一点但行为一模一样」的分叉，一律选后者。

★ **M1 留下的这一枪，M2 来兑现**：M1 把「服务边界单向」按 HTTP 重新表述成
「**业务语义的接口住在消费方；提供方只暴露 URL，不暴露类型**」——
M1 只有 2 条边，M2 有 **15 个端点 / 4 个方向**，是这条新表述第一次被大规模检验。

### 开工前的实测事实（全部有据，不是推断）

| 事实 | 怎么核实的 |
|---|---|
| `modules/content` **零编译期依赖** on interaction / ai | `grep -rn "modules\.interaction" modules/content` → 空 |
| `modules/ai` **零依赖** on content / interaction | 只 import `com.novel.common.*` 和自己 |
| **没有任何地方** import `modules.ai` | ai 是叶子 |
| 全仓活跃 `@PreAuthorize` **0 个** | 鉴权 100% 靠 URL 前缀闸 → **劈分不需要搬任何方法级鉴权** |
| 前端 **100% 零改动** | 三端调用全是相对 `/api`，`baseURL: '/api'` |
| **12 个锁定文件一个字节都不用动** | 同上（★ 铁律 2） |
| `ChapterStatus` / `NovelStatus` / `AuditTargetType` / `AuditAction` 都在 `novel-common` | 跨界类型天然可共享，不引入 content 实体耦合 |
| ★ `modules/content` **确实用 Redis** | `NovelViewCounter` 注入 `StringRedisTemplate`（阅读量脏集 + 定时回写） |
| ★ `RedisConfig` 只有一个 bean `redisTemplate(RedisConnectionFactory)` | 随 `com.novel` 被**每个服务**扫到 → content **必须**带 Redis 客户端（决定 A） |

### 端点归属（M2 前后对比）

| 服务 | 端口 | 模块 | 公开端点（Mapping 数） |
|---|---|---|---|
| `novel-gateway` | 8080 | —— | 0（只有路由与过滤器） |
| `novel-auth` | 8081 | `modules/system` | 30 |
| `novel-content` | **8082** | `modules/content` + `modules/ai` | **51**（46 + 5） |
| `novel-interaction` | **8083** | `modules/interaction` | 18 |

★ 另有**内部端点** 14 个（`/internal/**`，不对外）：auth 4 / content 9 / interaction 1。

---

## 二、目标结构

```
浏览器 :3000
  └─ dev-proxy（开发期）/ Nginx（生产）
       └─ :8080  novel-gateway   ★ 唯一入口，唯一验 JWT 的地方
            ├─ lb://novel-auth        → :8081  modules/system      （登录/用户/角色菜单/公告/作者申请/ping）
            ├─ lb://novel-content     → :8082  modules/content+ai  （作品/章节/分类/审核/搜索/AI）
            └─ lb://novel-interaction → :8083  modules/interaction （评论/书架/历史）
```

| 进程 | 端口 | M1 之后是什么 | M2 之后 |
|---|---|---|---|
| `novel-gateway` | 8080 | 验 JWT、写身份头 | **路由表重写**：同前缀二分（见 §五） |
| `novel-auth` | 8081 | 整个 `modules/system` | **只改一处**：`BackendAuditClient` → `ContentAuditClient` |
| `novel-content` | **8082** | 不存在 | **新增**，`modules/content` + `modules/ai` |
| `novel-interaction` | **8083** | 叫 `novel-backend`，:8090，装 content+interaction+ai | **改名 + 退端口**，只剩 `modules/interaction` |

### ★ 包名一个都没改

`com.novel.modules.content.*` / `com.novel.modules.ai.*` / `com.novel.modules.interaction.*` 原地保留。
沿用 M1 的理由（`docs/P2-内容主干.md:56` 那条「一个包对应将来要拆出去的一个服务」），
另加两条 M2 特有的：

1. `mybatis-plus.type-aliases-package: com.novel.modules.**.entity` 在三个服务里**逐字不改**就继续生效
   —— 各自的 classpath 只装着自己的模块（和 M1 的 `@MapperScan` 同一个道理）。
2. `git mv` 是纯物理搬家，`git log --stat` 里是 `R` 而不是 `A`+`D` —— 这是**结构回归的判定样本**
   （实测 **108 个 `R`**，见 §十一 ⑫）。

### ★ 与大纲 §四 目录结构的已知偏差（**别以为是漏改**）

大纲写的是 `.../content/modules/{novel,chapter,category,audit}/`，
我们保留 `modules/content/{controller,service,mapper,entity,dto,vo}/`。
理由与 M1 相同：**一个包对应将来要拆出去的一个服务**，`modules/content/` 整体就是那个服务。
`docs/P2` 已经写过这条约定，保留包名就是兑现它。

---

## 三、已拍板的六个决定（开工前定的，实施中没动摇）

| # | 决定 | 后果 |
|---|---|---|
| 1 | **一个 `novel` schema + 表归属独占**（不物理拆库） | 大纲 §三约束 6 要的是「每个服务只访问自己的表」，一个 schema 完全满足。M2 **只修 1 处**跨表读 |
| 2 | **interaction → content 接缝一次划干净，全走 Feign** | 延续 M1 决定 2。不留「共享库直查」的中间态 |
| 3 | **`NovelBriefVO` / `ChapterBriefVO` 搬进 `novel-common` 共用一份** | 两侧不各写一份，避免漂移 |
| 4 | **`/api/admin/dashboard` 留 content 共享库直查，记为 M5 展示项** | **M2 唯一的刻意例外**，类注释里写明理由（见 §八） |
| 5 | **`modules/ai` 跟 content 一起搬进 `novel-content`** | 照大纲 §三约束 4 / §11.1 服务表 / §四目录结构三处原文 |
| 6 | **`novel-backend` 改名 `novel-interaction`，端口 8090 → 8083** | 照大纲目录结构（`:8083 互动域`）。纯 rename，`git` 显示 `R` |

★ 决定 1 在**回滚**上有个直接好处，见 §十二。
★ 决定 6 是**唯一**一个「改了就不存在」的名字：实测全仓 `novel-backend` 的剩余命中
全部是**明确框成历史**的句子（见 §十三），没有一句现在时假话。

---

## 四、三条被代码「逼」出来的结构决定（不是审美）

### 决定 A：`novel-content` **必须**带 Redis 客户端 —— **不是因为要迁就 common**

`RedisConfig` 是 `com.novel.common.config` 下的 `@Configuration`，唯一 bean 是
`redisTemplate(RedisConnectionFactory)`。三个服务的启动类都在 `com.novel`，**默认扫描一定能扫到它**。

> 少了 Redis 客户端，Spring Boot 就不会自动配置 `RedisConnectionFactory`，
> 于是 `redisTemplate` 这个 `@Bean` 找不到参数 → **启动直接失败**。
> 而且报错指向的是「`RedisConfig` 缺一个 bean」，**不指向「你少加了一个依赖」**。

★ 反过来说：content **本来就真的需要 Redis** —— `NovelViewCounter` 用 `StringRedisTemplate`
做阅读量脏集（`NOVEL_VIEWS` + `NOVEL_VIEW_DIRTY`）并按 `SchedulingConfig` 定时回写。
所以这不是「为迁就 common 而加的依赖」，它本来就在。

★★ **同理 `novel-interaction` 也要保留这套配置** —— `modules/interaction` 自己不直接读写 Redis，
但 `RedisConfig` 一样会被扫到。**这条不在 M2 的改动清单里（它是保留，不是新增），
但必须写在这里**，否则将来有人「清理没用的 Redis 依赖」时会在 interaction 上踩同一个坑。

### 决定 B：**不把 `FeignIdentityConfig` 搬进 common —— 保持每个消费方一份**

M2 之后仓库里有**三份互为副本**的 `FeignIdentityConfig`：

| 位置 | 写 `X-User-*` + `X-Internal-Token` | Retryer |
|---|---|---|
| `novel-auth/.../client/` | ✓ | `NEVER_RETRY` |
| `novel-content/.../modules/content/client/` | ✓ | **无**（用 Feign 默认重试） |
| `novel-interaction/.../interaction/client/` | ✓ | `NEVER_RETRY`（**M2 新写的**，照 novel-auth 抄） |

**为什么不在 M2 顺手合并成单源**：合并就要在 `novel-common` 里加
`<optional>spring-cloud-starter-openfeign</optional>`，且必须为「两份 Retryer 语义不同」选一个赢家 ——
那是**行为变更**（content 现有的读接口会失去重试），与 M2「业务无回归」的目标相冲突。
**记成 M5 治理项**，M2 不动。

★ 为什么 interaction 这份要 `NEVER_RETRY`：它调的 8 个端点里有 3 个是**写**
（`collect-count` / `score` / `comment-audit-logs`），Feign 默认的 `Retryer.Default`
（最多 5 次尝试）会在超时时重发。★ 实际上那两处写的都是**绝对值**（不是 `+1`），
所以重发幂等 —— **但这是运气，不是设计**，所以仍然关掉。

### 决定 C：**消费方各自声明自己用得到的 Feign 接口**，不做「一个共享客户端」

`AuthUserClient` 原来住在 `modules/content/client`，4 个方法。M2 之后 interaction **也需要**调 auth
取昵称（`CommentServiceImpl` 渲染评论者昵称，复用 M1 就有的 `POST /internal/users/nicknames`）。

- **content**：`AuthUserClient` 4 个方法**原样跟 content 搬走**，零改动。
- **interaction**：**新写一个窄客户端** `AuthNicknameClient`，只要 1 个方法。

★ **为什么不把它提到 `novel-common` 共用**：`novel-auth` 的启动类也在 `com.novel`，
`@EnableFeignClients` 会把它注册成**指向自己的** Feign 客户端 —— 那正是铁律 1 点名的
「**能编译、能被扫描、永远不会被调用的死 bean**」。而且 interaction 确实只需要 1 个方法，
声明 4 个是用不上的面。**按铁律「接口住在消费方」，各声明各的。**

---

## 五、★★ 网关路由表（M2 风险最高的地方）

### 事实：`/api/novels/**` 与 `/api/admin/**` **被两个模块共享**

逐方法核对全部 `@RequestMapping` 之后确认：

| 前缀 | content 占用 | interaction 占用 | 能否一条 `/**` 划开 |
|---|---|---|---|
| `/api/novels/**` | `""`、`/rank`、`/{id}`、`/{id}/chapters`、`/{id}/catalog`、`/{id}/chapters/{cid}`、`POST /{id}/view` | `/{n}/comments`(GET+POST)、`/{n}/chapters/{c}/comments`(GET+POST)、`/{n}/comments/{c}/replies`(GET) | ✗ **不能** |
| `/api/admin/**` | `novels`、`chapters`、`categories`、`logs`、`dashboard` | `comments` | ✗ **不能** |
| `/api/author/**` | 4 个 Controller 全部 | — | ✓ |
| `/api/categories`、`/api/search` | 各 1 个方法 | — | ✓（精确路径，无子路径） |
| `/api/reader/shelf`、`/api/reader/history` | — | 各 1 个 Controller | ✓（**走兜底即可**） |
| `/api/ai/**` | `modules/ai` 5 个方法 | — | ✓ |

★ **区分 `/api/novels/{a}/chapters/{b}` 的唯一特征，是末尾多出的 `comments` 段**：
content 是 `GET .../{cid}`（章节正文），interaction 是 `GET .../{chapterId}/comments`（章评）。

### 新路由表（`novel-gateway/src/main/resources/application.yml`）

| order | id | uri | Path |
|---|---|---|---|
| **1** | `auth-*` × **10 条** | `lb://novel-auth` | `/api/auth/**`、`/api/ping/**`、`/api/notices/**`、`/api/reader/profile/**`、`/api/reader/author-apply/**`、`/api/admin/{users,roles,menus,notices,author-applies}/**` |
| **4** | `interaction-novel-comments` | `lb://novel-interaction` | `/api/novels/*/comments`、`/api/novels/*/comments/**` |
| **4** | `interaction-novel-chapter-comments` | `lb://novel-interaction` | `/api/novels/*/chapters/*/comments` |
| **4** | `interaction-admin-comments` | `lb://novel-interaction` | `/api/admin/comments`、`/api/admin/comments/**` |
| **5** | `content-novels` | `lb://novel-content` | `/api/novels`、`/api/novels/**` |
| **5** | `content-author` | `lb://novel-content` | `/api/author`、`/api/author/**` |
| **5** | `content-admin` | `lb://novel-content` | `/api/admin`、`/api/admin/**` |
| **5** | `content-misc` | `lb://novel-content` | `/api/categories`、`/api/search`、`/api/ai`、`/api/ai/**` |
| **10** | `backend-uploads` | `lb://novel-interaction` | `/uploads/**`（**原样不动**，共享绝对路径目录，见 R10） |
| **20** | `interaction-api` | `lb://novel-interaction` | `/api/**`（★ **从 order 10 挪到 20**） |

★ **为什么兜底要从 `order: 10` 挪到 `20`**：`order` 相同时路由的命中顺序**没有保证**
（Spring Cloud Gateway 按 `order` 升序、同序时行为不可依赖）。把兜底放到 20 就空出 `1~19` 一整段
给具体路由，将来任何人加一条具体路由都不会和它打平。
**症状对照**：content 路由被兜底吞掉的表象是**静默 404**，看起来完全像「这个接口没实现」。

★★ **写进 yml 注释的不变式**：

> 任何与 `/api/novels/**` 或 `/api/admin/**` **重叠**的 interaction 路由，
> `order` 必须**严格小于** content 的对应规则（现为 **4 < 5**）。

★ `/api/reader/shelf/**` 与 `/api/reader/history/**` **不需要新规则** —— 它们落到 order 20 的兜底
→ interaction，而 auth 的 `/api/reader/profile/**`、`/api/reader/author-apply/**` 在 order 1 已经先命中。
**这个「不需要」也写进了注释**，否则后人会以为漏配了。

★ **网关不做路径白名单** —— 每个服务自己的 `SecurityConfig` 才是白名单的所在地（见 §六）。

---

## 六、★★ SecurityConfig 劈分（**头号静默回归风险**）

M1 之后单体的 `SecurityConfig` 里有一条 **`GET /api/novels/**` → `permitAll()`**。
它**顺带**放行了 interaction 的三条评论读接口 —— 因为那时候它们在同一个进程里。

**拆开之后这条通配符不再覆盖 interaction**。症状：

> 游客（未登录）打开作品详情页看**评论列表** → 从 **200** 变成 **401**
> → 前端 `request.js` 的 `handleUnauthorized` 清登录态跳登录页
> → **而只有真的点一次才会发现**（没有登录的人不会报错，只会被弹去登录页）。

### 劈分结果

**两边都要**（基础设施）：`ASYNC` dispatcher（★ 必须排最前，SSE 收尾）、`OPTIONS /**`、
`/doc.html`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-resources/**`、
`/favicon.ico`、`/error`（★ 少了会「401 套 401」）、`/uploads/**`、
`/internal/**`（permitAll + `HeaderAuthenticationFilter` 的令牌闸）、`anyRequest().authenticated()`。

**跟 `novel-content` 走**（★ `GET /api/novels/**` **必须收窄**）：

```
GET  /api/novels            GET  /api/novels/rank        GET  /api/novels/*
GET  /api/novels/*/chapters GET  /api/novels/*/catalog   GET  /api/novels/*/chapters/*
POST /api/novels/*/view     ← ★ 单星号，别写成 /**（会连带放行 interaction 的写接口）
GET  /api/categories        GET  /api/search
/api/author/**  → hasAnyRole("AUTHOR","ADMIN")
/api/ai/**      → hasAnyRole("AUTHOR","ADMIN")
/api/admin/**   → hasRole("ADMIN")
```

★ **为什么收窄而不是留 `GET /api/novels/**`**：留着不会立刻出洞（评论那几条已经被路由去 interaction 了），
但它是**纵深防御** —— 将来 content 在 `/api/novels/**` 下新增一个需要登录的接口时，
通配符会**静默**把它放行。收窄是写死 7 条，改动成本一次性。

**跟 `novel-interaction` 走**（★ **三条必须新增**，M2 之前全靠 `GET /api/novels/**` 蹭到）：

```
GET /api/novels/*/comments
GET /api/novels/*/chapters/*/comments
GET /api/novels/*/comments/*/replies
/api/admin/**   → hasRole("ADMIN")     ← 自己那份，覆盖 /api/admin/comments
```

★ **绝不能留 `GET /api/novels/**`** —— 那会把 content 的正文读接口在 interaction 里也开一遍。
★ 三个 `POST` 评论接口**刻意不在白名单**：它们落到 `anyRequest().authenticated()`，
和 M2 之前**逐字一致**（同一路径的 GET 匿名 / POST 需登录，`ReaderCommentController` 的类注释专门论证过）。

---

## 七、跨服务边 → Feign

按铁律 1 的 M1 版表述：**业务语义的接口住在消费方；提供方只暴露 URL，不暴露类型。**

### 全景：4 个方向 / 5 个 Feign 客户端 / 15 个方法

| 方向 | 客户端 | 方法数 | 消费方住哪 |
|---|---|---|---|
| interaction → content | `ContentClient` | **8** | `novel-interaction/.../interaction/client/` |
| content → interaction | `CommentBriefClient` | **1** | `novel-content/.../modules/content/client/` |
| content → auth | `AuthUserClient` | **4** | `novel-content/.../modules/content/client/` |
| interaction → auth | `AuthNicknameClient` | **1** | `novel-interaction/.../interaction/client/` |
| auth → content | `ContentAuditClient` | **1** | `novel-auth/.../auth/client/` |

★ 提供方那一侧的落点（`/internal/**`，共 14 个端点）：
auth 4（`InternalUserController` 3 + `InternalAuthorApplyController` 1）/
content 9（`InternalContentController` 7 + `InternalAuditLogController` 2）/ interaction 1（`InternalCommentController`）。

### ★★ `ContentGateway`：把「拆信封」收成唯一一处实现

`ContentClient` 的每个方法都返回 `Result<T>` 信封，而 **HTTP 200 不等于成功**（铁律 3）。
于是每个调用点都要做同一件事：**拆信封，失败就抛**。

**漏掉它的后果不是「报错」，是「静默失效」**：

```java
contentClient.requirePublished(novelId);   // ← 返回值没人看
```

这一行**能编译、能跑、不报错**，而「作品已上架」这个校验**彻底不存在了**：
给未上架的书发评论、给不存在的书加书架、给别人的章节刷章评 —— 全部成功。
而它坏掉的样子非常隐蔽：功能测试全绿（正常路径本来就该成功），
**只有真的去点一本下架的书才会发现**。

所以 interaction 侧的对 content 调用**全部**经过 `ContentGateway`，它是「interaction 望向 content 的
唯一一个窗口」，职责和 content 望 auth 的 `ContentUserLookup` 完全对称。

### ★★ `InternalResults` 两个解包方法的选择**不是风格问题**

| 方法 | 失败时 | 用在哪 | 用错的症状 |
|---|---|---|---|
| `unwrapBusiness` | **把对端的业务码原样抛回** | `requirePublished`(2001)、`chapterStatus`(3001) | 用户点一本下架的书，看到的是**「服务器开小差了」而不是「作品不存在或未上架」**，且监控上凭空多出一批假 5xx |
| `unwrap` | 翻成 **500** | 其余 6 个（批量取摘要 / 章节 id / 写回 / 写流水） | 对端的业务码在那些场景**没有意义**（「评论查不到」不是错误，是「被删了」，以「不在 Map 里」表达） |

判据：**对端执行的是不是「调用方要转达给前端的那条业务规则」**。是 → `unwrapBusiness`；不是 → `unwrap`。

★ **实测证据**：`requirePublished` 的 2001 与 `chapterStatus` 的 3001 都**穿过了 Feign 原样到达前端**
（见 §十一 的 [7] 和 [8]）。

### ★ Feign 的三个操作细节

1. **用 `POST + @RequestBody List<Long>` 取批量摘要，不用 GET query** —— id 列表可能很长，
   URL 长度是硬约束。镜像 `InternalUserController` 的 `POST /internal/users/nicknames` 既有范式。
2. **`refreshCollectCount` 第二参是 `long`（基本类型）、`refreshScore` 是 `BigDecimal`** —— 没顺手改成 `Long`/`double`。
3. **`recordCommentAudit` 刻意不带 `AuditTargetType` 参数**：改造前是
   `auditLogService.record(COMMENT, commentId, action, remark)`，类型由调用方传。
   现在对端那个端点**只接受评论，类型写死**。★ **少一个参数就少一个能用错的面** ——
   改造前若手滑写成 `record(AUTHOR_APPLY, commentId, ...)`，会往流水表里写一条
   「作者申请 #5 通过」的假记录，**能编译、不报错，而流水表只追加不可改，那条假记录删不掉**。

### ★★ M2 造出第二个【服务级】环：`novel-content ⇄ novel-interaction`

- interaction → content：**8** 个端点（上面的 `ContentClient`）
- content → interaction：**1** 个端点（管理端审核日志要显示**评论正文**）

它和 M1 的 `novel-auth ⇄ novel-content` 同源 —— 都是「同进程 DI 接缝」变成「HTTP 调用」的必然产物，
且**两条接缝都在铁律 1 的原文里被明确列为受认可的例外**。

★★ **但要诚实记一笔**：M1 那个环，M3 的 MQ 能解（审核事件异步化）。
**这一个不一定** —— 「管理端审核日志列表要显示评论正文」是一次**同步读**，MQ 解不了，
除非改成「审核时把正文快照写进 `audit_log`」，**那是 DDL、属于 M5**。
**它可能活到 M5，别以为 M3 一定会顺手解掉。**

---

## 八、两处跨表读：一处必须修，一处刻意留

### 必须修：`NovelCommentMapper.selectAdminPage`

原来是内联子查询：

```sql
AND (target_type = 2 AND target_id IN (SELECT id FROM chapter WHERE novel_id = #{novelId}))
```

`chapter` 是 content 的表 —— 这是 M2 **唯一的表归属违规**（决定 1）。

**改法**：`CommentServiceImpl.adminPage` 先调 `contentGateway.chapterIdsOfNovel(novelId)`，
把 `List<Long>` 传进 SQL，用 `<foreach>` 展开成 `IN (...)`。

★★ **空列表的处理是 M2 最微妙的一处**，三种写法里只有一个是对的：

| 写法 | 结果 |
|---|---|
| 只 `<if>` 里面的 `foreach` | 空集合生成 `IN ()` → **SQL 语法错误** → 一本没有章节的新书 → 管理端评论页 **500** |
| 空集合直接**返回空页** | 会把这本书的**书评一起吞掉**（书评不经章节）→ **静默少数据** |
| ★ **整段（含 `OR`）一起 `<if>` 掉** | 条件退化成「只要书评」，**和原来那个空子查询的行为逐字一致** ✓ |

★ **这个坑改造前不存在**（原来是个子查询，空子查询合法），是 M2 把子查询拆成两次查询**新造出来**的。
★ 实测两种边界都验过：有 3 条评论的书 → `[90, 91, 92]`；**一本没有章节的书** → 它的书评照样返回。

★ **记一笔债**：章节数理论上无上限，`IN` 列表过长会撑爆 SQL。
M2 加了一个 **2000 章**的 WARN 阈值（**只告警，不截断** —— 截断会静默吞掉章评，
比慢一点严重得多）。M4 上 ES 时这块会重做。

### 刻意留着：`DashboardMapper`（决定 4）

它读 `sys_user` / `author_apply` / `novel_comment` 三张不属于 content 的表。
**这是 M2 唯一的刻意例外**，类注释已经从「将来要处理」改写成
「**这是刻意的例外，理由如下，记为 M5 展示项**」，判据是「**它在业务链路上吗**」——
数据大盘是管理端的只读展示，不参与任何写路径，为它引入 3 次跨服务调用是负收益。

★ 对比：`selectAdminPage` 同样是管理端读，但它**必须**修 —— 因为它的跨表读是
**WHERE 条件的一部分**，不修就是「按作品筛」这个功能本身不工作。这就是判据的用法。

---

## 九、实施顺序（每步独立可验证）

| 步 | 内容 | 验证 |
|---|---|---|
| **S0** | 开虚拟机（MySQL/Redis/Nacos）→ 端口全停 | `Get-NetTCPConnection -State Listen` 干净 |
| **S1** | 父 POM：`novel-backend` → `novel-interaction`；加 `<module>novel-content</module>` | `package` 绿 |
| **S2** | **纯改名**：`git mv novel-backend novel-interaction`；改 `spring.application.name`、`server.port`、启动类文案、`logging.file.name` | `git status` **全是 `R`**；单独 `package` 绿 |
| **S3** | **纯物理搬家**：`git mv` `modules/{content,ai}` → `novel-content`；搬 `InternalAuditLogController`；搬 content 的 `client/` | `git status` **全是 `R`**；`package` 绿 |
| **S4** | 新建 `ContentApplication` / `InteractionApplication` / `ContentSecurityConfig` / `InteractionSecurityConfig`；common 加 `NovelBriefVO` / `ChapterBriefVO` / `CommentBriefDTO` | 两个服务都能起；★ **匿名读评论列表必须 200** |
| **S5** | 内部端点：`InternalContentController`(7) + `InternalCommentController`(1) + `InternalAuditLogController`(2，随 content) | `curl :8082/internal/...` 不带令牌 → **403**；带令牌 → 200 |
| **S6** | Feign：`ContentClient` / `AuthNicknameClient` / `ContentGateway` / `CommentTextLookup` 的 Feign 实现；**删 interaction 里那份 `CommentTextLookupImpl`**；`novel-auth` 改 `ContentAuditClient` | 编译绿；`grep -rn "modules.content" novel-interaction` 只剩 client/ 与 VO 的 import |
| **S7** | 改 `selectAdminPage`（去掉子查询）+ `DashboardMapper` 类注释 | 管理端评论列表**按作品筛**结果与改前一致 |
| **S8** | 网关路由表重写（order 4/5/20） | ★ **停掉 novel-content → 详情页 503、评论区仍 200**（两条必须同时成立） |
| **S9** | 全量回归 + 清理一次性对象 | 见 §十 |
| **S10** | 文档 + `package.json` + 提交 `m2完成` | 照 README 从**冷启动**真跑一遍（重打包 → 起四个 jar → 起四端 → 走文档里的每一条，见 §十一 ⑭） |

★ **S4~S7 之间前端是断的**（内部端点/Feign 还没接好），这是预期的。
★ **S2 与 S3 各自 `package` 一次再动代码** —— 把「搬错了」和「改错了」两类错误分开。

### ★ 实施中的四处偏离（如实记录）

| # | 计划里写的 | 实际做的 | 为什么 |
|---|---|---|---|
| **a** | `selectAdminPage` 空列表「**直接返回空页**」 | **整段 `<if>` 掉**，条件退化成「只要书评」 | 计划那条会**静默吞掉书评**。原实现是子查询，空子查询合法且返回「只要书评」—— 要逐字保行为只有 `<if>` 这一种写法。见 §八 |
| **b** | 「M2 加一个上限保护**并打 WARN**」 | **只 WARN，不截断**（阈值 2000 章） | 截断会静默吞掉章评，比 SQL 慢一点严重得多。「保护」的语义被收窄成「告警」 |
| **c** | 结构回归「直接查 jar：有 `spring-boot-starter-data-redis`」 | **这条检查在本项目不成立** | 实测**任何** `spring-boot-starter-*` 都不出现在任何 fat jar 里（网关也没有）。有效判据是**有效依赖**：`lettuce-core` + `spring-data-redis` 在不在，以及 `/view` 的 Redis 写真的发生 |
| **d** | 「jar 里**没有** `jjwt`」（content 不签 JWT） | **做不到**，`jjwt-api/impl/jackson` 三个都在 content 的 jar 里 | 它是从 `novel-common` **传递**过来的（common 有 `JwtUtil`）。可成立的重述是：**content 的代码里没有任何 `JwtUtil` 的使用**（它不验签也不签，身份来自网关头） |

★ 偏离 a 和 b 是**行为正确性**上的修正，c 和 d 是**验证手段**上的修正 ——
共同点是：**计划里的那一条是按 M1 的经验写的，而 M2 的实际情况不同。**

---

## 十、踩坑记录

### 坑 1 ★★ M1 文档里那句「M2 千万别顺手发明新解法」，M2 自己最容易犯

`novel-auth` 的 `@FeignClient(name = "novel-backend", ...)` 在 M2 之后指向一个**不存在的服务名**。
Nacos 里查不到实例 → **审核作者申请时审计流水写不进去 → 前端 500**。

★★ **这个坑的危险不在于难，在于「很可能被跳过」**：
「作者申请审核」是**低频操作**，回归脚本里极易漏。
所以 M2 的验收清单把它列为**必测项**，实测走的是完整路径
（注册 → 申请 → 管理员通过 → 断言流水行真的落了库）。

★ 同时**改了类名**（`BackendAuditClient` → `ContentAuditClient`）——
不改类名的话，「给 backend 发审计」这个说法在 M2 之后是一句假话。

### 坑 2 ★★ 「同前缀被两个服务占用」这件事，**只有把所有 `@RequestMapping` 逐个列出来才会发现**

`/api/novels/**` 和 `/api/admin/**` 看起来像 content 的地盘，实际上 interaction 也在用。
**光看前缀会漏**，必须逐方法核对（content 46 + interaction 18 个端点全列了一遍）。

★ 症状对照：**content 的路由被 interaction 的兜底吞掉 → 静默 404**，
而在浏览器里它和「这个接口没实现」长得**完全一样**。这就是为什么兜底必须挪到 `order: 20`。

### 坑 3 ★★ `GET /api/novels/*/chapters/*` 的形状被两个模块共享

- content：`GET /api/novels/{id}/chapters/{chapterId}` —— **章节正文**
- interaction：`GET /api/novels/{novelId}/chapters/{chapterId}/comments` —— **章评**

**唯一的区别是末尾多一段 `comments`**。order 4 那三条 interaction 规则**缺一不可**，
少一条就是「章评全部落到 content 并 404」。验收里 1 vs 2 是判定样本。

### 坑 4 ★ 空书架的 200 **证明不了任何事**

`GET /api/reader/shelf` 对一个**空书架**根本不调 content（一页 0 条，没有 id 可查）。
所以「停掉 content 之后书架仍 200」**不能**说明「书架不依赖 content」。

★ **完整证据要三条一起看**（实测）：
1. 空书架 → **200**
2. **非空**书架 → **500**（它需要 `novelBriefs`）
3. 加书架（`requirePublished`）→ **500**

### 坑 5 ★★★ `mvn package` 时后端还跑着 → repackage 失败、留下**瘦 jar**

M1 记过一次，M2 的进程数从 2 个变成 4 个，**踩中的概率翻倍**。
瘦 jar 的表现是启动时报 `no main manifest attribute`，而**上一次的胖 jar 已经被覆盖了**。
★ 打包前先确认 `8080/8081/8082/8083` 四个端口都没有监听。

### 坑 6 ★★ 日志路径跟着**工作目录**跑，而且**静默**

`logging.file.name` 是相对路径。`-WorkingDirectory` 一旦写成 `backend/novel-interaction/`
而不是 `backend/`，日志就换到 `backend/novel-interaction/logs/` ——
**所有「日志在 `backend/logs/`」的文档同时变假话，且没有任何报错**。
M2 现在有**四个**日志文件（`novel-gateway.log` / `novel-auth.log` / `novel-content.log` / `novel-interaction.log`）。

### 坑 7 ★ `novel.internal.token` 现在有**五处**必须一致

网关 + auth + content + interaction + 手工 curl。少一处 → 那个服务把所有请求当匿名 →
**全站 401 或内部接口 403**，而报错信息里完全看不到「配置项」三个字。

### 坑 8 ★ 搬完家之后，「服务名」这个词在注释里会**同时说谎**

M2 移动了 108 个文件，每个文件里都可能有「本服务」「单体」「backend」这类词。
`docs/M1` 踩坑 5 记过「硬编码的服务名搬完家就在说谎」，M2 的规模是它的**几十倍**。

**处置原则**（M2 实际执行的）：
- **只改「现在时且已经是假话」的**，例如「`/internal/**` 是给兄弟服务（novel-backend）调的」。
- **不改明确框成历史的**，例如「M2 之前它叫 novel-backend」「从 novel-backend 的 NovelApplication 抄来的」。
- ★ 最省事的写法是**把名字去掉**：「调用方（M1 时是 novel-backend，M2 起是 content / interaction）」——
  这样它**不会第三次过期**。

### 坑 9 ★ 计划里的两条「结构回归检查」本身是错的（见 §九 偏离 c / d）

**教训不是「计划写错了」，是「M1 的验证手段不能直接搬到 M2」**：
`spring-boot-starter-*` 不进 fat jar 这件事在 M1 就是真的，只是 M1 没用那条检查。
**凡是「用一个新手段验证一个新结构」，先在小样本上确认那个手段本身有效。**

---

## 十一、验收证据（实测）

> 全部跑在 **`:8080`（网关）** 上，业务对象一律带时间戳（`0922151634` / `0922151623` 等），
> 结尾断言种子完好（★ 铁律 5）。

### ① 主链路 —— 大纲 M2 的验收原文「详情页跨服务取到作者信息」

| 场景 | 结果 |
|---|---|
| 管理员审批作者申请（★★ R2：auth → content 的 `ContentAuditClient`） | **200** ★「一个陈旧的 `lb://novel-backend` 会在这里 500」 |
| 审批后旧 token 立即失效（M1 改角色踢下线仍生效） | **401** |
| 重新登录，角色带上 `AUTHOR` | `roles=['READER','AUTHOR']` |
| 匿名 `GET /api/novels/{id}` | **200**，`title='M2验收书0922151634'` |
| ★ **`authorName` 跨服务取到（content → auth）** | `authorName='申请人0922151634'` |
| ★ 直连 `:8082/api/novels/{id}` 同样 200 | 证明它确实住在 novel-content 里 |

★ 这一条背后是**两条跨服务边同时成立**：content → auth（取昵称）、interaction → content（书架/历史的摘要）。

### ② 业务无回归 —— P6 全写路径（16 步全绿）

注册 → 申请 → 管理员审批 → 建书 → 提审 → 审批 → 建章 → 提审 → 审批 →
匿名可读 → 加书架 → 记阅读进度 → 书架/历史列表 → 书评 → 章评 → 回复 → 点赞。

★ 每一步都是**跨服务**的，逐条断言的关键值：

| 断言 | 值 |
|---|---|
| ★ `collectCount` 由 interaction 写回 content（再跨服务读回） | `1` → 移除后 `0` |
| ★ 不存在的作品 → `requirePublished` 的 **2001 穿过 Feign 原样到达** | code `2001` |
| ★ 别人的章节 → `chapterStatus` 的 **3001 穿过 Feign 原样到达** | code `3001` |
| ★ 书架行 `title` / `authorName` / `chapterCount` 都有值 | 跨了两条边 |
| ★ 历史行 **`chapterNo=1`**（`chapterBriefs` 跨服务） | 有值 |
| ★ 书评审核后 **`score` 写回 content**（`refreshScore` 跨服务） | `4.0` |
| ★ 10 秒评论频控**穿过网关仍然生效** | **200 + code=4004** |

### ③ ★★ 判定性反证：把 `novel-content` 停掉

**两条必须同时成立**：

| 类别 | 结果 |
|---|---|
| content 自己拥有的 5 个端点（详情 / 列表 / 目录 / 作品管理 / 大盘） | **全部 503**（**没有静默回落**） |
| interaction 自己拥有的 8 个端点（书评 / 章评 / 回复 / 评论管理 / 用户管理 / 资料 / **空书架** / ping） | **全部 200** |
| ★ **同一个书架接口，书架非空时** | **500**（它需要 `novelBriefs`） |
| ★ 加书架（`requirePublished`） | **500** |
| 匿名读 content 的章节列表 | **503** |

★ 上面第 2 行与第 3 行**合起来**才是完整证据：200 不是因为「书架不依赖 content」，
而是因为**空书架根本不需要发那次调用**（坑 4）。

★ content 恢复后：详情页 200、非空书架 200、大盘 200 —— **全部自愈，无残留**。

### ④ 路由判定性（★ 最关键的一组，15/15 通过）

| # | 场景 | 期望走 | 结果 |
|---|---|---|---|
| 1 | `GET /api/novels/1/chapters/1`（正文） | content | 200 + code=2001 |
| 2 | `GET /api/novels/1/chapters/1/comments`（章评） | interaction | **200 + code=200** ★ 与 1 形状极像 |
| 3 | `GET /api/novels/1/comments` | interaction | 200 |
| 4 | `GET /api/novels/1/comments/1/replies` | interaction | 200 ★ 少了 order 4 的第一条就会挂 |
| 5 | `GET /api/admin/comments` | interaction | 200 |
| 6 | `GET /api/admin/novels` | content | 200 |
| 7 | `GET /api/admin/users` | auth（order 1 先命中） | 200 |
| 8 | `GET /api/reader/shelf` | interaction（order 20 兜底） | 200 |
| 9 | `GET /api/reader/profile` | auth（order 1 先命中） | 200 |
| 10 | `GET /api/reader/history` | interaction（兜底） | 200 |
| 11 | `GET /api/novels`（裸路径） | content | 200 |
| 12-15 | `/api/novels/rank`、`/api/categories`（裸路径）、`/api/ping`、`/api/novels/1` | 各自 | 全 200 |

★ 注意第 1 行 content 返回的是 `code=2001`（书 1 不存在，种子书是 1001~1012）——
**这本身就是路由判定的证据**：业务码说明请求真的到了 content（网关对未匹配路由是 404，不是 200）。

### ⑤ ★★ 头号回归专项：匿名读（10/10 + 1）

| 场景 | 期望 | 结果 |
|---|---|---|
| **不带任何 token** `GET /api/novels/1/comments` | 200 | **200** |
| **不带任何 token** `GET /api/novels/1/chapters/1/comments` | 200 | **200** |
| **不带任何 token** `GET /api/novels/1/comments/1/replies` | 200 | **200** |
| 匿名 `GET /api/categories` / `/api/novels` / `/api/novels/rank` / `/api/search` | 200 | **全 200** |
| 匿名 `GET /api/novels/1/chapters/1` / `/catalog` / `/chapters` | 200 | **全 200**（业务码 2001） |
| ★ **不带 token `POST /api/novels/1/comments`** | **401** | **401**（同路径 GET 匿名 / POST 需登录，逐字不变） |
| ★ 匿名评论列表里**昵称有值**（interaction → auth） | 有值 | `nickname='读者0922151634'` |

### ⑥ 三态与越权（★ 铁律 3）

| 场景 | 期望 | 结果 |
|---|---|---|
| 匿名 → `/api/admin/users` | 401 | **401** |
| READER → `/api/admin/users` | 403 | **403** |
| 业务失败（密码错） | **200 + code=1002** | **200 + 1002** |
| 伪造头：不带 token，经网关附 `X-User-Id: 1` | 401（网关先剥后写） | **401** |
| 直连 `:8082` 伪造 `X-User-Id: 1`（不带内部令牌） | **401，不是 200** | **401** |
| 直连 `:8083` 伪造 `X-User-Id: 1` | **401** | **401** |
| `:8080/internal/...` | 404（路由表里根本没有它） | **404** |
| `:8082/internal/...` 缺令牌 | 403 | **403** |
| `:8083/internal/...` 缺令牌 | 403 | **403** |

### ⑦ 边 2 反向（content → interaction）与 S7

| 场景 | 结果 |
|---|---|
| 管理端审核日志列出评论行 | **200**，1 行 |
| ★ `targetTitle` **含评论正文**（content → interaction 跨服务） | `'《M2验收书0922151634》 · M2 验收书评 0922151634'` |
| `operatorName` 有值（content → auth） | `'系统管理员'` |
| ★ 作者申请审计行存在（R2 端到端） | `'笔名0922151634（申请人0922151634）'` |
| ★ S7 按作品筛评论 | `got=[90, 91, 92] want=[90, 91, 92]` |
| ★ S7 边界：**没有章节的书照样返回它的书评** | `ids=[93]` |

### ⑧ 决定 4：数据大盘仍读共享库

三个接口全 **200**；`novelTotal` 精确增长 2（我们建的书）`17 → 19`，`userTotal` `16 → 18` —— **种子一本没动**。

### ⑨ 两次真实上传**按字节**验收 + D-10

| 场景 | 结果 |
|---|---|
| 封面（★ **由 content `:8082` 写**） | 上传 200 → 经网关取回 200 → **上传/下载 sha 一致** |
| 头像（★ **由 auth `:8081` 写**） | 同上，**字节一致** |
| ★ D-10：文本文件伪装 `.png` | **HTTP 400** + 魔数错误信息（★ 上传是少数**不走**「200+业务码」的接口，符合铁律 3） |
| ★ 路径穿越文件名 | 返回 url 里**不含 `..`**（文件名不采信客户端） |

★ 三个服务共用一个绝对路径目录（auth 写头像 / content 写封面 / interaction 服 `/uploads/**`），
**单机裸 jar 下成立**；容器化会坏 —— 见 §十四 R10。

### ⑩ SSE 过网关**逐块到达**（实测时序）

现在多了一跳（`:8080` → `:8082`）：

```
HTTP 200  Content-Type: text/event-stream;charset=UTF-8
241 行，跨度 1.886s
  +0.000s data:山
  +0.016s data:谷
  ...
  +1.886s data:[DONE]
```

★ `Accept: text/event-stream` 与 `Authorization` 都被透传；`event:error` 帧（`__MOCK_FAIL__` 钩子）
也**原样穿过网关**。★ 判定依据是**跨度 > 1s**，不是「看起来是流式的」—— 缓冲也会吐出全部内容。

### ⑪ 登出真的登出了（M1 的验证必须仍然过）

两次登录产生两个不同 token → 两个都能用 → 登出 T1 → **T1 401、T2 仍 200**（per-jti，不是「全端登出」）
→ 再登出 T2 → T2 401。

### ⑫ 结构回归

| # | 检查 | 期望 | 结果 |
|---|---|---|---|
| 1 | 12 个锁定文件 md5 | 与基线逐字节一致 | ✓ `request.js` = `2047681401d85ed69cebbd069f4ee7dd`，三端四个文件各自相同 |
| 2 | 三端 `npm run build` | 通过；前端 **0 改动** | ✓ 退出 0；`git status` 里前端 0 改动 |
| 3 | `git diff --cached --name-status -M` | content/ai 的搬家全是 `R` | ✓ **108 个 `R`**（其中 **107 个**从 `novel-backend` 出来）+ 3 `D` + 19 `A`。★ 那 3 个 `D` 各自有一份 `A` 与之对应（`NovelApplication`→`InteractionApplication`、`InternalAuditLogController` 搬去 content、`CommentBrief`→`CommentBriefDTO`）——**是改动量太大、相似度没过 git 的阈值，不是「删了又新建」**。其余的 `A` 才是真正的新文件 |
| 4 | ★ 有效依赖：content 的 jar 里有 `lettuce-core` + `spring-data-redis` | 有（决定 A） | ✓ `lettuce-core-6.3.2.RELEASE.jar` / `spring-data-redis-3.2.12.jar`（偏离 c：`spring-boot-starter-*` 不进 fat jar，那不是有效判据） |
| 5 | `grep -rn "novel-backend"` 全仓 | 只剩**明确框成历史**的 | ✓（见 §十三） |
| 6 | `grep -rn "modules\.content"` 在 interaction | 只剩 `client/` 与 VO 的 import | ✓ |
| 7 | `grep -rn "modules\.interaction"` 在 content | 只剩 `CommentTextLookup` 那一条边 | ✓ |
| 8 | 种子基线 | `READER 0 / AUTHOR 2 / ADMIN 15`，`sys_role_menu` **17 行**，账号 1~4 | ✓ 全部一致，12 本种子书完好 |
| 9 | jar 里没有 `jjwt` | **做不到**（偏离 d） | ✗ → 重述为「content 的代码里没有 `JwtUtil` 的使用」 |
| 10 | staged diff 里没有**新增的**密钥 | 无 | ✓ 扫出来的 `root123456` / `redis123456` / `nacos` / `JWT_SECRET` 的 dev 默认值**在 HEAD 里就有**（`novel-backend/.../application-dev.yml`），M2 只是把它们带进 content（新增文件）与 interaction（改名文件）——**不是新引入的密钥类别**。`application-prod.yml` 被 `.gitignore:95` 挡住、`git ls-files` 里 **0 个**；prod 的 `secret: ${JWT_SECRET}` **无默认值**（刻意） |

### ⑬ 一次性对象的清理（★ 铁律 5）

15 轮验收造出的一次性对象：**users 200~214 / applies 20~26 / novels 1067~1073 /
chapters 101301~101304 / comments 85~93**，外加 6 个上传文件。

★ 删除语句**全是字面 id 的 `IN (...)`，没有一条 `LIKE`**；
收集阶段脚本自己断言了「每一个 user id 都不是种子账号 1~4」「每一个 novel id 都 ≥ 1000」，
**字面量纪律由脚本强制，不靠人记得**。

★ **发现并修复了两处种子变动，如实记录**：

1. `reader01` 的 `avatar` —— 头像是拿种子账号上传的。`.backup/` 里 P6 之前那份 dump
   显示四个种子账号的 `avatar` **全是 NULL**，所以**恢复成 NULL**。
   （只删文件不还原，`reader01` 就会指向一个不存在的图。）
2. ★★ **种子书 1001 的 `view_count` 现在是 1282，且【不可还原】。**
   `/view` 冒烟测试会真的 +1，而**当时没有记基线**。它是一个**任何访客都会推高的计数器**，
   不是完整性约束。这条**不能假装没发生**。

清理后复验：所有一次性对象计数为 **0**，种子基线**逐项一致**。

### ⑭ 照 README 从**冷启动**跑一遍（真跑，不是照着文档念）

停掉全部进程 → `mvn -f backend/pom.xml package`（**6 个模块全 SUCCESS**）→ 按 README 的两步式
起**四个** jar → 再按 README 起 `npm run dev` 的**四端**。**39 + 9 项检查全过**：

| 组 | 检查 | 结果 |
|---|---|---|
| 拓扑 | `:8080`/`:8081`/`:8082`/`:8083` 应答；**`:8090` 已死** | ✓ |
| 文档入口 | **三个** `/doc.html` 都是**真 HTML**（不是 SPA 回落）；网关**仍不透传** knife4j（404） | ✓ |
| `/internal/**` | `:8080/internal/...` → **404**；`:8082` / `:8083` 缺令牌 → **403**；带令牌 → **200** | ✓ |
| 伪造身份头 | 直连 `:8082`/`:8083` 带 `X-User-Id:1` 但不带令牌 → **401**（当匿名，决策 4 的核心） | ✓ |
| ★ **大纲验收原文** | 匿名 `GET /api/novels/1001` → **200**，`authorName='测试作者'`、`title='九霄剑主'` | ✓ |
| ★ 同上，直连 | `:8082/api/novels/1001` 同样 200 → 它确实住在 novel-content 里 | ✓ |
| 匿名列表 | `GET /api/novels` 10 行，**每行 `authorName` 都有值** | ✓ |
| R1 头号回归 | 三条匿名评论读全 **200**；同路径 `POST` 仍 **401**；`/api/categories`、`/api/search`、`/api/novels/rank` 全 200 | ✓ |
| 三态 | `admin`/`reader01` 登录出 token；匿名→`/api/admin/users` **401**、READER→**403**、ADMIN→**200**；密码错 → **200 + code=1002** | ✓ |
| 路由劈分 | 8 个前缀各归其位（comments→interaction、novels/users→auth、dashboard/author→content…） | ✓ |
| ★ 种子完好 | `novelTotal=12`、`userTotal=4` —— **与基线逐项一致** | ✓ |
| 开发入口 `:3000` | 三端外壳 200；`:3000/api/novels` 代理到网关 → 200 且 `authorName` 有值 | ✓ |
| ★ 铁律 4 | `/reader/src/main.js` → `text/javascript`；**不存在的模块也 200 但回落 `text/html`** | ✓ |

★ 这一轮**全程只读**（不回写种子），写路径的证据全部来自 §十一 ①~⑨ —— 它们跑在一次性对象上。

★ **一个诚实的边界**：这一轮验的是「**全新的 jar 能不能按文档起来、文档里的每一条是不是真的**」，
**不是**把 §十一 ①~⑨ 的写路径重跑一遍。后者跑在 S9 建的一次性对象上，那些对象已经按铁律 5 清理掉了。

---

## 十二、回滚

**M2 是单 commit。** 回滚 = `git revert`（或 `git reset --hard <M1 的提交> c64d7d4`）。

这套回滚**干净**，理由与 M1 相同：

1. **没有任何 DDL、没有任何数据迁移。** 三处跨表读（`sys_user` / `author_apply` / `novel_comment`）
   在 M2 **之后仍然读同一个库**（决定 1），回滚不涉及数据恢复。
2. **Redis 的 key 与格式一个字节没变** → **回滚不会强制任何人重新登录**。
3. **前端 0 改动** → 回滚不需要动前端。
4. 步骤：停**四个**进程 → `git reset --hard c64d7d4` → `rm -rf backend/novel-content`
   + `rm -rf backend/novel-interaction`（改名/新增目录会变成未跟踪文件）→
   `git checkout backend/novel-backend` → `mvn -f backend/pom.xml package` →
   按 M1 的方式起两个进程（8080 / 8081 / 8090）。**全程 < 5 分钟。**
5. ★ 唯一要注意的：M2 之后在 content / interaction 里写过的数据，回滚后仍在**同一个 `novel` 库**里、
   由单体继续访问 —— **不丢也不错位**。这是「共享库」这个决策在回滚上的直接好处。

---

## 十三、明确**没有**改的东西

| 不动的 | 理由 |
|---|---|
| **12 个锁定文件** | ★ 铁律 2。M2 **根本不需要**改它们（前端调用全是相对 `/api`） |
| **`docs/P1` ~ `docs/P6`、`docs/M0`、`docs/M1`** | 它们记录的是**当时的事实**。本文档负责说明「哪些描述在 M2 之后过期了」 |
| 三个前端的任何代码 | 0 改动 |
| **`modules/interaction` 的业务实现逻辑** | **只允许 import 行与局部变量类型变化**。★ `HistoryServiceImpl` 那两段「为什么这一步不能并入上一步」的长注释**逐字保留** |
| `modules/content` / `modules/ai` 的业务实现逻辑 | 同上，只换接缝类型 |
| `dev-proxy/vite.config.js` | 转发目标仍是 8080（网关） |
| `FeignIdentityConfig` 的两份副本 | 决定 B：沿既有习惯，合并记为 M5 |

### ★ M0 / M1 文档里在 M2 之后变了的地方

**M0 / M1 的文档不要回头改** —— 它们记录的是当时的事实。对照关系：

| 文档里的写法 | M2 之后读作 |
|---|---|
| `novel-backend` | `novel-interaction` |
| `novel-backend:8090` / 单体 `:8090` | `novel-interaction:8083` |
| `:8090/doc.html` | `:8083/doc.html`（另加 `:8082/doc.html`） |
| 「后端分成**两个**进程」（M1） | 四个进程 |
| M1 §六 的路由表（1 条 `lb://novel-backend` 兜底 + 1 条 auth） | M2 §五 的新路由表（auth×10 + interaction×3 + content×4 + uploads + 兜底） |

★ 唯一**改了**的历史文档是 `docs/部署.md` 和 `docs/环境配置.md` —— 它们是**操作手册**，
写的是「现在怎么部署」，不是「当时怎么部署」。**手册必须跟着现状走**，否则它就是在教人做错事。

---

## 十四、记一笔（M3 / M5 债，M2 只记录不处理）

| # | 债 | 归属 | 备注 |
|---|---|---|---|
| **R8** | ★★ **`refreshScore` 的调用点在【事务里】**（评论审核）。Feign read-timeout 是 **5000ms**，content 慢一次 → DB 事务被挂住最长 5 秒 → 管理员以为「按钮没反应」**再点一次** | **M3（MQ）** | M2 接受（改造前是同进程调用，不存在超时） |
| **R9** | ★★ **`refreshCollectCount` 的调用点不在事务里** → 本地重算成功 + 远程写失败 → **收藏数停在旧值且不会自愈** | **M3（MQ）** | R8/R9 一起构成 M3 的 MQ 要解决的两件事 |
| **环 #2** | ★★ `novel-content ⇄ novel-interaction`（8 + 1） | **可能活到 M5** | 「审核日志显示评论正文」是**同步读**，MQ 解不了；要解只能改成「审核时把正文快照写进 `audit_log`」= **DDL** |
| **环 #1** | `novel-auth ⇄ novel-content`（M1 造的，M2 后名字变了） | **M3（MQ）** | |
| **`IN` 列表长度** | `selectAdminPage` 的 `chapterIds` 理论上无上限 | **M4（ES）** | M2 加了 2000 章 WARN 阈值，**只告警不截断** |
| **上传目录跨进程共享** | auth 写头像 / content 写封面 / interaction 服 `/uploads/**`；容器化各有文件系统 → **404** | **M5（对象存储）** | 与 M1 R10 同源，M2 扩大了涉及面 |
| **`FeignIdentityConfig` 三份副本** | 两份 Retryer 语义不同 | **M5 治理** | 合并需要选一个赢家 = 行为变更 |
| **可用性耦合** | content 挂掉 → 凡要渲染书名的读者接口也挂（书架/历史/发评论前的校验）。**宁可 500，也不要一个看起来正常但内容是错的页面** | **M5（缓存兜底 / 熔断降级）** | 和 M1 那边「auth 挂掉连匿名首页也 500」是同一笔账 |
| **`/internal/**` 的强鉴权** | 仍是共享密钥头 | **M5 治理** | 更彻底的是内网隔离 + mTLS |
| **网关聚合接口文档** | M0 推迟 → M1 推迟 → **M2 变成三个文档入口**（`:8081` / `:8082` / `:8083`） | 仍推迟 | |
| **`X-Permissions` 头体积** | M1 记的那笔仍然成立（几百字节，远低于 Tomcat 8KB 上限） | —— | |

---

## 十五、这一期最该记住的三句话

1. **`/api/novels/**` 和 `/api/admin/**` 被两个服务同时占用** ——
   所以网关**不能**用一条 `/**` 划开。不变式：重叠的 interaction 路由 `order` **必须严格小于** content 的。
   **症状是静默 404**，长得和「接口没实现」一模一样。

2. **HTTP 200 不等于成功**（铁律 3），所以 Feign 调用**必须拆信封**，
   而「拆信封」**必须有唯一一处实现**（`ContentGateway`）。
   漏掉的症状不是报错，是**校验静默消失**：功能测试全绿，只有真的点一本下架的书才会发现。

3. **服务级环现在有两个，而第二个 MQ 不一定解得了。**
   「管理端审核日志要显示评论正文」是一次**同步读**，要解只能改 DDL。
   **别以为 M3 一定会顺手解掉它。**
