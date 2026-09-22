# M1 · 摘出 auth + 网关接管 JWT

> 第二期第二个里程碑。上一个是 `docs/M0-骨架搭建.md`（网关只转发、不认证）。
> **M1 做的事一句话**：把 `modules/system` 从单体里搬进独立进程 `novel-auth:8081`，
> 把 JWT 校验从「每个服务自己验」搬到「网关统一验」，下游只读请求头。

---

## 一、验收标准（大纲原文）

`项目大纲.md` §九：

```
| **M1 摘出 auth**（2~3 天） | 先拆认证服务（边界最清晰、被依赖最多），网关接管 JWT 校验
                            | 登录走 auth 服务，其他服务从请求头拿用户信息，全流程仍正常 |
```

拆开是三件事，第三件才是验收标准：

1. 登录走 auth 服务
2. 其他服务从请求头拿用户信息
3. ★ **全流程仍正常** —— 三态（401 / 403 / 200+code）、匿名只读、P6 全写路径，
   **业务行为逐字节不变**。前两条是手段，这一条是目的。

★ 由此推出一条贯穿全文的取舍原则：**M1 宁可多写一层搬运，也不改业务语义。**
凡遇到「这样更优雅但会改变可观察行为」和「这样笨一点但行为一模一样」的分叉，
一律选后者。

---

## 二、目标结构

```
浏览器 :3000
  └─ dev-proxy（开发期）/ Nginx（生产）
       └─ :8080  novel-gateway   ★ 唯一入口，M1 起在这里验 JWT
            ├─ lb://novel-auth    → :8081  登录/注册/用户/角色/菜单/公告/作者申请/ping
            └─ lb://novel-backend → :8090  作品/章节/评论/书架/搜索/AI/审核日志
```

| 进程 | 端口 | 原来是什么 | M1 之后 |
|---|---|---|---|
| `novel-gateway` | 8080 | 只按路径转发 | **多了一件正事**：验 JWT、查 Redis、写身份头 |
| `novel-auth` | 8081 | 不存在 | **新增**，整个 `modules/system`（58 类 / 7 表） |
| `novel-backend` | 8090 | 全部业务 + 认证 | 只剩 content / interaction / ai，**不再验签** |

★ **包名一个都没改**：`com.novel.modules.system.*` 原地保留。
`docs/P2-内容主干.md:56` 早就写过「一个包对应将来要拆出去的一个服务
（`modules/system/` ↔ 二期 `novel-auth`）」—— 保持包名就是兑现那条约定。
副作用是 `git mv` 成了纯物理搬家，`git log --stat` 里是 `R` 而不是 `A`+`D`。

---

## 三、已拍板的四个决定（开工前定的，实施中没动摇）

| # | 决定 | 后果 |
|---|---|---|
| 1 | **auth 边界 = 整个 `modules/system`** | 登录注册 + 用户 + 角色权限 + 作者申请 + 公告 + ping |
| 2 | **跨服务数据边 M1 就上 Feign** | 比大纲原定的 M2 早一期，一次划干净，不留半截 |
| 3 | **共用一个 `novel` 库** | M1 不拆库、不做任何数据迁移，7 张表一张没动 |
| 4 | **内部信任边界：网关身份头 + `/internal/**` 两处都加共享密钥** | 直连 `:8090`/`:8081` 伪造 `X-User-*` → 当**匿名**，不是变成管理员 |

★ 决定 3 在**回滚**上有个直接好处，见 §十一。

---

## 四、三条被代码「逼」出来的结构决定（不是审美）

### 决定 A：`RedisConfig` 必须搬到 `novel-common`，绝不能复制一份

网关接管 JWT 后，每个请求都要 `tokenStore.get(userId, jti)` 才知道这把 token 还有没有效 ——
**登出、被踢下线、改角色立刻生效，全靠这一查**。而 `TokenStore` 注入的是
`RedisTemplate<String, Object>`，它的序列化格式由 `RedisConfig` 里那套
`BasicPolymorphicTypeValidator` 白名单 + `activateDefaultTyping` 决定。

> **复制一份的后果**：网关侧的 `RedisTemplate` 只要有一点点不同（漏了 default typing、
> 换了 serializer、白名单少一个前缀），读出来的 `LoginUser` 就是 `LinkedHashMap`
> 或直接抛 `InvalidTypeIdException`。症状是**全站静默登出** ——
> 网关拿不到身份 → 下游当匿名 → 401 → 前端 `request.js` 的 `handleUnauthorized`
> → `clearAuth()` → 跳登录页。而排错的人会一路往「token 是不是过期了」
> 「Redis 是不是没连上」上查，**永远查不到序列化**。

所以 `RedisConfig` 单源在 `com.novel.common.config`，网关 `@Import` **同一个类**。

★ 一个顺带的好结果：`LoginUser` / `JwtUtil` / `TokenStore` / `UserContext` 本来就都在
`novel-common`，M1 一个字节没动 —— 于是 Redis 里那份 JSON 的
`@class: com.novel.common.security.LoginUser` **全限定名不变**，
**已登录用户不会被强制登出**。这不是运气，是「公共类库放哪」这个早期决定的红利。

### 决定 B：`com.novel.config` 其余 6 个也一起搬进 common（修正了 M0 的记录）

M0 说「`config/` 的 6 个技术配置留单体」时全项目只有一个进程，那个决定是对的。
M1 之后 `novel-auth` **同样需要**分页拦截器、`create_time/update_time` 自动填充、
逻辑删除约定、JSON 时间格式 —— 少了 `MybatisPlusConfig` 就是分页失效，
少了 `MybatisMetaObjectHandler` 就是 `create_time` 写 null。

