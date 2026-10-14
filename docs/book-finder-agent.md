# 阅读端找书助手：基础版

## 功能和入口

登录阅读端后，点击顶部“找书助手”，或访问 `http://localhost:3000/reader/book-finder`。

支持自然语言查询、多轮修改筛选条件、“换一批”“类似第二本”、搜索与详情工具调用、推荐卡片、停止请求、会话恢复和新对话。查询当前已上架作品，按分类、单个标签、完结状态、字数范围和关键词筛选。

本文记录基础版。当前已叠加正文知识库、证据工具、摘要审核和 Skill 启停，以及可配置的 Embedding / Rerank 接入；使用方法和边界见 [进阶版说明](book-finder-advanced.md)。

## 启动

1. 在 IDEA 重新编译并重启 `ContentApplication`（8082）、`SearchApplication`（8084）、`AiApplication`（8085）。网关现有 `/api/ai/**` 路由已覆盖新接口，不需要新增路由。
2. 确保 auth、gateway、Redis、MySQL、Nacos 和现有依赖可用，启动 reader-web 与 dev-proxy。
3. 管理端 AI 配置继续沿用已有的模型地址、模型 ID、Key 和启用开关。Key 只在后台安全入口配置，不放入源码或浏览器。
4. `novel.ai.provider=deepseek` 使用真实兼容接口；`--novel.ai.provider=mock` 使用本地确定性演示，不调用模型，页面明确标注演示模式。该启动参数切换整个 AI 服务的 provider。

不用执行新增 SQL，基础版会话、配额与执行记录保存在 Redis；模型用量写入现有 `ai_usage_log`，场景编号 7，显示“读者找书”。没有修改现有业务表字段、状态机或种子数据。

