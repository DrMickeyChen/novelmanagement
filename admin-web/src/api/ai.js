/**
 * AI 管理端接口 —— 对应后端 AdminAiConfigController / AdminAiUsageController。
 *
 * ------------------------------------------------------------
 * ★★ 这个文件里【没有】任何接收 API Key 明文的「读」函数
 * ------------------------------------------------------------
 * `getAiConfig()` 拿回来的 `apiKeyMasked` 是掩码 `sk-****abcd`，
 * 而且**服务端永远不会返回明文**（连密文都不返回）。
 * 所以：
 *   ① 不要试图「把 Key 读出来显示一下」—— 读不到，这是设计；
 *   ② `saveAiConfig` 里的 `apiKey` 字段是**只写**的：不传 = 保持库里那把不变。
 *      这个「空 = 不改」的语义是后端 `AiConfigSaveDTO` 定的，
 *      前端照着做就行 —— 千万别自己「顺手补一个空串」上去，那会被当成清空。
 *      （实际上后端把空串也当「不变」，但依赖那条兜底会让意图变模糊。）
 *   ③ 保存成功后**要把输入框清空、且不回显刚输入的值**（见 views/ai/index.vue）。
 *
 * ------------------------------------------------------------
 * ★★ `provider` 和 `effectiveProvider` 是两件事，别比较它们
 * ------------------------------------------------------------
 *   provider          —— 数据库那一行记录的厂商名，恒为 `DEEPSEEK`，只读
 *   effectiveProvider —— YAML `novel.ai.provider`，`mock` / `deepseek`，**它才决定跑的是谁**
 *
 * 判断「现在到底是不是真模型」只看 `effectiveProvider === 'deepseek'`。
 * 把两者拿去互相比较（先大小写折叠再比）会把「它们不是一回事」这个信息抹掉，
 * 而后端 `AiConfigVO` 的注释专门警告过这件事。
 */
import request from '@/utils/request'

/* ==================== 模型配置 ==================== */

/**
 * 读取 AI 配置。★ 永远拿不到 API Key 明文，只有掩码。
 *
 * @returns {Promise<object>} 见后端 AiConfigVO
 */
export function getAiConfig() {
  return request.get('/admin/ai/config')
}

/**
 * 保存 AI 配置。
 *
 * ★ 入参里**不要**放 `provider` —— 它不在 DTO 里，传了也没用。
 * ★ `apiKey` 不传 = 保持原值；要清空请显式传 `clearApiKey: true`。
 *   两个同时传后端返 400（不猜用户想要哪个）。
 *
 * @param {object} data 见后端 AiConfigSaveDTO
 */
export function saveAiConfig(data) {
  return request.put('/admin/ai/config', data)
}

/* ==================== 用量账（只读） ==================== */

/**
 * 分页查 AI 调用流水。
 *
 * ★★ 这一份数据里有两个「可能不出现 / 需要额外解读」的字段，
 *   页面必须区别对待（详见 views/ai/index.vue）：
 *
 *   `userName`        —— 取不到时该字段**直接不出现**（跨服务降级），
 *                        页面回落显示 `用户 #7`。**不要显示成「佚名」**：
 *                        那句话说的事没被验证过（那本书必然有作者，这条流水也必然有调用人）。
 *   `tokenUntracked`  —— `true` 时 `totalTokens` 读作**「未统计」而不是「没消耗」**。
 *                        页面必须显示「未统计」，显示 0 等于把账目系统性地改小，
 *                        而且方向恰好是「看起来更省钱」。
 *
 * ★ 日期是 `yyyy-MM-dd` 字符串，**两端都含**（后端把它换算成次日 0 点）。
 *
 * @param {object} params
 * @param {number} [params.scene] 1大纲 2续写 3灵感 4润色 5审核。不传=全部
 * @param {boolean} [params.success] true=只看成功 false=只看失败。不传=全部
 * @param {number} [params.userId] 调用人
 * @param {string} [params.startDate] yyyy-MM-dd（含）
 * @param {string} [params.endDate] yyyy-MM-dd（含）
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 * @returns {Promise<{records: Array, total: number}>}
 */