复制一份 = 把「逻辑删除 / 自动填充 / JSON 约定」变成两份会各自漂移的副本，
**而且漂移时不报错**。搬进 `novel-common` 之后，两个服务的
`@SpringBootApplication`（**都在 `com.novel` 包**）自动扫到，**零 import 改动**。

★ **唯一不搬的是 `SecurityConfig`** —— 它的全部内容就是「本服务的白名单 + URL 闸」，
天然是 per-service 的（auth 的白名单里有 `/api/auth/login`，backend 的没有）。
novel-backend 的留在 `com.novel.config`；novel-auth 新写一个
`com.novel.auth.config.AuthSecurityConfig`。**类名不同、包名不同**，
不会出现「同 FQN 两个类、谁赢看 classpath 顺序」的鬼故事。

### 决定 C：网关**不扫描 `novel-common`**，用 `@Import` 点名装配

网关是 WebFlux，classpath 上**没有 `jakarta.servlet-api`**。而 `novel-common` 里有一批
servlet-bound 的类：

| 类 | 扫进去会怎样 |
|---|---|
| `GlobalExceptionHandler` | 方法签名收 `jakarta.servlet.http.HttpServletRequest` |
| `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` | 实现 spring-security-web 的接口（网关没有那个 jar） |
| `HeaderAuthenticationFilter` | `extends OncePerRequestFilter` → 要 `jakarta.servlet.Filter` |
| `UploadProperties` | `@Component` + `@ConfigurationProperties`，缺 `novel.upload.*` 时可能抛异常 |

```java
@SpringBootApplication
@Import({RedisConfig.class, JwtUtil.class, TokenStore.class})   // ★ 只有这三个
public class GatewayApplication { ... }
```

★ **这个坑的症状值得单独记**：报错会说「找不到某个类」或「某个 bean 创建失败」，
**指向那个被误扫的类**，完全不指向「你把扫描范围开大了」这个真正的原因。
最自然的反应是「那我把那个类也搬走 / 加上依赖」，于是越修越歪 ——
一路把 servlet 依赖拖进 WebFlux 网关，直到撞上 `Spring MVC found on classpath` 才被迫回头。
**一行 `@Import` 就是用来避免这条歪路的。**

★ 网关**绝不加 `@EnableFeignClients`** —— 它不做任何出站业务调用。

---

## 五、★★ 网关的核心设计：**只「加」不「拦」**

这是 M1 最重要的一条取舍。写清楚是因为后来的人一定会「顺手优化」掉：

| 网关**不做** | 为什么 |
|---|---|
| **不返回 401** | 401 的唯一出口仍然是下游的 `RestAuthenticationEntryPoint`。token 无效/过期/已被踢 → 网关**原样放行为匿名**（和改造前的 `JwtAuthenticationFilter` 完全一样）→ 请求继续走到下游 → 由下游的 `.anyRequest().authenticated()` 拦下并输出统一信封。**这样对外行为逐字节不变。** ★ 在网关自己拼一份 401 的附加坏处：前端 `request.js` 认的是下游 `Result` 信封的格式，两份实现迟早漂移，而症状是「有的 401 会跳登录页、有的不会」 |
| **不做匿名路径白名单** | 若网关维护一份「哪些路径免鉴权」，它就**复制了 `SecurityConfig` 的白名单**。将来给后端加一个公开接口、忘了同步网关 → 那个接口 401。这是纯自找的漂移地雷。★ 本过滤器天然不需要白名单：它对**任何**请求都只是「有有效 token 就写头，没有就不写」，然后原样放行 |
| **不解析也不改写响应** | 免得给 SSE（AI 写作助手的流式输出）引入缓冲 |

### ★★ 唯一的例外：Redis 挂了必须是 500，绝不能是 401

前端 `request.js` 把 HTTP 401 解释为「登录态没了」→ `clearAuth()` + 跳登录页。所以：

```
Redis 抖一下  →  全站所有请求 401  →  所有在线用户被清登录态、踢回登录页
```

而真正的原因（Redis 不可用）在用户侧完全看不出来。改造前的实现是
`loadLoginUser` 里 log.error 之后 **rethrow**（→500），**M1 逐字保持**。

### ★★ 先剥后写

转发前把客户端可能自带的 `X-User-Id` / … / `X-Internal-Token` **全部删掉**，再写自己的。
少剥一个，任何人都能靠 `curl -H "X-User-Id: 1"` 变成管理员。
剥和写都用 `IdentityHeaders.ALL` 这**同一份清单**，所以「加了新头忘了剥」结构上不会发生。

### 身份头清单 —— 每一个漏了会怎样

| 头 | 来源 | 漏了会怎样 |
|---|---|---|
| `X-User-Id` | `LoginUser.userId` | `UserContext.requireUserId()` 拿不到 → 所有写接口 500/401 |
| **`X-Jti`** | `LoginUser.jti` | ★★ **`AuthServiceImpl.logout()` 从 `UserContext.get().getJti()` 取 jti**，取到 null → `tokenStore.remove(userId, null)` → 删的是 `novel:auth:token:{userId}:null`，**一把 key 都删不掉** → **「点了退出但还是登录着」**，而且日志那行 `记录存在=false` 看着完全正常。**这是 M1 最容易静默出事的一处** |
| `X-Username` | `LoginUser.username` | `/api/auth/me` 返回值变化 |
| `X-Nickname` | `LoginUser.nickname` | 同上 |
| `X-Avatar` | `LoginUser.avatar` | 同上 —— 少了它 `/api/auth/me` 的 `avatar` 会从「陈旧值」变成 `null`，是**可观察的行为变化** |
| `X-Roles` | `LoginUser.roles` | **所有 URL 闸失效** → `/api/admin/**` 对 ADMIN 也 403 |
| `X-Permissions` | `LoginUser.permissions` | 今天全仓 0 个 `@PreAuthorize`，**无影响**；为 M2/M3 的 `hasAuthority` 保留 |
| `X-Internal-Token` | 共享密钥 | 下游 `HeaderAuthenticationFilter` 当作匿名 → 全站 401；`/internal/**` 直接 403 |

