<script setup>
/**
 * AI 配置 + 提示词模板 + 用量 —— E1 的管理端页面（/ai 一条菜单，三个 tab）。
 *
 * ============================================================
 * ★★★ tab 1 的核心规矩：那把 Key 前端【永远拿不到】，所以输入框必须一直是空的
 * ============================================================
 * 后端 `AdminAiConfigController` 只返回掩码 `sk-****abcd`（长度固定，不泄露真实长度），
 * 明文既不进 JSON、也不进浏览器内存。于是这一页有两种「Key 显示」，**它们不能混**：
 *
 *   ① **当前配置**（只读）：显示掩码 / 「未配置」/ `apiKeyNotice` 那句话。
 *      它是**文本**，不是输入框 —— 一个装了掩码的输入框会让人以为
 *      「编辑它就能改 Key」，而那不是真的。
 *   ② **设置新 Key**（可写）：**永远从空开始**。保存成功后也必须清空，
 *      **且绝不回显刚才输入的值**。
 *
 * ★★ 为什么连「回显刚才输入的值」都不行：那会让「页面上有一个像 Key 的东西」
 * 变成常态。下一次截图、录屏、共享屏幕，带出去的就是一把真 Key。
 * 而管理员并不需要看到它才能确认保存成功 —— 掩码的末四位就是干这个的
 * （`AiConfigVO` 的注释：「末四位是运维手上好几把 Key 时唯一能用来确认『我填的是这一把』的信息」）。
 *
 * ★ 还有一条不能忘：**不填 ≠ 清空**。读接口不返回 Key，输入框打开本来就是空的，
 * 把空当成清空的话，管理员只想改个温度顺手保存就把 Key 抹了 ——
 * 而界面上看不出任何变化。所以空串**根本不进 payload**，
 * 清空要走旁边那个显式的勾选框。
 *
 * ============================================================
 * ★★ tab 1 上方的横幅：让「填了 Key 却不生效」变成可见的
 * ============================================================
 * `effectiveProvider`（YAML）和 `provider`（数据库那一列）**不是一回事**：
 * 前者决定哪个 Bean 存在，也就是 AI 面板背后是真模型还是假数据。
 * YAML 还是 `mock` 时，管理员在这里填什么都不会生效 ——
 * 不显示横幅的话，他会以为自己填错了，然后反复重填。
 *
 * ★ 判据只看 `effectiveProvider === 'deepseek'`，**不把两个字段互相比较**：
 *   它们大小写不同（`DEEPSEEK` vs `deepseek`），要比较就得先折叠大小写，
 *   而那一折叠恰好把「它们不是一回事」这个信息抹掉了（后端 VO 的原文警告）。
 *
 * ============================================================
 * ★★★ tab 2：提示词模板 —— 这一页的实质是「让作者按按钮之前，先看见他会遇到什么」
 * ============================================================
 * E1 把提示词的拼装从**前端**搬到了**服务端**（作者端只发 prompt + context + 三个 id，
 * 作品名/大纲/摘要由服务端补），于是「模板写错了会怎样」第一次变成一个
 * 只有管理员能回答的问题。这一页就是回答它的地方。
 *
 * ★★ 三件必须看懂的事：
 *   ① **预览是只读的**：不调模型、不扣配额、不写任何表。所以它是这一页唯一
 *      可以随便点、随便试的按钮 —— 「把 {{summary}} 那一行删掉会怎样」试到满意再保存。
 *   ② **预览渲染的是库里那一份**，不是编辑器里的缓冲。改完要先保存再预览，
 *      所以弹窗里显式写了这句话（不写的话，管理员会以为预览没生效）。
 *   ③ ★★★ **`removedVars` 必须显示出来**。可选变量没值的那一行会被**整行移除**：
 *      它不是错误、不报错、在最终文本里也什么都不剩。不显式显示，管理员只会觉得
 *      「结果里少了点什么」而说不清少了什么 —— 铁律 10 的同一取向。
 *
 * ★★ 变量表（变量名 + 必填/可选/不适用）**由后端返回**，页面不写死：
 *   写死的那份和 `AiVarResolver` 迟早会对不上，而症状是「提示说可选、实际必填」，
 *   没有任何报错，只能靠人去比。页面里那份 `SAMPLE_DEFAULTS` 是**样本值**，
 *   作用只是让第一次点预览就能渲染成功（预览对「必填没值」返 400 是**正确的**，
 *   但让管理员第一次点就撞一个报错，学到的第一件事是「这个功能坏了」）。
 *
 * ★ 模板停用**不是删除**，随时改得回来 —— 但把某场景**最后一条启用的**模板停掉，
 *   作者点生成会收到 5005。停用前的确认框专门说了这件事。
 *
 * ============================================================
 * ★★ tab 3：用量表里有两处「不能照字面显示」的地方
 * ============================================================
 * ① `userName` 取不到时该字段**直接不出现**（跨服务降级）。
 *    回落成 `用户 #7`，**不是「佚名」** —— 后者是一句没被验证过的断言。
 * ② `tokenUntracked === true` 时，`totalTokens` 读作**「未统计」**。
 *    显示 0 等于把账目改小，而方向恰好是「看起来更省钱」。
 *    同理，**失败行的 token 一律显示 `—`**：那三列在失败行上
 *    不承担「消费量」的语义（有的是真 0，有的是不知道），不假装分得出来。
 *
 * ★★ 汇总卡片失败时**显示「暂时拿不到」，绝不显示 0**。
 *    一张写着「今日 0 次调用」的卡片和一次系统故障在视觉上必须不同 ——
 *    这是 M5 立下、E1 继续用的口径（`usePagedList` 的 `failed` / `isEmpty` 同源）。
 *
 * ★ 这一页【没有】任何写流水的入口，后端也只有 GET：账是只追加的。
 *
 * ============================================================
 * ★ 「作品 / 章节」那一列：S6 加上了，但它对老数据一定是空的
 * ============================================================
 * 这两个 id 是 **S6 才**由作者端开始发送的（`NodeWorkspace.vue` 的 `gatherAiRequest`），
 * 所以 E1 之前的流水在这一列上全是 `—`。
 * ★★ 那个 `—` **不是**「这次调用没关联作品」，而是「那时还没记」——
 *   两者在这一列上分不出来，所以刻意不给它加任何 tooltip 断言。
 *   （把「没记」显示成「没有」正是铁律 10 反复挡的那个形状。）
 * ★ 只给裸 id：管理端没有作品/章节的详情页可跳，编一个跳不过去的链接更糟。
 * **归属仍然追得下去**：点调用人 → 筛出这个人的全部调用。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAiConfig,
  saveAiConfig,
  getAiUsagePage,
  getAiUsageSummary,
  getAiPromptList,
  createAiPrompt,
  updateAiPrompt,
  previewAiPrompt,
  getAiWordList,
  getAiWordStatus,
  reloadAiWords,
  scanAiWords,
  createAiWord,
  updateAiWord,
  updateAiWordEnabled,
} from '@/api/ai'
import { usePagedList } from '@/composables/usePagedList'
import { confirm } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'

const activeTab = ref('config')

/* ==================== tab 1：模型配置 ==================== */

const configLoading = ref(false)
const saving = ref(false)

/**
 * 当前配置（只读展示用）。★ 里面**没有 apiKey 字段** —— 后端不给。
 */
const config = ref(null)

/**
 * 表单。★ 从 `config` 里挑字段拷过来，**不是**整个响应对象的副本 ——
 * 那样会把 `provider` / `effectiveProvider` / `apiKeyMasked` 一起带进 payload，
 * 而后端 DTO 里根本没有它们（多传的字段会被静默忽略，于是「我明明发了」成了错觉）。
 */
const form = reactive({
  baseUrl: '',
  model: '',
  temperature: 0.7,
  maxTokens: 2048,
  dailyLimit: 50,
  enabled: 0,
})

/**
 * 新 Key 的输入框。★★ 它的初始值**永远是空串**，保存后也被清回空串。
 * 任何「把当前 Key 填进来」的改动都是在破坏这个页面的安全前提。
 */
const newApiKey = ref('')

/** 显式清空。★ 与填新 Key 互斥，两个同时来后端返 400 */
const clearApiKey = ref(false)

async function loadConfig() {
  configLoading.value = true
  try {
    const data = await getAiConfig()
    config.value = data
    form.baseUrl = data.baseUrl ?? ''
    form.model = data.model ?? ''
    // ★ 后端是 BigDecimal，序列化出来是数字；Number() 兜住「字符串数字」那种情况
    form.temperature = data.temperature == null ? 0.7 : Number(data.temperature)
    form.maxTokens = data.maxTokens ?? 2048
    form.dailyLimit = data.dailyLimit ?? 50
    form.enabled = data.enabled ?? 0
    /*
     * ★★ 这两行是「保存后清空」的**另一半**：重新拉配置时也要把输入框清掉。
     *   少了它们，保存成功后如果因为任何原因重新 load 了一次配置，
     *   输入框里那把 Key 还留着 —— 而「保存成功」和「重新拉取」
     *   在代码上是两件事，很容易只在一处清。
     */
    newApiKey.value = ''
    clearApiKey.value = false
  } catch (e) {
    // ★ 加载失败时不要把 config 清成 null 之外的任何东西：
    //   模板里 v-if="config" 保证失败时不会渲染出一个「全是空值」的表单
    //   （那会让人以为后端配置真的被清空了）。
    config.value = null
  } finally {
    configLoading.value = false
  }
}

