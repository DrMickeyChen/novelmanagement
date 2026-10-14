# 找书助手增强版

本次按偏好判断 → ES 近邻检索 → 混合检索与重排 → MCP → 评测落地。

## 启用

重启 `novel-search` 与 `novel-ai`，刷新管理端和阅读端；静态部署需更新两端构建产物。本次无需新增 MySQL 表，也不修改现有模型密钥。

现有 221 本知识资料、8726 个向量片段已复制到原生索引，复用已有向量，模型调用为零。迁移记录见 `docs/evals/book-finder/native-index-migration.json`。重启前旧服务继续构建的作品，以及以后从旧数据恢复的资料，可在「向量模型与知识库 → 正文知识库」勾选已构建作品，点击「迁移 ES 检索索引」。每批最多50本，任务异步执行，不调用模型；过期资料需重新构建。新服务构建资料时会同时写入原生索引。

## 偏好与证据

硬条件继续使用分类、状态、字数等真实字段筛选。`searchBooks.preferences` 将软偏好拆成最多六条 `{criterion,required}`；明确必须满足的要求才标记 required。续问保留有效偏好，新需求重置。

模型最终逐项返回 `assessments`。服务端只接受当前作品、本轮检索且最终复核仍有效的证据 ID。没有引证、伪造引证或资料过期时降为 `UNKNOWN`。`SUPPORTED`、`CONTRADICTED` 是模型对片段的判断，阅读卡片显示「有支持线索 / 有反面线索 / 资料不足」，不声称全书符合。开篇不能证明「全书没有、永不、从不」等普遍否定，因此支持判断也会降为未知。必须满足的偏好未知或被反驳时不推荐该书。

这是可审核的模型判断，并非程序已经证明语义真值；人工评测检查其是否读懂证据。

## 检索与重排

- 原索引 `novel_book_knowledge_v1` 保留完整资料，供管理预览和向量复用。
- 新索引 `novel_book_chunks_v2_<维度>` 一片段一文档；正文使用 ES 标准分析器/BM25，向量使用 `dense_vector(index=true, similarity=cosine)` 与 HNSW，m=16、ef_construction=100。没有向量的片段位于维度0索引。
- 两路召回各取最多60条；k=60、num_candidates=200。按书籍 ID、提交的内容指纹及模型身份过滤；查询向量只能检索相同模型/端点标识、相同维度的向量。关键词检索可跨维度索引。
- Java 仅融合有限召回结果，RRF 的常数为60，不再读取所有候选片段向量计算余弦。融合后保留作品多样性，最多20段调用可选 Rerank，最后最多6条证据、每书最多2条。
- 内容数据库仍复核上架状态及内容版本；ES 相似度与 Rerank 分数均不代表偏好成立。接口失败保留可用检索结果并返回降级说明。
- 原生索引先写片段，成功后才提交原知识文档；查询只使用已提交指纹，半成品和旧版本不会成为证据。未提交/旧代片段可能暂留索引，删除知识资料会清除该书所有片段。
- 当前候选目录仍上限1000本，正文仍是管理员选择的前1至50章；未实现全库无限规模分页、全书语义理解或中文专用分析器。