★ `X-Jti` 之所以必须走头，是因为 `logout()` **压根不解析 `Authorization`** ——
它读的是 `UserContext`。改造前 `UserContext` 由 `JwtAuthenticationFilter` 从 JWT claims 填，
M1 之后由网关的请求头填。**链路的这一段换了载体，字段一个都不能少。**

★ 下游的 `HeaderAuthenticationFilter` 有一条**硬校验**：`X-User-Id` 和 `X-Jti`
只要缺一个就当**匿名**并打 `log.error`（网关与本服务版本不一致的信号）。
不这样的话，网关漏写一个头会退化成「部分功能静默异常」，而不是一眼可见的日志。

---

## 六、路由表：10 条精确前缀 + 2 条兜底

`/api/admin/**` 和 `/api/reader/**` **两个前缀都横跨两个服务**：

```
/api/admin/users/**          → novel-auth（用户管理）
/api/admin/author-applies/** → novel-auth（作者申请审核）
/api/admin/dashboard         → novel-backend（数据大盘，读作品表）
/api/reader/profile/**       → novel-auth（个人资料、头像）
/api/reader/bookshelf        → novel-backend（书架）
```

所以**不能**按 `/api/admin/**` 一刀切，必须拆到「具体前缀」这一层。

★ **`order` 必须显式写死**，不依赖 YAML 里的书写顺序：路由按 order 升序匹配取第一个命中，
而 `RouteDefinition` 不写 order 时**默认是 0** —— 只要有一条忘了写，
它会排在所有显式 order 的路由**前面**把流量抢走。这里 auth 一律 `1`、backend 一律 `10`。

★ **每条前缀都写「裸路径 + `/**`」两种形状**，这不是冗余：
`PathPattern` 里 `/api/notices/**` 匹配 `/api/notices/1`，但**不匹配 `/api/notices` 本身**。
而本项目的 Controller 写法不统一（`ReaderNoticeController` 是方法级 `@GetMapping("/api/notices")`
没有类级前缀）。只写 `/**` 那一种的话，这些接口会**掉进兜底路由**被转发给 novel-backend
→ 那边没有它们 → 404。★ 症状是「改昵称 404、传头像却正常」，看起来像接口没实现。

★ **`/internal/**` 刻意不在 `/api/**` 之下** → 网关的路由表**结构上不可能**转发它。
这是「内部接口从公网不可达」的结构保证，不靠配置纪律。

★ **判定性验证**（比逐条 curl 更能说明问题）：
停掉 novel-backend → auth 的路径**仍然活着**、backend 的路径 503。两条同时成立，
才证明这些路由真的分开了。

---

## 七、跨服务边 → Feign

铁律 1 的原话是「接口定义在**消费方**那一侧」。换成 HTTP 之后这条要重新表述：

> **业务语义的接口住在消费方；提供方只暴露 URL，不暴露类型。**

原来「实现在提供方」是**同进程 DI** 的形态 —— 接口在 system、impl 在 content，
注入进来就能用。换成 HTTP 之后，「实现」的内容就是「发一次 HTTP 请求」，
它必须住在**调用发生的地方**（消费方），否则就是一个
**能编译、能被扫描、但永远不会被调用**的死 bean。

| 边 | 方向 | 提供方新增的端点 |
|---|---|---|
| `ContentUserLookup` | content → auth | `GET /internal/users/{id}/nickname`、`POST /internal/users/nicknames`（批量，用 POST 避开 URL 长度）、`GET /internal/users/by-nickname`、`GET /internal/author-applies/brief` |
| `AuthorApplyAuditRecorder` | auth → backend | `POST /internal/author-apply-audit-logs` |

★ 4 个方法签名**一字不改**，调用方零改动
（`AuditLogServiceImpl:58,75`、`NovelServiceImpl:102`、`CommentServiceImpl:94`、
`AuthorApplyServiceImpl:311` 全都没动）。

★ `nicknamesOf` 会被**匿名请求**间接触发（首页书列表要显示作者昵称，此时 `UserContext` 是空的、
**透传不出任何用户身份头**）→ 这类内部读端点**必须是 `permitAll()`**，
由 `X-Internal-Token` 把关，**不能**设成 `hasRole("ADMIN")` —— 否则匿名首页直接 500。

### ★★ 这会造出一个服务级临时环：`novel-auth ⇄ novel-backend`

`backend → auth`（内容要作者昵称）与 `auth → backend`（审核要写审计日志）**同时存在**。

- 铁律 1 的「禁止 `system → content`」针对的是**代码依赖**，而且它已经把这个接缝
  明确列为**受认可的例外**（原文：「`AuthorApplyAuditRecorder`（system 读 content，
  实现在 content）」）。所以 M1 的做法**符合铁律 1 的原文**。
- 但**服务级**上它确实成了一个环，这是「同进程 DI 接缝」变成「HTTP 调用」的必然产物。
- **解药是大纲 §M3 的 MQ**（审核事件异步化 → 环断掉）。**M1 接受它，并在此显式记录**，
  不留给后人去发现。

