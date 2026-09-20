<script setup>
/**
 * AI 写作助手面板 —— 四个动作按钮 + 流式输出 + 采纳/丢弃。
 *
 * ============================================================
 * ★★★ 这个面板的产物【绝不直接入库】（大纲 §8.1 边界 1）
 * ============================================================
 * 点按钮只会走一条路：发请求 → 逐字显示 → **停在这里**。
 * 想让它落到正文 / 树 / 笔记上，必须再点一次「采纳」。
 *
 * 这条约束是刻意的，理由是 AI 会写错 —— 而且错得很像对的。
 * 自动入库的话，作者一回神儿发现正文章节里多了一段不是自己写的
 * 文字，而他已经记不清是什么时候进去的。
 * `outline` 场景尤其危险：它会**建出一堆节点**，删起来远比插进去麻烦。
 *
 * ============================================================
 * ★★ 它和「流」的分工：面板管状态，utils/sse.js 管协议
 * ============================================================
 * 这个文件里**没有一行 fetch、没有一行解帧** —— 那些全在
 * `utils/sse.js` 里（那边的文件头记着三条在真实字节上验出来的解帧规则，
 * 尤其是「多个 `data:` 行必须用 `\n` 拼」那条，改动那里之前先读它）。
 *
 * 这里负责的是：按钮什么时候能点、输出显示在哪、中断按钮长什么样、
 * 什么情况下才允许「采纳」。
 *
 * ============================================================
 * ★★ 请求由调用方拼（`gather` prop），不在这里拼
 * ============================================================
 * 「续写」的上下文是**正文的末尾**、「润色」的上下文是**用户选中的那段**
 * —— 这两样东西都在 `ChapterEditor` 里，而这个面板碰不到它们。
 *
 * 把这些知识塞进面板就意味着面板要知道「当前是哪个分支、哪个编辑器
 * 挂载着」，那它就绑死在工作台上了，将来挪不动。
 * 反过来，让调用方提供一个 `gather(scene)` 函数，面板就只是
 * 「拿着 prompt 去请求」—— 它甚至不知道上下文是从哪来的。
 *
 * ★ `gather` 返回 `{error}` 而不是抛异常，是因为「没选中就点润色」
 *   是**用户操作顺序**问题，不是程序错误，提示语要由最懂上下文的人来写。
 */
import { ref, computed, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getQuota } from '@/api/ai'
import { streamPost } from '@/utils/sse'

const props = defineProps({
  /** 当前节点（OutlineNodeVO）。★ 只用来显示「正在给谁生成」，不参与请求拼接 */
  node: { type: Object, default: null },
  /**
   * 哪些动作可用。默认全开。
   * ★ 由调用方按当前分支裁剪 —— 比如「这一章还没建正文」时，
   *   「续写正文」和「润色段落」点下去也无处可放。
   *   事先禁用比让作者等它生成完 500 字再说「没地方放」好得多。
   */
  scenes: {
    type: Array,
    default: () => ['outline', 'write', 'inspire', 'polish'],
  },
  /**
   * 拼请求。`(sceneKey) => ({prompt, context}) | ({error})`
   * ★ 不传时退化成一个只有标题的 prompt（面板能被单独打开看效果）。
   */
  gather: { type: Function, default: null },
})

const emit = defineEmits([
  /**
   * 用户点了「采纳」。
   * `{scene, text, payload}` —— `payload` 只有 outline 场景有
   * （已经 `JSON.parse` 好的那棵树；让调用方自己 parse 会 parse 两遍，
   *  而且两边的错误处理还不一样）。
   */
  'adopt',
])

/* ==================== 四个动作 ==================== */

/**
 * ★ `path` 是**不含 `/api` 前缀**的路径 —— 前缀在 `utils/sse.js` 里拼，
 *   和 `utils/request.js` 的 `baseURL` 保持一致。
 *
 * ★ 场景由 **URL** 决定，请求体里**没有** scene 字段
 *   （`AiRequestDTO` 只有 `prompt` 和 `context`）——
 *   所以下面四个 `path` 就是四个场景本身，不要试图往 body 里塞 scene。
 */
const ALL_SCENES = [
  {
    key: 'outline',
    label: '生成本章大纲',
    path: '/ai/outline',
    tip: '按当前节点生成一棵子树，采纳后加进左侧大纲树',
  },
  {
    key: 'write',
    label: '续写正文',
    path: '/ai/write',
    tip: '接着正文末尾往下写，采纳后追加到正文',
  },
  {
    key: 'inspire',
    label: '提供灵感',
    path: '/ai/inspire',
    tip: '给三个剧情走向（顺接 / 反转 / 留白），采纳后写进剧情笔记',
  },
  {
    key: 'polish',
    label: '润色段落',
    path: '/ai/polish',
    tip: '改写正文里选中的那一段，采纳后替换选区',
  },
]