/**
 * ★★ 现在跑的到底是真模型还是假数据。
 *
 * 判据只看 `effectiveProvider`，理由见文件头。
 */
const isRealModel = computed(() => config.value?.effectiveProvider === 'deepseek')

/**
 * 「当前 API Key」那一行的文本。
 *
 * 三种状态**必须分开**，合并任意两个都会说谎：
 *   ① 没配        → 「未配置」
 *   ② 配了、解不开 → 原样显示后端给的 `apiKeyNotice`（一句人话）
 *   ③ 配了、能解开 → 显示掩码
 *
 * ★ ② 排在 ③ 前面：解不开时 `apiKeyMasked` 是 null，
 *   先判 ③ 会走到「未配置」—— 而库里**明明有**一把 Key。
 */
const apiKeyText = computed(() => {
  const c = config.value
  if (!c) return ''
  if (c.apiKeyNotice) return c.apiKeyNotice
  if (!c.apiKeyConfigured) return '未配置'
  return c.apiKeyMasked || '已配置（掩码不可用）'
})

/** ★ 有 notice 时要换成告警色 */
const apiKeyIsWarning = computed(() => !!config.value?.apiKeyNotice)

async function submit() {
  saving.value = true
  try {
    /*
     * ★★ 只发 DTO 里有的字段，而且 apiKey **只在非空时才带**。
     *   写成 `apiKey: newApiKey.value` 的话，空串会被发上去 ——
     *   后端把空串也当「不变」，所以功能上没错，但**意图变模糊了**：
     *   读代码的人分不出这是「刻意不发」还是「忘了判空」。
     */
    const payload = {
      baseUrl: form.baseUrl,
      model: form.model,
      temperature: form.temperature,
      maxTokens: form.maxTokens,
      dailyLimit: form.dailyLimit,
      enabled: form.enabled,
    }
    const key = newApiKey.value.trim()
    if (key) {
      payload.apiKey = key
      // ★ 填了新 Key 就不可能同时是「清空」，不让两个互斥字段一起发（后端会 400）
    } else if (clearApiKey.value) {
      payload.clearApiKey = true
    }

    await saveAiConfig(payload)

    /*
     * ★★★ 保存成功后：立刻清空输入框，**且不回显**。
     *   顺序上【先清再重新拉配置】和「重新拉配置里也清一遍」是双保险 ——
     *   因为这个页面里「输入框里留着 Key」是最不能被容忍的一种残留。
     */
    newApiKey.value = ''
    clearApiKey.value = false
    ElMessage.success('保存成功')

    /*
     * ★ 重新拉一次配置：掩码 / updateTime / apiKeyNotice 都可能变了。
     *   尤其「刚填了第一把 Key」时，不重拉就看不到掩码，管理员无法确认有没有生效。
     *   （这不违反上面那条：loadConfig 自己也会清输入框。）
     */
    await loadConfig()
  } catch (e) {
    /*
     * ★ 失败时【不清】输入框 —— 这里的取舍和上面正好相反，理由也正好相反：
     *   保存失败时留着内容，管理员可以直接重试，不用把 Key 再找出来粘一遍。
     *   「清空」是为了防泄漏（成功之后页面上不该长期躺着一把真 Key），
     *   「保留」是为了可用性。两者冲突时按「这一次操作有没有成功」分。
     */
  } finally {
    saving.value = false
  }
}

/* ==================== tab 2：提示词模板 ==================== */

const promptList = ref([])
const promptLoading = ref(false)
/**
 * ★★ 这一页有两个「看着一样、其实不同」的空状态，必须分开：
 *   ① 拉取失败 → `promptFailed`，显示「暂时拿不到」；
 *   ② 拉到了、只是这个场景还没有模板 → el-empty「还没有模板」。
 *   把 ① 显示成 ②，管理员会以为模板被谁删了 —— 而真实原因是后端挂了。
 *   这是 M5 立下的口径（`usePagedList` 的 `failed` / `isEmpty` 同源）。
 */
const promptFailed = ref(false)
const promptScene = ref(null)

async function loadPrompts() {
  promptLoading.value = true
  try {
    promptList.value = await getAiPromptList(promptScene.value)
    promptFailed.value = false
  } catch (e) {
    promptList.value = []
    promptFailed.value = true
  } finally {
    promptLoading.value = false
  }
}

/* ---- 新增 / 编辑 ---- */

const editVisible = ref(false)
const editSaving = ref(false)
/** ★ null = 新增，有值 = 编辑。用一个 ref 而不是两个 boolean：两个 boolean 会出现「都是 true」的坏状态 */
const editId = ref(null)
const editForm = reactive({ scene: null, name: '', systemPrompt: '', userTemplate: '', enabled: 1, sort: 0 })

function openCreate() {
  editId.value = null
  Object.assign(editForm, {
    scene: promptScene.value || 2,
    name: '',
    systemPrompt: '',
    userTemplate: '',
    enabled: 1,
    sort: 0,
  })
  editVisible.value = true
}

function openEdit(row) {
  editId.value = row.id
  Object.assign(editForm, {
    scene: row.scene,
    name: row.name,
    systemPrompt: row.systemPrompt || '',
    userTemplate: row.userTemplate || '',
    enabled: row.enabled,
    sort: row.sort,
  })
  editVisible.value = true
}

/**
 * 保存。
 *
 * ★★ `scene` 是**只读**的：编辑时不允许改场景。理由不是「后端不让改」——
 *   后端其实允许，但改场景的后果是**该场景原本的模板没被替换，只是多了一条，
 *   而旧那条仍然 enabled** —— 于是「我明明改成大纲了，作者点续写还是老样子」。
 *   要换场景，正确做法是停用旧的、再新建一条。
 */
