# 小说管理系统

一个**单体后端 + 三前端**的网文平台，跑通「作者创作 → 编辑审核 → 读者阅读 → 运营管理」全链路。
后端是一个 Spring Boot 应用，前端拆成读者端 / 创作中心 / 管理端三个独立应用，**开发和生产的拓扑完全一致**（同一域名下的三个路径）。

> 第一期（P0~P6）已完成：认证权限、内容主干、读者端、创作中心、管理后台、书架评论与打磨。
> 第二期按服务拆分（网关 / 认证 / 内容 / 交互），第三期接 AI 能力。进度见文末。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.2.12 · Java 21 · MyBatis-Plus · Spring Security + JWT · MySQL 8 · Redis 7 · Knife4j |
| 前端 | Vue 3.5 · Vite 8 · Element Plus 2.14 · Pinia · Vue Router · Axios（三端同栈） |
| 中间件 | MySQL / Redis（第一期）→ 再加 Nacos / RocketMQ / Elasticsearch（第二期）<br>★ Nacos 自 **M0** 起、RocketMQ 自 **M3** 起（阅读量 / 阅读进度 / 收藏数评分 / 审核流水四条写路径）、**Elasticsearch 自 M4 起已启用**（搜索倒排索引，自建镜像装了 IK 分词器） |

---

## 目录结构

```
novelManagement/
├── backend/                 # Maven 多模块（M0 起）。后端 M2 起四个进程，M4 起五个（网关 + auth + 内容 + 互动 + 搜索）
│   ├── pom.xml              # 聚合 POM（novel-parent）★ 打包打的是它，不是子模块
│   ├── novel-common/        # 公共设施：Result / 异常 / 安全 / 上传 / config（库，不是可执行 jar）
│   │                        #   ★ M1 起 config/ 里的技术配置（Redis/MyBatis/Jackson…）住这里，
│   │                        #     各服务共用一份 —— 复制第二份就是序列化漂移的开始
│   ├── novel-auth/          # ★ M1 新增，:8081，整个 modules/system（登录/用户/角色菜单/公告/作者申请）
│   ├── novel-content/       # ★ M2 新增，:8082，modules/content + modules/ai（作品/章节/分类/审核/AI）
│   │                        #   ★ M4 起「搜索」不在它这儿了 —— /api/search 搬去 novel-search；
│   │                        #     它还留着两个角色：降级路径的数据源 + 索引/装配的数据源（/internal/**）
│   ├── novel-search/        # ★ M4 新增，:8084，ES 检索 + 索引重建；★ 它【没有数据库】
│   ├── novel-interaction/   # ★ M2 由 novel-backend 【改名】而来，:8083，只剩 modules/interaction
│   │   └── src/main/        #   （评论 / 书架 / 阅读历史）
│   │       ├── java/com/novel/
│   │       │   ├── interaction/  # ★ M2 后 SecurityConfig 劈成两份，各进程一份
│   │       │   │                 #   （白名单天然是 per-service 的），这里放 interaction 那份
│   │       │   └── modules/     # content / ai 已搬去 novel-content，这里只剩 interaction
│   │       └── resources/
│   │           ├── application.yml        # 所有环境共用的配置
│   │           └── application-dev.yml    # ★ 开发环境，唯一需要你改的文件（见下）
│   └── novel-gateway/       # Spring Cloud Gateway，:8080 的唯一入口
│                            #   ★ M1 起它【还要验 JWT】：验签 + 查 Redis → 写身份头 → 转发
│                            #   ★ M2 起路由要区分 content / interaction：/api/novels/** 和
│                            #     /api/admin/** 是多边【共享】的，靠 order 分先后。
│                            #     ★ M4 起判据是「与 content 的这两个前缀重叠的具体路由，
│                            #       order 必须严格小于 5」：interaction=4、search=3、兜底=20
│
├── reader-web/              # 阅读端   → 挂 /reader/   dev :5173
├── author-web/              # 创作中心 → 挂 /author/   dev :5174
├── admin-web/               # 管理端   → 挂 /admin/    dev :5175
├── dev-proxy/               # 开发代理 → dev :3000，把三个前端收进同一个 origin
│
├── docs/
│   ├── P1-认证权限.md ~ P6-书架评论.md   # ★ 第一期的完整设计论证（含踩过的坑）
│   ├── M0-骨架搭建.md                    # ★ 第二期 M0：多模块 + 网关 + Nacos 注册
│   ├── M1-摘出auth.md                    # ★ 第二期 M1：摘出 auth 服务 + 网关接管 JWT
│   ├── M2-摘出content.md                 # ★ 第二期 M2：摘出 content + 跨服务边改走 Feign
│   ├── M3-引入MQ.md                      # ★ 第二期 M3：引入 RocketMQ，四条写路径改走消息
│   ├── M4-摘出search.md                  # ★ 第二期 M4：摘出 search + ES 倒排检索 + 索引消息
│   ├── 部署.md                          # 生产部署（Nginx / jar / 环境变量）
│   ├── 环境配置.md                       # 虚拟机与 Docker 中间件搭建
│   ├── mq-notes.md                      # 消息队列笔记（第二期配套）
│   ├── docker/                          # docker-compose.yml + broker.conf + .env.example
│   │                                    #   ★ M4 起多一个 Dockerfile-es（ES 官方镜像不含 IK 分词器，
│   │                                    #     只能自建；compose 的 es 段也从 image: 改成 build:）
│   └── sql/
│       ├── schema.sql                   # 建表（21 张，自带建库语句）
│       └── seed.sql                     # 种子数据（分类、菜单、账号、12 本演示作品）
│
├── CLAUDE.md                # 项目约定（给 AI 协作者看的铁律，人也可以读）
└── 项目大纲.md              # 需求与拆分的完整设计
```

