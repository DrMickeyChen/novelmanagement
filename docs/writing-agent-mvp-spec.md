# 写作 Agent MVP 实施规格

版本：V0.2，2026-10-14  
状态：目标实施规格，首版功能已实现，验证与差异见实施记录。预算及质量指标是初始约束和验收目标，不是已验证的模型质量。

> 2026-10-14 实施记录：首版代码已落地，当前实现与本规格的调整及验证边界见 [启用与使用说明](writing-agent-usage.md)。任务使用 UUID/JSON 持久化，SSE 发送已提交完整快照；作品默认配置暂存浏览器、实际任务输入存 MySQL；额外指定前文、全书检索、备份删除传播和真实质量验收尚未完成。本文仍作为目标规格，不能把所有设计项视为已验收。

本文件是首版范围和施工依据。[长期设计](writing-agent-development-plan.md) 保留后续能力，不得扩大首版。实施中发现不兼容的代码或依赖时记录决策变更，不静默改变验收规则。

## 1. 首版闭环与延期范围

首版闭环：**有效大纲 → 有限前文回顾 → 一个计划确认 → 一份草稿 → 检查 → 选区采纳 → 有条件撤销**。

保留简单作品配置：题材、视角、作者手写文风要求、对话符号、禁用表达。作者可选择平台内置的“克制叙述”“对话驱动”两类表达 Skill，也可不选；它们不控制权限或状态。计划与检查是业务步骤，不要求作者选择 Skill 才能执行。

允许对当前草稿顺序修改，每次成功结果替代当前候选，旧尝试只用于恢复和审计；不提供多方案并行、分支比较。首版一项任务最多一次成功采纳，可对草稿选定部分采纳；再次采纳创建新任务。

延期：MCP Client、外部 Skill 导入及编辑、样文自动分析、作者文件知识库、ES 私有向量索引、全书自动回顾、三方合并、选区自动迁移、复杂分支、批量确认、多人协同。仍保留这些长期目标。

首版允许空正文的插入任务，但必须先创建章节及有效大纲。补大纲走现有编辑功能，不让模型绕过确认。

## 2. 已验证的代码边界与先决修改

本次阅读发现：

- `author-web/src/components/AiPanel.vue` 的 `buildRequest` 丢弃 `novelId/nodeId/chapterId`，先修复透传并补请求测试。
- `ChapterSaveDTO` 只有标题和正文；`Chapter/ChapterContent` 没有正文版本字段。
- `ChapterServiceImpl.saveContent` 全量写入正文，待审核禁止修改，已发布修改会退回草稿并更新统计。
- 作者端已有自动保存；Agent 采纳必须协调它，不能另发一次无版本的全量保存。

先决修改：`chapter` 增加 `content_version BIGINT NOT NULL DEFAULT 0`；详情返回版本；普通编辑保存也必须传 `expectedVersion`。成功变更标题/正文增加版本，同内容保存不增加版本。不能只给 AI 接口加版本而让普通保存绕过保护。

审计状态变化、删除、提交审核与正文写入对同一章节统一持有行锁，锁内重新判定状态和归属。导入、创建及其他正文写入口必须列入写入清单，禁止旁路更新。阅读量等统计更新不增加正文版本。

迁移按开关和协调发布：加列并部署兼容读 → 发布新版详情/编辑器 → 启用写作和强制版本校验。启用后无版本请求返回 428，旧标签页提示刷新。不得在启用 AI 采纳时保留无版本覆盖通道。已有样例与 HTTP 测试同步调整。

## 3. 输入与资料范围

### 3.1 大纲与目标

创建请求提交 `chapterId、nodeId、expectedChapterVersion、outlineHash、goal、constraints、selection、profile、skillIds`。

