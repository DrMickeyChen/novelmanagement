# 小说管理系统 · 项目约定

> ★★ **这份文件每个会话都会自动加载 —— 它本身就是成本。**
> 只写「不看就会做错」的东西；能从代码里读出来的（目录结构、类名、某个方法怎么实现）不要写。
> **加内容之前先问自己：删掉这段，下次会不会做错？** 不会就别加。
>
> 每期的完整设计论证：第一期在 `docs/Pn-*.md`（P1 认证权限 / P2 内容主干 / P3 读者端 / P4 创作中心 / P5 管理后台），
> 第二期在 `docs/Mn-*.md`（M0 骨架搭建 / M1 摘出 auth / M2 摘出 content / M3 引入 MQ / M4 摘出 search）。

## 项目

求职作品集，主线是「单体 + 三前端」跑通全链路，第二期再拆微服务来体现微服务/消息队列能力。
第一期 P0 脚手架 → P1 认证权限 → P2 内容主干 → P3 读者端 → P4 创作中心 → P5 管理后台 → P6 书架评论+打磨（**一期已全部完成**）。
第二期 **M0 骨架搭建、M1 摘 auth+网关接管 JWT、M2 摘 content+Feign、M3 引入 RocketMQ、M4 摘 search+ES、M5 治理（均已完成）**。第三期 E1 AI。
★ **M3 的实质只有 MQ**：大纲把 M3 写成「摘出 interaction + 引入 MQ」两件事，而 interaction 在 M2 就已经是独立进程了 —— 拆分那半早就兑现。见 `docs/M3-引入MQ.md` 开头。
★ **M4 的实质是「换检索内核」**：搜索从 MySQL `LIKE`（+ 一次全表扫描的作者名 Feign）换成「ES 出有序 id + 一次主键批量查」，
**并且顺手证伪了大纲里的 `group-content-stat` 消费组**（`syncNovelStat` 读写的两张表同库同事务，拆成消息是**降级**）—— 见 `docs/M4-摘出search.md` 判断 1。
★ **M5 的实质是「把降级收紧成一条可判定的规则」**，不是「加超时」（超时 M2 就做完了，且 auth 里已没有 Feign）——
判据是 **「消费方能不能诚实地把这个缺失渲染出来」**（见铁律 10）；顺带证伪了大纲的「白屏」
（真实症状是一句**语气笃定的谎话**），并把「跨服务聚合改并行」的对象从详情页挪到**管理端审核日志**。
见 `docs/M5-治理.md` 一、二。
**★ 一次只拆一个服务**，顺序不能反：先摘 auth（system）再摘 content，否则双向依赖拆不开（见铁律 1）。

三前端：`reader-web`:5173 `/reader/`、`author-web`:5174 `/author/`、`admin-web`:5175 `/admin/`、`dev-proxy`:3000（开发期拓扑 == 生产 Nginx 拓扑）。
开发入口一律用 **`http://localhost:3000/...`** —— localStorage 按 origin 隔离，直连 5175 读不到另外两端的登录态。