async function submitEdit() {
  editSaving.value = true
  try {
    const payload = { ...editForm }
    if (editId.value) {
      await updateAiPrompt(editId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createAiPrompt(payload)
      ElMessage.success('已新增')
    }
    editVisible.value = false
    await loadPrompts()
  } catch (e) {
    /*
     * ★ 失败**不关弹窗**：这里的失败多半是「占位符写错了」或「同场景重名」，
     *   两种都要求管理员接着改那一份内容。关掉弹窗等于把他刚写的东西丢了。
     *   ★ 提示语由 request.js 的响应拦截器统一弹出（含后端 message），这里不重复弹。
     */
  } finally {
    editSaving.value = false
  }
}

/** 启用 / 停用。★ 停用不是删除，随时可以改回来 —— 但**最后一个启用的模板被停掉**会让该场景返 5005 */
async function toggleEnabled(row) {
  const next = row.enabled === 1 ? 0 : 1
  if (next === 0) {
    const ok = await confirm(
      `停用后，作者在「${sceneLabel(row.scene)}」里点生成会收到「该场景尚未配置提示词模板」。`,
      '停用这条模板？',
      { confirmText: '停用' },
    )
    if (!ok) return
  }
  try {
    /*
     * ★★ 必须把**整行**发回去：后端的保存 DTO 里 scene/name/userTemplate 都是
     *   @NotNull/@NotBlank，只发 {enabled: 0} 会被校验拦下返 400。
     *   这是「部分更新用 PUT 全量语义」的既定形状，不是省事。
     */
    await updateAiPrompt(row.id, {
      scene: row.scene,
      name: row.name,
      systemPrompt: row.systemPrompt || '',
      userTemplate: row.userTemplate,
      enabled: next,
      sort: row.sort,
    })
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await loadPrompts()
  } catch (e) {
    /* 同上：提示由拦截器统一弹 */
  }
}

/* ---- 预览 ---- */

const previewVisible = ref(false)
const previewLoading = ref(false)
const previewResult = ref(null)
const previewRow = ref(null)
/** 变量名 → 样本值 */
const previewVars = ref({})

/**
 * 预览用的**样本值**。
 *
 * ★★★ 这是「样本值」，**不是**「变量表」。
 *   变量名、必填/可选、以及「哪些场景不适用」**全部由后端返回**
 *   （`RenderedPromptVO.availableVars`），预览面板里的档位与禁用状态一律读那一份。
 *   前端**不写死**变量表：写死的那份和 `AiVarResolver` 迟早会对不上，
 *   而症状是「提示说可选、实际必填」—— 这种错没有任何报错，只能靠人去比。
 *
 * ★★ 那为什么还要放一份默认值：**为了让第一次点「预览」就能成功**。
 *   预览接口对「必填变量没值」是**返 400** 的（那是真实生成时会发生的事，
 *   预览必须如实复现，否则它就没有预览的意义）。不给默认值的话，
 *   管理员第一次点预览看到的是一个报错，学到的第一件事是「这个功能坏了」。
 *   给了之后，「把 {{summary}} 那一行删掉会怎样」这种试错才是零成本的 ——
 *   而那正是这一页存在的理由。
 *
 * ★ 值只是随便编的演示文字，**不要**往这里放真实稿件。
 */
const SAMPLE_DEFAULTS = {
  1: {
    request: '为「雨夜」生成下一级大纲',
    novelTitle: '剑冢',
    outline: '雨夜 · 主角在破庙避雨',
    summary: '主角已拿到残剑，正在被追杀。',
  },
  2: {
    selected: '（正文末尾）雨点砸在瓦上，他握紧了那把残剑。',
    request: '接着往下写「雨夜」',
    novelTitle: '剑冢',
    outline: '雨夜 · 相遇',
    summary: '主角已拿到残剑，正在被追杀。',
  },
  3: {
    request: '为「雨夜」提供几个剧情走向',
    novelTitle: '剑冢',
    outline: '雨夜 · 主角在破庙避雨',
  },
  4: {
    selected: '雨点砸在瓦上，他握紧了那把残剑。',
    novelTitle: '剑冢',
  },
  5: {
    selected: '他把刀架在对方脖子上，笑着说：这把刀今天要见血。',
  },
}

function openPreview(row) {
  previewRow.value = row
  previewVars.value = { ...(SAMPLE_DEFAULTS[row.scene] || {}) }
  previewResult.value = null
  previewVisible.value = true
  renderPreview()
}

/** 把样本值恢复成默认 —— 试坏之后一键回到「一定能渲染成功」的状态 */
function resetPreviewVars() {
  previewVars.value = { ...(SAMPLE_DEFAULTS[previewRow.value?.scene] || {}) }
  renderPreview()
}

/**
 * 渲染一次（**只读**：不调模型、不扣配额、不写任何表）。
 *
 * ★★ 它渲染的是**库里那一份**，编辑器里没保存的改动不会出现。
 *   「改完想看看效果」必须先保存再预览 —— 这不是 bug，是后端刻意的
 *   （预览要回答的是「作者会遇到什么」，而作者遇到的永远是库里那一份）。
 *   所以弹窗里要显式提醒这一点，否则管理员会以为预览没生效。
 */
async function renderPreview() {
  if (!previewRow.value) return
  previewLoading.value = true
  try {
    previewResult.value = await previewAiPrompt({
      scene: previewRow.value.scene,
      id: previewRow.value.id,
      sampleVars: previewVars.value,
    })
  } catch (e) {
    /*
     * ★ 失败时**必须清掉上一次的结果**：留着一份旧的渲染文本，配上一条错误提示，
     *   会让人以为「渲染成功了一半」。这里没有「一半」。
     */
    previewResult.value = null
  } finally {
    previewLoading.value = false
  }
}

/**
 * 本场景的变量表 —— **整张表来自后端**（预览响应的 `availableVars`）。
 *
 * ★★ 页面只做两件后端不做的事：把档位名翻成中文、数一下每个占位符在模板里出现了几次。
 *   变量名与必填/可选**一个字都不在这里定义** —— 见文件头那条理由。
 */
const varTable = computed(() => {
  const available = previewResult.value?.availableVars
  if (!available) return []
  return Object.entries(available).map(([name, req]) => ({
    name,
    req,
    count: varCountInTemplate(name),
  }))
})

/** 变量在模板里出现的次数 —— 0 次的时候要提醒「这个变量本场景用不上/没写」 */
function varCountInTemplate(name) {
  const token = `{{${name}}}`
  const text = `${previewRow.value?.systemPrompt || ''}\n${previewRow.value?.userTemplate || ''}`
  return text.split(token).length - 1
}

/**
 * 档位名的中文翻译。
 *
 * ★★ 只翻译**档位名**，变量名与档位本身依旧来自后端（`availableVars`）。
 *   ★ 后端把 `AiVarRequirement` 序列化成**枚举名**（`REQUIRED` / `OPTIONAL` /
 *     `NOT_APPLICABLE`）—— 这一层只负责把它换成人话和颜色。
 *
 * ★★★ 遇到不认识的档位名**原样显示那个名字，不猜**。
 *   猜（比如「不是必填就当可选」）的后果是：后端将来加一个新档位，
 *   页面会把它的语义**静默地换成另一个**。原样显示至少是诚实的 ——
 *   管理员看到一个没见过的英文词，会去问，而不是被误导。
 */
const REQ_LABELS = {
  REQUIRED: '必填',
  OPTIONAL: '可选',
  NOT_APPLICABLE: '不适用',
}

function reqLabel(name) {
  return REQ_LABELS[name] || name
}

function reqTagType(name) {
  if (name === 'REQUIRED') return 'danger'
  if (name === 'OPTIONAL') return 'info'
  return 'warning'
}

/** 场景编号 → 中文名。★ 用的是页面上那张 SCENES 表，不另建一份 */
function sceneLabel(code) {
  const hit = SCENES.find((s) => s.value === code)
  return hit ? hit.label : `场景 ${code}`
}

/* ==================== tab 3：用量统计 ==================== */

/*
 * ★ 场景码表放在页面里（同 views/log/index.vue 的取舍）。
 *   值必须和后端 AiScene 逐一对应：1大纲 2续写 3灵感 4润色 5审核。
 *   ★ 后端的 `sceneDesc` 已经给了中文，但筛选下拉需要**全部选项**，
 *     而列表里出现过的场景不一定覆盖全部 —— 所以下拉还是得有这张表。
 *     两者对不上时以这张表为准去改（它就是给人看的对照物）。
 */
const SCENES = [
  { label: '全部场景', value: null },
  { label: '生成大纲', value: 1 },
  { label: '续写正文', value: 2 },
  { label: '提供灵感', value: 3 },
  { label: '润色段落', value: 4 },
  { label: '违禁审核', value: 5 },
]

const SUCCESS_OPTIONS = [
  { label: '全部结果', value: null },
  { label: '成功', value: true },
  { label: '失败', value: false },
]

/**
 * ★ `null` = 不限（axios 跳过 null/undefined，正好对上后端「不传 = 全部」）。
 * `range` 是 el-date-picker 的 `[start, end]`，清空时是 null。
 */
const filter = ref({ scene: null, success: null, userId: null, userName: '', range: null })

const paged = usePagedList(
  ({ page, size }) => {
    const f = filter.value
    return getAiUsagePage({
      scene: f.scene,
      success: f.success,
      userId: f.userId,
      // ★ value-format="YYYY-MM-DD" 保证这里拿到的是字符串而不是 Date 对象，
      //   后端要的也正是这个格式
      startDate: f.range ? f.range[0] : null,
      endDate: f.range ? f.range[1] : null,
      page,
      size,
    })
  },
  { pageSize: 20 },
)

/* ---- 汇总卡片 ---- */

const summary = ref(null)
const summaryLoading = ref(false)
/**
 * ★★ 汇总**失败**的状态。它必须和「全 0」分开：
 *   一张写着「今日 0 次调用」的卡片和一次后端故障，在视觉上不能一样。
 */
const summaryFailed = ref(false)

async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await getAiUsageSummary()
    summaryFailed.value = false
  } catch (e) {
    summary.value = null
    summaryFailed.value = true
  } finally {
    summaryLoading.value = false
  }
}

onMounted(() => {
  loadConfig()
  paged.load()
  loadSummary()
  /*
   * ★ 模板与词库也在这里读一次（四个 tab 的数据都在挂载时取齐）。
   *   ★★ 少了这两行，切到那两个 tab 看到的是**一张空表** —— 而空表
   *   和「后端挂了」长得一模一样，和「真的一条都没有」也长得一模一样。
   *   本地接口、单管理员页面，多这两个请求换掉一类无法区分的状态，划算。
   */
  loadPrompts()
  loadAllWords()
})

/** 换筛选条件 → 回第 1 页（见 usePagedList 的 reload） */
function search() {
  paged.reload()
}

function reset() {
  filter.value = { scene: null, success: null, userId: null, userName: '', range: null }
  paged.reload()
}

/** 点某一行的调用人 → 筛出这个人的全部调用（同 views/log/index.vue 的钻取） */
function filterByUser(row) {
  if (!row.userId) return
  filter.value.userId = row.userId
  // ★ 名字只用于显示那个标签。后端这一行可能没给昵称，那就退回显示 id
  filter.value.userName = row.userName || ''
  paged.reload()
}

function clearUser() {
  filter.value.userId = null
  filter.value.userName = ''
  paged.reload()
}

function refreshAll() {
  paged.reload()
  loadSummary()
}

/* ---- 展示 ---- */

/**
 * 调用人。★ `userName` 缺失时回落 `用户 #7`，**不是「佚名」** —— 见文件头。
 */
function userLabel(row) {
  if (row.userName) return row.userName
  return row.userId ? `用户 #${row.userId}` : '—'
}

/**
 * token 那一格。
 *
 * ★★ 三种情况必须分开（这是这一页最要紧的一个函数）：
 *   ① 失败行     → `—`。那三列在失败行上不承担「消费量」的语义：
 *                  401 是真 0，中断是不知道，页面分不出来就不假装分得出来。
 *   ② 成功但未统计 → 「未统计」。**绝不能显示 0** —— 那是把一次真实支出记成 0，
 *                  而且方向恰好是「看起来更省钱」。
 *   ③ 正常        → 合计，并把「输入 + 输出」放进 tooltip。
 */
function tokenText(row) {
  if (!row.success) return '—'
  if (row.tokenUntracked) return '未统计'
  return row.totalTokens == null ? '—' : String(row.totalTokens)
}

function tokenTip(row) {
  if (!row.success) return '调用未成功，用量列读作「未统计」而不是「没消耗」'
  if (row.tokenUntracked) return '这次调用确实发生了，但上游的用量帧没拿到，无法统计'
  return `输入 ${row.promptTokens ?? 0} + 输出 ${row.completionTokens ?? 0}`
}

/** ★ 失败原因来自后端白名单常量的人话翻译，不是上游原文（见 AiFailure 的类注释） */
function errorText(row) {
  return row.errorDesc || row.errorMsg || ''
}

function fmtNum(n) {
  if (n == null) return '—'
  return Number(n).toLocaleString('en-US')
}

/* ==================== tab 4：违禁词库（E1/S7） ==================== */

