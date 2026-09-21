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
| 中间件 | MySQL / Redis（第一期）→ 再加 Nacos / RocketMQ / Elasticsearch（第二、三期） |

---

## 目录结构

```
novelManagement/
├── backend/                 # Spring Boot 单体（第二期才按 modules/ 拆服务）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/novel/
│       │   ├── common/      # Result / 异常 / 安全 / 上传 等公共设施
│       │   ├── config/      # SecurityConfig / WebMvcConfig / RedisConfig ...
│       │   └── modules/     # system（用户角色权限）/ content（作品章节）/ interaction（评论书架历史）
│       └── resources/
│           ├── application.yml        # 所有环境共用的配置
│           ├── application-dev.yml    # ★ 开发环境，唯一需要你改的文件（见下）
│           └── mapper/                # 复杂查询的 XML
│
├── reader-web/              # 阅读端   → 挂 /reader/   dev :5173
├── author-web/              # 创作中心 → 挂 /author/   dev :5174
├── admin-web/               # 管理端   → 挂 /admin/    dev :5175
├── dev-proxy/               # 开发代理 → dev :3000，把三个前端收进同一个 origin
│
├── docs/
│   ├── P1-认证权限.md ~ P6-书架评论.md   # ★ 每一期的完整设计论证（含踩过的坑）
│   ├── 部署.md                          # 生产部署（Nginx / jar / 环境变量）
│   ├── 环境配置.md                       # 虚拟机与 Docker 中间件搭建
│   ├── mq-notes.md                      # 消息队列笔记（第二期配套）
│   ├── docker/                          # docker-compose.yml + broker.conf + .env.example
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
> ★ **`broker.conf` 的 `brokerIP1` 第一期不用改** —— 那是 RocketMQ 的配置，
> 只有真的要起 RocketMQ 的时候才需要填虚拟机 IP。同样，`sudo sysctl -w vm.max_map_count=262144`
> 是给 Elasticsearch 用的，第一期也不需要。

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

### 3. 改配置 + 打包 + 起后端

先把 `backend/src/main/resources/application-dev.yml` 里**两处 `192.168.127.137`** 改成你的虚拟机 IP
（`spring.datasource.url` 和 `spring.data.redis.host`），然后：

```bash
# ---------- 在【仓库根目录】打包 ----------
mvn -f backend/pom.xml package

# ★ 必须在后端【停掉】的时候打包 —— jar 正在运行时 repackage 会失败并留下一个瘦 jar。
# ★ 如果 Maven 的本地仓库不在默认的 ~/.m2 下（本项目的机器就是），要显式指定：
#     mvn -Dmaven.repo.local=D:/你的仓库路径 -f backend/pom.xml package
#   不确定的话先跑一次不带这个参数的，报「找不到依赖」就说明得加上。

# ---------- 进 backend/ 启动 ----------
cd backend
java -jar target/novel-backend.jar
```

> ★ 为什么非得 `cd` 进去：`application-dev.yml` 里日志文件写的是**相对路径** `logs/novel-backend.log`，
> 相对的是**进程的工作目录**。在仓库根目录跑 `java -jar backend/target/...` 也不会报错，
> 只是日志会落到根目录的 `./logs/` 下 —— 于是「照文档去 backend/logs/ 找日志」的人会扑空。
> 生产环境用绝对路径就没这个讲究（见部署文档）。
>
> ★ 改了 `application-*.yml` **必须重新打包才生效** —— 配置是打进 jar 里的。
>
> ★ 后端起不来先看日志：`backend/logs/novel-backend.log`
> （★ **SQL 不在这个文件里** —— MyBatis 的 SQL 走 stdout，要抓 SQL 得在启动时额外重定向 stdout）。

后端起来后：`http://localhost:8080/api/ping` 返回 200、`http://localhost:8080/doc.html` 是接口文档。

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
| 8080 | 后端 | `/api/ping` → 200；`/doc.html` → 接口文档 |

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
| **P6** | **书架、阅读历史、书评与章评、点赞、移动端适配、README/部署文档** | [docs/P6-书架评论.md](docs/P6-书架评论.md) |

**第二期**（M0~M5）按服务拆分：网关 / 认证 / 内容 / 交互，引入 Nacos + RocketMQ + ES。
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

# 后端打包（★ 打包前后端必须是停的）
mvn -Dmaven.repo.local=<你的本地仓库> -f backend/pom.xml package
```

★ 用 `concurrently` 起的进程，**按 Ctrl+C 不一定能杀干净子进程**；端口还被占着的话，按端口找 PID 再杀：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3000,5173,5174,5175 | Select-Object LocalPort,OwningProcess
Stop-Process -Id <PID> -Force
```
