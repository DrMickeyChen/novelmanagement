# AI 微服务拆分

现有 AI 能力迁入 `novel-ai`，端口 **8085**，启动类 `com.novel.AiApplication`。本次只拆分服务，尚未新增找书 Agent。

## 启动

**不需要执行 SQL。** 沿用现有 schema 和表数据，`ai_config`、`ai_prompt_template`、`ai_usage_log`、`ai_summary`、`ai_audit_result`、`ai_sensitive_word` 改由 AI 服务独占；小说、章节、大纲和人工审核流水继续由内容服务独占。

1. 停止旧内容服务，避免旧、新 AI 消费者同时运行。
2. 项目根目录执行 `mvn -f backend/pom.xml clean package`。必须 clean，避免已移动的类残留在内容服务的旧编译产物中。
3. IDEA 重新加载 Maven 项目，新增启动 `AiApplication`，再启动新 `ContentApplication` 并重启 `GatewayApplication`。

也可从项目根目录执行 `npm run dev:ai`，或在 `backend` 目录执行：

```powershell
java -jar novel-ai/target/novel-ai.jar
```

文档入口 `http://localhost:8085/doc.html`，前端入口仍是 `http://localhost:3000`。升级需要重启内容、网关并新增 AI；其余服务继续运行。

AI 的 dev 配置沿用现有虚拟机地址。生产环境需配置 MySQL、Redis、Nacos、RocketMQ、`INTERNAL_TOKEN`、`JWT_SECRET`。**沿用原 `AI_KEY_SECRET`**，否则已有 API Key 无法解密。原 `novel.ai.*` 启动参数改为配置在 AI 服务上。8085 仅对内网开放。

## 边界

- `/api/ai`、`/api/ai/**`、`/api/admin/ai`、`/api/admin/ai/**` 路由至 AI，order 2 先于内容管理通配路由 5；前端 URL、SSE 完成帧和错误协议不变。
- AI 经 Feign 访问 `/internal/ai-content/**`，使用独立 DTO，不依赖内容服务的 JAR、实体或 Mapper。作者读稿继续由内容服务的 `NovelAccessGuard` 检查归属，Feign 透传内部令牌和当前身份。
- MQ 审核及管理端事实查询仅允许有效内部令牌访问；取数失败抛错，不能装成“已删除”或“无风险”。昵称保留原可见降级。
- AI 审核消费者迁入 AI，仍沿用 `group-content-ai-audit`，保留消费位点，避免换组重放历史计费任务。章节、评论审核消息仍由各自业务服务生产。
- 内容服务不新增同步调用 AI，业务发布状态机仍属于内容服务。

## 人工处置最终一致

原来的人工流水与 AI 待办同库事务，改为 **最终一致**：内容保存人工审核流水，AI 定期读已提交流水、同步自己待办。AI 不可用不会回滚正常审核。

每 5 秒扫描最多 100 条待办，按 ID 轮转；大量待办需多个周期。失败保留游标和待办，下轮重试；重启后从头扫描。队列查询会触发一批同步，点击排除前会核对该目标人工处置。

章节按提交流水划分范围，能找到模型仍在运行时发生的人工审核；评论正文不可修改，按对应评论的处置读取。更新带行 ID、待办状态和判定时间条件，防止迟到结果覆盖已处置记录或不同判定时间的新结果。现有 DATETIME 秒级精度和跨主机时钟限制仍存在，本次未增加内容版本字段。

## 验证和限制

内部批量上限 200 个 ID；单次摘要最多 200 章、正文累计 8000 字，超限拒绝而非截断。包含标题的最终文本仍由原摘要服务检查 8000 字上限。

新增测试覆盖所有权失败不读正文、批量上限、超长摘要拒绝、孤立正文不泄漏、业务错误跨服务保留、故障不伪装成不存在、处置同步失败重试，以及 HTTP 权限、SSE 完成帧和工作线程身份。

完整 `clean package` 成功，28 项测试通过；检查 JAR 确认 content 不含 AI 类，AI 不含内容业务类，12 个锁定前端文件保持原样。

隔离启动验证：文档 200、匿名 AI 请求 401、缺内部令牌的内部请求 403。真实 MySQL/Redis 下，AI 配置、词库状态、模板和配额接口均返回业务成功；零计费 mock 模型的真实 HTTP SSE 输出以 `[DONE]` 结束，专用测试身份的配额键已清理，未修改种子配置或调用计费模型。

隔离进程不注册 Nacos，也不消费 MQ，验证后已关闭。实际网关路由、跨服务稿件取数与 MQ 消费交接还需在按上述步骤切换三个服务后联调；不能将隔离验证称为全链路生产验收。