/**
 * ★★★ 这一页真正要回答的问题不是「库里有几条词」，而是
 * **「此刻的审核正在拿哪一份词库做判断」** —— 这两件事可以不一致，
 * 而且不一致的时候页面上一切正常：词在列表里、开关是开的、状态显示「就绪」。
 *
 * 所以下面三组数据是**分开读、分开显示**的：
 *   `status`   —— 快照的状态（内存里那一份）
 *   `words`    —— 库里的行（磁盘上那一份）
 *   `scanResult` —— 拿快照**当场跑一遍**的结果
 * ★ 把它们合并成「一个词库」来显示，就等于把这个页面存在的理由删掉了。
 */

/** 等级选项。★ 与后端 AiRiskLevel 的 1~3 对齐，**0（无风险）不应该出现在这里** */
const LEVELS = [
  { value: 1, label: '低' },
  { value: 2, label: '中' },
  { value: 3, label: '高' },
]

/* ---- 快照状态 ---- */

const status = ref(null)
const statusLoading = ref(false)
/** ★ 「读不到状态」必须和「状态是坏的」分开 —— 前者是页面故障，后者才是词库故障 */
const statusFailed = ref(false)

async function loadStatus() {
  statusLoading.value = true
  try {
    status.value = await getAiWordStatus()
    statusFailed.value = false
  } catch (e) {
    status.value = null
    statusFailed.value = true
  } finally {
    statusLoading.value = false
  }
}

/**
 * ★★ 「库里启用的词条数」和「快照里的词条数」对不上 = 有人改了词但还没重载。
 *
 * ★ 三个前置判断一个都不能省：
 *   `!loaded`        → 那是「快照从没建起来」，不是「陈旧」，由另一条横幅负责；
 *   `dbUnavailable`  → 库里那两个数**根本不存在**，拿 null 去比会得到一个假的「不一致」；
 *   `enabledRows == null` → 同上，多一道保险。
 * ★★ 缺了这三条的症状是：横幅在**后端挂掉**时反而更爱出现 —— 正好把人的注意力
 *   引向「去看看词库」，而不是「去看看数据库」。
 */
const wordLibStale = computed(() => {
  const s = status.value
  if (!s || !s.loaded || s.dbUnavailable || s.enabledRows == null) return false
  return s.enabledRows !== s.wordCount
})

/**
 * 库里的行数。★ `null`（读不到）渲染成 `—`，**不是 0** ——
 * 「读不到」和「一条都没有」在这一页上是两句意思相反的话。
 */
function dbNum(n) {
  return n == null ? '—' : String(n)
}

const reloading = ref(false)

/**
 * 立即重载。
 *
 * ★★ 这个函数里最容易写错的一句是「重载成功」的判断 ——
 * **接口返回 200 不代表重载成功了**：重载失败时它照样 200
 * （「重载失败」是一个要在状态里被看见的事实，不是一个请求级错误），
 * 失败原因在返回值的 `lastError` 里。所以这里判的是 `lastError`，不是「有没有抛异常」。
 */
async function doReload() {
  reloading.value = true
  try {
    status.value = await reloadAiWords()
    statusFailed.value = false
    if (status.value.lastError) {
      ElMessage.warning('重载失败，仍在用上一份快照 —— 原因见下方状态卡')
    } else {
      ElMessage.success(`已重载，当前快照 ${status.value.wordCount} 条词`)
    }
  } catch (e) {
    statusFailed.value = true
  } finally {
    reloading.value = false
  }
}

/* ---- 试扫 ---- */

const scanText = ref('')
const scanResult = ref(null)
const scanning = ref(false)
/** ★ 同上：扫不了（未命中）与扫不成（词库不可用）必须分开 */
const scanFailed = ref(false)

async function doScan() {
  if (!scanText.value.trim()) {
    ElMessage.warning('请先输入要试扫的文本')
    return
  }
  scanning.value = true
  scanFailed.value = false
  try {
    scanResult.value = await scanAiWords(scanText.value)
  } catch (e) {
    /*
     * ★★ 词库不可用时后端返 5007（fail-closed），此时**没有**结果可显示。
     *   这里必须把旧结果清掉 —— 留着上一次的「命中 0 条」，管理员会把它
     *   读成这一次的结论，而它恰恰是「我没在筛」被渲染成「这段文字是干净的」。
     */
    scanResult.value = null
    scanFailed.value = true
  } finally {
    scanning.value = false
  }
}

/** 等级 → tag 颜色。★ 与列表页共用，别写两份映射 */
function levelTagType(level) {
  if (level >= 3) return 'danger'
  if (level === 2) return 'warning'
  return 'info'
}

/* ---- 词库列表 ---- */

const words = ref([])
const wordsLoading = ref(false)
/** ★ 「拿不到词库」必须和「词库是空的」分开 —— 空表看上去就是「没有词」，而它不作数 */
const wordsFailed = ref(false)

/**
 * 过滤：`null` = 全部。
 * ★ 默认**不是**「只看启用的」—— 默认藏掉停用词会让「我明明加了那个词」查不出原因。
 */
const wordEnabledFilter = ref(null)

async function loadWords() {
  wordsLoading.value = true
  try {
    /*
     * ★ 显式归一化而不是直接把 ref 传下去：el-select 清空时给的是**空串**
     *   （不是 null、也不是 undefined），而 API 那边只认 0 / 1。
     *   这里写清楚「只认 0 和 1，其余都当全部」，就不必依赖下游的兜底。
     */
    const only = wordEnabledFilter.value
    words.value = await getAiWordList(only === 0 || only === 1 ? only : null)
    wordsFailed.value = false
  } catch (e) {
    words.value = []
    wordsFailed.value = true
  } finally {
    wordsLoading.value = false
  }
}

async function loadAllWords() {
  await Promise.all([loadWords(), loadStatus()])
}

/* ---- 新增 / 编辑 ---- */

const wordDialogVisible = ref(false)
/** `null` = 新增，非空 = 编辑的那一行 */
const wordEditing = ref(null)
const wordSaving = ref(false)
const wordForm = reactive({ word: '', level: 1, category: '', enabled: 1, remark: '' })

function resetWordForm() {
  Object.assign(wordForm, { word: '', level: 1, category: '', enabled: 1, remark: '' })
}

function openCreateWord() {
  wordEditing.value = null
  resetWordForm()
  wordDialogVisible.value = true
}

function openEditWord(row) {
  wordEditing.value = row
  Object.assign(wordForm, {
    word: row.word,
    level: row.level,
    category: row.category || '',
    enabled: row.enabled,
    remark: row.remark || '',
  })
  wordDialogVisible.value = true
}

/**
 * ★★★ 提交之后那句提示是全页最要紧的一句话。
 *
 * 保存接口**刻意不自动重载**（见 api/ai.js 的论证），所以此刻快照还是旧的 ——
 * 说成「已生效」就是一句**会造成实际后果的假话**：管理员以为新词在拦内容了，
 * 而它一条都没拦，且界面上一切正常（词在列表里、开关是开的、状态「就绪」）。
 * ★ 所以「已保存」和「已生效」必须是两句不同的话，中间隔着一次「立即重载」。
 */
async function submitWord() {
  if (!wordForm.word.trim()) {
    ElMessage.warning('请填写违禁词')
    return
  }
  wordSaving.value = true
  try {
    if (wordEditing.value) {
      await updateAiWord(wordEditing.value.id, { ...wordForm, word: wordForm.word.trim() })
    } else {
      await createAiWord({ ...wordForm, word: wordForm.word.trim() })
    }
    wordDialogVisible.value = false
    await loadAllWords()
    ElMessage.success('已保存到词库。★ 点「立即重载」后才会生效')
  } catch (e) {
    /*
     * ★ 不关对话框：5008（重复）/ 400（校验失败）都应该让管理员改完再提交。
     *   关掉的话，刚填的那一屏连同那句话一起没了，他还得重新输一遍。
     */
  } finally {
    wordSaving.value = false
  }
}

/**
 * 列表页的启用开关。
 *
 * ★★ 模板那边用的是 `:model-value` 单向绑定 + `@change`，**不是 `v-model`**：
 *   `v-model` 会让开关在请求发出【之前】就翻过去，一旦请求失败，
 *   界面就停在了一个和库里**相反**的状态上 —— 管理员会以为那个词已经停用了。
 *   单向绑定下 `row.enabled` 只在请求成功后才被改，失败时界面原样不动，
 *   而「原样不动」正是库里的真实状态。**不需要手写回滚代码**，那是这个写法白送的。
 */
async function toggleWord(row) {
  const target = row.enabled === 1 ? 0 : 1
  try {
    // ★ 只发 id + 目标值，不回传整行（回传整行会静默覆盖别人在这期间的修改）
    await updateAiWordEnabled(row.id, target)
    row.enabled = target
    ElMessage.success(
      target === 1 ? '已启用。★ 点「立即重载」后才会生效' : '已停用。★ 点「立即重载」后才会生效',
    )
    await loadStatus()
  } catch (e) {
    // ★ 这里【刻意什么都不做】—— 见上面的注释
  }
}
</script>