- 从用户身份获取作者，由章节推导作品；不接受用户或模型指定作者。
- 节点必须绑定本章且归属同一作品。
- 大纲正文非空且不是仅标题；目标非空；计划结构必须有主要事件和结尾安排。作者确认计划时一并确认本次大纲快照。
- 首版不以语义评分强制拒绝短大纲，也不把“字数够了”当作有效；缺少关键内容时计划返回 `needsInput`，作者补充后重建任务。
- `goal` 最长 1200 字符，约束最多 10 条，每条 200 字符，选区原文最长 8000 UTF-16 单位。

当前章节在创建前暂停自动保存、等待在途保存完成、flush 未保存内容并获取服务端版本。失败时不创建任务。首版使用已落库正文作为基准快照，生成期间作者仍可编辑，但后续发生变化则拒绝采纳。

### 3.2 有限前文回顾

首版不依赖旧 `ai_summary` 作为可信最新回顾，因为旧记录没有完整来源版本。

- 默认取紧邻当前章之前的最多 3 个未删除章节，状态限作者可读的草稿、已驳回、已发布；待审核正文不用于本次回顾。
- 单章取末尾最多 2500 UTF-16 单位；记录真实覆盖章节、区间和截断标识，不宣称完整阅读该章。
- 额外引用章节由作者明确选择，最多 2 章，仍只允许当前章之前，每章最多 2000 单位。
- 合计来源正文上限 12000 单位；超限要求缩小范围，不静默丢弃作者指定资料。
- 准备阶段生成有来源标识的回顾，和原文快照一起保存。回顾不替代证据。

模型可在冻结快照中使用 `searchPreviousEvidence(query, limit)` 查找人物、事件和物品；首版是本次有限上下文检索，最多 5 个片段、每片段 500 单位，不称为全书 RAG。

UI 常驻“本次阅读范围”，对更早的事实返回资料不足。需要全书回顾时另行实现长期文档的摘要和索引管道。

### 3.3 来源清单与失效

`sourceManifest` 保存每份章节/大纲/配置的 ID、版本或哈希、范围、用途。模型只能引用服务端返回的 `sourceId`；服务端验证原文引用能定位于快照，失效引用剔除并标记错误。

确认、生成、采纳前重新检查清单归属、删除状态和版本。失败转入 `INPUT_STALE`，允许保留草稿或新建任务，不在原任务静默换资料。执行期间撤权后不再发送新的模型请求，停止展示私有来源；已发送到供应商的数据不能承诺追回。

## 4. 状态转换与执行模型

首版不用泛化工作流引擎、独立 step 表或 Redis 分布式锁。采用任务 + 分阶段尝试 + 持久化租约。数据库条件更新保证同一任务仅一个有效执行者。

### 4.1 任务状态

| 当前状态 | 触发 | 条件 | 目标状态 |
| --- | --- | --- | --- |
| 无 | 作者创建 | 权限、版本、输入通过 | `QUEUED_PLAN` |
| `QUEUED_PLAN` | Worker | 成功领取租约 | `PLANNING` |
| `PLANNING` | Worker | 回顾、计划校验完成 | `AWAITING_CONFIRM` |
| `AWAITING_CONFIRM` | 作者确认 | taskVersion/planHash/来源仍有效 | `QUEUED_DRAFT` |
| `QUEUED_DRAFT` | Worker | 成功领取租约 | `GENERATING` |
| `GENERATING` | Worker | 完整草稿保存 | `CHECKING` |
| `CHECKING` | Worker | 检查完成或明确降级 | `READY` |
| `READY` | 作者修改 | 未采纳，预算可用，要求非空 | `QUEUED_DRAFT` |
| `READY` | 作者采纳 | content 确认事务成功 | `ADOPTED` |
| `ADOPTED` | 作者撤销 | content 版本校验和事务成功 | `UNDONE` |
| 排队/执行/待确认/READY | 作者取消 | 非采纳事务处理中 | `CANCELLED` |
| 执行状态 | Worker | 明确失败或租约失效 | `FAILED` |
| 待确认/READY/排队/执行 | 系统 | 来源失效 | `INPUT_STALE` |
| `FAILED` | 作者重试 | 可重试、来源有效、预算可用 | 对应排队状态 |