★★ **M2 起后端分成四个进程，M4 起是五个**：**网关 `:8080`（唯一入口，也是唯一验 JWT 的地方）** →
`lb://novel-auth` → **auth `:8081`**（`modules/system` 全部：登录/用户/角色菜单/公告/作者申请/ping）／
`lb://novel-content` → **content `:8082`**（`modules/content` + `modules/ai`：作品/章节/分类/审核/AI）／
`lb://novel-interaction` → **interaction `:8083`**（`modules/interaction`：评论/书架/历史）／
`lb://novel-search` → **search `:8084`**（★ **M4 新增**：`GET /api/search`、`POST /api/admin/search/reindex`。
**它没有数据库** —— ES 只当「id 检索器」，装配 `NovelVO` 是 Feign 回 content 做。
★ **M5 起重建改【异步】**：`POST` 立刻返回 `{taskId}`，进度轮询 `GET /api/admin/search/reindex/{taskId}`。
**「提交成功」≠「重建成功」** —— 200 只说明任务进了队列，别读成「索引已经好了」）。
后三个**不再验签，只读身份头**。
所以 `localhost:8080` 是网关、不是后端；排错要绕过网关时直连 `:8081` / `:8082` / `:8083` / `:8084`。
★★ **直连 `:8081`/`:8082`/`:8083`/`:8084` 必须带 `novel.internal.token`（走 `X-Internal-Token`）**，否则身份头一律被忽略、当**匿名**处理 —— 这是刻意的（决策 4），不是 bug。
★★ **判据：这条链路上 401 = 身份没被采纳（当匿名），403 = 采纳了但权限不够。** 别把两者混成一句话 —— 修法完全不同（一个是配置，一个是赋权）。
（`HeaderAuthenticationFilter` 要求 `X-User-Id` **和** `X-Jti` **同时**在，缺一个就算匿名 —— M4 实测。）
★ **开发期不走 `:3000` 的入口只有接口文档，且现在有四个**：`:8081/doc.html`、`:8082/doc.html`、`:8083/doc.html`、`:8084/doc.html`（网关【故意】不透传 knife4j，理由见 `docs/M0-骨架搭建.md`）。
★ **`:8081`/`:8082`/`:8083`/`:8084` 在生产上必须只对内网开放** —— 共享密钥只是第二道锁，第一道永远是网络隔离（见 `docs/M1-摘出auth.md` R9）。
★★ **路由不变式（M4 起按这个表述，别只记 `4 < 5`）**：`/api/novels/**` 与 `/api/admin/**` 被**多个**服务同时占用
（content vs interaction 的正文/章评、作品管理/评论管理；content vs search 的 `/api/admin/search/**`），
所以网关**不能**用一条 `/**` 划开。**判据：任何与 content 的 `/api/novels/**` 或 `/api/admin/**` 重叠的具体前缀，
`order` 必须严格小于 content 的 `5`。** 现有三个适用者：
interaction 的 `/api/novels/*/comments` 系列（`4`）、search 的 `/api/search/**` 与 `/api/admin/search/**`（`3`）；
兜底那条在 `order: 20`。
**症状对照：被兜底吞掉的表现是静默 404**（看着完全像「这个接口没实现」）；
**只写 `/**` 不写裸路径**则裸路径掉进兜底 —— 两种漏法症状一样，**只能用「停一个服务看谁跟着死」分开**。
（不变的原文与理由在 `backend/novel-gateway/src/main/resources/application.yml` 的注释里。）

## ★★ 铁律