### Feign 的三个操作细节

1. **首次构建要联网**：本地仓库 `mvn_repo` 里没有 `spring-cloud-starter-openfeign` /
   `feign-core` 的 jar（只有两个 BOM）。第一次 `package` 必然要拉包。
2. **`name = "novel-auth"` 需要 loadbalancer**：缺
   `spring-cloud-starter-loadbalancer` 会报 `No Feign Client for loadBalancing defined`。
   两个服务都要显式加。
3. **写流水那条边必须 `Retryer.NEVER_RETRY`**：Feign 默认会重试，
   `POST /internal/author-apply-audit-logs` 重试一次就是**两条重复审计记录**。

---

## 八、实施顺序

| 步 | 内容 |
|---|---|
| **S0** | 开虚拟机（MySQL/Redis/Nacos 都在上面）；停掉全部旧进程 |
| **S1** | 父 POM 加 `<module>novel-auth</module>`；建 novel-auth 骨架 |
| **S2** | **纯物理搬家**：`git mv modules/system` → novel-auth；`git mv` 7 个 config → common |
| **S3** | 改包引用（**只允许 import 行变化**）；两个白名单做加减法 |
| **S4** | 删 `JwtAuthenticationFilter`；新增 `HeaderAuthenticationFilter`；两个 SecurityConfig 换 filter |
| **S5** | 网关：pom 加依赖、`@Import` 装配、写 `JwtAuthGlobalFilter`、路由拆 12 条、yml 补配置 |
| **S6** | 内部令牌：网关写头、Feign `RequestInterceptor` 加同一个头、`/internal/**` 的校验 |
| **S7** | Feign 两条边：`novel-common/internal` 的 DTO、两侧 `/internal/**` 端点、两个 `@FeignClient` |
| **S8** | 全量回归 + 清理一次性对象 |
| **S9** | 文档 + `package.json` + 提交 |

### ★ 实施中的两处偏离（如实记录）

1. **S7 排到了 S5 前面。** 计划是 S5 网关 → S6 → S7 Feign，但实际做下来
   **novel-backend 在 Feign 接缝落地之前根本编译不过** ——
   `ContentUserLookup` 的实现要调 `/internal/users/**`，而那个端点在 novel-auth 里、
   `AuthorApplyAuditRecorderImpl` 要从 content 搬到 auth。所以 Feign 两条边必须
   和「搬家」同一步完成。**这不是顺序写错了，是「编译单元」这个约束比「验收顺序」更硬。**
2. **路由是 12 条，不是计划里写的 11 条。** 计划那张表自己列的是
   10 条 auth + `/api/**` + `/uploads/**` = **12**，「11」是加法算错了。
   实际生效 12 条，与设计一致。
3. **`dev:auth` 没有按计划写成 `spring-boot:run`。** 计划里那一行是照抄
   `dev:backend` / `dev:gateway` 的形状写的，实测发现那个形状**从 M0 起就是坏的**
   （见踩坑 7），所以三个脚本一起改成了「打包 + 跑 jar」。

---

## 九、★★ 踩坑记录

### 坑 1 ★★★ 多值请求头被整体 URL 编码 → 逗号变成 `%2C` → **元素个数错了**

**这是 M1 唯一一个真正会造成用户可见故障的 bug，而且是代码写完之后才发现的。**

`IdentityHeaders` 把身份写进请求头时，每个值都做 URL 编码（HTTP 头的值按规范是
ISO-8859-1，而本项目的昵称、头像路径全是中文，不编码就是乱码）。
但最初的实现是：

```java
put(target, ROLES, join(user.getRoles()));   // join = String.join(",", roles)
```

而 `put` 编码的是**整个字符串** —— `URLEncoder` 把逗号编成 `%2C`。
于是 `READER,AUTHOR` 变成了 `READER%2CAUTHOR` 发出去。
读的那一端 `split` 是**先按 `,` 切、再逐个解码**，可这时候字符串里
**一个逗号都没有了**（只剩 `%2C`），切出来就是**一个**元素 `READER,AUTHOR`，
解码之后又变回长得一模一样的字符串。

> **★★ 症状极具迷惑性：文本完全正确，只有元素的个数错了。**
> `buildAuthorities` 于是拼出唯一一条权限 `ROLE_READER,AUTHOR`，
> 它和 `hasRole("AUTHOR")` 永远匹配不上。

**为什么种子数据完全测不出来**：种子账号 1~4 恰好**全是单角色**
（ADMIN / READER / READER / AUTHOR），单元素时切不切都一样。
它只在「审核通过之后的真实作者」身上发作 —— `AuthorApplyServiceImpl` 给的是
**追加** AUTHOR，所以那个人是 `READER,AUTHOR` 双角色
→ **一被批准就再也进不去作者端**，症状是「申请通过了，但作者中心打不开」。

实测证据（修复前）：

```
account      roles                    /api/author/novels
m1t132907    ['READER,AUTHOR']       403      ← 一个元素，闸匹配不上
author01     ['AUTHOR']              200      ← 种子账号，看不见问题
```

修复：**逐元素编码，用字面逗号连接**（逗号本来就是 HTTP 规范里的列表分隔符，
不该被编码），这样和 `split` 的「先切后解」严格对称。修复后：

```
account      roles                    /api/author/novels
m1t132907    ['READER', 'AUTHOR']    200
```

★ **一般化的教训**：把一个 `List` 塞进**一个**字符串字段时，
**分隔符的编码方式必须和拆分方式严格对称**，而且要专门测
「**两个**元素」的用例 —— 一个元素的用例对这个 bug **零信息量**。

