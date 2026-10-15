# 写作 Agent 代码结构与 Skill 文件

## 职责划分

代码位于 `backend/novel-ai/src/main/java/com/novel/modules/agent/writing/`：

| 目录 | 主要职责 |
| --- | --- |
| `api` | 写作任务、SSE、资料预览与索引任务接口；路径不变 |
| `application` | `WritingService` 编排规划、确认、生成、修订、采纳与撤销；`WritingBudgetFactory` 管理执行预算、用量记录和检索证据入任务 |
| `domain` | 计划与操作校验、运行状态集合、预算参数、JSON 合约及证据定位检查 |
| `context` | 冻结和复核资料版本、组装提示上下文、私有 RAG 与待确认记忆提案 |
| `tool` | `WritingTools` 按规划、检查阶段装配工具；检查阶段才提供候选记忆写入 |
| `skill` | 文件发现、元信息校验、选项列表及所选规则的版本快照 |
| `infrastructure/model` | LangChain4j AI Services、流式模型与取消处理 |
| `infrastructure/client` | 内容、搜索服务 Feign 接口及客户端错误转换 |
| `infrastructure/persistence` | MySQL 任务、事件、尝试、幂等及租约存储 |
| `infrastructure/job` | 任务线程池、调度、心跳、恢复、保留清理和私有索引队列 |

权限和小说资料归属仍由内容服务校验，ES 查询仍由搜索服务提供。任务合法操作由业务编排显式校验，存储继续保证事务、租约和并发条件；没有更换数据库格式、接口或业务状态机。

## 两个默认文件

- `backend/novel-ai/src/main/resources/writing/skills/restrained/SKILL.md`：克制叙述。
- `backend/novel-ai/src/main/resources/writing/skills/dialogue/SKILL.md`：对话驱动。

本次保留原有两条提示规则，避免结构整理同时改变写作效果。前端从 `/api/ai/writing/capabilities` 的 `skills` 获取 ID、名称、说明、版本与文件哈希，不再维护独立的名称与规则清单。最多同时选择两个。

## 新增内置 Skill

在 `writing/skills/<唯一目录>/SKILL.md` 新增 UTF-8 文件：

```markdown
---
id: atmosphere
name: 氛围描写
description: 用环境细节营造场景氛围
version: 1
---
围绕本章目标描写环境，让可观察的细节承载情绪。
```

元信息使用四个单行 `key: value` 字段，不使用 YAML 的嵌套、引号转义、列表或脚本。ID 为小写字母开头，后续允许小写字母、数字和连字符，最多 40 个字符；正文最多 6000 字，整个文件最多 16 KB。名称最多 60 字，说明最多 300 字，版本最多 32 个字母、数字、点、下划线或连字符。

后端启动时扫描 `classpath*:writing/skills/*/SKILL.md`，平台文件随 JAR 打包。**新增或修改平台文件后重新打包、重启 `novel-ai`，刷新作者端**，无需修改 Java 选项白名单或前端清单。作者个人 Skill 现支持数据库编辑及 MD/ZIP 文本导入，详见 [扩展说明](writing-agent-extensions.md)；平台文件仍不热重载，导入不执行脚本。

没有可用文件、文件过大、元信息错误、正文为空或 ID 重复会导致启动失败，日志包含资源位置与原因，避免带着缺失规则运行。单个文件被移除后，其 ID 不再是可新选的选项，未知或重复选择返回 422。

## 任务快照与兼容

新建任务时，服务端保存所选文件的 ID、名称、版本、SHA-256 和实际规则正文。客户端不能通过提交 `skillSnapshots` 指定自己的规则。规划、生成和检查均使用该任务保存的规则；修改文件并重启后，已有快照仍使用原正文。新增选择使用当前文件，继续保留的选择复用已有快照；已生成草稿不会自动重写。

任务创建幂等键只根据规范化请求计算，不把服务端加载的规则正文作为请求身份，避免部署文件变化后同一请求重复创建任务。

旧任务只有 Skill ID，没有文件快照。此类任务通过文件中的相同默认规则兼容，首次更新选择时形成快照。本次默认正文逐字保留；不能据此声称没有快照的历史任务已具备永久版本归档。

## 验证与部署

类的包名已移动，必须先 `mvn clean` 再打包，避免旧类留在产物内造成重复 Spring Bean。早期仅目录调整无需 SQL；本次扩展增加数据库迁移、来源事件和删除接口，须按扩展说明执行迁移，并部署、重启内容、搜索和 AI 服务，刷新作者端。

回归覆盖文件发现与校验、选项扩展、规则快照、客户端伪造拒绝、幂等、Spring 组件装配、真实隔离 MySQL 写作流程、工具协议与前端事件。没有调用付费模型验证文学质量。