1. **服务边界单向：`content → system` 允许，`system → content` 禁止。**
   二期先摘 auth（system）再摘 content，反了就有双向依赖拆不开。
   跨模块读数据只能走接缝，接口定义在**消费方**那一侧。★ **M3 之后只剩两条**：
   `ContentUserLookup`（content 读 system）、`CommentTextLookup`（content 读 interaction）。
   （第三条 `AuthorApplyAuditRecorder`（system 读 content）**已随 M3 删掉** ——
   它整条边换成了事务消息，见铁律 7。）
   ★ **M1 之后这条要按 HTTP 重新表述**：接口**实现**住在消费方（就是「发一次 Feign 调用」），
   提供方只暴露 URL、不暴露类型 —— 否则那是个能编译、能被扫描、**永远不会被调用**的死 bean。
   ★★★ **「服务级环」的定义 = 【同步调用】的环，MQ 不算边**（M3 断环 #1 用的就是这个定义，
   M4 起它成为承重条款）：所以 `search → content`（Feign 读）是边，
   `content → search`（**只有 MQ**）**不是**边。★ 唯一的前提是**消费方必须是 search** ——
   若让 content 去消费再调 search 推文档，就造出了 `content → search` 的**同步**边 → 环。
   ★★ **M4 新增一条边**：`search → content`（`/internal/novels/index-docs` 等 3 个端点）。
   ★★★ **绝不新增 `auth → search`**：把作者名索引进 ES 之后，昵称改名会让索引陈旧，
   自然的想法是「auth 改名时通知 search」—— 但 **auth 现在的出站边是 0 条，那是 M3 好不容易挣来的**。
   **不为了一个极低频的场景把它加回来**，收敛点是定时全量重建。
   ★ **M1 造出的那个【服务级】环** `novel-auth ⇄ novel-content`：**M3 用事务消息解掉了** ——
   auth 对 content 的**唯一**一条出站边（`ContentAuditClient`）已删。
   ★★★ **复核 auth 出站边为 0，判据必须钉在【结构】上，不能 grep 一个词** ——
   M3 记的是 `grep -rn "novel-content\|ContentAuditClient" backend/novel-auth/src/` → 0 命中，
   **M5 实测它是 5**（而且**每写一次「它应该是 0」，注释就多含几个那个词，判据离 0 更远一步**）。
   **工作判据（M5 起）**：`grep -rn "^\s*@FeignClient(" backend/novel-auth/src/` = 0、
   `grep -rn "^\s*@EnableFeignClients" ...` = 0、**pom 去掉 XML 注释后**的 openfeign+loadbalancer 依赖 = 0。
   ★★ **M2 又造出第二个【服务级】环** `novel-content ⇄ novel-interaction`：**M3 没解，也解不了**。
   interaction 读 content 的 8 个内部端点 → M3 后降到 4 条左右（删掉 3 条写回 + 1 条审核流水，保留读）；
   content 反过来读 interaction 的 1 个（管理端审核日志要显示评论正文）。
   ★★ **这一个 MQ 解不了** —— 「审核日志要显示评论正文」是一次**同步读**，
   要解只能改成「审核时把正文快照写进 `audit_log`」，那是 DDL、属于 M5。
   **★ 而 M5 没做它 —— 它活过了 M5（第 4 期）**（论证见 `docs/M2-摘出content.md` 与 `docs/M3-引入MQ.md` §十三）。
   ★ M5 反而**加强了**这条边上的判断：这条回程**是刻意不降级的**（铁律 10 的第一个例子）。
2. **12 个文件逐字节锁定，任何一期都不许改**：三端各自的 `src/utils/request.js`、`src/utils/auth.js`、`src/stores/user.js`、`src/styles/index.css`。
   改完要复验 md5（基线值见 `docs/P4-创作中心.md` —— `request.js` = `2047681401d85ed69cebbd069f4ee7dd`，
   三端四个文件各自逐字节相同）。`utils/` 下**其它**文件（format/status/confirm/echarts）不在锁内。
3. **业务码一律返 HTTP 200**，只有 `400~599` 段一一对应真实状态码（见 `ResultHttpStatus`）。验收脚本**别用状态码判断业务成败**。
   三态要分清：匿名 → **401**；身份不足 → **403**；业务失败 → **200 + code**。
4. **前端模块校验要看 `content-type`**，不是状态码 —— 模块 URL 必须带 `base` 前缀（`/admin/src/...`），且**不存在的模块也返回 200**（SPA 回落 HTML），看到 `text/html` 等于什么都没验。
5. **验收 / 回归脚本绝不拿种子数据当试验台**：实验一律跑在带时间戳的一次性对象上，结尾断言种子完好。
   种子基线：`READER` 0 条菜单 / `AUTHOR` 2 条 / `ADMIN` 15 条，`sys_role_menu` 共 17 行，账号 1~4。
   清理数据用**字面 id 的 `IN (...)`**，不要用 `LIKE`。