### 坑 2 ★★ `optional` 依赖**一个字节都不会**传递给下游模块

M0 把 `novel-common` 里所有 Spring starter 标成 `<optional>true</optional>`，
为的是让 `spring-webmvc` 传不进 WebFlux 网关。M1 网关第一次真的依赖 `novel-common`
（要用 `JwtUtil` / `TokenStore`），于是这条防线的**第一个副作用**出现了：

```
JwtAuthGlobalFilter.java:[..] 程序包 lombok 不存在
                             找不到符号: 类 Slf4j
```

`novel-common` 明明带着 lombok —— 它传不过来，因为那边写的是 `<optional>`。

★ 所以规则的完整形态是：
**「你在本模块的 `.java` 文件里用到的注解，就要在本模块的 pom 里声明」**，
不能指望从 `novel-common` 顺过来。**一个字节都不会顺过来。**

★ 反过来说，这也**证明 M0 那套设计确实生效了** —— 它真的挡住了 webmvc。
所以 M1 的 S5 里那条验证不是走过场，是这一枪的兑现检查。

### 坑 3 ★★ WebFlux 的 `HttpHeaders` 是 `Map<String, List<String>>`

`IdentityHeaders.write(Map<String, String>, LoginUser)` 收的是**单值** Map
（这个签名是为 Feign 那侧定的，`RequestTemplate.header` 收的确实是单值），
而 WebFlux 的 `HttpHeaders` 是 `Map<String, List<String>>`（一个头名对应多个值）。
直接写 `headers::set` 编译不过：

```
不相容的类型: java.util.Map 不是函数接口
```

★ 修法是先攒一个普通的 `Map<String,String>`，再整个落到请求头上。
**没有为了网关把 `IdentityHeaders` 改成多值签名** —— 那会把这个「唯一的编解码单源」
按调用方分裂成两套形状，正是它存在要防的事。

### 坑 4 ★ 网关需要自己的 `application-dev.yml`（计划里没预料到）

`application.yml` 里 `novel.jwt.secret: ${JWT_SECRET}` 和
`novel.internal.token: ${INTERNAL_TOKEN}` **故意不给默认值**（生产没配就起不来，
而不是静默用一个写死在 git 里的弱值）。但开发机上没人会去设这两个环境变量 ——
所以默认值只能放在 profile 文件里。网关因此也要有 `application-dev.yml`。

★ 这造成 M1 之后网关配置有一点**不对称**，而且它是刻意的：

| 文件 | 装什么 |
|---|---|
| `application.yml` | 端口、Nacos 地址、路由表、actuator、日志（M0 留下的，无真实口令） |
| `application-dev.yml` | Redis 口令、JWT 密钥、共享令牌（M1 新增的，有口令 + 必须有 dev 默认值） |

### 坑 5 ★ 硬编码的服务名搬完家就在说谎

`PingController` 的返回体里硬编码了 `"novel-backend"`。M1 把整个 `modules/system`
搬进 novel-auth 之后，它打印出来还是「novel-backend」，而这个进程叫 novel-auth。

★ **这类错误不报错、也不影响功能**，只在排障时误导人：网关日志里看到
`from=novel-backend` 会让人去翻 novel-backend 的日志，而那个进程压根没参与。

★ 修法不是「把 novel-backend 改成 novel-auth」—— 那只是把一个会漂移的常量换个值。
改成读 `spring.application.name` 之后，它**永远等于注册到 Nacos 上的那个名字**。

### 坑 6 ★ dev 页面 `/api/ping*` 现在报的是 auth 服务

`author-web` / `admin-web` 的 `views/dev/ping.vue` 打的是 `/api/ping`。
M1 之后它经网关落到 **novel-auth**（路由 `auth-ping`），所以那个页面
测的是「auth 服务能不能连上 MySQL / Redis」，不再是单体。

★ **功能不坏**（两个服务连的是同一台 MySQL、同一个 Redis database，结论一致），
而且返回体里的 `application` 字段现在会显示 `novel-auth`，页面自己就说清楚了。
**M1 不改前端**（见 §十二），所以这里只记录。

### 坑 7 ★★ M0 拆多模块时，`npm run dev:*` **静默坏了**，M1 又照抄了一遍

**症状**：`mvn -f backend/novel-auth/pom.xml spring-boot:run` 直接失败：

```
Could not find artifact com.novel:novel-common:jar:1.0.0 in alimaven
```

**成因**：`novel-common` 是**同一 reactor 里的模块**，从来没有被 `install` 进本地仓库
（`mvn_repo/com/novel/` 下甚至只有一个 `.lastUpdated` 失败标记）。
M0 之前只有一个模块，`spring-boot:run` 自然能跑；M0 拆出 `novel-common` 之后，
**单独进子目录跑任何 goal 都解析不到它** —— 只有 `-f backend/pom.xml`（聚合 POM）
才在 reactor 内解析得到，而 `package` 从来不 `install`，所以仓库里永远不会有它。

★ **`-pl novel-auth -am` 也救不了**：CLI 的 goal 会先落到**聚合 POM** 上，
报 `spring-boot-maven-plugin:run ... on project novel-parent`（没有主类）。
`-am` 对**生命周期阶段**（`package`）有效，对**直接点名的 goal** 无效。

★ **为什么这么久没被发现**：`dev:backend` / `dev:gateway` 是 M0 加的，
而 `docs/环境配置.md` 一直把 `npm run dev:backend` 写成可用命令 ——
但真正每天用的是 README 里那套 `mvn package` + `java -jar`，脚本没人跑。
**文档里一条从没被执行过的命令，就是这样慢慢变成假话的。**