---

## 快速启动

### 0. 前置

| 需要 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | `java -version`（本项目用 24 跑的） |
| Node.js | **`^20.19` 或 `>=22.12`** | `node -v`。这是 Vite 8 的硬要求，低一点点都起不来 |
| Maven | 3.6.3+ | 仓库里**没有** `mvnw`，得自己装一个，或者用 IDE 自带的 |
| MySQL / Redis | 8.0 / 7 | ★ 推荐按 [docs/环境配置.md](docs/环境配置.md) 用 Docker 跑在虚拟机上 |

### 1. 起中间件

按 [docs/环境配置.md](docs/环境配置.md) 装好 Docker，然后：

```bash
# 把 docs/docker/ 整个目录传到虚拟机的 ~/novel-docker/
cd ~/novel-docker
cp .env.example .env          # 改里面的密码和 IP
docker compose up -d mysql redis
```

> ★ **第一期只需要 MySQL + Redis** —— 上面这条只起这两个（`docker compose up -d` 不带参数会把
> Nacos / RocketMQ / ES 一起拉起来，8G 的机器会很吃力）。第二期拆服务时再开其余的。
>
> ★ **M3 起需要 RocketMQ**：`docker compose up -d rmqnamesrv rmqbroker`（容器定义 M0 就写好了，
> M3 只是第一次真的启动它）。★★ **`docker compose ps` 显示 `Up` 不算数** ——
> 要 `grep "boot success" broker 日志`。三条坑（bind mount 的 uid 3000 / `brokerIP1` /
> `boot success`）写在 [docs/环境配置.md](docs/环境配置.md)，实测记录在
> [docs/M3-引入MQ.md](docs/M3-引入MQ.md)。
>
> ★ **`broker.conf` 的 `brokerIP1`** 起 RocketMQ 之前必须填成虚拟机局域网 IP（M0 已填好）。
> 另外 `defaultTopicQueueNums = 4` 是 M3 显式加的，别删 —— 顺序消息的讨论每次都要说清队列数。
>
> ★★ **M4 起需要 Elasticsearch**：`sudo sysctl -w vm.max_map_count=262144`（这条现在真的要做了）
> 然后 `docker compose up -d --build elasticsearch`。
> ★ **注意 `--build`** —— ES 的官方镜像**不含 IK 中文分词器**，M4 为此加了 `docs/docker/Dockerfile-es`，
> compose 里的 `es` 段也从 `image:` 改成了 `build:`（这是 `docs/docker/` 第一次不是「零改动」）。
> ★★ **验收不能只看容器 `Up`**：`_analyze` 要拿 `ik_max_word` 和 `standard` **各跑一遍比输出**
> ——「斗破苍穹」前者出 `斗/破/苍穹`、后者出 `4 个单字`。**只跑 IK 的话两种结果都「像对的」。**
> 三条坑（`vm.max_map_count` / bind mount 的 uid 1000 / `docker.elastic.co` 不在 Docker Hub）
> 与实测记录见 [docs/环境配置.md](docs/环境配置.md) 和 [docs/M4-摘出search.md](docs/M4-摘出search.md)。
>
> ★★ **起来的顺序：RocketMQ 必须先于 novel-search / content** —— M4 实测，
> namesrv 不可达时 search **起不来**（MQ 挂在**注册路径**上）；
> 而 **ES 挂掉时服务照常能起**（ES 挂在**请求路径**上，有降级兜底）。