6. **上传安全**（`docs/P4-创作中心.md` D-10）：文件名绝不采信客户端；**校验文件头魔数**而不是只看扩展名；上传目录必须是**外部绝对路径**。
7. **★★ 消息可靠性分级（M3）：推「增量」的可以乱序；推「绝对值」的，要么顺序、要么带时间戳做单调写。**
   五条消息**故意不一样**，别统一：`VIEW_REPORT` 增量 / 并发 / 不用防；`PROGRESS_REPORT` 绝对值 /
   ★ **全项目唯一一条 ORDERLY**；`NOVEL_STAT` 绝对值 / 并发 / **载荷时间戳 + 单调写**；`AUDIT_RECORD` 事实 / 并发 / **SETNX 幂等**；
   ★★ **`NOVEL_INDEX`（M4）推【事实】、载荷只有一个 id** —— **幂等天然、顺序无关、不需要 SETNX、不需要时间戳**。
   ★★★ **它是五条里唯一「幂等免费」的，因为载荷里没有数据**：消费者读的是**当时的真值**，
   两条乱序消息各自触发一次重建、两次都读同一份当前状态、写同一个文档 → 结果一样。
   **判据：「去重算一次」这个指令，永远比「算好的结果」好维护。**
   （★ 代价是它**不覆盖**「谁都没通知」这种情况 → 靠 `AfterCommit` 之外的那条定时全量重建兜底。）
   ★★ **一个 (服务, Tag) 一个消费组**，五个组不许合并 —— 订阅按**消费组**存在 Broker 上，
   组内不一致时后注册的**覆盖**前一个。**症状对照：某个 Tag 收不到消息且不报错 → 先查有没有两个 Tag 共用一个消费组。**
   （形状与路由那条 `order` 不变式一模一样。）
   ★ `maxReconsumeTimes` **必须显式写**（四个消费者都写 3）—— 顺序消费下不写 = `Integer.MAX_VALUE`。
   ★★ **但「我的消费者代码永不抛异常」这个保证只覆盖【方法体之内】** ——
   `rocketmq-spring` 的类型转换（如枚举反序列化）失败发生在方法被调用**之前**，
   我们的 `try/catch` 根本没机会执行 → 直接走框架的重试阶梯进 DLQ。
   **实操警告：给消息载荷里的枚举改名/删值，在途消息会【全部】静默进 DLQ。**
   ★★ **但别把「默认危险」说成「必然危险」**：显式写了之后，顺序消息重试 3 次照样进 `%DLQ%`、
   队列照样前进。**实测推翻了初稿里「永不进 DLQ、永久卡死」的断言**（`docs/M3-引入MQ.md` §四 判断 B′）——
   留一条 ORDERLY 的真正代价是**队头阻塞 + 固定 1 秒重试 + 单线程消费**，不是不可恢复。
8. **★★ 一条不会失败的护栏比没有护栏更糟。** 加护栏时必须同时准备一个**能证明它会失败**的阳性对照。
   实测教训：网关那条 `dependency:tree | grep` 有两个「看起来也对、其实什么都验不到」的写法，
   其中一个在仓库里存在了两期（`-pl` 单模块时 `novel-common` 解析不到 → 被当叶子节点 → 子树一个字不打印）。
   ★ 同理 `mvn dependency:tree -q` **不是安静，是什么都不打印**。
   ★★ **第二种形状（M4）：判据本身永远为真。** `grep -rni elasticsearch backend/novel-content`
   在任何 Boot 应用上都会命中 —— `spring-boot-autoconfigure` 里含有**所有**技术的自动配置类名
   （`@ConditionalOnClass` 让它们惰性，库不在就什么都不做）。
   **判据要换成看 `BOOT-INF/lib`**（`unzip -l x.jar | grep -i elastic`）。
   ★ **第三种形状（M5）：判据被自己的说明文字推翻。** 上面那条「auth 出站边 0 命中」的 grep
   每次被写进注释/文档，那段文字本身就含那几个词 —— **判据每被记录一次，就离通过更远一步**，
   最终**必然**失败，且失败原因和被测对象毫无关系。**判据要钉在结构上（包/依赖树/声明形式），别钉在词上。**
   ★ 解药是同一个：**换成差分式判据（和对照组比），别用阈值式（数个数）** —— 对照组的对齐是自动的。
   ★★ **M5 白捡的阴性对照**：同一条 traceId 日志里，请求行有 `[xxxx]`，调度线程行是 **`[]` 空的** ——
   这才证明 `[%X{traceId}]` 真在读 MDC，不是个写死的常量。**对照组就在同一份输出里，成本为零。**