**影响面**：三个脚本（`dev:gateway` / `dev:auth` / `dev:backend`）**全都跑不起来**。
M1 的计划里写的就是 `"dev:auth": "mvn -f backend/novel-auth/pom.xml spring-boot:run"`，
照抄下去就是把 M0 的坑原样复制到第三个服务。

**修法**：三个脚本都改成「**打整个 reactor 的对应模块 + 跑 jar**」两步，
也就是把 README 里那套**已经验证过**的流程脚本化：

```json
"dev:auth": "mvn -f backend/pom.xml -DskipTests -pl novel-auth -am package && cd backend && java -jar novel-auth/target/novel-auth.jar"
```

★ 选它而不是「先 `mvn install` 一次 `novel-common`」的理由：
`install` 之后就变成**用仓库里的旧 jar** —— 改了 `novel-common` 不重新 install，
dev 跑的还是旧的，**而且不报错**。那正是 CLAUDE.md 早就警告过的那个失败模式
（「改了没生效」且不报错）。`-pl <模块> -am package` 每次都从源码重建 `novel-common`，
结构上不会陈旧。代价是没有 `spring-boot:run` 的热重启 —— 对作品集可以接受。

★ **实测证据**（三个模块逐个跑 `-pl <模块> -am package`）：

```
novel-gateway  Reactor: novel-parent, novel-common, novel-gateway   BUILD SUCCESS
novel-auth     Reactor: novel-parent, novel-common, novel-auth      BUILD SUCCESS
novel-backend  Reactor: novel-parent, novel-common, novel-backend   BUILD SUCCESS
产物：54.5MB / 74.1MB / 74.2MB，都含 BOOT-INF/classes（是胖 jar，不是 R14 那种瘦 jar）
```

然后用脚本里那半段 `cd backend && java -jar novel-*/target/novel-*.jar` 起回来，
`:8080` Netty / `:8081` Tomcat / `:8090` Tomcat 三个都 Listen，
`/api/ping` 走网关返回 `application=novel-auth`。

★ **顺带纠正一个写法**：M0 起 CLAUDE.md 一直教人加
`-Dmaven.repo.local=D:/.../mvn_repo`。其实**不必要** ——
`apache-maven-3.9.9/conf/settings.xml` 里已经写了
`<localRepository>` 指向同一个目录。加上去无害，但不知道这件事的人会以为不加就跑不了。

### 坑 8 ★★★ `.gitignore` 的「忽略规则静默失效」**第二次复发**

**这是 M1 发现的最有安全后果的一个问题。**

M1 加了一个新模块 `novel-auth`。而 `.gitignore` 里对生产配置的忽略规则是**逐条枚举模块路径**的：

```
backend/novel-backend/src/main/resources/application-prod.yml
backend/novel-gateway/src/main/resources/application-prod.yml
```

于是 `backend/novel-auth/src/main/resources/application-prod.yml` **不在忽略范围内**。
实测（`git check-ignore`）：

```
backend/novel-gateway/.../application-prod.yml  → 已忽略
backend/novel-backend/.../application-prod.yml  → 已忽略
backend/novel-auth/.../application-prod.yml     → ★ 未忽略
```

> **后果**：按 `docs/部署.md` 建的那个文件里装的是**生产数据库密码、Redis 密码**。
> `git add -A` 一下就直接进仓库 —— **没有任何报错或提示**。
> 而 `.gitignore` 的失效永远是静默的：它不会说「我漏了一条」，
> 它只是**不再匹配**，看起来和「本来就没有这个文件」一模一样。

★★ **真正值得记的是它已经是第二次了。** 上一个 commit（`b84ec90`）
的标题就是「修复 .gitignore：M0 搬迁后生产配置忽略规则静默失效」——
M0 把代码从 `backend/src/` 搬到 `backend/novel-backend/` 时漏改了路径，修法是把新路径**加进列表**。
M1 加了个模块，同一个坑立刻复发。

★ **所以 M1 的修法不是「再加一行 `novel-auth`」**，而是把枚举换成通配：

```gitignore
backend/*/src/main/resources/application-prod.yml
backend/*/src/main/resources/application-prod.yaml
```

验证：三个已有模块 + 模拟出来的 `backend/novel-content/`（M2 将来的模块）**全部命中**，
`.yaml` 变体也命中；**反面对照** `application.yml` / `application-dev.yml` 仍未被忽略
（证明规则没有误伤 —— 这个对照很重要，写宽了会把该提交的配置一起吞掉）。

★ **一般化的教训**（这条比修法本身更值钱）：
**凡是「新增一个同类对象就要回来补一条规则」的写法，都是在等下一次漏。**
同类的还有 M1 里那个「每加一条路由就要记得写 `order`」——
那个的解法是显式写死 order（见 §六）。这里的解法是通配。
**能用模式表达的就别用枚举** —— 枚举的失效是静默的，而模式不会漏。

---

## 十、验收证据（实测）

### ① 主链路 —— 大纲 M1 的验收原文

| 检查 | 结果 |
|---|---|
| 经网关登录 `:8080/api/auth/login` | **200 + token** ✓ |
| **直连 `:8081/api/auth/login`** | **200** ✓（证明它确实住在 novel-auth 里） |
| 带 token 经网关打 `/api/admin/users` | **200，total=4** ✓ |

### ② ★★ 判定性反证：认证真的搬到网关了