<template>
  <div>
    <el-tabs v-model="activeTab" class="ai-tabs">
      <!-- ==================== tab 1：模型配置 ==================== -->
      <el-tab-pane label="模型配置" name="config">
        <!--
          ★★ provider 横幅：只有 effectiveProvider 才是「现在跑的是谁」。
             mock 下填 Key 完全无效，而这件事必须被看见，否则管理员会反复重填。
        -->
        <el-alert
          v-if="config && !isRealModel"
          type="warning"
          show-icon
          :closable="false"
          class="provider-alert"
          title="当前后端配置为 mock（演示模式），此处填写的 API Key 不会生效"
        >
          <template #default>
            生效的 provider 来自后端启动配置 <code>novel.ai.provider</code>，
            改它需要改配置并重启服务。<br />
            现在作者端看到的是<b>演示数据</b>；配好之后重启，作者端那个「演示模式」标签会自己消失。
          </template>
        </el-alert>

        <el-alert
          v-else-if="config"
          type="success"
          show-icon
          :closable="false"
          class="provider-alert"
          title="当前后端配置为 deepseek（真实模型）—— 生成会产生真实费用"
        />

        <el-card v-loading="configLoading" shadow="never">
          <!-- 配置拉取失败：不要渲染一个空表单，那看起来像「配置被清空了」 -->
          <el-empty v-if="!configLoading && !config" description="暂时加载不到 AI 配置，请稍后重试">
            <el-button @click="loadConfig">重试</el-button>
          </el-empty>

          <el-form v-else-if="config" label-width="150px" class="config-form">
            <!-- ---------- 只读区 ---------- -->
            <el-divider content-position="left">当前状态</el-divider>

            <el-form-item label="生效的实现">
              <el-tag :type="isRealModel ? 'success' : 'info'" size="small">
                {{ config.effectiveProvider || '—' }}
              </el-tag>
              <span class="hint-inline">
                {{ isRealModel ? '真实模型，按 token 计费' : '演示数据，不调用任何模型' }}
              </span>
            </el-form-item>

            <el-form-item label="记录的服务商">
              <span>{{ config.provider || '—' }}</span>
              <span class="hint-inline">
                这是配置表里那一列，只读 —— 它<b>不决定</b>哪个实现在跑（上面那行才决定）
              </span>
            </el-form-item>

            <!--
              ★★★ 当前 Key：**文本，不是输入框**。
                装着一个掩码的输入框会让人以为「编辑它就能改 Key」，
                而那不是真的（掩码不是 Key，且后端只接受完整的新 Key）。
            -->
            <el-form-item label="当前 API Key">
              <span :class="{ 'key-warning': apiKeyIsWarning }">{{ apiKeyText }}</span>
              <span v-if="!apiKeyIsWarning && config.apiKeyConfigured" class="hint-inline">
                只显示末四位 —— 它够你确认「我填的是哪一把」，又不足以被拿去用
              </span>
            </el-form-item>

            <!-- ---------- 可写区 ---------- -->
            <el-divider content-position="left">修改配置</el-divider>

            <el-form-item label="OpenAI 兼容端点">
              <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
              <span class="hint-inline">必须以 http:// 或 https:// 开头</span>
            </el-form-item>

            <el-form-item label="模型名">
              <el-input v-model="form.model" placeholder="deepseek-chat" />
            </el-form-item>

            <!--
              ★ 新 Key 的输入框：**永远是空的**（保存成功后也清空，且不回显）。
                这不是省事，是这一页的安全前提 —— 见文件头。
            -->
            <el-form-item label="设置新 API Key">
              <el-input
                v-model="newApiKey"
                type="password"
                show-password
                autocomplete="new-password"
                :disabled="clearApiKey"
                placeholder="留空 = 保持当前 Key 不变"
              />
              <span class="hint-inline">
                <b>留空不会清掉现有的 Key。</b>要清空请勾下面那个框 ——
                这个区分很重要：你只想改温度时顺手保存，不该把 Key 弄丢。
              </span>
            </el-form-item>

            <el-form-item label=" ">
              <el-checkbox v-model="clearApiKey" :disabled="!!newApiKey.trim()">
                清空已保存的 API Key（清空后真实调用会失败，直到重新录入）
              </el-checkbox>
            </el-form-item>

            <el-form-item label="温度">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" />
              <span class="hint-inline">0~2。越高越发散（协议规定，不是列宽决定的）</span>
            </el-form-item>

            <el-form-item label="单次最大 token">
              <el-input-number v-model="form.maxTokens" :min="1" :max="8192" :step="256" />
              <span class="hint-inline">上限 8192 —— 这是<b>直接拦住花钱</b>的那道闸</span>
            </el-form-item>

            <el-form-item label="每人每日调用上限">
              <el-input-number v-model="form.dailyLimit" :min="1" :max="100000" :step="10" />
              <span class="hint-inline">
                想「谁都不许用」请关下面的开关，而不是把这里填 0 ——
                后者的提示会指向一个完全不同的原因
              </span>
            </el-form-item>

            <el-form-item label="启用 AI 功能">
              <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
              <span class="hint-inline">
                关闭时作者端的生成请求会收到「AI 功能未启用」。Key / 模板 / 配额都原样留着
              </span>
            </el-form-item>

            <el-form-item label=" ">
              <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
              <el-button :disabled="saving" @click="loadConfig">放弃修改</el-button>
              <span v-if="config.updateTime" class="hint-inline">
                最后更新：{{ formatDateTime(config.updateTime) || '—' }}
              </span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- ==================== tab 2：提示词模板 ==================== -->
      <el-tab-pane label="提示词模板" name="prompt">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="provider-alert"
          title="模板决定模型【实际收到】什么 —— 作者端只发指令与素材，作品名 / 大纲 / 前情摘要由服务端按这里的模板拼进去"
        >
          <template #default>
            改完模板<b>立刻生效</b>（本系统不做模板缓存），作者下一次点生成用的就是新模板。<br />
            写错的占位符在<b>保存时就会被拒绝</b>并指出是哪一个 —— 不会等到作者点按钮那一刻才炸。
          </template>
        </el-alert>

        <el-card shadow="never">
          <div class="prompt-toolbar">
            <el-select
              v-model="promptScene"
              placeholder="全部场景"
              clearable
              class="prompt-scene-select"
              @change="loadPrompts"
            >
              <!-- ★ 跳过 SCENES 的第一项（「全部场景」，值是 null）—— 它只用于用量筛选 -->
              <el-option v-for="s in SCENES.slice(1)" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-button type="primary" @click="openCreate">新增模板</el-button>
            <el-button :disabled="promptLoading" @click="loadPrompts">刷新</el-button>
            <span class="hint-inline">列表包含<b>已停用</b>的模板 —— 停用不是删除，改得回来</span>
          </div>

          <!--
            ★★ 「拿不到」必须和「没有模板」分开显示。
               把这一支写成 el-empty，管理员会以为模板被谁删了，而真实原因是后端挂了。
          -->
          <el-alert
            v-if="promptFailed"
            type="error"
            show-icon
            :closable="false"
            title="暂时拿不到模板列表（这不等于「没有模板」）"
          >
            <template #default>后端没有返回数据。下面这张表现在是空的，但它不作数。</template>
          </el-alert>

          <el-table v-else v-loading="promptLoading" :data="promptList" border stripe>
            <el-table-column label="场景" width="110">
              <template #default="{ row }">{{ sceneLabel(row.scene) }}</template>
            </el-table-column>
            <el-table-column prop="name" label="模板名" min-width="150" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
                  {{ row.enabled === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="70" />
            <el-table-column label="最后修改" width="170">
              <template #default="{ row }">{{ formatDateTime(row.updateTime) || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPreview(row)">预览</el-button>
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link :type="row.enabled === 1 ? 'warning' : 'success'" @click="toggleEnabled(row)">
                  {{ row.enabled === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-if="!promptFailed && !promptLoading && !promptList.length" description="这个筛选下还没有模板" />
        </el-card>

        <!-- ---------- 新增 / 编辑 ---------- -->
        <el-dialog
          v-model="editVisible"
          :title="editId ? '编辑模板' : '新增模板'"
          width="820px"
          top="6vh"
        >
          <el-form label-width="120px">
            <el-form-item label="场景">
              <!--
                ★ 新增时可选，编辑时锁定：允许改场景的后果是「该场景原本那条模板
                  没被替换，只是多了一条」，而旧那条仍然启用 —— 于是「我明明改成大纲了，
                  作者点续写还是老样子」。要换场景请停用旧的、再新建一条。
              -->
              <el-select v-model="editForm.scene" :disabled="!!editId" class="prompt-scene-select">
                <el-option v-for="s in SCENES.slice(1)" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
              <span v-if="editId" class="hint-inline">场景不可改：换场景请停用这条、再新建一条</span>
            </el-form-item>

            <el-form-item label="模板名">
              <el-input v-model="editForm.name" placeholder="续写正文-默认" maxlength="50" show-word-limit />
              <span class="hint-inline">同一场景下不能重名（重名会被拒绝，不会静默覆盖）</span>
            </el-form-item>

            <el-form-item label="system 模板">
              <el-input
                v-model="editForm.systemPrompt"
                type="textarea"
                :rows="3"
                placeholder="可以留空 —— 留空时这次请求不带 system 消息"
              />
            </el-form-item>

            <el-form-item label="user 模板">
              <el-input
                v-model="editForm.userTemplate"
                type="textarea"
                :rows="10"
                placeholder="必须带 {{占位符}}，否则模型不知道该写什么"
              />
              <span class="hint-inline">
                <!--
                  ★★★ 这里必须用 v-pre，**不能**写成 <code>{{ '{{名字}}' }}</code>。
                     Vue 的模板编译器找插值的结束符时**不认识字符串字面量**：它扫到
                     那个字符串里的 `}}` 就当作插值结束，于是表达式变成 `'{{名字` ——
                     一个没闭合的字符串常量，**整个文件编译失败**（vite 直接 500）。
                     v-pre 让这一小段跳过编译、原样输出，是这里唯一干净的写法。
                -->
                占位符写成 <code v-pre>{{名字}}</code>。本场景有哪些变量、哪些必填 ——
                点一次<b>预览</b>就能看到（变量表由后端给出，这里不写死一份）
              </span>
            </el-form-item>

            <el-form-item label="启用">
              <el-switch v-model="editForm.enabled" :active-value="1" :inactive-value="0" />
              <span class="hint-inline">
                停用后该场景取不到这条模板；如果它是这个场景<b>唯一启用</b>的一条，作者点生成会收到 5005
              </span>
            </el-form-item>

            <el-form-item label="排序">
              <el-input-number v-model="editForm.sort" :min="0" :step="1" />
              <span class="hint-inline">
                越小越靠前。生成时取「启用的第一条」—— 和列表看到的顺序是<b>同一个排序</b>
              </span>
            </el-form-item>
          </el-form>

          <template #footer>
            <el-button :disabled="editSaving" @click="editVisible = false">取消</el-button>
            <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
          </template>
        </el-dialog>

        <!-- ---------- 预览（只读） ---------- -->
        <el-dialog v-model="previewVisible" title="预览渲染结果" width="900px" top="5vh">
          <el-alert
            type="info"
            show-icon
            :closable="false"
            title="只读：不调模型、不扣配额、不写任何表 —— 随便试"
          >
            <template #default>
              渲染的是<b>库里那一份</b>：编辑器里没保存的改动<b>不会</b>出现在这里，改完请先保存再预览。
            </template>
          </el-alert>

          <div class="preview-section">
            <div class="preview-head">
              <span>样本变量值</span>
              <el-button link type="primary" @click="resetPreviewVars">恢复默认样本</el-button>
            </div>
            <div v-for="(_, name) in previewVars" :key="name" class="preview-var-row">
              <el-input v-model="previewVars[name]" :placeholder="`没有值（那一行会被整行移除）`">
                <template #prepend>{{ name }}</template>
              </el-input>
              <el-tag v-if="varCountInTemplate(name) === 0" type="warning" size="small" class="preview-var-tag">
                模板里没写这个占位符
              </el-tag>
            </div>
          </div>

          <el-button type="primary" :loading="previewLoading" @click="renderPreview">渲染一次</el-button>

          <template v-if="previewResult">
            <el-descriptions :column="3" border size="small" class="preview-meta">
              <el-descriptions-item label="总长度">
                {{ previewResult.totalLength }} / {{ previewResult.maxLength }}
              </el-descriptions-item>
              <el-descriptions-item label="有值的变量">
                {{ (previewResult.usedVars || []).join('、') || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="被移除的变量">
                <template v-if="(previewResult.removedVars || []).length">
                  <el-tag v-for="n in previewResult.removedVars" :key="n" type="warning" size="small" class="preview-var-tag">
                    {{ n }}
                  </el-tag>
                </template>
                <span v-else class="none">无</span>
              </el-descriptions-item>
            </el-descriptions>

            <!--
              ★★★ 这一段是这一页最要紧的输出。见文件头：整行移除是**静默的**，
                 不显式说出来的话，管理员只会觉得「结果里少了点什么」。
            -->
            <el-alert
              v-if="(previewResult.removedVars || []).length"
              type="warning"
              show-icon
              :closable="false"
              class="provider-alert"
              title="上面那些变量的【那一行连同它的标签行被整行移除了】"
            >
              <template #default>
                这不是错误，也不会报错 —— 模型收到的那份提示词里<b>根本没有这几行</b>。<br />
                想让它们出现，给个样本值；如果生成时它们本该有值却没有，说明服务端没取到
                （比如这本书还没有前情摘要）。
              </template>
            </el-alert>

            <div class="preview-block">
              <div class="preview-head">system（可能为空）</div>
              <pre class="preview-pre">{{ previewResult.systemPrompt || '（空 —— 这次请求不会带 system 消息）' }}</pre>
            </div>
            <div class="preview-block">
              <div class="preview-head">user —— 这才是真正发给模型的那段</div>
              <pre class="preview-pre">{{ previewResult.userMessage }}</pre>
            </div>

            <el-divider content-position="left">本场景的变量表（由后端给出，页面不写死）</el-divider>
            <el-table :data="varTable" size="small" border>
              <el-table-column label="占位符" width="140">
                <!--
                  ★ 两个花括号交给 v-pre 原样输出，**不能**写成 {{ '{{' + row.name + '}}' }}：
                     那两个相邻的 `}}` 会被编译器当成插值的结束符，整个文件编译失败
                     （同上面那处 v-pre 的注释）。
                -->
                <template #default="{ row }"><code v-pre>{{</code>{{ row.name }}<code v-pre>}}</code></template>
              </el-table-column>
              <el-table-column label="档位" width="100">
                <template #default="{ row }">
                  <el-tag :type="reqTagType(row.req)" size="small">{{ reqLabel(row.req) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="模板里出现" width="110">
                <template #default="{ row }">
                  <span :class="{ none: row.count === 0 }">{{ row.count }} 次</span>
                </template>
              </el-table-column>
              <el-table-column label="说明">
                <template #default="{ row }">
                  <span v-if="row.req === 'REQUIRED'">没有值会让这次生成<b>失败</b>（400，并说清缺什么）</span>
                  <span v-else>没有值时那一行被整行移除</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== tab 3：用量统计 ==================== -->
      <el-tab-pane label="用量统计" name="usage">
        <!--
          ★★ 汇总失败 → 显示「暂时拿不到」，**绝不显示 0**。
             「今日 0 次调用」和「后端挂了」在视觉上必须能分开。
        -->
        <el-alert
          v-if="summaryFailed"
          type="error"
          show-icon
          :closable="false"
          class="provider-alert"
          title="用量汇总暂时拿不到（不是「今天没人用」）"
        >
          <template #default>
            <el-button link type="primary" @click="loadSummary">重试</el-button>
          </template>
        </el-alert>

        <el-row v-else :gutter="12" class="stat-row">
          <!-- 今日 -->
          <el-col :span="6">
            <el-card shadow="never" class="stat-card">
              <div class="stat-label">
                今日调用
                <span class="stat-day">{{ summary?.statDay || '' }}</span>
              </div>
              <div class="stat-value">
                <span v-if="summary">{{ fmtNum(summary.today.calls) }}</span>
                <span v-else class="none">—</span>
              </div>
              <div class="stat-sub">
                <template v-if="summary">
                  成功 {{ fmtNum(summary.today.successCalls) }} ·
                  失败 {{ fmtNum(summary.today.failedCalls) }} ·
                  {{ fmtNum(summary.today.distinctUsers) }} 人
                </template>
              </div>
            </el-card>
          </el-col>

          <!-- 今日 token -->
          <el-col :span="6">
            <el-card shadow="never" class="stat-card">
              <div class="stat-label">今日 token</div>
              <div class="stat-value">
                <span v-if="summary">{{ fmtNum(summary.today.totalTokens) }}</span>
                <span v-else class="none">—</span>
              </div>
              <!--
                ★★★ 这一行是这张卡片存在的全部意义。
                   没有它，「今日 3,240 token」会被读成一个完整的数，
                   而真相可能是「3,240 + 4 次不知道」——
                   偏小的方向恰好是「看起来更省钱」。
              -->
              <div v-if="summary && summary.today.untrackedTokenCalls > 0" class="stat-sub warn">
                其中 {{ fmtNum(summary.today.untrackedTokenCalls) }} 次未统计到用量
              </div>
              <div v-else-if="summary" class="stat-sub">全部调用都有用量数据</div>
            </el-card>
          </el-col>

          <!-- 累计 -->
          <el-col :span="6">
            <el-card shadow="never" class="stat-card">
              <div class="stat-label">累计调用</div>
              <div class="stat-value">
                <span v-if="summary">{{ fmtNum(summary.total.calls) }}</span>
                <span v-else class="none">—</span>
              </div>
              <div class="stat-sub">
                <template v-if="summary">
                  成功 {{ fmtNum(summary.total.successCalls) }} ·
                  失败 {{ fmtNum(summary.total.failedCalls) }} ·
                  {{ fmtNum(summary.total.distinctUsers) }} 人
                </template>
              </div>
            </el-card>
          </el-col>

          <!-- 累计 token -->
          <el-col :span="6">
            <el-card shadow="never" class="stat-card">
              <div class="stat-label">累计 token</div>
              <div class="stat-value">
                <span v-if="summary">{{ fmtNum(summary.total.totalTokens) }}</span>
                <span v-else class="none">—</span>
              </div>
              <div v-if="summary && summary.total.untrackedTokenCalls > 0" class="stat-sub warn">
                其中 {{ fmtNum(summary.total.untrackedTokenCalls) }} 次未统计到用量
              </div>
              <div v-else-if="summary" class="stat-sub">全部调用都有用量数据</div>
            </el-card>
          </el-col>
        </el-row>

        <!-- ==================== 筛选栏 ==================== -->
        <el-card shadow="never" class="filter-card">
          <div class="filter">
            <el-select v-model="filter.scene" style="width: 140px" @change="search">
              <el-option v-for="o in SCENES" :key="String(o.value)" :label="o.label" :value="o.value" />
            </el-select>

            <el-select v-model="filter.success" style="width: 130px" @change="search">
              <el-option
                v-for="o in SUCCESS_OPTIONS"
                :key="String(o.value)"
                :label="o.label"
                :value="o.value"
              />
            </el-select>

            <el-date-picker
              v-model="filter.range"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
              @change="search"
            />

            <el-button @click="reset">重置</el-button>
            <el-button @click="refreshAll">刷新</el-button>

            <!--
              ★ 调用人筛选做成可见、可关闭的标签（同审核日志页）：
                藏起来的话，用户点完「只看此人」再换别的条件，
                会忘记还挂着一个看不见的筛选，然后觉得用量「少了很多」。
            -->
            <el-tag v-if="filter.userId" closable type="warning" effect="plain" @close="clearUser">
              只看：{{ filter.userName || `用户 #${filter.userId}` }} 的调用
            </el-tag>
          </div>

          <p class="hint">
            这一页是<b>只读</b>的流水账，界面上不提供任何修改和删除 —— 能改的账不能用来对账。
            点击「调用人」可以筛出某个人烧掉的全部调用。
          </p>
        </el-card>

        <!-- ==================== 列表 ==================== -->
        <el-card shadow="never">
          <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                {{ formatDateTime(row.createTime) || '—' }}
              </template>
            </el-table-column>

            <el-table-column label="场景" width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.sceneDesc || '—' }}</el-tag>
              </template>
            </el-table-column>

            <el-table-column label="模型" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.model || '—' }}</template>
            </el-table-column>

            <el-table-column label="调用人" width="130" show-overflow-tooltip>
              <template #default="{ row }">
                <el-button v-if="row.userId" link type="primary" @click="filterByUser(row)">
                  {{ userLabel(row) }}
                </el-button>
                <span v-else>—</span>
              </template>
            </el-table-column>

            <!--
              ★ S6 起作者端会发 novelId / chapterId，这一列才有数据可显示。
                ★★ 老数据一律是 `—`，那**不是**「没关联作品」，而是「E1 之前的调用
                   本来就没记」—— 两者在这一列上分不出来，所以不给它加 tooltip 断言。
                只给裸 id：管理端没有作品/章节的详情页可跳，编一个跳不过去的链接更糟。
            -->
            <el-table-column label="作品/章节" width="120" align="right">
              <template #default="{ row }">
                <span v-if="row.novelId" class="none">#{{ row.novelId }}</span>
                <span v-else class="none">—</span>
                <span v-if="row.chapterId" class="none"> / #{{ row.chapterId }}</span>
              </template>
            </el-table-column>

            <!--
              ★★ token 这一列的三种显示见 tokenText() 的注释。
                 其中「未统计」和「—」都不能被换成 0。
            -->
            <el-table-column label="token" width="110" align="right">
              <template #default="{ row }">
                <el-tooltip :content="tokenTip(row)" placement="top">
                  <span :class="{ warn: row.tokenUntracked, none: !row.success }">
                    {{ tokenText(row) }}
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="耗时" width="90" align="right">
              <template #default="{ row }">
                <span v-if="row.durationMs == null" class="none">—</span>
                <span v-else>{{ (row.durationMs / 1000).toFixed(1) }}s</span>
              </template>
            </el-table-column>

            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.success ? 'success' : 'danger'" size="small">
                  {{ row.success ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>

            <!-- 失败原因：来自后端白名单的人话，可能是「请检查 base_url 是否带 /v1」这种可行动的话 -->
            <el-table-column label="说明" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.success" class="none">—</span>
                <span v-else>{{ errorText(row) }}</span>
              </template>
            </el-table-column>

            <template #empty>
              <!-- ★ 空态与失败态互斥，由 usePagedList 的构造保证（M5） -->
              <el-empty v-if="paged.isEmpty.value" description="没有符合条件的调用记录" :image-size="80" />
              <el-empty v-else-if="paged.failed.value" description="暂时加载不到调用记录" :image-size="80">
                <el-button @click="paged.reload">重试</el-button>
              </el-empty>
            </template>
          </el-table>

          <el-pagination
            v-if="paged.total.value > 0"
            class="pager"
            :current-page="paged.page.value"
            :page-size="paged.size.value"
            :total="paged.total.value"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="paged.changePage"
            @size-change="paged.changeSize"
          />
        </el-card>
      </el-tab-pane>

      <!-- ==================== tab 4：违禁词库 ==================== -->
      <el-tab-pane label="词库管理" name="word">
        <!--
          ★★★ 这个 tab 存在的理由只有一个：让「库里改了」和「生效了」在屏幕上分得开。
             所以状态横幅在最上面、词表在下面 —— 顺序反了这页就白做了。
        -->

        <el-alert
          v-if="statusFailed"
          type="error"
          show-icon
          :closable="false"
          class="provider-alert"
          title="暂时拿不到词库状态（这不等于「词库是空的」）"
        >
          <template #default>下面那张状态卡现在不作数，请先刷新。</template>
        </el-alert>

        <!-- ★★★ 快照从未建起来：此刻【所有】审核都被拒。全页最重的一句话 -->
        <el-alert
          v-else-if="status && !status.loaded"
          type="error"
          show-icon
          :closable="false"
          class="provider-alert"
          title="词库快照从未加载成功 —— 此刻所有 AI 审核都会被【拒绝】"
        >
          <template #default>
            这是<b>刻意</b>的：词库是审核的输入，它不可用时系统宁可拒审，也绝不「当成没有违禁词」放过去
            —— 那等于把「我没在筛」说成「这段文字是干净的」。<br />
            <span v-if="status.lastError" class="key-warning">上次失败原因：{{ status.lastError }}</span>
            <span v-else>常见原因是数据库连不上。修好之后点「立即重载」。</span>
          </template>
        </el-alert>

        <!-- ★★ 加载成功，但一条启用的词都没有 -->
        <el-alert
          v-else-if="status && !status.ready"
          type="warning"
          show-icon
          :closable="false"
          class="provider-alert"
          title="词库里没有任何【启用】的词 —— 此刻所有 AI 审核都会被【拒绝】"
        >
          <template #default>
            这不是故障，是还没录词：一条词都没有时，「审核通过」和「没在审核」是同一件事，
            所以系统同样拒审。请在下面添加词条，然后点「立即重载」。
          </template>
        </el-alert>

        <!-- ★★ 有改动尚未生效 -->
        <el-alert
          v-else-if="wordLibStale"
          type="warning"
          show-icon
          :closable="false"
          class="provider-alert"
          title="词库有改动【尚未生效】—— 审核此刻用的还是上一份快照"
        >
          <template #default>
            库里启用 {{ dbNum(status.enabledRows) }} 条，快照里 {{ status.wordCount }} 条。
            点「立即重载」让改动生效；不点的话最多等 10 分钟（定时兜底）。
          </template>
        </el-alert>

        <el-alert
          v-else-if="status"
          type="success"
          show-icon
          :closable="false"
          class="provider-alert"
          title="词库快照就绪：库里的启用词与快照一致"
        />

        <!-- ---------- 快照状态卡 ---------- -->
        <el-card shadow="never" class="filter-card">
          <div class="word-status-head">
            <span class="word-status-title">审核此刻依据的快照</span>
            <div>
              <el-button :disabled="statusLoading" @click="loadAllWords">刷新</el-button>
              <el-button type="primary" :loading="reloading" @click="doReload">立即重载</el-button>
            </div>
          </div>

          <el-descriptions v-if="status" :column="3" border size="small">
            <el-descriptions-item label="快照词条数">{{ status.wordCount }}</el-descriptions-item>
            <el-descriptions-item label="快照版本">v{{ status.version }}</el-descriptions-item>
            <el-descriptions-item label="快照加载时间">
              {{ formatDateTime(status.loadedAt) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="上次重载尝试">
              {{ formatDateTime(status.lastReloadAt) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="库里启用">{{ dbNum(status.enabledRows) }}</el-descriptions-item>
            <el-descriptions-item label="库里总数">{{ dbNum(status.totalRows) }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="状态不可用" :image-size="60" />

          <p class="hint">
            ★ 「快照词条数」和「库里启用」<b>不相等</b>就说明有人改了词还没重载 ——
            这两个数刻意<b>不合并</b>显示，就是为了让这件事露出来。
          </p>
          <p v-if="status && status.dbUnavailable" class="hint">
            ★ 库里那两个数是 <b>—</b> 而不是 0：这一次没读到数据库。<b>「读不到」不等于「一条都没有」</b>。
          </p>
          <p v-if="status && status.lastError" class="hint">
            ★ 上次重载失败（{{ formatDateTime(status.lastErrorAt) }}）：
            <span class="key-warning">{{ status.lastError }}</span>
            —— 失败时<b>不会清掉旧快照</b>，所以现在用的是更旧的那一份，<b>不是空的</b>。
          </p>
        </el-card>

        <!-- ---------- 试扫 ---------- -->
        <el-card shadow="never" class="filter-card">
          <div class="word-status-head">
            <span class="word-status-title">试扫一段文本</span>
            <span class="hint-inline">只读：不写表、不扣配额、不调模型</span>
          </div>

          <el-input
            v-model="scanText"
            type="textarea"
            :rows="3"
            maxlength="20000"
            show-word-limit
            placeholder="粘贴一段正文或评论，看看会不会命中"
          />

          <div class="word-scan-actions">
            <el-button type="primary" :loading="scanning" @click="doScan">扫一次</el-button>
            <span class="hint-inline">
              它扫的是<b>快照</b>不是库：刚保存的词重载之前扫不到 —— 这不是 bug
            </span>
          </div>

          <!--
            ★★ 「没命中」与「没扫成」必须分开。把这一支写成「未命中任何词」，
               就是把一次故障渲染成了一句「这段文字是干净的」—— 最坏的那种假话。
          -->
          <el-alert
            v-if="scanFailed"
            type="error"
            show-icon
            :closable="false"
            class="preview-section"
            title="这次没扫成（不代表没命中）"
          >
            <template #default>
              词库不可用，后端拒审了 —— 详情见上方状态卡。
              ★ 下面没有结果，也<b>不要</b>把空白读成「干净」。
            </template>
          </el-alert>

          <template v-else-if="scanResult">
            <el-descriptions :column="3" border size="small" class="preview-meta">
              <el-descriptions-item label="命中词数">{{ scanResult.hitCount }}</el-descriptions-item>
              <el-descriptions-item label="最高等级">
                <el-tag
                  v-if="scanResult.maxLevel > 0"
                  :type="levelTagType(scanResult.maxLevel)"
                  size="small"
                >
                  {{ scanResult.maxLevel }} 级
                </el-tag>
                <span v-else class="none">无</span>
              </el-descriptions-item>
              <el-descriptions-item label="依据的快照">
                共 {{ scanResult.wordCount }} 条词 · v{{ scanResult.version }}
              </el-descriptions-item>
            </el-descriptions>

            <el-alert
              v-if="scanResult.hitCount === 0"
              type="success"
              show-icon
              :closable="false"
              title="未命中任何词"
            >
              <template #default>
                依据的是<b>上面那一份快照</b>（共 {{ scanResult.wordCount }} 条词）。
                ★ 词库为空时后端会直接报错、不会给你一个「命中 0」——
                所以看到这一条，就说明快照确实在跑。
              </template>
            </el-alert>

            <el-table v-else :data="scanResult.hits" border stripe class="preview-section">
              <el-table-column prop="word" label="命中的词" min-width="160" show-overflow-tooltip />
              <el-table-column label="等级" width="90">
                <template #default="{ row }">
                  <el-tag :type="levelTagType(row.level)" size="small">{{ row.level }} 级</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分类" width="140">
                <template #default="{ row }">
                  <span v-if="row.category">{{ row.category }}</span>
                  <span v-else class="none">—</span>
                </template>
              </el-table-column>
              <el-table-column label="定位" min-width="200">
                <template #default>
                  <!--
                    ★ 结果里**没有**位置字段（后端刻意的）：位置对审核动作没有任何影响，
                       而给一个偏移量会让前端高亮对错地方 —— 用词本身去搜更靠谱。
                  -->
                  <span class="none">在正文里搜这个词即可定位</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>

        <!-- ---------- 词库列表 ---------- -->
        <el-card shadow="never">
          <div class="word-status-head">
            <span class="word-status-title">词库</span>
            <div>
              <el-select
                v-model="wordEnabledFilter"
                placeholder="全部"
                clearable
                class="word-filter-select"
                @change="loadWords"
              >
                <el-option label="只看启用" :value="1" />
                <el-option label="只看停用" :value="0" />
              </el-select>
              <el-button type="primary" @click="openCreateWord">新增词条</el-button>
              <el-button :disabled="wordsLoading" @click="loadWords">刷新</el-button>
            </div>
          </div>

          <!--
            ★★ 「拿不到」和「没有词」分开显示。
               写成 el-empty，管理员会以为词被谁删了，而真实原因是后端挂了。
          -->
          <el-alert
            v-if="wordsFailed"
            type="error"
            show-icon
            :closable="false"
            title="暂时拿不到词库列表（这不等于「没有词」）"
          >
            <template #default>后端没有返回数据。下面这张表现在是空的，但它不作数。</template>
          </el-alert>

          <el-table v-else v-loading="wordsLoading" :data="words" border stripe>
            <el-table-column prop="word" label="违禁词" min-width="150" show-overflow-tooltip />
            <el-table-column label="等级" width="90">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" size="small">
                  {{ row.levelLabel || row.level }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="130">
              <template #default="{ row }">
                <span v-if="row.category">{{ row.category }}</span>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.remark">{{ row.remark }}</span>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="最后修改" width="170">
              <template #default="{ row }">{{ formatDateTime(row.updateTime) || '—' }}</template>
            </el-table-column>
            <el-table-column label="启用" width="90">
              <template #default="{ row }">
                <!--
                  ★★ 单向绑定 + @change，**不是 v-model**：
                     v-model 会在请求发出之前就把开关翻过去，失败了界面就停在
                     一个和库里相反的状态上。见 toggleWord 的注释。
                -->
                <el-switch
                  :model-value="row.enabled"
                  :active-value="1"
                  :inactive-value="0"
                  @change="toggleWord(row)"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditWord(row)">编辑</el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty description="还没有词条 —— ★ 此时审核会拒审而不是放行" :image-size="80" />
            </template>
          </el-table>

          <p class="hint">
            ★ 改词（新增 / 编辑 / 开关）<b>只改库</b>，要点一次「立即重载」才会命中；
            不点的话最多等 10 分钟。<b>「保存成功」不等于「已生效」</b>。
          </p>
          <p class="hint">
            ★ 停用不是删除：词条留在库里、改得回来，也不会出现在审核里。
          </p>
        </el-card>

        <!-- ---------- 新增 / 编辑 ---------- -->
        <el-dialog
          v-model="wordDialogVisible"
          :title="wordEditing ? '编辑违禁词' : '新增违禁词'"
          width="620px"
          top="8vh"
        >
          <el-form label-width="100px">
            <el-form-item label="违禁词">
              <el-input
                v-model="wordForm.word"
                placeholder="如：占位敏感词 甲"
                maxlength="64"
                show-word-limit
              />
              <span class="hint-inline">
                两端空白会被去掉。★ 词<b>中间</b>不要夹空格 —— 那样的词永远不可能被正文命中
              </span>
            </el-form-item>

            <el-form-item label="等级">
              <!--
                ★ 只有 1~3，没有「无风险(0)」：一条「无风险的违禁词」是自相矛盾的。
                   命中的最高等级会作为预填值交给 AI 复核层，所以这个数直接决定风险档位。
              -->
              <el-select v-model="wordForm.level" class="word-level-select">
                <el-option v-for="l in LEVELS" :key="l.value" :label="l.label" :value="l.value" />
              </el-select>
              <span class="hint-inline">命中的最高等级会成为这条内容的<b>初判</b>风险等级</span>
            </el-form-item>

            <el-form-item label="分类">
              <el-input v-model="wordForm.category" placeholder="如：涉政 / 广告 / 辱骂" maxlength="32" />
              <span class="hint-inline">只用于筛选与展示，不参与判定</span>
            </el-form-item>

            <el-form-item label="备注">
              <el-input v-model="wordForm.remark" placeholder="为什么加这条、判据是什么" maxlength="255" />
            </el-form-item>

            <el-form-item label="启用">
              <el-switch v-model="wordForm.enabled" :active-value="1" :inactive-value="0" />
              <span class="hint-inline">
                停用的词<b>不参与</b>扫描，但仍然留在库里（改得回来）
              </span>
            </el-form-item>
          </el-form>

          <template #footer>
            <el-button :disabled="wordSaving" @click="wordDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="wordSaving" @click="submitWord">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.ai-tabs {
  padding: 0 4px;
}

.provider-alert {
  margin-bottom: 12px;
}

.config-form {
  max-width: 720px;
}

/* 只读字段旁边的小字说明：灰色、小一号，和值本身分开 */
.hint-inline {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

/* Key 有问题（解不开）时那句话要显眼 —— 它同时是一个可行动的修复指引 */
.key-warning {
  color: #e6a23c;
}

.stat-row {
  margin-bottom: 12px;
}

.stat-card {
  text-align: center;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-day {
  font-size: 12px;
  color: #c0c4cc;
  margin-left: 4px;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.5;
  color: #303133;
}

.stat-sub {
  font-size: 12px;
  color: #909399;
  min-height: 18px;
}

/* ★ 「有 N 次未统计」用告警色：它是这一页唯一会让人误读总数的东西 */
.warn {
  color: #e6a23c;
}

.filter-card {
  margin-bottom: 12px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

/* 「不适用」的浅灰：和真正的 0 分开 */
.none {
  color: #c0c4cc;
}

/* ---------- tab 2：提示词模板 ---------- */

.prompt-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.prompt-scene-select {
  width: 170px;
}

.preview-section {
  margin: 12px 0;
}

.preview-head {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.preview-var-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

/* 变量输入框占满整行，那个「模板里没写这个占位符」的标签跟在后面不换行 */
.preview-var-row .el-input {
  flex: 1;
}

.preview-var-tag {
  margin-right: 4px;
  flex: none;
}

.preview-meta {
  margin: 14px 0 10px;
}

.preview-block {
  margin-top: 12px;
}

/*
 * 渲染结果用 <pre> 而不是普通文本：提示词里的换行是【语义】——
 * 一行被整行移除、标签行跟着消失，这些在折叠空白的渲染下全都看不出来。
 */
.preview-pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 260px;
  overflow: auto;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: #303133;
}

/* ---------- tab 4：违禁词库 ---------- */

/* 卡片标题行：左边一句话、右边一组按钮。和 prompt-toolbar 分开是刻意的 ——
   那个是「筛选 + 操作」的工具栏，这个是「标题 + 操作」，右对齐方式不同 */
.word-status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.word-status-title {
  font-size: 14px;
  font-weight: 600;
  color: #606266;
}

.word-scan-actions {
  margin: 10px 0 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.word-level-select {
  width: 120px;
}

.word-filter-select {
  width: 130px;
  margin-right: 10px;
}
</style>