9. **★★ 削峰削的是【写】，不是读。** 「请求路径上跨服务调用归零」这种说法要警惕 ——
   M3 保留下来的两次 Feign 读**是有信息量的错误码**（`2001` / `3001` 的抗探测设计），不能为了好看搬走。
   准确说法：「请求路径上的**写**从『两次远程读 + 一次本地写』变成『一次投递』」。
10. **★★★ 降级只对「消费方能【诚实地】把这个缺失渲染出来」的字段成立（M5）。**
   ★ **不是**「缺失在界面上看不看得见」—— 那是**被实测推翻**的前一版判据（`docs/M5-治理.md` §2.3）。
   反例：审核日志的评论正文那一格**只有两个渲染分支**（「有标题」/「已删除」），
   降级后它把「拿不到」渲染成 **「评论 #94（已删除）」** —— 对一条**从没被删过**的评论说的具体假话，
   还是在**追责页面**上。**没有第三个状态能容纳「暂时拿不到」→ 这里没有安全的降级值 → 宁可 500。**
   ★★ **降级 = 让字段从 JSON 里【消失】**（靠全局 `default-property-inclusion: non_null` + 包装类型），
   **绝不是**填 `0` / `false` / `""` 这类「看起来正常」的假值 ——
   所以**降级前先看字段是不是基础类型**：`int` 表达不了「未知」，降级它只会把 `0` 送出去。
   ★ **判别式是「这个字段会不会因为【别人挂了】而消失」，不是「它重不重要」** ——
   所以同一张卡片上，`authorName` 显示 `—`（跨服务，取不到=auth 挂了），
   而 `categoryName` 仍回落「未分类」（同库 JOIN，取不到=真被删了）。**「佚名」在本系统里是假话，不是兜底**
   （`novel.author_id` 是外键，每本书必然有作者）。
   ★★ **「参与筛选的输入」一律不降级**：`userIdsByNickname` 降级成空集合 = 结果集 0 条 + 界面说
   「没有找到相关作品」= M1 最反对的那种页面。**它停 auth 时必须 500，这是刻意设计的。**
   ★ **验收口径由此变成：用户能分清「没有数据」和「暂时拿不到」** —— 前端 `usePagedList` 的
   `isEmpty` 里**含** `!failed`，两支由构造互斥（别把 `error` 这个 ref 加了却没人消费）。

## ★ 注释规矩

**照项目既有习惯写**：注释讲「为什么」和「不这样做会怎样」，该展开就展开，不设行数上限。
样板见 `CommentServiceImpl` / `ContentUserLookup` / `ContentSecurityConfig` / `InteractionSecurityConfig`。
设计论证的详细版在 `docs/Pn-*.md`，但代码里也允许写全 —— **不要求搬进 docs 再留指针**。

- ★ **不刻意动注释**：已提交的注释**不做「瘦身」批量重写**。这是作品集，注释密度是给人读的，
  一次全量重写是几万行输出、收益却只在将来被读到的那几次（P5 实测：116 个文件 / 9,244 行注释 / 11.6 万汉字）。
  只有「同一段论证确实写了两遍」时顺手去重，不做压缩。
- **必须留在代码里、不能只放 docs 的**：弄错会造成不可逆损害或静默错误的那几条 ——
  ① 防自锁 `1020` / `1018`（`AdminRoleServiceImpl`）② 封禁/改角色踢下线（`AdminUserServiceImpl`）
  ③ 趋势零填充（`DashboardServiceImpl`）④ `sys_role_menu` 无 `deleted` 列、只能物理删 ⑤ 跨模块接缝方向 ⑥ 评论 10 秒频控的 `SET NX EX`。
- ★ **改动前先 `grep` 定位，别整文件读**；大文件用 `offset`/`limit` 只读相关段。
  （读大文件是本项目成本的大头 —— 一次整读 600 行的文件比写 50 行注释还贵。）

