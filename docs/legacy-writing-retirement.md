# 旧写作助手退役

2026-10-14，按作者要求移除早期直接调用模型生成的四按钮助手。

## 已移除

- 作者端 `AiPanel.vue`、专用额度 API 和旧 SSE 客户端。
- `AiController` 的大纲、续写、灵感、润色四个 POST 接口，以及旧额度 GET 接口。
- 旧 SSE 转发、专用线程池与额度展示 VO。
- Mock 中的大纲、续写、灵感和润色生成实现。
- 管理端旧写作模板配置入口；服务端拒绝旧场景的模板读取、预览和增改。

## 当前入口与保留范围

作者写作使用 `WritingController` 的 `/api/ai/writing/**` 任务接口。找书助手、审核、模型配置、加密 Key、用量统计和配额服务保持可用。

`/api/ai/summary` 独立迁入 `AiSummaryController`，保留已有章节摘要能力。`AiStreamSource` 及其真实、模拟实现只能处理 `SUMMARY`，其他场景在访问模型之前即拒绝。

数据库历史模板、摘要、用量不删除。场景编号 1 至 4 仍可用于历史账单解码，其中编号 2 同时用于当前写作 Agent 用量；它们不再对应旧生成端点。管理端模板页只展示审核和摘要；历史用量筛选仍可查询退役场景。

## 生效方式与验证

重新构建并重启 `novel-ai`，刷新作者端和管理端。无需 SQL 迁移。构建必须清理旧编译产物，避免被删除的 Controller 类残留在 JAR 中。

`AiHttpBoundaryTest` 验证作者和管理员访问旧四个生成 URL 及旧额度 URL 均返回 404，不调用摘要服务；并验证摘要入口、身份及角色边界。`LegacyWritingRetirementTest` 验证旧场景在底层生成器和模板服务被拒绝，不访问模型或数据库。

旧设计文档及历史验收材料仅作历史记录，不能作为重新接入旧助手的依据。

2026-10-14 验证结果：作者端、管理端生产构建通过；后端 `clean package` 通过，共 181 项测试，171 项通过，10 项需要显式启用数据库或 Redis 的测试跳过。使用 `-Dnet.bytebuddy.experimental=true -DreuseForks=false`，按测试类隔离 JVM，避免 JDK 24 下不同 Mockito MockMaker 互相干扰。本轮未调用付费模型。

最终 JAR 检查确认旧 Controller、专用线程池配置和额度 VO 均不存在，独立摘要 Controller 存在。HTTP 边界 7 项、退役防误用 2 项测试全部通过。