官方 DeepSeek 的实际模型 ID 以配置为准；V4.1 Flash 的官方调用名为 `deepseek-flash`。第三方供应商可能使用其他 ID，必须支持 OpenAI 兼容 `tools` / `tool_calls` / `tool` 消息协议。[官方工具调用说明](https://api-docs.deepseek.com/guides/tool_calls/)

## 接口与调用链

```text
Vue → gateway → novel-ai
                  ├─ 模型：选择工具、理解条件变化、选择候选和简介引文
                  ├─ searchBooks → novel-search → novel-content 的结构化数据库查询
                  └─ getBookDetails → novel-content 的批量可见详情查询
```

现有 GET `/api/search` 的 ES 搜索保持原行为。找书工具使用新增的内部结构化查询，空关键词也能筛选；没有宣称该工具已支持 ES 结构化索引或语义检索。服务不可用返回明确失败，不伪装成“没有书”。关键词匹配书名、简介、标签及外部作者，原生作者昵称检索沿用普通搜索入口。

读者 API：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ai/book-finder/chat` | SSE；message/requestId/conversationId |
| GET | `/api/ai/book-finder/quota` | 读者找书独立配额、Mock 标识 |
| GET | `/api/ai/book-finder/conversations/{id}` | 当前用户会话；最近推荐重新校验可见性 |
| DELETE | `/api/ai/book-finder/conversations/{id}` | 清除当前用户会话 |

`requestId` 是客户端生成的 UUID；同一个请求 ID 与同一内容的成功重试重放结果，不再次调用模型或扣次数。重放时检查会话存在性、作品可见性和硬条件。失败请求不自动重跑，用户主动重发使用新请求 ID，避免隐藏的重复费用。

SSE 事件：`session`、`progress`、`text`、`result`、`error`、`done`。阶段进度实时发送；基础版模型调用为非流式工具消息，最终通过校验的回答与卡片发送给前端。当前没有模型原生逐 Token 输出，不展示隐藏推理。

请求必须带 `Accept: text/event-stream, application/json;q=0.9`，登录 Token 放请求头。所有书籍 ID 使用字符串。

## 条件、上下文和结果校验

- Redis 会话按用户 ID + UUID 隔离，TTL 24 小时；保存最近 6 轮对话、当前条件、上轮有序推荐和已推荐 ID。
- `searchBooks` 接收条件补丁：省略字段保留旧值，明确 null 清除对应条件。模型按用户要求理解本轮变更；首次搜索后 Java 禁止本轮后续搜索更改硬条件，只能调整关键词。
- “换一批”由 Java 把已推荐 ID 写入查询排除条件；每个会话最多记录 500 个，达到上限后开始新对话。
- “第二本”通过上轮有序作品资料解析，不用模糊匹配书名。Mock 仅演示第二本和常见分类/字数表达，不具备真实模型的自然语言理解能力。
- 工具名与参数字段白名单、类型和长度在 Java 校验；详情工具只能查本轮候选或上轮推荐。
- 最终 ID 必须同时出现在本轮搜索候选和详情结果中；再次从 content 查询实时详情，剔除下架、删除和不满足硬条件的作品。
- 推荐理由由真实分类、完结状态、字数和标签生成。模型提供的简介引文必须逐字匹配当前简介，否则丢弃；不直接使用模型自由编写的事实性推荐理由。
- 资料是数据，不能覆盖系统规则或增加工具；没有执行任意代码、任意 SQL 或调用任意 URL 的工具。

## 运行控制与用量

每个用户同一时间只允许一个找书请求。Redis 锁 90 秒，释放使用令牌比较，防止误解锁。

当前每次最多 5 轮模型调用、8 次模型工具调用、20 本搜索候选、5 张推荐卡片；总运行预算 60 秒，上游每轮最多 30 秒、输出最多 2048 Token，总已报告 Token 上限 20000。每轮调用之后核对总用量，因此该总量上限不是模型供应商的精确预扣费用额度。

工作线程池最多 4 个并发，没有等待队列；服务繁忙在 SSE 开始前返回 503。模型配置不可用返回 503，额度用完返回 429。取消和超时会中断本地执行并尽力取消上游请求，已产生费用不会自动撤销。取消后用户立即再发可能遇到旧任务尚未释放锁，稍后重试。

读者找书独立计次，每日上限 `min(20, ai_config.daily_limit)`，Mock 同样计次；作者 AI 配额独立。失败/中止保留已接受请求的计次，线程池拒绝的请求退还次数。新对话不会重置每日额度。

Redis 键：

- `novel:book-finder:session:{userId}:{conversationId}`：24 小时。
- `novel:book-finder:request:{userId}:{requestId}`：幂等记录，24 小时。
- `novel:book-finder:quota:{userId}:{date}`：计次，48 小时过期。
- `novel:book-finder:lock:{userId}`：90 秒。
- `novel:book-finder:run:{userId}:{requestId}`：工具名称、数量、耗时、用量和状态，7 天。

每轮真实模型请求通过现有用量设施记账；Mock 不写模型账本。未返回 usage 的失败请求，其 0 Token 不能解释为没有费用。基础版尚未提供运行记录管理页面。

## 代码位置

- Agent：`backend/novel-ai/src/main/java/com/novel/modules/agent/bookfinder/`。
- 模型规则和工具 Schema：`backend/novel-ai/src/main/resources/agent/bookfinder/prompts/`。
- 搜索接缝：`backend/novel-search` 的 `InternalBookFinderSearchController` 和 `BookFinderContentClient`。
- 内容查询：`backend/novel-content` 的 `InternalBookFinderController`。
- Vue：`reader-web/src/views/book-finder/`、`components/book-finder/`、`composables/useBookFinder.js`、`api/bookFinder.js`、`utils/bookFinderSse.js`。

## 验证

常规验证：

```powershell
mvn -f backend/pom.xml -pl novel-ai,novel-content,novel-search -am package -q
node --test reader-web/tests/bookFinderSse.test.js
npm --prefix reader-web run build
```

连接开发虚拟机的额外验收，显式选择执行：

```powershell
mvn -f backend/pom.xml -pl novel-ai,novel-content -am test -q '-Dbookfinder.live=true' '-Dtest=BookFinderDatabaseSmokeTest,BookFinderRedisSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

数据库验收只读已有已上架小说，不改配置或种子。Redis 验收使用独立测试用户与 UUID，并清除确切的测试键。HTTP 模型兼容性验收使用本机假端点，没有付费请求。

覆盖工具闭环、伪造 ID 拒绝、最终可见性检查、引文校验、多轮条件与排除 ID、循环上限、未知工具、幂等重放、读者权限、SSE 事件、工作线程身份和 UTF-8/CRLF 拆包、断流处理。

2026-10-13 实测结果：Java 常规测试 43 项通过，2 个显式连接环境的测试默认跳过；SSE 解析测试 4 项通过；reader-web 生产构建通过。另显式运行开发数据库只读验收与真实 Redis 验收，均通过。

还启动了隔离的完整 AI 服务，使用真实 Redis、MySQL 配置读取、Feign HTTP 调用及本机测试书籍端点，三轮分别推荐 5、5、2 本，互不重复；首次请求重放后计次仍为 3，恢复会话、删除和跨用户访问拒绝通过。隔离进程已停止，确切的测试 Redis 键已清理，付费调用数为 0。此验收使用 Mock 模型和测试书籍端点，不能解释成真实模型的推荐效果或 Nacos 三服务链路验收。

真实付费模型的推荐效果、Nacos 三服务链路及浏览器实际交互仍需在重启服务后联调，不把单元测试或本机假接口结果当作真实模型效果。
# 找书助手问题修复（2026-10-13）

管理员角色 `ADMIN` 不受找书助手每日次数配额限制，普通读者继续使用原配额。豁免由后端认证上下文决定，前端只显示 `unlimited`；管理员请求不会扣减或退还读者配额。单次执行轮数、Token、超时和并发限制仍保留。重启 `novel-ai` 并刷新阅读端后生效，无需 SQL。

首页示例改由已上架作品的真实分类生成，新增 content 内部 `/internal/book-finder/categories` 和 AI `/api/ai/book-finder/examples`。分类简称仅在唯一匹配时转换；分类与同义关键词重复时去除关键词，保留用户指定的完结状态及字数限制。空结果仍如实展示，不能保证任意条件都有书。

进度提示只保留当前对话内的一处；恢复历史会话期间阻止发送，避免恢复结果覆盖新请求。AI 安全过滤器提前写响应头，避免 SSE 工作线程与过滤器同时修改 Tomcat 响应头；超时线程先中断工作线程，不再争抢 SSE 发送锁。增加连续 12 次请求、分类简称、实际分类来源的回归测试。MockMvc 验证不等同于真实模型与网关的端到端复测。

更新后重启 `novel-content` 和 `novel-ai`，刷新阅读端。无需执行 SQL。真实 DeepSeek 第二次请求是否恢复，需要在新服务进程上复测。