## 项目范式（照抄，别重新发明）

- **后端分页**：`PageResult<T>` + 每个 Service 的 `buildPage` 兜底（`size` 夹到 1~100）。
  ★★★ **`PageResult` 里绝对不许再出现 `IPage`（或任何 ORM 类型）** —— M4 实测：
  方法签名里**出现**第三方类型，`Class.getDeclaredMethods()` 解析不到就抛 `NoClassDefFoundError`，
  **整个类就此不可用**（哪怕那个方法一个字都没被调用）→ `novel-search` 一调降级路径就 500。
  ★ 判据不是「别把 `IPage` 放进 DTO」，而是 **「`IPage` 不许和任何人共处一个类」** ——
  连重载都不行（javac 的重载决议要把所有候选方法的参数类型都解析出来）。
  现拆成三个：`PageResult`（纯 DTO）／`PageResults`（零依赖装配）／`MybatisPageResults`（`IPage` 适配器）。
  ★ 同类判据的**完整阶梯**：字段注入第三方类型 → 起服务时炸；`implements`/`extends` 第三方类型 → 类加载时炸；
  **方法签名里出现 → 反射枚举方法时炸（最隐蔽）**；**注解里出现 → 永远不炸**（反射遇到解析不了的注解会静默丢弃）。
- **手写 `@Select` 时 `@TableLogic` 完全不生效** → 逻辑删除的表必须自己写 `deleted = 0`；
  但 `sys_role_menu` **没有** `deleted` 列（和 `sys_role` / `sys_menu` 规则相反），赋权只能物理 `DELETE` + `INSERT`。
- **单列 `.select()` 投影 + 可空列 → `List` 里会塞 `null` 元素** → 方法引用 NPE → 500（`docs/P5` 实施记录 ⑥）。
- **「按时间倒序」的列表一律带 `id DESC` 兜底** —— 同秒 `DATETIME` 会打平，翻页顺序会跳。
- **前端的 `filter` 用 `ref({...})` 整体替换**，在 fetcher 里展开现取值；列表钩子是 `usePagedList(fetcher, {pageSize})`。
- **`confirm()` 永不抛异常，返回布尔**；`ElMessageBox` 是命令式 API，**读不到语言包**，按钮文字要手写「确定」/「取消」。
- **批量操作顺序 `for...of await`，绝不 `Promise.all`**（会并发 re-UPDATE 同一行）；被跳过的条目**必须报出来**，绝不静默过滤。
- **「重复审核」四套口径各不相同**：作品=前置判待审、章节=`3003`、作者申请=同目标状态幂等/跨终态 `1016`、评论=`4005`。别统一成一句话。
- **「驳回必须填理由」是前端规则，后端刻意不拦**（`AuditDialog.vue` 的 `remarkRequired`）—— 别去后端加校验。

## 环境

