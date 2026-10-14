# 找书助手 LangChain4j 迁移

2026-10-14：找书助手默认执行器迁移为 LangChain4j 1.22.0 AI Services。保留原执行器作为可显式切换的对照；发生失败不会自动切换执行器重跑，以免重复调用和费用。

## 调用链

`BookFinderController → BookFinderAgent → BookFinderAiServices → BookFinderAssistant（AI Services代理）→ ChatModel → DeepSeek`

模型提出工具调用后，由 AI Services 调度注册的 ToolExecutor；执行器进入 BookFinderAgent 的请求级 ToolSession，校验参数与候选范围，再调用现有搜索、详情、正文证据业务工具。AI Services 将工具结果回传模型并组织下一轮交互。最终结果仍经过实时可见性、条件、逐字引文与正文版本校验，才转换成前端推荐卡片。

采用官方支持的 `ToolSpecification + ToolExecutor` 注册方式，复用 `prompts/tools.json`。这是为了保留“省略字段保留旧条件、显式null清除条件”的补丁语义以及字符串作品ID，不把搜索方法直接暴露为可随意查询的工具。当前不使用 `@Tool` 自动生成工具参数；这是 AI Services 的程序化工具注册方式，并非再次手写模型与工具循环。

模型由公共 `modules/agent/infrastructure/model/LangChain4jModelFactory` 创建，后续写作助手可复用。使用普通Java依赖、手动组装 Spring Bean，不引入 Spring Boot starter，也不升级现有 Spring Boot 3.2.12。

## 会话与权限

AI Services 使用 MessageWindowChatMemory，窗口48条消息，ID为用户ID与会话UUID的组合。ConversationChatMemoryStore 从现有Redis会话的最近6轮已验证历史重建消息；本轮工具消息只暂存在请求内。执行成功后由 BookFinderStore.complete 保存用户问题和已验证回答，失败不写入工具消息或未验证模型回答。

这属于“Redis持久化业务会话 + 框架请求内消息记忆”的适配，不会在Redis新增一份无限增长的原始工具聊天记录。卡片位置、筛选条件、偏好和已推荐ID仍属于独立业务状态，不能用ChatMemory替代。既有会话无需数据库迁移；新话题仍重置旧条件，无效序号在模型调用前拦截。

读者权限、管理员配额豁免、用户级锁、请求幂等、正文覆盖范围与证据判断继续由业务层控制。MCP继续复用同一组业务工具，不经过内部Agent绕行。

## 运行控制与密钥

- 每次模型调用按当前剩余任务时间构建客户端，单次HTTP上限30秒，任务上限60秒；JDK HTTP客户端支持线程中断。
- 正常模型调用最多5次；第5次移除工具，要求JSON最终回答。格式修正最多1次，总调用最多6次。
- AI Services工具轮次限制配合模型调用保护；业务工具总次数最多8次，自动补查详情也计入预算。工具保持串行执行。
- 模型返回的累计Token仍受现有预算控制，默认60000；每个HTTP调用单独记账。供应商没有返回的用量无法凭空推算。
- SDK `maxRetries=0`，不会偷偷重试供应商错误。失败不会自动转旧实现再次调用。
- 管理端加密Key继续由AiConfigService读取，在创建客户端时解密；不放入配置文件、日志或前端，不缓存明文客户端。模型配置按本次请求快照使用，之后的新请求读取最新配置。
- DeepSeek请求继续禁用thinking，保留当前2048输出上限；SSE仍是进度与最终结果流，不是逐字转发未经核验的模型输出。

## 生效与回退

重启 `novel-ai` 后默认使用新执行器；不需要SQL脚本、知识库重建或重新输入API Key。刷新管理端后，运行记录与评测报告会显示执行框架；旧报告标记为“未记录”，不会被冒充新框架结果。

默认配置：`novel.ai.book-finder.engine: ${BOOK_FINDER_ENGINE:langchain4j}`。

若需要旧实现对照，在IDEA程序参数设置 `--novel.ai.book-finder.engine=native`，或设置环境变量 `BOOK_FINDER_ENGINE=native`，重启AI服务。恢复时改为 `langchain4j`。`novel.ai.provider=mock/deepseek` 与执行器选择是两件事：Mock也会通过真实AI Services循环运行，但不会请求模型厂商。

## 验证与评测

BookFinderFrameworkRegressionTest继承整套43项业务回归测试，让它们通过实际AI Services循环再次执行，覆盖条件继承、去重、序号、权限配额、必要偏好、证据和格式修正。

BookFinderLangChainHttpTest使用真实OpenAiChatModel与AI Services请求本地HTTP服务，验证完整三轮、nullable工具参数、卡片顺序、密钥更新、用量、无隐式重试、剩余时间超时和停止中断；不调用付费API。

管理端继续用相同9条题库进行真实DeepSeek复测。JSON报告新增engine、engineVersion、frameworkCodeSha256，便于与迁移前报告区分。自动化通过不代表真实推荐相似度提高；人工证据审核仍需完成。

框架依据：[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)、[工具注册](https://docs.langchain4j.dev/tutorials/tools/)、[Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)。

本次验证记录：`docs/evals/book-finder/langchain4j-migration-verification.json`。后端120项通过，1项真实Redis测试跳过；管理端19项通过，前后端构建通过。记录包含SDK依赖、打包配置、源码/产物哈希和测试名，本次没有执行真实DeepSeek质量评测或浏览器视觉检查。
