# 写作 Agent 空大纲页面加载修复

日期：2026-10-14。

## 原因与证据

实际运行日志 `backend/logs/novel-ai.log` 在 15:23～15:25 多次显示：

```text
GET /api/ai/writing/tasks
WritingContentClient.snapshot → HTTP 422：请先填写本章大纲
FeignException.UnprocessableEntity → 全局处理为 500
```

网络和服务连接正常。历史列表错误复用了创作快照接口，空大纲被当作页面加载失败；跨服务 HTTP 业务拒绝又没有被转换成 BusinessException。

## 修改

- 内容服务增加仅检查作品、章节、节点归属的 `access` 接口，允许大纲内容为空。
- 历史列表及已有任务读取采用 access；创建、确认和执行仍校验有效大纲与来源版本。
- WritingContentClient 使用独立 ErrorDecoder，保留 4xx 状态和安全业务提示，不向前端透出 Feign URL/原始异常；5xx 使用统一依赖故障提示。
- 后台任务身份补齐内部请求协议要求的 Jti，使用服务端任务关联标识，不创建用户 JWT，也不绕过内部令牌和资源 ACL。
- 前端空大纲显示操作说明。

## 回归

数据库用例覆盖空大纲历史列表正常、创建拒绝 422、清空大纲后已有任务仍可查看，以及后台身份能够通过实际 IdentityHeaders 编解码。

内容服务用例覆盖空大纲 access 成功、错误章节绑定仍拒绝。错误解码用例覆盖 422 保留和内部 5xx 信息不外泄。

需重启 novel-content 和 novel-ai，刷新作者端。无新 SQL、无模型计费测试。