```bash
# 后端（★ 改过 application-*.yml 必须重新打包才生效）
D:/MickeyChen/usefultool/apachemaven/download/apache-maven-3.9.9/bin/mvn.cmd \
  -Dmaven.repo.local=D:/MickeyChen/usefultool/apachemaven/download/apache-maven-3.9.9/mvn_repo \
  -f backend/pom.xml package
# ★ 必须打整个 reactor（backend/pom.xml 是聚合 POM）。在子目录单独跑【任何】goal 都不行：
#   novel-common 从没被 install 进本地仓库，只有聚合 POM 才在 reactor 内解析得到它 ——
#   实测是硬报错 `Could not find artifact com.novel:novel-common:jar:1.0.0`。
#   ★ 加 -pl <模块> -am 也救不了直接点名的 goal（会先落到聚合 POM 上报「没有主类」），
#     只对 package 这类【生命周期阶段】有效。所以 npm run dev:gateway/auth/content/interaction
#     都是「-pl <模块> -am package + java -jar」两步，不是 spring-boot:run（docs/M1 踩坑 7）。
# ★ -Dmaven.repo.local 可省：settings.xml 里的 <localRepository> 已指向同一个 mvn_repo。
# ★ 必须在后端停掉的时候打包（jar 正在运行时 repackage 会失败并留下瘦 jar）
# 启动（用 Bash 工具，不要用 PowerShell 跑 mvn）
Start-Process -FilePath 'D:\MickeyChen\usefultool\IDEA\JDK\bin\java.exe' `
  -ArgumentList '-jar','novel-interaction/target/novel-interaction.jar' -WorkingDirectory '<backend 绝对路径>' `
  -RedirectStandardError '<backend>\logs\jvm-error.log' -WindowStyle Hidden
# ★★ -WorkingDirectory 必须是 backend/【不是 backend/novel-interaction/】：
#    logging.file.name 是相对路径，跟着工作目录走 —— 改了就静默换目录，
#    所有「日志在 backend/logs/」的文档同时变假话，且没有任何报错。
# ★ M2 起【四个】jar 都要起，M4 起【五个】（少一个就是全站 401 / 503 / 500）：
#   novel-gateway/target/novel-gateway.jar          （:8080，logs/novel-gateway.log）
#   novel-auth/target/novel-auth.jar                （:8081，logs/novel-auth.log）
#   novel-content/target/novel-content.jar          （:8082，logs/novel-content.log）
#   novel-interaction/target/novel-interaction.jar  （:8083，logs/novel-interaction.log）
#   novel-search/target/novel-search.jar            （:8084，logs/novel-search.log）← ★ M4 新增
# SQL 日志在 stdout —— 要 grep SQL 得额外加 -RedirectStandardOutput
# ★★★ M5 起【每个进程还多监听一个 8719】（Sentinel 的 transport 端口，被占则向上探测）。
#   ★ 这也是 M5 唯一新增的【对外暴露面】：生产必须和 8081~8084 一起被防火墙挡掉。
#   ★ 排查时多一个「这是谁的端口」——别再当成野进程。
# ★★ M5 起排查的入口是 **traceId**：五份日志的 pattern 都是 `[%X{traceId}]`，
#   同一个请求在网关/content/... 里是【同一个】32 位串。用法：`grep <traceId> backend/logs/*.log`。
#   ★ 它由网关注入并【强制覆盖】客户端自带的 `X-Trace-Id`（否则能把别人的日志关联到自己身上）。
#   ★★ **前端【看不到】traceId** —— `request.js` 是锁定文件、响应拦截器 `return res.data`，
#      响应头在前端被丢掉。用户报错时**问他要不到号**，只能按「时间 + 页面」反查。别以为加了就能报号。
#   ★ Sentinel 自己的日志【不在】 backend/logs/ —— 在 `backend/logs/csp/`（按 PID 分文件），
#     `*-block.log` 是熔断拦截、`*-record.log*` 是规则与状态变更（含熔断打开/恢复）。
#     ★ 直接写 `csp.sentinel.log.dir` 是【无效】的，SCA 只认 `spring.cloud.sentinel.log.dir`。

# ★ M3 起中间件多一个 RocketMQ，在【同一个虚拟机】上（不是第五个 Java 进程）：
#   namesrv :9876 / broker :10909,:10911,:10912；容器定义在 docs/docker/docker-compose.yml
#   （★ 那个文件 M3 零改动，只是第一次被启动）
#   ★★ 起来之后【不能只看 docker compose ps 显示 Up】，要 grep broker 日志里的 "boot success"
#      —— 三条坑（uid 3000 的 bind mount / brokerIP1 必须是虚拟机局域网 IP / boot success）
#      写在 docs/环境配置.md。
#   ★★ 起来的顺序：**broker 必须先于消费方服务** —— ★ M4 已实测（补掉了 M3 的 R3）：
#      namesrv 不可达时 search **起不来**（`RemotingConnectException` 抛在 `DefaultMQPushConsumerImpl.start`）。
#      ★ 必须连 caveat 一起引用：测的是 **namesrv 不可达**，不是「namesrv 在、broker 挂了」。
#      ★ 而 **ES 挂掉时服务照常能起**（M4 实测，指向死地址也 9.1s 起来、日志里 0 条 ES 访问）
#      —— 这个不对称是刻意的：ES 挂在**请求路径**上（有降级），MQ 挂在**注册路径**上（注册不了就没得谈）。
#      ★ 诊断陷阱：启动失败的 JVM **不一定退出**（Netty/RocketMQ 的非守护线程吊住进程）——
#      **「进程在」不等于「服务在」，看端口在不在监听，别看 PID 在不在。**
#   ★ broker.conf 的 defaultTopicQueueNums = 4 是【显式写的】，别删 ——
#     ORDERLY 的讨论每次都要说清队列数，它不该藏在默认值里。

# ★ M4 起中间件多一个 Elasticsearch（同在那个虚拟机上，:9200）：
#   ★★ 官方镜像不含 IK 分词器，所以 **M4 起 docs/docker/ 有零改动之外的第一个例外**：
#      多了 Dockerfile-es（`FROM 8.13.4` + `elasticsearch-plugin install analysis-ik`），
#      compose 的 es 段也从 `image:` 改成了 `build:`。★ M3 文档里「那份 compose 零改动」这句话
#      M4 起不再成立（见 docs/M4-摘出search.md 开头）。
#   ★ 单节点 ES 必须 `number_of_replicas: 0`，否则索引一建出来健康就是 **yellow**。
#   ★★ 判据要**差分式**：`_analyze` 拿 `ik_max_word` 和 `standard` **都跑一遍**比输出 ——
#      「斗破苍穹」前者出 `斗/破/苍穹`（3 个，含 2 字词）、后者出 `斗/破/苍/穹`（4 个单字）。
#      **只跑 IK 的话 3 个和 4 个都「像对的」，等于没验。**
#   ★ ES 的 `xpack.security.enabled: false` 是 **dev 专用**，compose 注释里自己写了生产不能这么配。
#   ★ search 的 `spring.elasticsearch.uris` 是 `${ES_URIS:http://192.168.127.137:9200}` ——
#      不配会**静默 fallback 到 localhost:9200**（只有 INFO，没有 WARN）。
#      ★★ 这个环境变量也是 M4 的**测试钩子**：指向本地 TCP 代理即可做「ES 挂掉/恢复」三阶段实验，
#       **无需改代码、无需重新打包**（实测方法见 docs/M4-摘出search.md 九）。

# 前端：根目录 npm run dev（concurrently 起四端）；npm 装包要 --cache $env:TEMP\novel-npm-cache
# ★ 绝不在某子项目跑着 vite dev 时在同一子项目 npm run build / install（争 node_modules/.vite，dev server 会崩）

# MySQL（PATH 里没有；密码走环境变量，别写进脚本文件）
MYSQL_PWD=root123456 "/c/Program Files/MySQL/MySQL Workbench 8.0/mysql.exe" \
  -h192.168.127.137 -uroot novel --default-character-set=utf8mb4 < x.sql 2>&1 | grep -v "Using a password"

# 种子账号：admin/admin123（ADMIN）、author01/123456（AUTHOR）、reader01/123456、reader02/123456（READER）
```

★ 端口用 `strictPort: true`，漂移不报错（`docs/P5` 环境踩坑 3）。
★ `TaskStop` **杀不掉 `concurrently` 的子进程** —— 要 `Get-NetTCPConnection -State Listen` 找 PID 再 `Stop-Process -Force`。
★ Windows 上 Python 的 stdout **和 stdin 都是 ANSI 码页**：print 只输出 ASCII；读 curl 结果要写文件再用 `io.open(..., encoding='utf-8')`。

## 提交

一期一个 commit，消息 `pN完成`（正文写清这一期做了什么、怎么验的）。直接提交到 `main`。
★ 提交前复验：12 个锁定文件 md5 未变、staged diff 里没有密钥/密码。