const visibleScenes = computed(() =>
  ALL_SCENES.filter((s) => props.scenes.includes(s.key)),
)

/* ==================== 状态 ==================== */

/** 正在跑的场景 key，`null` 表示空闲 */
const running = ref(null)
/** 本次生成的场景 key（跑完之后仍然保留，用来决定怎么渲染和采纳） */
const scene = ref(null)
/** 累积的输出。★ 是**增量拼起来**的，不是每次替换 */
const output = ref('')
/** 上次请求的结果对象（`utils/sse.js` 的返回值）。★ 决定「采纳」能不能点 */
const result = ref(null)
/** 面板上那条错误/提示。`''` 表示没有 */
const notice = ref('')

/** 额度。★ 只在挂载时和每次生成后拉一次 */
const quota = ref(null)

const controller = ref(null)
const streamRef = ref(null)

const isMock = computed(() => quota.value?.provider === 'mock')

const isRunning = computed(() => running.value !== null)

/** 跑完了、或者被中断了，总之有东西可看 */
const hasOutput = computed(() => output.value.length > 0)

/**
 * ★★ outline 场景的「采纳」还需要 JSON 能解析。
 *
 *   只有**完整**的 JSON 才有意义：半截的树采纳进去就是一串残缺节点。
 *   而「中断」和「超时」两种情况下 JSON 必然是半截的 ——
 *   `utils/sse.js` 会把它们标成 `aborted` / `incomplete`，
 *   `JSON.parse` 也会失败。两道保险，任何一道生效都不给采纳。
 *
 * ★ 正则那一行是 `JSON.parse` 的**性能护栏**，不是校验：
 *   流式跑完之后可能还剩几万字的文本，直接 parse 一个明显不是
 *   JSON 的东西会让主线程卡一下。先看首字符是不是 `{` 就够了。
 */
const outlineDraft = computed(() => {
  if (scene.value !== 'outline') return null
  if (result.value?.reason !== 'done') return null
  const text = output.value.trim()
  if (!text.startsWith('{')) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
})

/** 把生成的树压成一张可渲染的清单（带层级），用来预览 */
const previewRows = computed(() => {
  const rows = []
  const walk = (items, depth) => {
    for (const it of items || []) {
      rows.push({ depth, title: it?.title ?? '(无标题)', type: it?.type })
      walk(it?.children, depth + 1)
    }
  }
  walk(outlineDraft.value?.children, 0)
  return rows
})

/**
 * 能不能采纳。
 *
 * ★★ outline 场景**必须** JSON 完整（见上）；其它三个场景只要**有字**
 *    就行 —— **包括被中断的那些**。
 *
 *    中断之后还能采纳是刻意的：作者点中断最常见的原因不是「写得不对」，
 *    而是「够了，不用再写了」。此时把那半段丢掉等于白等。
 *    ★ 但 outline 不一样 —— 半棵树是没法用的，所以那边卡死。
 */
const canAdopt = computed(() => {
  if (!hasOutput.value) return false
  if (scene.value === 'outline') return outlineDraft.value !== null
  return true
})

/* ==================== 额度 ==================== */

async function loadQuota() {
  try {
    quota.value = await getQuota()
  } catch {
    // ★ 静默失败。额度拿不到不影响生成，「演示模式」标签不显示即可 ——
    //   为一条辅助信息弹一个红色报错是在打扰作者。
    quota.value = null
  }
}

/* ==================== 跑一次生成 ==================== */

async function run(s) {
  if (isRunning.value) return

  // ★ 先清干净上一次的结果。不清的话作者会看到新内容接在旧内容后面，
  //   而「采纳」会把两段一起采纳进去。
  output.value = ''
  result.value = null
  notice.value = ''
  scene.value = s.key

  const req = buildRequest(s.key)
  if (req.error) {
    // ★ 没通过前置检查（比如没选中要润色的段落）—— 连请求都不用发
    notice.value = req.error
    scene.value = null
    return
  }

  running.value = s.key
  const ctl = new AbortController()
  controller.value = ctl

  const r = await streamPost(s.path, req, {
    signal: ctl.signal,
    onChunk: (t) => {
      output.value += t
    },
  })

  running.value = null
  controller.value = null
  result.value = r

  if (!r.ok && r.reason !== 'aborted') notice.value = r.message

  // ★ 生成完再拉一次额度。mock 下它是个固定值（后端返回
  //   `new AiQuotaVO(dailyQuota, dailyQuota, provider)`），拉不出变化；
  //   但 E1 接了真实模型之后它会真的递减，那时候这里不用改。
  if (r.ok) loadQuota()
}