### 2. 建库灌数据

```bash
# 把 docs/sql/ 传到虚拟机，或者直接在虚拟机里执行
mysql -uroot -p < schema.sql
mysql -uroot -p < seed.sql
```

> ★ **不用手工 `CREATE DATABASE`** —— `schema.sql` 第 26 行自己带 `CREATE DATABASE IF NOT EXISTS novel`。
>
> ★ **两个脚本都是幂等的，可以反复执行**：建表全是 `CREATE TABLE IF NOT EXISTS`，灌数据全是 `INSERT IGNORE`。
> 重复执行只会打印一堆 `Duplicate entry` 警告，不影响结果。`seed.sql` 结尾自带断言，会打印
> `novels_should_be_12 / chapters_should_be_47 / contents_should_be_47` 和 `finished_should_be_2` —— 对得上就说明数据是好的。

### 3. 改配置 + 打包 + 起五个进程

★ **M2 起有四个 `application-dev.yml`，M4 起五个**（`novel-gateway` / `novel-auth` / `novel-content` / `novel-interaction` / `novel-search`），
**每一个**里面的 `192.168.127.137` 都要改成你的虚拟机 IP
（`spring.datasource.url`、`spring.data.redis.host`、`spring.cloud.nacos.discovery.server-addr`）。
漏改一个的症状是那个服务起不来或注册不上 —— 网关那个漏了就是全站 503。然后：

```bash
# ---------- 在【仓库根目录】打包 ----------
mvn -f backend/pom.xml package

# ★ 必须在后端【停掉】的时候打包 —— jar 正在运行时 repackage 会失败并留下一个瘦 jar。
# ★ 打的是聚合 POM backend/pom.xml（一次编出 novel-common + novel-auth + novel-content
#   + novel-interaction + novel-gateway）。
#   不要进子目录单独 package：那样 novel-common 会用仓库里的旧 jar，「改了没生效」且不报错。
# ★ 如果 Maven 的本地仓库不在默认的 ~/.m2 下（本项目的机器就是），要显式指定：
#     mvn -Dmaven.repo.local=D:/你的仓库路径 -f backend/pom.xml package
#   不确定的话先跑一次不带这个参数的，报「找不到依赖」就说明得加上。

# ---------- 进 backend/ 启动【四个】进程（★ 顺序无所谓，但四个都要起）----------
cd backend
java -jar novel-gateway/target/novel-gateway.jar              # :8080 唯一入口（验 JWT）
java -jar novel-auth/target/novel-auth.jar                    # :8081，不直接对外（登录/用户/角色）
java -jar novel-content/target/novel-content.jar              # :8082，不直接对外（作品/章节/分类/AI）
java -jar novel-interaction/target/novel-interaction.jar      # :8083，不直接对外（评论/书架/历史）
```

> ★ **少起一个会怎样**：少 `novel-gateway` → 全站连不上（:8080 没人监听）；
> 少 `novel-auth` → **登录全挂**（登录接口在 auth 里），而且匿名首页也会 500
> （它要调 auth 取作者昵称）；少 `novel-content` → 作品/章节/搜索/AI 全 503，评论区还是好的；
> 少 `novel-interaction` → 评论/书架/历史全 503，但**书架和历史的列表还会 500**
> （它们要反过来问 content 要书名）。
>
> ★ 三个下游服务（`:8081`/`:8082`/`:8083`）之间还要互相调用，
> 所以 `novel.internal.token` 必须**五处一致**（网关 + 三个服务 + 手工 curl）。