export function getAiUsagePage(params) {
  return request.get('/admin/ai/usage', { params })
}

/**
 * AI 用量汇总：今日 + 累计。
 *
 * ★★ `untrackedTokenCalls` 必须和 `totalTokens` **一起显示**，
 *   否则那个 token 和会被读成完整的花费，而它一定是偏小的。
 *
 * @returns {Promise<{statDay: string, today: object, total: object}>}
 */
export function getAiUsageSummary() {
  return request.get('/admin/ai/usage/summary')
}

/*
 * ★ S6 / S7 会往这里加提示词模板与词库的接口（后端 AdminAiPromptController 已经在，
 *   但它的消费方是 S6 的模板 tab）。**不要提前加**：没有调用方的导出函数
 *   在编辑器里看起来完全一样，只会让「哪些接口真的在被用」变得查不清。
 */

/* ==================== 提示词模板 ==================== */

/**
 * 模板列表。★ **包含已停用的模板** —— 后端刻意不过滤，
 * 否则停用就变成一个「看不到、也就改不回来」的操作。
 *
 * ★ 列表顺序（场景 → sort → id）与「生成接口取哪一条」**是同一个排序**，
 *   页面不要再排一次：两处各排各的，症状是「列表里看着生效的是 A，
 *   实际用的是 B」，而且不报错。
 *
 * @param {number} [scene] 1大纲 2续写 3灵感 4润色 5审核。不传=全部
 * @returns {Promise<Array>} 见后端 AiPromptTemplateVO
 */
export function getAiPromptList(scene) {
  return request.get('/admin/ai/prompt', { params: scene ? { scene } : {} })
}

/**
 * 新增模板。★ 占位符在**写入之前**就校验，不认识的词返 400 并指出是哪一个。
 *
 * @param {object} data 见后端 AiPromptSaveDTO
 * @returns {Promise<number>} 新 id
 */
export function createAiPrompt(data) {
  return request.post('/admin/ai/prompt', data)
}

/**
 * 编辑模板。校验规则同新增；不存在返 5005，同场景重名返 5006。
 *
 * @param {number} id
 * @param {object} data 见后端 AiPromptSaveDTO
 */
export function updateAiPrompt(id, data) {
  return request.put(`/admin/ai/prompt/${id}`, data)
}

/**
 * 预览渲染结果。**只读**：不调模型、不扣配额、不写任何表 ——
 * 所以这是这一页唯一可以随便点、随便试的按钮。
 *
 * ★★ 它渲染的是**库里那一份**，不是编辑器里的缓冲。所以「改完想看看效果」
 *   必须先保存再预览，两次调用不是 bug 而是设计（见后端 AiPromptPreviewDTO）。
 *
 * ★★ 两个返回值页面必须都读：
 *   `removedVars` —— 哪些变量的那一行**被整行移除了**。这不是错误、不报错、
 *                    在文本里也什么都不剩，所以不显式显示的话，管理员只会觉得
 *                    「结果里少了点什么」而说不清少了什么（铁律 10 的取向）。
 *   `availableVars` —— 本场景的变量表（名字 → 必填/可选/不适用），
 *                    编辑器右侧的提示语用它生成，**不要在前端写死一份**：
 *                    写死的那份和 AiVarResolver 迟早会对不上，而症状是
 *                    「提示说可选、实际必填」这种没法查的错。
 *
 * 失败语义（后端逐条对应）：没有启用的模板 → `5005`；样本里有不认识的变量名 /
 * 必填变量没值 / 渲染超长 → `400`；模板里有未知占位符（只可能来自绕过管理端改库）→ **`500`**。
 *
 * @param {object} data 见后端 AiPromptPreviewDTO
 * @returns {Promise<object>} 见后端 RenderedPromptVO
 */
export function previewAiPrompt(data) {
  return request.post('/admin/ai/prompt/preview', data)
}

/* ==================== 违禁词库（E1/S7） ==================== */