/**
 * 拼请求体。
 *
 * ★ 调用方的 `gather` 可能返回 `{error}` —— 那是「前置条件不满足」，
 *   见文件头。这里不做判断，原样透传。
 */
function buildRequest(key) {
  const fallback = { prompt: props.node?.title ? `围绕「${props.node.title}」生成` : '生成内容' }
  const req = props.gather?.(key) ?? fallback
  if (req.error) return req

  return {
    // ★ 后端 `AiRequestDTO`：prompt 必填且 max 2000，context 可空且 max 8000。
    //   超了会 400（提示语是「请求失败（HTTP 400）」这种没信息量的话），
    //   所以这里截断。调用方也截了一次 —— 两边都截是刻意的：
    //   调用方知道该截哪里（比如正文只取末尾），这里只知道不能超。
    prompt: String(req.prompt ?? '').slice(0, 2000),
    context: req.context ? String(req.context).slice(0, 8000) : undefined,
  }
}

/* ==================== 中断 / 采纳 / 丢弃 ==================== */

/**
 * 中断。
 *
 * ★ 只 abort，**不清空**已经收到的内容 —— 见 `canAdopt` 那段：
 *   中断之后还能采纳是刻意的。
 */
function stop() {
  controller.value?.abort()
}

/**
 * 采纳。
 *
 * ★ outline 场景多发一个 `payload`（已经 parse 好的树），
 *   因为调用方要的是**结构**不是字符串 —— 让调用方自己再 parse 一遍
 *   意味着两处 parse 的错误处理可能不一致，而其中一处会被忘掉。
 */
function adopt() {
  if (!canAdopt.value) return
  emit('adopt', {
    scene: scene.value,
    text: output.value,
    payload: scene.value === 'outline' ? outlineDraft.value : null,
  })
}

function discard() {
  output.value = ''
  result.value = null
  notice.value = ''
  scene.value = null
}

/* ==================== 输出区自动滚到底 ==================== */

/*
 * ★ 流的价值就是「边写边看」，不跟着滚的话作者得手动往下拖，
 *   而那正好抵消了流式的意义。
 * ★ `flush: 'post'`：等 DOM 长高之后再滚，否则滚的是旧高度。
 */
watch(
  output,
  () => {
    nextTick(() => {
      const el = streamRef.value
      if (el) el.scrollTop = el.scrollHeight
    })
  },
  { flush: 'post' },
)

/* ==================== 生命周期 ==================== */

/*
 * ★★★ 卸载时必须 abort。
 *
 *   工作台的右侧是 `<router-view :key="nodeId">` —— 切节点会把
 *   NodeWorkspace（连同这个面板）整个销毁重建。
 *   不 abort 的话那条连接还开着，服务端还在生成、还在往一个
 *   已经不存在的组件里写 —— 已经发生的现象是：
 *     · 控制台报「已卸载组件收到数据」
 *     · 如果作者切回来又点一次，两条流会交错显示（旧流在写同一个输出区）
 *
 * ★ 这也是「用 fetch 不用 EventSource」的第三个理由的实际兑现：
 *   EventSource 想中断只能 `close()`，而它没有 AbortSignal 那种
 *   和 `await` 配套的取消语义。
 */
onBeforeUnmount(() => {
  controller.value?.abort()
})

loadQuota()
</script>