实现与参数核对依据：[Elasticsearch 8.13 kNN 文档](https://www.elastic.co/guide/en/elasticsearch/reference/8.13/knn-search.html)。真实 ES 测试覆盖原生近邻检索、元信息投影、过期指纹过滤与删除。

## MCP 接入

管理端「运行与用量 → MCP 工具接入」可查看说明。

- 网关地址：`http://127.0.0.1:8080/api/ai/book-finder/mcp`。
- 采用 MCP 2025-03-26 的无状态 Streamable HTTP；POST 返回 JSON，通知返回202；GET 返回405，不提供服务器主动 SSE。
- 支持 initialize、notifications/initialized、ping、tools/list、tools/call，以及该版本的有界批请求。仅提供 searchBooks、getBookDetails、searchBookEvidence，不提供写入或删除工具。
- 使用平台 JWT：`Authorization: Bearer <access token>`。需经网关访问；普通读者每次工具调用消耗一次额度，管理员不限日次数，并发上限4。过期登录凭证需重新登录更新。
- 浏览器 Origin 校验允许列表通过 `novel.book-finder.mcp.allowed-origins` 配置；默认允许 localhost/127.0.0.1 的3000和8080端口，其他客户端来源需显式配置。
- 输入和工具结果仍是不可信数据；正文工具会向已认证客户端返回有限引文，客户端应遵守无剧透策略。服务端重新校验作品可见性。
- 内部 Agent 仍使用原生 Tool Calling，与 MCP 适配层复用 `BookFinderTools`；不人为增加 Agent → MCP → 自身的网络绕行。没有实现 OAuth 自动授权发现、MCP Resources/Prompts 或服务器主动推送。

仅支持 stdio 的客户端可启动 `python docs/tools/book-finder-mcp-stdio.py`，由客户端进程环境设置 `NOVEL_MCP_URL` 与 `NOVEL_ACCESS_TOKEN`。不要将真实 token 写进仓库。桥接器只向 stdout 输出 JSON-RPC，不打印凭证。

协议依据：[MCP 传输](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)、[MCP 工具](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)。

## 评测与留证

管理端「运行与用量 → 评测与报告」填写题库 JSON，支持每批1至30题；同 group 续聊，其他题目开新会话。`expected` 是独立预期硬条件，不能仅以助手自己输出的筛选条件判自己正确；`allowEmpty` 决定空推荐是否通过，`clarify` 可指定预期追问。

执行真实模式会调用已配置 API，界面执行前说明费用。Mock 仅验证流程，不能证明真实推荐准确率。可以停止后续题目，当前题目完成后停止；服务重启后未结束的任务显示 INTERRUPTED，不隐蔽重跑付费请求。

报告包括测试题、真实回答、书籍 ID、当前硬条件、偏好判断、证据快照及指纹、工具轨迹、模型、提示词 SHA256、Agent 字节码 SHA256、知识库版本、耗时、已返回输入/输出 Token、错误结果和人工审核历史。可通过 `novel.build-version` 补充 Git 提交号；默认明确标记 unrecorded。运行日志保存在 Redis 7天；评测报告另以原子替换的 JSON 文件持久保存，不随 Redis TTL 消失。

报告默认存于 **novel-ai 进程工作目录下的 `logs/book-finder-evals/`**；用 `novel.book-finder.eval.directory` 设置稳定目录，部署时挂载持久卷并备份。列表显示最近100份，文件不自动过期。可从管理端导出完整 JSON。文件仅由管理员接口读取，路径限定为服务生成的 UUID。

自动统计：硬条件符合率、逐题通过率、重复推荐、平均耗时、P95、报告 Token。空推荐不会被算成100%推荐准确率。人工审核：证据支持 / 不支持 / 资料不足，须写理由，重复审核保留历史；这是逐题人工支持率，并非无条件等价于整体推荐准确率。

可填写输入/输出每百万 Token 单价、币种及价格来源，估算聊天费用。不含 Embedding、Rerank、缓存优惠与未返回用量的调用，不能充当供应商账单。多次评测的 JSON 可比较版本，但应使用相同题库与书库版本；本次尚未完成真实模型的人工语义评测，不宣称提升百分比。

## 验证文件

- `docs/evals/book-finder/native-index-migration.json`：现有向量复制记录，未调用模型。
- `docs/evals/book-finder/native-knn-verification.json`：使用现有作品的1024维向量验证真实 ES HNSW，只读、无模型调用；单次耗时不代表性能基准。
- `docs/evals/book-finder/mock-verification-report.json`：真实 ES/Redis 与 Mock Agent 的隔离流程评测记录，不是语义质量成绩。
- `docs/evals/book-finder/implementation-verification.json`：54项后端测试、20项前端测试和两端构建结果；未进行浏览器视觉检查或真实模型语义评分。
- `docs/tools/smoke-book-finder-advanced.py`：临时进程、隔离书籍、真实 ES/Redis、MCP HTTP 与评测导出验证；不改模型密钥，不向 MySQL 导入测试小说，不停止原服务。
- 现有 `advanced-cases.json` 含产品验收和故障场景，部分需要专门构造环境；不能直接把 setup 场景当成用户问题发送。接口超时、坏 JSON、非法引证等由自动化测试模拟，不通过破坏真实服务进行验证。