终态取消或失效不自动重入；新建任务可复用作者输入。等待确认不计执行超时，也不自动确认；7 天未操作标记 `EXPIRED` 并停止提交。冲突采纳返回 409，任务仍 READY。

### 4.2 尝试与重试

尝试阶段：`RECAP、PLAN、DRAFT、CHECK`。尝试状态：`PENDING → RUNNING → SUCCEEDED/FAILED/CANCELLED/ABANDONED`。每次重试创建新 attempt，不重新打开旧 attempt。

- 成功回顾和计划仅在来源不变时复用。
- 生成失败重跑 DRAFT，新 attempt 的正文从空开始；旧部分正文只能查看，首版不能采纳。
- 检查失败复用完整 DRAFT，只重跑 CHECK；也可按规则保存 `checkStatus=UNAVAILABLE` 后进入 READY。
- 顺序修改读取当前完整草稿，成功后更新 `currentDraftAttemptId`，失败不替换旧草稿。
- 租约过期标记 ABANDONED，任务 FAILED；不自动再花钱重试，作者明确点击重试。

### 4.3 条件更新

示意 SQL，正式 mapper 需一并校验租约时间和拥有者：

```sql
UPDATE writing_task
SET status = :next, row_version = row_version + 1,
    lease_epoch = lease_epoch + 1, lease_owner = :worker,
    lease_until = :until
WHERE id = :id AND owner_user_id = :uid
  AND status = :expected_status AND row_version = :expected_version;
```

更新 0 行不得继续模型调用。每次提交检查 task/attempt、执行代次、租约、预算和取消状态；状态更新、结果、事件同事务提交。长任务心跳续租，不把所有数据写入同一个长事务。

## 5. SSE 事件协议

路径 `GET /api/ai/writing/tasks/{id}/events?afterSeq=N`，沿用 fetch + 授权请求头，token 不放 URL。

事件类型：`snapshot、state、draft_delta、plan_ready、check_ready、error、terminal`。数据至少包含 `taskId、attemptId、seq、taskVersion、type、payload`；ID 在 JSON 中作为字符串。

- `seq` 为任务内递增，事件唯一约束 `(task_id, seq)`。
- 普通事件先落库再发送；草稿片段和事件一并落库，每 500ms 或 512 UTF-16 单位提交一批。
- 快照带 `throughSeq` 及累计正文。读快照时用一致事务取得数据和序号，只应用更大的事件。
- 客户端忽略 `seq <= lastSeq`；缺口从 lastSeq 补拉。不同 attempt 的片段不能追加到同一正文。
- 增量带 `offset`，必须等于该 attempt 已有正文长度，否则重新取快照。
- 心跳每 15 秒，不分配业务 seq。断线只重连，不新建任务。
- 事件保留 7 天；游标早于最小保留序号或高于当前序号时发送 snapshot 重置，随后订阅新事件。
- POST 创建/确认/重试与 SSE 订阅分离，订阅没有触发模型执行的副作用。

## 6. 首版表结构约束

以下字段是建表依据；公共 ID 为应用生成的 BIGINT，前端字符串传输，时间使用 UTC。快照/草稿为 MEDIUMTEXT，结构化内容为 JSON。`row_version` 默认 0；所有 owner 不能为空。跨服务不建外键，AI 内部关联由事务和测试保证。