> ★ 为什么非得 `cd` 进 `backend/`：四个服务里日志文件写的都是**相对路径**
> （`logs/novel-gateway.log` / `logs/novel-auth.log` / `logs/novel-content.log` / `logs/novel-interaction.log`），
> 相对的是**进程的工作目录**。
> 在仓库根目录跑 `java -jar backend/novel-interaction/target/...` 也不会报错，
> 只是日志会落到根目录的 `./logs/` 下 —— 于是「照文档去 backend/logs/ 找日志」的人会扑空。
> ★★ **注意 `cd` 的是 `backend/` 而不是 `backend/novel-interaction/`** —— 后者同样不报错，只是日志
> 静默换到 `backend/novel-interaction/logs/`，所有文档同时变成假话。
> 生产环境用绝对路径就没这个讲究（见部署文档）。
>
> ★ 改了 `application-*.yml` **必须重新打包才生效** —— 配置是打进 jar 里的。
>
> ★ 起不来先看日志：`backend/logs/` 下的
> `novel-gateway.log` / `novel-auth.log` / `novel-content.log` / `novel-interaction.log`
> （**按进程分开**，先确认该看哪一个）
> （★ **SQL 不在这个文件里** —— MyBatis 的 SQL 走 stdout，要抓 SQL 得在启动时额外重定向 stdout）。

后端起来后，**走网关**：`http://localhost:8080/api/ping` 返回 200
（返回体里的 `application` 字段会告诉你是**哪个服务**答的 —— `/api/ping*` 归 auth，所以是 `novel-auth`）。
接口文档是**不走网关**的开发入口，现在有**三个**：`http://localhost:8081/doc.html`（auth）、
`http://localhost:8082/doc.html`（内容服务）、`http://localhost:8083/doc.html`（互动服务）。

### 4. 起前端

★ 这几条要在**仓库根目录**跑（上一步 `cd backend` 了的话先 `cd ..` 回来）：

```bash
npm run install:all     # 一次装好根目录 + 四个子项目的依赖
npm run dev             # concurrently 同时起 reader / author / admin / proxy
```

> ★ **Windows 上 npm 装包报 `EPERM` 的话**，是默认缓存目录不可写，换个目录即可：
>
> ```powershell
> $env:npm_config_cache = "$env:TEMP\novel-npm-cache"
> npm run install:all
> ```
>
> 用**环境变量**而不是 `--cache` 参数：`install:all` 内部还会调四次 `npm install`（每个子项目一次），
> 环境变量能传给它派生的所有 npm 进程，命令行参数不一定传得到。
>
> ★ 子项目正在跑 `vite dev` 时，**不要**在同一个子项目里 `npm run build` / `install` ——
> 会争 `node_modules/.vite`，把 dev server 搞崩。

### 5. 打开

**一律从 `http://localhost:3000/` 进**，那里有一个导航页。

> ★★ **不要直接访问 `localhost:5173/5174/5175`** —— `localStorage` 按 **origin（协议+主机+端口）** 隔离，
> 5174 读不到 5173 写的登录态，于是「在阅读端登录了，跳创作中心又要重新登录」。
> `dev-proxy` 就是为了消掉这个差异而存在的：**它把三个前端收进 :3000 的三个路径下，和生产 Nginx 是同一个拓扑**。

---

## 端口对照

| 端口 | 是什么 | 直接访问根路径 |
|---|---|---|
| **3000** | **开发代理（入口，只用这个）** | 200 → 导航页 |
| 5173 | 阅读端 | 302 → `/reader/` |
| 5174 | 创作中心 | 302 → `/author/` |
| 5175 | 管理端 | 302 → `/admin/` |
| 8080 | **网关**（第二期 M0 起的唯一入口，M1 起还负责验 JWT） | `/api/ping` → 200 |
| 8081 | **auth 服务**（M1 新增，不直接对外） | `/doc.html` → 接口文档（★ 不走网关） |
| 8082 | **内容服务**（M2 新增：作品/章节/分类/审核/AI，不直接对外） | `/doc.html` → 接口文档（★ 不走网关） |
| 8083 | **互动服务**（M2 由 novel-backend 改名：评论/书架/历史，不直接对外） | `/doc.html` → 接口文档（★ 不走网关） |
| 8084 | **搜索服务**（★ M4 新增：ES 检索 + 索引重建，不直接对外；★ 它没有数据库） | `/doc.html` → 接口文档（★ 不走网关） |
| 9200 | **Elasticsearch**（★ M4 起，在中间件虚拟机上） | `/_cluster/health` → `green`；`/_cat/plugins` → `analysis-ik 8.13.4` |
| 9876 | **RocketMQ namesrv**（M3 起，在中间件虚拟机上） | 无 HTTP 界面 |
| 10909 / 10911 / 10912 | **RocketMQ broker**（M3 起；10911 是客户端连的那个，`RemotingTooMuchRequestException` 里那个地址就是它） | 无 HTTP 界面 |