/**
 * 词库列表。★ **包含已停用的词** —— 后端刻意不过滤，同模板列表的理由。
 *
 * ★ 排序（等级倒序 → id 升序）由后端定，页面不要再排一次。
 *
 * @param {number} [enabled] 1 只看启用的，0 只看停用的。不传=全部
 * @returns {Promise<Array>} 见后端 SensitiveWordVO
 */
export function getAiWordList(enabled) {
  return request.get('/admin/ai/word', {
    params: enabled === 0 || enabled === 1 ? { enabled } : {},
  })
}

/**
 * 快照状态。**这是这一页最重要的一次读**。
 *
 * ★★★ 它同时给两组数，而**它们不相等才是要看的东西**：
 *   `enabledRows` / `totalRows` —— **库里**的行数（前者只算启用的）
 *   `wordCount`                 —— **快照里**的词条数
 *   两个「启用数」不等 ⇒ 有人改了库但还没重载，此刻的审核用的是旧词库。
 *
 * ★★ 三个不能读错的字段（详见页面里的横幅与后端 SensitiveWordStatusVO）：
 *   `ready === false`      → 此刻**所有审核都被拒**（fail-closed）。这不是「审核通过」。
 *   `dbUnavailable === true` → `enabledRows` / `totalRows` 是 **null 而不是 0**。
 *                             「读不到」不是「一条都没有」。
 *   `loaded === false`     → 快照**从没加载成功过**（查 DB）；与「加载成功但词是空的」不是一件事。
 *
 * @returns {Promise<object>} 见后端 SensitiveWordStatusVO
 */
export function getAiWordStatus() {
  return request.get('/admin/ai/word/status')
}

/**
 * 重建词库快照 —— **改完词必须调它，改动才会生效**（或等最多 10 分钟的定时兜底）。
 *
 * ★★ 保存接口**刻意不自动重载**，这不是漏了：自动重载会让「这次改动到底生效了没有」
 *   变得无法验证 —— 每次保存都换快照，就分不出「生效」和「本来就该生效」。
 *   所以页面上「保存成功」和「已生效」必须是**两句不同的话**。
 *
 * ★ 重载**失败也不报错**（照样 200）：失败的事实写在返回值的 `lastError` 里。
 *   理由同后端 —— 「重载失败」不是一个请求级别的错误，而是一个要在状态里被看见的事实。
 *
 * @returns {Promise<object>} 重载后的状态，同 getAiWordStatus
 */
export function reloadAiWords() {
  return request.post('/admin/ai/word/reload')
}

/**
 * 试扫一段文本。**只读**：不写表、不扣配额、不调模型。
 *
 * ★ 它扫的是**内存快照**，不是库：刚保存的词**重载之前扫不到**。这不是 bug。
 * ★★ 结果里带 `wordCount` / `version`，所以**「命中 0」和「词库是空的」分得开** ——
 *    后者后端直接返 `5007`（fail-closed），压根不会以 200 + 「命中 0」的形态回来。
 *
 * @param {string} text
 * @returns {Promise<object>} 见后端 SensitiveWordScanVO
 */
export function scanAiWords(text) {
  return request.post('/admin/ai/word/scan', { text })
}

/**
 * 新增违禁词。★ 两端空白会被后端去掉；词重复返 `5008`。
 *
 * ★ 新增之后**需要重载**才会命中。
 *
 * @param {object} data 见后端 SensitiveWordSaveDTO
 * @returns {Promise<number>} 新 id
 */
export function createAiWord(data) {
  return request.post('/admin/ai/word', data)
}

/**
 * 编辑违禁词。不存在返 `5009`，与别的词重复返 `5008`。
 *
 * ★ 改错字走**编辑**，不要「新增一条再停用旧的」—— 后者会让词库里堆一排停用的错字。
 *
 * @param {number} id
 * @param {object} data 见后端 SensitiveWordSaveDTO
 */
export function updateAiWord(id, data) {
  return request.put(`/admin/ai/word/${id}`, data)
}