| 表 | 必须字段 | 索引与唯一约束 |
| --- | --- | --- |
| `writing_task` | id, owner_user_id, novel_id, chapter_id, status, row_version, input_json, chapter_snapshot, base_chapter_version, source_manifest, source_snapshots, recap_json, plan_json, plan_hash, plan_confirmed_at, skill_snapshot, current_draft_attempt_id, check_json, active_attempt_id, lease_owner, lease_epoch, lease_until, reserved_tokens, used_tokens, model_calls, error_code, created_at, updated_at, expires_at, last_event_seq | PK(id); INDEX(owner_user_id, updated_at, id); INDEX(status, lease_until, id) |
| `writing_attempt` | id, task_id, phase, attempt_no, status, lease_epoch, input_hash, output_text, output_json, last_offset, usage_json, error_code, started_at, finished_at | PK(id); UNIQUE(task_id, phase, attempt_no); INDEX(task_id, status) |
| `writing_event` | task_id, seq, attempt_id, event_type, payload_json, created_at | UNIQUE(task_id, seq); INDEX(created_at) |
| `writing_command` | id, owner_user_id, scope_id, operation, idempotency_key, request_hash, status, result_json, created_at | UNIQUE(owner_user_id, scope_id, operation, idempotency_key) |
| `chapter_ai_adoption`（content 所有） | id, owner_user_id, novel_id, chapter_id, task_id, draft_attempt_id, operation, idempotency_key, request_hash, before_version, after_version, before_content, after_content_hash, range_start, range_end, replacement_text, undo_of, created_at | UNIQUE(owner_user_id, chapter_id, operation, idempotency_key); UNIQUE(task_id, operation) |

不单建 claim/evidence 表：首版 `check_json` 存问题、草稿区间、来源 ID、原文区间和判断状态。计划、回顾在 task 保存，历史结果在 attempt 保留。以后需要搜索分析时再拆表。

关系：task.active/currentDraft 指向本任务 attempt；event.attempt 可空，非空必须属该任务；adoption 只接收授权 READY task 的完整草稿；UNDO 必须引用该任务成功 ADOPT 记录。

命令幂等：相同 key + 相同 requestHash 返回原结果；相同 key 不同内容返回 409。hash 对服务端规范化 JSON 计算。创建 scope 为 chapterId，其余为 taskId；网络重试使用同 key，作者主动重跑使用新 key。

命令结果未知时不能再次直接执行：查 task/attempt 或 content 幂等记录，对账后返回成功/失败/PENDING。AI 没有确认 content 结果前不得宣布采纳失败并诱导重新覆盖。

## 7. 拟议 HTTP 接口

| 方法与路径 | 输入关键字段 | 输出/约束 |
| --- | --- | --- |
| POST `/api/ai/writing/tasks` | 第 3 节输入，Idempotency-Key | taskId、状态；202 |
| GET `/api/ai/writing/tasks/{id}` | 无 | 快照、版本、来源、当前草稿、检查、throughSeq |
| GET `/api/ai/writing/tasks/{id}/events` | afterSeq | SSE，仅订阅 |
| POST `.../{id}/confirm` | expectedTaskVersion, planHash, editedPlan 可选 | 校验结构与来源，202 排队；确认编辑后的规范化计划并固定哈希 |
| POST `.../{id}/revise` | expectedTaskVersion, draftAttemptId, instruction | READY 且未采纳，202 |
| POST `.../{id}/retry` | expectedTaskVersion, failedAttemptId | 有预算且可重试，202 |
| POST `.../{id}/cancel` | expectedTaskVersion | 记录取消；重复取消幂等 |
| POST `.../{id}/adopt` | expectedTaskVersion, draftAttemptId, draftStart, draftEnd, expectedChapterVersion | 选择草稿片段替换固定目标选区；结果由 content 事务决定 |
| POST `.../{id}/undo` | adoptionId, expectedChapterVersion | 条件撤销，返回新版本 |

改写后的计划由作者一次点击确认，不额外要求再次确认同一版本。首版不提供计划自动重写；作者可手动调整或新建任务。

所有写命令要求 Idempotency-Key。错误 HTTP 状态：401 未登录；403 角色/权限拒绝；404 不存在或非本人资源（不暴露存在性）；409 版本/状态/幂等冲突；422 输入或大纲不足；428 缺版本；429 配额/预算/繁忙；503 依赖不可用。

内部 content 提供 owned-writing-snapshot、source-version-check、apply-patch、undo-patch、query-command-result。沿用内部服务认证并转发用户身份，内部调用不因来自 AI 服务而放弃归属校验。