| 场景 | 期望 | 实测 |
|---|---|---|
| **直连 `:8090/api/admin/users` 带【合法 token】** | **401** | **401** ✓ |

★ 这一条是整个 M1 最有说服力的证据：token 本身完全有效，
但 novel-backend **压根不解析 `Authorization`** 了 —— 它只信头。
如果它还返回 200，说明下游还在自己验签，那「网关接管」就是假的。

### ③ 三态与安全矩阵（铁律 3）

| 场景 | 期望 | 实测 |
|---|---|---|
| 匿名 → 受保护接口 | 401 | **401** ✓ |
| READER 打 `/api/admin/**` | 403 | **403** ✓ |
| 密码错 | **200 + code=1002** | **200 / 1002** ✓ |
| 匿名只读 ×7（categories / novels / novels/{id} / rank / search / chapters / notices） | 全 200 | **全 200** ✓ |
| **经网关伪造 `X-User-Id`+`X-Roles`** | 401 | **401** ✓ |
| **直连 `:8090` 伪造 `X-User-Id`+`X-Roles`** | 401 | **401** ✓ |
| **直连 `:8081/internal/**` 不带令牌** | 403 | **403** ✓ |
| **直连 `:8081/internal/**` 带令牌** | 放行 | **200** ✓ |
| **经网关打 `/internal/**`** | **404** | **404** ✓ |

### ④ ★ 那条「最容易静默出事」的验证：登出真的登出了

| 步骤 | 结果 |
|---|---|
| reader01 登两次，两把 token | **不同** ✓（jti 各自独立） |
| T1 登出 | 200 ✓ |
| **T1 再用 `/api/auth/me`** | **401** ✓ ← 若 `X-Jti` 没传，这里会仍然 200 |
| **T2 仍可用 `/api/auth/me`** | **200** ✓（jti 没串） |

### ⑤ 跨服务两条边（端到端）

| 检查 | 结果 |
|---|---|
| 一次性账号申请 → 审核通过（**走 Feign 写审计流水**） | 200 ✓ |
| `audit_log` 里出现该行，且**只有一行**（`Retryer.NEVER_RETRY` 生效） | n=1 ✓ |
| 该行 `targetTitle` 非空（**边 1：backend → auth 取昵称**） | `'M1Probe132907'` ✓ |
| 该行 `operatorName` 非空（边 1） | `'系统管理员'` ✓ |
| action=2 / targetType=4 | ✓ |
| **审核后该用户 `roles == ['READER','AUTHOR']`（两个元素）** | ✓ |
| **该用户能进 `/api/author/**`** | **200** ✓ |
| **匿名首页 `/api/novels` 的 `authorName` 全非空** | 5/5 ✓ |

### ⑥ ★ SSE 过网关**逐块到达**（实测时序，不是「看起来正常」）

```
DIRECT :8090   HTTP 401   ← 无内部令牌 → 匿名 → 不是 AUTHOR，正确地被挡
THROUGH :8080  HTTP 200   Content-Type=text/event-stream;charset=UTF-8
               bytes=1234  first_byte@0.122s  last_byte@2.077s  span=1.955s
               arrival: 0.12s@1B, 0.60s@309B, 1.11s@618B, 1.59s@926B, 2.08s@1234B
               -> STREAMED ✓
```

★ 判定方法是**量到达时序**：字节在 1.955 秒里分 5 批陆续到，
如果网关做了缓冲，它们会在最后一刻**一次性**到齐（span≈0）。

### ⑦ 两次上传**按字节**验收（不是「能出图」）

| 检查 | 结果 |
|---|---|
| 封面 → 过网关 GET 回来 | sha256 **完全一致** ✓ |
| 头像 → 过网关 GET 回来 | sha256 **完全一致** ✓ |

★ sha256 = `42a4ce20bcc287641ce2d86abc7a595c21b3bff7a3799923170cef2f8f765806`
（69 字节的合法 PNG）。★ 只看「200 + 正确的 Content-Type」是不够的，
**内容坏掉时这两样照样是对的**。

★ 顺带证明了 R10 那条债在**开发期**成立：头像由 novel-auth 写、由 novel-backend 服，
两个服务在同一台机器上共用同一条绝对路径。

### ⑧ 结构回归

| # | 检查 | 结果 |
|---|---|---|
| 1 | **网关 `dependency:tree` 锚定 grep** `spring-boot-starter-web:jar\|spring-webmvc\|tomcat-embed-core` | **零命中** ✓（反向对照：`spring-boot-starter-webflux` 命中 1，证明日志非空、正则有效） |
| 2 | 网关启动日志 | **`Netty started on port 8080`**，**没有** `Tomcat started` ✓ |
| 3 | `/actuator/gateway/routes` | **12 条**（10 auth order=1 + 2 backend order=10）✓ |
| 4 | **12 个锁定文件 md5** | 三端**逐字节一致**；`request.js` = `2047681401d85ed69cebbd069f4ee7dd`，**与 P4 基线完全相同** ✓ |
| 5 | 三端 `npm run build` | 全绿（9.03s / 5.09s / 8.98s）✓ |
| 6 | 前端 git 改动 | **0 处** ✓ |
| 7 | 种子基线 | 菜单 **READER 0 / AUTHOR 2 / ADMIN 15**、`sys_role_menu` **17 行**、账号 1~4 未动、novels 12 / chapters 47 ✓ |
| 8 | 一次性对象 | 全部清理，复查 0 条残留 ✓ |

★ **验收全程跑在带时间戳的一次性账号上，结尾断言种子完好**（铁律 5）。
清理用**字面 id 的 `IN (...)`**，没有用 `LIKE`。

