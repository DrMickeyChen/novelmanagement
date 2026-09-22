# 小说管理系统 · 项目约定

> ★★ **这份文件每个会话都会自动加载 —— 它本身就是成本。**
> 只写「不看就会做错」的东西；能从代码里读出来的（目录结构、类名、某个方法怎么实现）不要写。
> **加内容之前先问自己：删掉这段，下次会不会做错？** 不会就别加。
>
> 每期的完整设计论证：第一期在 `docs/Pn-*.md`（P1 认证权限 / P2 内容主干 / P3 读者端 / P4 创作中心 / P5 管理后台），
> 第二期在 `docs/Mn-*.md`（M0 骨架搭建 / M1 摘出 auth）。

## 项目

求职作品集，主线是「单体 + 三前端」跑通全链路，第二期再拆微服务来体现微服务/消息队列能力。
第一期 P0 脚手架 → P1 认证权限 → P2 内容主干 → P3 读者端 → P4 创作中心 → P5 管理后台 → P6 书架评论+打磨（**一期已全部完成**）。
第二期 **M0 骨架搭建、M1 摘 auth+网关接管 JWT（均已完成）** → M2 摘 content+Feign → M3 摘 interaction+MQ 阅读量 → M4 摘 search+ES → M5 治理。第三期 E1 AI。
**★ 一次只拆一个服务**，顺序不能反：先摘 auth（system）再摘 content，否则双向依赖拆不开（见铁律 1）。

三前端：`reader-web`:5173 `/reader/`、`author-web`:5174 `/author/`、`admin-web`:5175 `/admin/`、`dev-proxy`:3000（开发期拓扑 == 生产 Nginx 拓扑）。
开发入口一律用 **`http://localhost:3000/...`** —— localStorage 按 origin 隔离，直连 5175 读不到另外两端的登录态。

★ **M1 起后端分成三个进程**：**网关 `:8080`（唯一入口，也是唯一验 JWT 的地方）** → `lb://novel-auth` → **auth `:8081`**（`modules/system` 全部：登录/用户/角色菜单/公告/作者申请/ping）／ `lb://novel-backend` → **单体 `:8090`**（content / interaction / ai，**不再验签，只读身份头**）。
所以 `localhost:8080` 是网关、不是后端；排错要绕过网关时直连 `:8081` 或 `:8090`。
★★ **直连 `:8081`/`:8090` 必须带 `novel.internal.token`（走 `X-Internal-Token`）**，否则身份头一律被忽略、当**匿名**处理 —— 这是刻意的（决策 4），不是 bug。
★ **开发期不走 `:3000` 的入口只有接口文档，且现在有两个**：`http://localhost:8090/doc.html` 和 `http://localhost:8081/doc.html`（网关【故意】不透传 knife4j，理由见 `docs/M0-骨架搭建.md`）。
★ **`:8081`/`:8090` 在生产上必须只对内网开放** —— 共享密钥只是第二道锁，第一道永远是网络隔离（见 `docs/M1-摘出auth.md` R9）。

## ★★ 铁律

1. **服务边界单向：`content → system` 允许，`system → content` 禁止。**
   二期先摘 auth（system）再摘 content，反了就有双向依赖拆不开。
   跨模块读数据只能走接缝，接口定义在**消费方**那一侧：`ContentUserLookup`（content 读 system）、
   `CommentTextLookup`（content 读 interaction）、`AuthorApplyAuditRecorder`（system 读 content，实现在 content）。
   ★ **M1 之后这条要按 HTTP 重新表述**：接口**实现**住在消费方（就是「发一次 Feign 调用」），
   提供方只暴露 URL、不暴露类型 —— 否则那是个能编译、能被扫描、**永远不会被调用**的死 bean。
   ★ **M1 造出了一个【服务级】临时环** `novel-auth ⇄ novel-backend`（内容要作者昵称 + 审核要写审计日志）。
   它符合铁律原文（那个接缝本就是受认可的例外），但服务级上确实是环 —— **M3 的 MQ 解掉**，别在 M2 顺手发明新解法。
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

## ★ 注释规矩

**照项目既有习惯写**：注释讲「为什么」和「不这样做会怎样」，该展开就展开，不设行数上限。
样板见 `CommentServiceImpl` / `ContentUserLookup` / `SecurityConfig`。
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
#     只对 package 这类【生命周期阶段】有效。所以 npm run dev:gateway/auth/backend
#     都是「-pl <模块> -am package + java -jar」两步，不是 spring-boot:run（docs/M1 踩坑 7）。
# ★ -Dmaven.repo.local 可省：settings.xml 里的 <localRepository> 已指向同一个 mvn_repo。
# ★ 必须在后端停掉的时候打包（jar 正在运行时 repackage 会失败并留下瘦 jar）
# 启动（用 Bash 工具，不要用 PowerShell 跑 mvn）
Start-Process -FilePath 'D:\MickeyChen\usefultool\IDEA\JDK\bin\java.exe' `
  -ArgumentList '-jar','novel-backend/target/novel-backend.jar' -WorkingDirectory '<backend 绝对路径>' `
  -RedirectStandardError '<backend>\logs\jvm-error.log' -WindowStyle Hidden
# ★★ -WorkingDirectory 必须是 backend/【不是 backend/novel-backend/】：
#    logging.file.name 是相对路径，跟着工作目录走 —— 改了就静默换目录，
#    所有「日志在 backend/logs/」的文档同时变假话，且没有任何报错。
# ★ M1 起【三个】jar 都要起（少一个就是全站 401 或全站 500）：
#   novel-gateway/target/novel-gateway.jar（:8080，logs/novel-gateway.log）
#   novel-auth/target/novel-auth.jar      （:8081，logs/novel-auth.log）
#   novel-backend/target/novel-backend.jar（:8090，logs/novel-backend.log）
# SQL 日志在 stdout —— 要 grep SQL 得额外加 -RedirectStandardOutput

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