## 8. 采纳与撤销算法：首版不合并

文本统一 LF 换行；Java 和浏览器以 UTF-16 code unit 计数。选区 `[start,end)` 不能切断代理对，不搜索相似文本，不使用浏览器像素或 DOM 序号。空选区表示插入。

创建任务验证 `substring(start,end) == selectedOriginal` 并保存基准快照、版本和 SHA-256。预览只展示候选替换，不自动移动范围。

采纳前前端暂停自动保存、等待在途保存、flush 并重新取版本；有新编辑就返回冲突，不尝试重定位。提交中冻结编辑器，完成后用 content 返回正文与版本替换本地状态，再恢复自动保存。失败保持作者当前内容和候选草稿。

content 在同一事务内锁定章节并执行：

1. 校验身份、状态、未删除及版本；PENDING 禁止，PUBLISHED 走现有退回草稿与统计/审计逻辑。
2. 已存在幂等记录则返回原结果，否则校验目标范围及原文。
3. `newText = base[0:start] + selectedDraft + base[end:]`；服务端从冻结完整草稿取得 selectedDraft，不能信任客户端任意正文。
4. CAS 增加 content_version，保存正文/字数及幂等记录，事务提交。

content 读取已授权草稿通过内部协议完成，先准备草稿证明再进入数据库事务，不能持行锁等待模型或外部 HTTP。版本/hash/任务归属须绑定该证明，避免任意正文冒充生成结果。

撤销首版保存采纳前完整正文；只有 `currentVersion == adoption.afterVersion` 且当前正文 hash 相符才能恢复正文并增加新版本。标题不会由 AI 修改；后续标题/正文编辑使撤销冲突。发布/待审核后禁止自动撤销；不恢复旧发布状态，按现有编辑审核规则处理。

任何版本变化即拒绝自动采纳/撤销。UI 提供“保留草稿、复制、重新创建任务”，明确首版不支持无关编辑后的智能合并。普通编辑保存冲突也保留本地内容，禁止无限自动覆盖重试。

## 9. ACL、工具和模型流程

统一 `WritingSourceAccess` 查询入口。模型只能输入 query/limit，不得输入 owner、novel、chapter 上界或任意 URL。通过服务端 `WritingScope` 固定 userId/novelId/currentChapterId/manifest。

数据库查询强制归属、未删除、章号范围及状态；返回值核对 manifest 版本。首版无私有 ES 和外部连接，避免把读者公开工具误用于作者草稿。缓存首版不缓存私有正文；若后续增加必须重新验证身份与来源版本。

规划使用 AI Services + 工具；正文输出使用经过验证的 LangChain4j 流式模型接口，以冻结计划和证据生成，不在正文流中动态执行工具；检查用结构化输出与 Java 引用/格式校验。框架不承担任务状态恢复。

首版只注册只读工具：`getChapterOutline、getWritingProfile、getStoryRecap、getWritingContext、searchPreviousEvidence`。保存由编排器统一处理，不把 DB 写操作交给模型，避免模型重复调用。

必须给出依据的检查：已发生事件、人物知情/能力、物品归属、时间地点、已有伏笔、与大纲目标的冲突。明确区分支持/冲突/资料不足；新对话、动作等创作不逐句引用。首版不读取未来章节或未来大纲。

## 10. 默认预算与故障行为

开工即启用以下保守默认值，测量后通过配置版本调整，不能默默放宽：