> ★★ **直连 8081 / 8082 / 8083 / 8084 要带内部令牌**（`X-Internal-Token`），否则身份头一律被忽略、当**匿名**。
> 这是有意的：那四个端口在生产上只对内网开放，直连只是排错手段。令牌值见各自的 `application-dev.yml`。

★ 四个端口都设了 `strictPort: true` —— 端口被占时**直接启动失败**，不会静默换到 3001。
少了这个，打开 `:3000` 看到的会是**上一个还活着的进程**转发出来的旧内容，页面能开、接口能通，
只有代码改动不生效，非常难查。

---

## 种子账号

`schema.sql` + `seed.sql` 跑完之后，**只有下面两个账号**：

| 账号 | 密码 | 角色 | 能做什么 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 用户/作品/章节/评论审核、菜单权限、系统公告、数据大盘 |
| `author01` | `123456` | AUTHOR | 创作中心：建作品、写大纲正文、AI 写作助手 |

> ★ **这两个是本地开发的固定演示值**，明文写在 `docs/sql/seed.sql` 里、随仓库提交，**不含任何真实凭据**。
> 上线前必须改掉 `admin` 的密码 —— 见 [docs/部署.md](docs/部署.md)。

### 想用一个读者账号？

**阅读端目前没有注册页** —— 这是 **P1 有意的取舍，不是漏做**（理由写在 [docs/P1-认证权限.md](docs/P1-认证权限.md) 里：
后端接口做完就等着接，凭空加第四个页面当时对验收标准没有帮助）。管理后台也**没有**「新增用户」（同样是有意的）。

所以直接调接口建一个：

```bash
# ① 先把请求体写进一个文件（★ 中文不能写在命令行里，见下）
cat > register.json <<'EOF'
{"username":"reader01","password":"123456","nickname":"测试读者"}
EOF

# ② 再发出去
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  --data-binary @register.json
```

> ★★ **为什么非要绕一个文件**：Windows 上命令行的活动代码页是 ANSI（GBK），
> 行内直接写中文会被按 GBK 编成字节，而 JSON 要求 UTF-8 —— 服务端解不出来就报 **400**，
> 看起来像「接口写错了」或「参数类型不对」，其实只是编码问题。
> `--data-binary @文件` 则原样按字节发送，文件是 UTF-8 就没问题。
>
> ★ PowerShell 里 `curl` 是 `Invoke-WebRequest` 的**别名**，参数完全不通用 ——
> 要么写 `curl.exe`，要么换 Git Bash / cmd 来跑这一段。

新账号默认是**普通读者（READER）**，登录后就能用书架、阅读历史、书评和章评。
想让它变成作者，走阅读端顶部的**「申请作者」**→ 管理端审核（这条完整链路 P6 已经打通）。

---

## 每一期做了什么