★ 特别说明：坑 1 那个 bug **正是被这条规矩抓出来的** ——
种子账号全是单角色，只有一次性账号才会出现「审核通过后的双角色」形态。

---

## 十一、回滚

**M1 是单 commit。** 回滚 = `git revert`（或 `git reset --hard <M0 的 712c500>`）。

这套回滚**特别干净**，四个理由：

1. **没有任何 DDL、没有任何数据迁移。** 7 张表一张没动，回滚不涉及数据恢复。
2. **Redis 的 key 与格式一个字节没变**（`novel:auth:token:{userId}:{jti}`，
   `LoginUser` 仍在 `com.novel.common.security`）→ **回滚不会强制任何人重新登录**。
3. **前端 0 改动** → 回滚不需要动前端。
4. 步骤：停三个进程 → `git reset --hard 712c500` → `rm -rf backend/novel-auth`
   （新增目录会变成未跟踪文件）→ `mvn -f backend/pom.xml package` →
   按 M0 的方式起两个进程。**全程 < 5 分钟。**

★ 唯一要注意的：如果 M1 之后**在 auth 里改过数据**（审核了一条申请、封禁了一个用户），
回滚后这些数据仍在**同一个 `novel` 库**里、由单体继续访问 —— **不丢也不错位**。
这是「共享库」这个决策在回滚上的直接好处。

---

## 十二、明确**没有**改的东西

| 不动的 | 理由 |
|---|---|
| **12 个锁定文件**（三端 `request.js` / `auth.js` / `stores/user.js` / `styles/index.css`） | ★ 铁律 2。而且 M1 **根本不需要**改它们（前端调用全是相对 `/api`） |
| 三个前端的任何代码 | **0 改动**（`views/dev/ping.vue` 的行为变化见坑 6，只记录不改） |
| 业务 Service / Controller 的实现 | **只允许 import 行变化**。`AuthorApplyServiceImpl` 的接缝调用点（`:72` 注入、`:311` 调用）是「调用方零改动」的判定样本 |
| `dev-proxy/vite.config.js` | 转发目标仍是 8080（网关），不用改 |
| `docs/P1` ~ `docs/P6` | 它们记录的是**当时的事实**。M1 的文档负责说明「哪些描述在 M1 之后过期了」，**不回头改历史文档** |

★ **`docs/M0-骨架搭建.md` 里那些 8090 的提法不要回头改** —— 那是 M0 当时的事实。
M1 之后的变化是：**8080 从「网关」变成了「网关 + 认证入口」**，
而 **8090 从「唯一后端」变成了「内容服务」**。

### M0 文档里在 M1 之后变了的地方

| M0 当时说的 | M1 之后 |
|---|---|
| 「两个进程」（网关 + 单体） | **三个进程**（网关 + auth + backend） |
| 「`/doc.html` 是唯一不走 `:3000` 的入口」 | **两个**：`:8090/doc.html` 和 `:8081/doc.html` |
| 「单体 :8090 提供全部业务」 | :8090 只剩 content / interaction / ai |
| 「`/api/ping*` 测的是单体」 | 现在落到 **novel-auth**（见坑 6） |

---

## 十三、记一笔（M2 债 / 已知行为，M1 只记录不处理）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **服务级临时环** `novel-auth ⇄ novel-backend` | M3 的 MQ 解掉（审核事件异步化） |
| 2 | **`DashboardMapper` 是第三条跨服务数据边** | 手写 `@Select` 直接读 `sys_user`（`:95,97,142`）与 `author_apply`（`:103`）。M1 共享库 → **不影响**。但它自己的类注释已经预告「M2 摘 content 时必须回来处理这里」 |
| 3 | **上传目录共享** | 头像 auth 写、backend 服；开发期靠同一条绝对路径成立，**生产上两个容器各有文件系统 → 404**。解法在 M5（接 MinIO/OSS） |
| 4 | **`/internal/**` 的强鉴权** | M1 用共享密钥头；更彻底的是内网隔离 + mTLS，记为 M5 治理项 |
| 5 | **网关用同步 `TokenStore` 查 Redis** | 网关是 WebFlux，理论上该用 `ReactiveRedisTemplate`。M1 不做，是因为 `TokenStore` 必须是**和 novel-auth 完全相同的一份**实现 —— 为了网关改成响应式而复制出第二份，正好撞上决定 A 要防的那件事。记为 M5 治理项 |
| 6 | **`/api/auth/me` 的昵称「陈旧」** | 改完昵称，同一会话里 `me` 仍返回**登录那一刻的快照**（`LoginUser` 存在 Redis 里，`ProfileServiceImpl.updateNickname` 只改 DB、不刷 Redis）。★ **这不是 M1 的回归** —— 改造前 `UserContext` 也来自 Redis，行为**逐字相同**。同理，「审核通过拿到 AUTHOR 角色后，旧 token 的 roles 仍是旧的」也是同一族行为（重新登录即刷新）。写在这里，否则会被当成 M1 引入的 bug |
| 7 | **网关聚合接口文档**（knife4j-gateway） | M0 已决定推迟，M1 继续推迟（两个文档入口 + 文档写清） |
| 8 | **`X-Permissions` 头体积** | 当前权限码 10~20 条、几百字节，远低于 Tomcat 的 8KB 上限。**若将来权限码上千会变成 400 Bad Request**，记一笔 |
| 9 | **auth 不可用 = 全站 500** | 已接受并记录：匿名首页要显示作者昵称 → 走 Feign 调 auth → auth 挂了首页也挂。M1 接受（M3 之后可以给这条读边加缓存或降级） |