<template>
  <div class="ai-panel">
    <div class="head">
      <span class="title">AI 助手</span>

      <!--
        ★★★ 「演示模式」标签 —— **不是可选项**。

          这个面板背后的内容现在全部由 `MockAiStreamSource` 本地生成，
          不是任何真实模型。一个看起来像 AI、实际是假的数据流，
          如果界面上没有任何标识，演示给面试官看的时候就是个诚信问题。

        ★ 判断依据是**接口返回的 `provider` 字段**，不是前端写死的常量
          —— E1 换成 DeepSeek 之后后端配置一改，这个标签自动消失，
          不需要有人记得来改前端。
      -->
      <el-popover v-if="isMock" placement="bottom" :width="300" trigger="click">
        <template #reference>
          <el-tag size="small" type="warning" effect="plain" class="mock-tag">演示模式</el-tag>
        </template>
        <div class="mock-explain">
          <p><b>这里的文字不是真实模型生成的。</b></p>
          <p>
            当前后端跑的是 <code>MockAiStreamSource</code>（本地假数据流），
            用来把「流式传输 → 中断 → 采纳落库」这条链路完整地跑通并测试。
          </p>
          <p>
            <b>流式管道本身是真的</b>：逐字推送、中断、错误处理、断流检测，
            换成真实模型后一行都不用改。
          </p>
        </div>
      </el-popover>
    </div>

    <!-- 四个动作 -->
    <div class="actions">
      <el-button
        v-for="s in visibleScenes"
        :key="s.key"
        size="small"
        :type="running === s.key ? 'primary' : 'default'"
        :loading="running === s.key"
        :disabled="isRunning && running !== s.key"
        @click="run(s)"
      >
        {{ s.label }}
      </el-button>
    </div>

    <div class="target">
      作用于：<b>{{ node?.title || '未选中节点' }}</b>
    </div>

    <!-- 前置条件不满足 / 生成失败 -->
    <el-alert
      v-if="notice"
      type="error"
      :closable="false"
      show-icon
      class="notice"
      :title="notice"
    />

    <!--
      ★ 被中断时**不当作错误**（`utils/sse.js` 把它标成 'aborted'）——
        是用户自己按的。给一句中性提示就够，红色报错会让他以为出问题了。
    -->
    <div v-else-if="result?.reason === 'aborted'" class="hint-line">
      已中断。下面的内容可以照常采纳。
    </div>

    <!-- 生成中 -->
    <div v-if="isRunning" class="running-line">
      <span class="dot" />
      正在生成…
      <el-button link type="danger" size="small" @click="stop">中断</el-button>
    </div>

    <!--
      ★ 输出区。
        `white-space: pre-wrap` 是必须的 —— 后端推来的散文里带
        `\n\n` 段落分隔（见 utils/sse.js 文件头第 4 条），
        不保留的话所有段落会糊成一行。
    -->
    <pre v-if="hasOutput" ref="streamRef" class="stream">{{ output }}</pre>

    <!--
      ★★ outline 场景生成完之后换一张脸：把 JSON 渲染成缩进的清单。
        对作者来说「一棵有五章的大纲」比「一坨带大括号的文本」有用得多，
        而且他能一眼看出层级对不对再决定采不采纳。
        ★ JSON 没解析成功时不走这里，仍然显示原文 ——
          比显示一个空框好（至少能看出它是从哪断的）。
    -->
    <div v-if="outlineDraft && previewRows.length" class="preview">
      <div class="preview-head">将创建 {{ previewRows.length }} 个节点：</div>
      <div
        v-for="(row, i) in previewRows"
        :key="i"
        class="preview-row"
        :style="{ paddingLeft: `${row.depth * 16}px` }"
      >
        <span class="preview-type">{{ row.type === 4 ? '细纲' : row.type === 3 ? '章' : '节点' }}</span>
        {{ row.title }}
      </div>
    </div>

    <!-- 采纳 / 丢弃 -->
    <div v-if="hasOutput && !isRunning" class="foot">
      <el-button type="primary" size="small" :disabled="!canAdopt" @click="adopt">
        采纳
      </el-button>
      <el-button size="small" @click="discard">丢弃</el-button>
      <span v-if="!canAdopt" class="hint">
        {{ scene === 'outline' ? '内容不完整，不能采纳' : '' }}
      </span>
    </div>

    <!-- 额度 -->
    <div v-if="quota" class="quota">
      今日剩余 {{ quota.remaining }} / {{ quota.total }} 次
    </div>
  </div>
</template>

<style scoped>
.ai-panel {
  border-top: 1px solid #f0f2f5;
  margin-top: 16px;
  padding-top: 12px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}

.mock-tag {
  cursor: pointer;
}

.mock-explain {
  font-size: 12px;
  line-height: 1.8;
  color: #606266;
}

.mock-explain p {
  margin: 0 0 8px;
}

.mock-explain p:last-child {
  margin-bottom: 0;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.target {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  /* ★ 节点标题可能很长，别把面板撑宽 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice {
  margin-top: 10px;
}

.hint-line {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}

.running-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
  color: #409eff;
}

/* ★ 一个会呼吸的小圆点，比一个转圈图标轻，而且不抢注意力 */
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #409eff;
  animation: blink 1s ease-in-out infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.2;
  }
}

.stream {
  margin: 10px 0 0;
  padding: 10px;
  max-height: 320px;
  overflow: auto;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
  /* ★ 保留换行；`break-word` 是防止超长的英文/URL 把面板撑出横向滚动条 */
  white-space: pre-wrap;
  word-break: break-word;
}

.preview {
  margin: 10px 0 0;
  padding: 10px;
  max-height: 260px;
  overflow: auto;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
}

.preview-head {
  color: #909399;
  font-size: 12px;
  margin-bottom: 6px;
}

.preview-row {
  line-height: 1.9;
  color: #303133;
}

.preview-type {
  display: inline-block;
  min-width: 30px;
  margin-right: 6px;
  padding: 0 4px;
  border-radius: 3px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 11px;
  text-align: center;
}

.foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.hint {
  font-size: 12px;
  color: #c0c4cc;
}

.quota {
  margin-top: 10px;
  font-size: 12px;
  color: #c0c4cc;
  font-variant-numeric: tabular-nums;
}
</style>