| 期 | 内容 | 设计文档 |
|---|---|---|
| P0 | 脚手架：三前端 + 后端 + 开发代理，四端口跑通 | — |
| P1 | 认证权限：注册/登录/JWT/RBAC，三端登录态共享 | [docs/P1-认证权限.md](docs/P1-认证权限.md) |
| P2 | 内容主干：分类/作品/大纲/章节，草稿到发布的状态机 | [docs/P2-内容主干.md](docs/P2-内容主干.md) |
| P3 | 读者端：首页/分类/搜索/排行榜/详情/阅读器 | [docs/P3-读者端.md](docs/P3-读者端.md) |
| P4 | 创作中心：作品管理、大纲、正文编辑、AI 写作助手、封面上传 | [docs/P4-创作中心.md](docs/P4-创作中心.md) |
| P5 | 管理后台：审核工作流、菜单权限、公告、数据大盘 | [docs/P5-管理后台.md](docs/P5-管理后台.md) |
| P6 | 书架、阅读历史、书评与章评、点赞、移动端适配、README/部署文档 | [docs/P6-书架评论.md](docs/P6-书架评论.md) |
| **M0** | **骨架搭建：父 POM 改多模块、抽出 `novel-common`、gateway 起来并配好路由、服务注册进 Nacos** | [docs/M0-骨架搭建.md](docs/M0-骨架搭建.md) |
| **M1** | **摘出 auth：`modules/system` 搬进独立进程 `:8081`、网关接管 JWT 校验、下游只读身份头、两条跨服务边改走 Feign** | [docs/M1-摘出auth.md](docs/M1-摘出auth.md) |
| **M2** | **摘出 content：`modules/content`+`modules/ai` 搬进独立进程 `:8082`、`novel-backend` 改名 `novel-interaction` 退到 `:8083`、九条跨服务边全部改走 Feign、详情页跨服务取到作者信息** | [docs/M2-摘出content.md](docs/M2-摘出content.md) |
| **M3** | **引入 RocketMQ：四条写路径改走消息（阅读量 / 阅读进度 / 收藏数评分 / 审核流水）、`NovelViewCounter` 与 30 秒定时回写整类删除、两个跨服务写回债（R8/R9）修掉、auth ⇄ content 那个服务级环用事务消息断开** | [docs/M3-引入MQ.md](docs/M3-引入MQ.md) |
| **M4** | **摘出 search + 上 ES：搜索从 MySQL `LIKE`（+ 一次全表扫描的作者名 Feign）换成「ES 出有序 id + 一次主键批量查」，相关性排序、简介进检索范围、IK 分词、ES 挂了自动降级回 `LIKE`、索引靠 MQ 增量 + 定时全量重建自愈** | [docs/M4-摘出search.md](docs/M4-摘出search.md) |

**第二期**（M5）继续：治理。
**第三期**（E1~E3）AI 能力、限流熔断、移动端 App。

> ★ 每一期的 `docs/Pn-*.md` **不只是设计稿，更是踩坑记录** —— 里面记着「为什么这么做」和
> 「不这么做会怎样」。改代码之前值得先搜一下对应那一期，很多坑已经踩过了。

---

## 开发约定

改代码之前请先读 [CLAUDE.md](CLAUDE.md)。几条最容易踩的：

1. **12 个文件被 md5 锁定**，任何一期都不许改：三端各自的 `src/utils/request.js`、`src/utils/auth.js`、`src/stores/user.js`、`src/styles/index.css`。
2. **业务失败一律返回 HTTP 200 + 业务码**，只有 400~599 段一一对应真实状态码。验收脚本**不要用状态码判断业务成败**。
   三态分清楚：匿名 → **401**；身份不足 → **403**；业务失败 → **200 + code**。
3. **服务边界单向**：`content → system` 允许，`system → content` 禁止（第二期要拆服务，反了就有双向依赖）。
   跨模块读数据走接口接缝，接口定义在**消费方**那一侧。
4. **验收/回归脚本绝不拿种子数据当试验台** —— 实验跑在带时间戳的一次性对象上，结尾断言种子完好。

---

## 常用命令

```bash
npm run dev            # 起四个前端（reader + author + admin + proxy）
npm run dev:reader     # 只起一个
npm run build          # 三端打包（产物在各子项目 dist/）
npm run install:all    # 装全部依赖

# 后端打包（★ 打聚合 POM，打包前后端必须是停的）
mvn -Dmaven.repo.local=<你的本地仓库> -f backend/pom.xml package
npm run dev:gateway     # 网关   （= 打包 + cd backend + java -jar）
npm run dev:auth        # auth
npm run dev:content     # 内容服务（作品/章节/分类/AI）
npm run dev:interaction # 互动服务（评论/书架/历史）
```

> ★★ **不要用 `mvn -f backend/novel-auth/pom.xml spring-boot:run` 单独跑子模块** ——
> 它会报 `Could not find artifact com.novel:novel-common:jar:1.0.0`。
> 因为 `novel-common` 是**同一 reactor 里的模块**，从没被 `install` 进本地仓库，
> 只有 `-f backend/pom.xml`（聚合 POM）才解析得到它。
> `npm run dev:*` 四个脚本因此都是「打整个 reactor 的对应模块 + 跑 jar」两步，
> 不是 `spring-boot:run`。★ 加了 `-pl <模块> -am` 也不行 —— CLI 的 goal 会先落到聚合 POM 上，报「没有主类」。

★ 用 `concurrently` 起的进程，**按 Ctrl+C 不一定能杀干净子进程**；端口还被占着的话，按端口找 PID 再杀：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3000,5173,5174,5175 | Select-Object LocalPort,OwningProcess
Stop-Process -Id <PID> -Force
```
