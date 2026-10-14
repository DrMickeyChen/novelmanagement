# 知识库目录间歇性 500

日志确认 `GET /api/admin/ai/book-finder/knowledge` 在 AI 服务调用搜索服务时抛出 `feign.RetryableException: Read timed out`，开发环境 Feign 默认读取超时为 5 秒。

目录查询从 `novel_book_knowledge_v1` 查询最多 1000 本作品，虽然过滤了 `chunks`，ES 仍需要读取并解析包含正文与向量的 `_source`。构建期间重复查询会放大负载。

本次修复：搜索服务目录结果缓存 10 秒，同一实例并发请求合并；写入、删除后立即失效；查询失败不缓存，不返回虚假的空列表。AI 服务 `agentBookSearch` 客户端读取超时调整为 12 秒，覆盖搜索服务 10 秒 ES socket 超时。其他客户端保持原配置。

真实 ES 隔离测试验证目录读取、缓存复用、写入与删除后的立即更新。测试不调用模型 API。该优化不保证数据库不可用或持续高负载时也能成功；后续可将目录元数据独立存储，彻底避免读取正文及向量大文档。多实例之间最多存在 10 秒目录延迟，推荐工具仍独立校验作品可见性和正文版本。

生效需要重启 `novel-search` 和 `novel-ai`。无需 SQL 迁移或重建向量。

## 进一步修复 ES 10 秒超时

23:39 的日志确认搜索服务本身查询 ES 超过 10 秒，短缓存和 Feign 余量无法消除冷查询的大文档开销。

新增 `novel_book_knowledge_catalog_v1` 保存不含正文、向量的 `CatalogEntry(sourceVersion, document)`。目录先从原索引查询 ID 和版本（`_source=false`），再用 real-time mget 读取轻量目录。未补齐或版本落后的条目按最多 20 本修复；已删除作品不出现在原索引的 ID 集合中，因此不会因目录残留而出现。

构建时提交原知识文档后写轻量目录并失效缓存，删除时清理目录；并发投影写入或部分写入失败产生的旧版本会在下一次查询核对时修复。仍保留 10 秒目录缓存与最多 1000 本的现有上限。推荐工具继续独立核对作品可见性和正文版本。

`docs/tools/backfill-knowledge-catalog.py` 已对现有 ES 资料执行服务器端目录补齐，无模型调用，无原知识文档修改。可再次运行以修复旧版本部署期间的新构建条目。

`docs/tools/verify-knowledge-catalog.py` 核对真实目录版本与两次 ES 请求的合计耗时，结果保存到 `docs/evals/book-finder/knowledge-catalog-verification.json`。真实 Java 隔离测试覆盖目录冷读取、缓存复用、写入和删除失效、过期投影修复。该证据不等同于已验证重启后的浏览器页面。

已有目录补齐后，必须重启 `novel-search` 以加载新代码；若前一轮已重启 AI 服务，本轮无需再次重启 AI 服务。