| 参数 | 默认 |
| --- | --- |
| 用户并发执行 / 单任务执行 | 1 / 1 |
| 全局执行 Worker / 等待队列 | 2 / 20；满则 429，无调用 |
| 活跃任务排队上限 | 60 秒，超时失败可重试 |
| 单次输入 | 最多 8000 Token；本地估计需留安全余量，不能称为供应商精确计数 |
| 回顾/计划/检查输出 | 每次最多 2048 Token |
| 草稿输出 | 每次最多 4096 Token，首版建议 1000～2000 汉字 |
| 单任务模型调用 | 最多 6 次，含回顾、工具循环、检查、修复、手动重试 |
| 工具调用 | 最多 6 次/任务，单工具 5 秒 |
| Token 软/硬预算 | 40000 / 60000，含全部尝试 |
| 单模型请求 / 执行尝试 | 90 秒 / 180 秒，等待作者确认不计入 |
| SDK 自动重试 | 0，禁止隐藏重试费用 |
| 心跳 / 租约 | 5 秒 / 20 秒，失效后拒绝旧提交 |
| 活跃任务数 | 每作者最多 5 个未终止任务 |

每个模型请求前按输入保守估算加最大输出预留预算，用量已知后结算；未知用量保留预留额，不填零。并发预算更新用数据库 CAS。请求启动前空间不足即停止，不靠输出完成后的检查防止超支。

用户每日配额沿用现有配置；系统管理员即使不限每日次数，也受任务预算、并发和超时约束。价格缺失时只报告 Token 和估算状态，不编造费用。

Redis/模型配置依赖不可用时 503，用户可继续手写、查看已保存草稿，但不能启动计费执行。MySQL 不可用时禁止写操作。检查模型失败可标“未完成检查”进入 READY，采纳需显式确认；确定性的格式错误提示作者修改，不擅自修改正文。部分草稿不能采纳。

## 11. 验收与初始指标

### 11.1 自动断言

| 用例 | 可执行断言 |
| --- | --- |
| 跨作者/跨作品 | 404/403；假模型收到的请求不含合成秘密；实际调用数为 0 |
| 大纲缺失/只有标题 | 422 或 needsInput；DRAFT 调用数为 0 |
| 冻结来源变化/删除 | INPUT_STALE；不会启动下一次模型调用 |
| 普通保存与采纳并发 | 只有符合 expectedVersion 的写入成功，另一个 409；不存在覆盖 |
| 重复采纳/撤销 | 相同 key 结果相同，版本只增加一次；改 payload 返回 409 |
| 重复文本、emoji、空选区 | 精确坐标替换正确；代理对中间拒绝；不模糊定位 |
| 断线、重复事件、旧游标 | 内容等于持久快照，无重复字符；订阅不新增 attempt |
| 失败重试 | attemptId 更新，新正文 offset 从 0；旧正文不拼接 |
| Worker 过期晚到 | 状态/输出提交更新 0 行，无额外正式版本 |
| 取消 | 终态不被晚到回调改写 |
| 检查引用 | sourceId 存在、quote 与快照区间一致；不合法引用标无效 |
| 预算与队列满 | 超限请求不抵达假模型，预留计数正确 |

测试使用合成正文和捕获请求的假供应商，不在生产日志记录敏感原文。生产记录来源 ID/hash/版本和 attempt 轨迹，只能证明系统路径，不能用审计摘要替代人工判断生成质量。

### 11.2 固定质量集与阈值

建立 12 个合成创作场景：修仙/悬疑/轻小说各 4 个；每类型含正常创作、人物能力冲突、时间/知情冲突、资料不足。每个包含确定大纲、有限前文、目标、格式和人工标注答案。

首次质量报告每场景运行 2 次，固定模型/Skill/提示词版本，记录失败样本，不只统计成功输出。真实调用总预算事先限定；无实际付费执行授权时先用协议测试，质量验收标为未执行。

初始验收目标：引用定位准确率 100%；检查结论支持率 ≥90%；标注冲突召回 ≥80%、误报率 ≤10%；生成可核验事实错误率 ≤5%；三项人工评分（大纲遵守、连贯性、规则符合）均值 ≥3.5/5。无有效样本不能填写 100%。这些目标若未达标应修复或记录阻塞，不事后改口径。

人工量表：1=严重违背/不可用，2=多处错误需大改，3=基本可用需局部调整，4=满足要求仅少量修改，5=充分满足且无明显问题。至少 6 场景双人独立评分，分差 ≥2 分复核；报告标注评分者与一致情况，无第二评审者则明确限制。