/**
 * 启用 / 停用。**只改 `enabled` 一列**，不会覆盖这一行的其它字段。
 *
 * ★ 所以列表页那个开关**只发 id + 目标值**，不要把整行回传 ——
 *   整行回传时，若另一个人在你读取之后改了这条词，这次「只是关一下开关」
 *   会把他的修改一并覆盖回去，**而且不报任何错**（后端专门为此开了这个端点）。
 *
 * @param {number} id
 * @param {number} enabled 1 启用，0 停用
 */
export function updateAiWordEnabled(id, enabled) {
  return request.put(`/admin/ai/word/${id}/enabled`, null, { params: { enabled } })
}

/* ==================== 违禁审核队列（E1/S9） ==================== */

/**
 * 分页查 AI 审核判定。
 *
 * ★★ **排序是「风险等级倒序 → id 倒序」**，不是时间倒序：队列是给人办事的顺序，
 *   高风险排在前面。页面不要再排一次 —— 两处各排各的，症状是
 *   「看到的顺序」和「翻页时的顺序」对不上。
 *
 * ★ 页面默认传 `status: 0`（这一页叫「违禁审核」，默认视图是**待办**），
 *   但服务端不写死：切到「全部」用同一条接口就能看到已处置的记录。
 *
 * ★★ 每行有四个「不出现 / 需要解读」的字段，页面必须区别对待（详见 views/ai-audit/index.vue）：
 *
 *   `targetTitle`      —— 取不到 = 目标被逻辑删除，或评论已被删。
 *                         页面回落「章节 #12（已删除）」，**不要留白**。
 *   `novelId`          —— 取不到时**禁用「去处置」按钮**，不要跳一个 404 的 URL。
 *   `contentUpdatedAt` —— 为 null **不等于**「内容没更新过」，所以它配了
 *                         `contentImmutable`（评论正文不可修改，没有这个时间可言）。
 *                         少了那个布尔量，「评论」和「章节但时间丢了」会渲染成同一个样子。
 *   `aiState`          —— `NOT_CALLED` / `SKIPPED` / `NO_REASON` / `RAN`。
 *                         ★ 它是 `aiComment` 那几个哨兵文本的**结构化说法**，
 *                         页面只认这四个 token，**不要自己去比字符串**。
 *
 * @param {object} params
 * @param {number} [params.targetType] 2章节 3评论。不传=全部（传 1 只会得到空列表）
 * @param {number} [params.riskLevel] 0无风险 1低 2中 3高。不传=全部
 * @param {number} [params.status] 0待确认 1已确认违规 2已排除。不传=全部
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 * @returns {Promise<{records: Array, total: number}>}
 */
export function getAiAuditPage(params) {
  return request.get('/admin/ai/audit', { params })
}

/**
 * 排除 —— 判定这是一次 AI（或词库）误杀，把它从待办里拿掉。
 *
 * ★★★ **它不写 `audit_log`，也不会改变内容的任何状态。**
 *   点了排除**不代表内容已经被放过** —— 它还在原来的待审队列里，
 *   管理员若认为内容确实违规，**仍然**要去处置页驳回。
 *   所以页面上的确认框必须把这句话说出来，不能只说「确定排除吗」。
 *
 * ★ 入参是 `(targetType, targetId)`，**不是 `ai_audit_result.id`**：
 *   「哪一行算被处置过」只允许有一个定义，它已经写在
 *   `AiAuditResultMapper#linkHumanDisposition` 里了（后端 DTO 的注释有完整论证）。
 *
 * ★★ 失败 `code 5010`（「该审核已被处置，请刷新后查看」）走的是 **HTTP 200** ——
 *   别用状态码判断成败（铁律 3）。两种触发方式：别人先处置了、自己点了两次。
 *   ★ 它刻意**不**装作成功：这一页是追责页面，对一条「已确认违规」的行
 *   回一句「已排除」是在说反方向的假话。
 *
 * @param {number} targetType 2章节 3评论
 * @param {number} targetId
 */
export function excludeAiAudit(targetType, targetId) {
  return request.post('/admin/ai/audit/exclude', { targetType, targetId })
}