性能为开发环境初始目标：非模型 API P95 ≤1 秒；取消状态持久化 P95 ≤1 秒；10 个故障注入用例恢复/拒绝旧提交全部通过；真实首个草稿文本 P95 ≤30 秒、完整生成及检查 P95 ≤180 秒（不含排队和作者确认）。小样本 P95 只作基准，不能宣传生产 SLO；记录环境与计算方式。

任务端到端 Token 必须不超过硬预算，不把未知费用当零。报告导出 JSON：用例版本、来源 hash、模型配置摘要、attempt/events、断言、评分、Token、耗时及错误。采纳率只作为以后真实使用反馈，不能从测试集伪造。

## 12. 纵向切片开发顺序

| 阶段 | 实施内容 | 退出条件 |
| --- | --- | --- |
| A 基础保护与骨架 | 修 ID，普通保存版本保护；任务/幂等最小表；假模型驱动大纲→计划→草稿→选区采纳→撤销 | 完整闭环接口测试和并发覆盖测试通过，先验证最高风险路径 |
| B 真实生成与前端闭环 | 固定上下文、内置 Skill、计划确认；接 LangChain4j；SSE 草稿和冲突 UI | SDK 本地 HTTP 测试；真实质量小样本；首次完整作者操作验证 |
| C 有限前文与检查 | 回顾、来源快照、只读工具、检查和引用校验 | 12 场景集固化，质量测试和资料范围断言通过 |
| D 持久恢复与打磨 | 租约、断线、重试、取消、源失效、对账及删除；完整报告 | 所有功能回归、故障注入、预算测试和最终质量报告达标 |

最早切片的表不省略后续所需版本/attempt/event 标识，完整恢复机制在 D 完成；A～C 功能开关仅开发使用，不能提前宣称可生产恢复。

开始 A 前必须提交：DDL、DTO/错误码、状态表测试、文本算法测试、预算配置、假模型 HTTP fixture 和验收集目录。这是有限的规格准备，不要求先实现所有基础模块。

每阶段跑已有回归；付费验证单独记录预算。阶段 B 用最小真实样本暴露创作效果问题，不能等阶段 D 才发现草稿不可用。

## 13. 目录、保留与回滚

```text
backend/novel-ai/.../agent/writing/{api,application,domain,tools,context}
backend/novel-content/.../content/       版本、原子采纳和撤销
backend/novel-common/.../internal/       内部 DTO 与协议
author-web/src/components/writing/      计划、草稿、检查、冲突
docs/sql/writing-agent-mvp.sql          增量脚本，实施时创建
docs/evals/writing-agent/               用例、标注、执行报告
```

持久任务与草稿默认保留 30 天；7 天未操作停止提交并标过期，但仍可在保留期内查看/复制；事件 7 天；审计与采纳前快照 90 天。首版撤销只承诺保留期内且无后续编辑的最近一次采纳；UI 展示期限。

删除作品/任务时先标不可访问，后台清理草稿和来源快照；技术审计去除正文仅保留操作标识。删除传播有状态和重试，内容删除优先于默认期限。备份恢复后按删除账本重放清理，再开放服务；账本覆盖备份可恢复窗口。

回滚关闭 `novel.ai.writing.enabled`，终止新任务提交，已有正文不回滚；原写作辅助可用但必须沿用章节版本保护。数据库新列和历史保留，不能为了回滚删掉作者数据。

## 14. 待实现证明与变更边界

本文已经冻结首版范围、采纳算法、默认预算、状态和接口方向；还需把描述转成实际迁移 SQL、DTO、mapper 和测试。SDK 能力必须用当前项目版本验证，不为迁移再引入新的框架。

遇到无法达到的指标或协议能力时，在本文记录环境、失败证据和替代方案，不默认扩大范围。MCP、外部 Skill、全书 RAG 和三方合并分别进入后续设计评审，不能成为首版延期理由。
