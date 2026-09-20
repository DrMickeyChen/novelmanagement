<script setup>
/**
 * 章节正文编辑器 —— 标题 + 正文 + 字数。
 *
 * ============================================================
 * ★★ 为什么单独抽一个组件（方案的文件清单里没列它）
 * ============================================================
 * 章节正文有两个入口，而**写正文这件事只有一套规则**：
 *   · 章节管理页：表格里点「编辑」，弹窗里改（任何章节都能改，包括
 *     没有对应大纲节点的 —— 那些章节是在章节管理页直接建出来的）
 *   · 工作台的正文编辑分支（S11）：内嵌在右侧工作区
 *
 * 抽出来的是**数据**，不是**策略**：
 *   · 这个组件管「当前这一章的标题和正文是什么、脏没脏、怎么存取」
 *   · 调用它的页面管「什么时候存」（弹窗上是显式的保存按钮；
 *     工作台里是 30 秒 debounce 自动存 + 切节点前强制 flush）
 * 把策略也塞进来的话，两种用法会打架：弹窗里点「关闭」应该问一句
 * 「要放弃修改吗」，而工作台点下一个节点必须直接存完再走。
 *
 * ============================================================
 * ★ 它和 `api/chapter.js` 的分工
 * ============================================================
 * 这里只做「组合调用 + 状态管理」，不直接碰 axios ——
 * 所有错误提示都由 request.js 的拦截器统一发，这个组件不重复弹。
 */
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { getChapterDetail, saveChapter, createChapter } from '@/api/chapter'

const props = defineProps({
  novelId: { type: Number, required: true },
  /**
   * 章节 id。
   * ★ `null` 表示**新建** —— 保存时才真正建出章节（`createChapter`）。
   *   不要在这里自作主张先建一个空章节：作者打完标题又反悔时，
   *   库里会留下一堆空草稿，而他没有任何入口能清理它们。
   */
  chapterId: { type: Number, default: null },
  /** 是否开启 30 秒 debounce 自动保存（工作台开、弹窗关） */
  autosave: { type: Boolean, default: false },
})

const emit = defineEmits(['saved', 'created'])

/** 自动保存的静默期。★ 和方案 D-9 的 30 秒一致 */
const AUTOSAVE_DELAY = 30_000

const title = ref('')
const content = ref('')

/** 当前真正在编辑的章节 id。新建时是 null，第一次保存成功后变成新 id */
const currentId = ref(props.chapterId)

/** 章节状态（0草稿 1待审核 2已发布 4已驳回）。新建时是 0 */
const status = ref(0)
const statusDesc = ref('草稿')

const loading = ref(false)
const saving = ref(false)

/**
 * 正文输入框的组件引用。
 *
 * ★★ 拿它是为了**读用户选中的那段字**（「润色段落」要用）。
 *   `el-input` 自己不暴露 selection，但它的 `__expose` 里有 `textarea`
 *   —— 那个原生 `HTMLTextAreaElement` 上才有 `selectionStart/End`。
 *   （已核实 element-plus 2.14.6 的 `el-input` 源码里 `textarea` 在 expose 列表里。）
 */
const inputRef = ref(null)

/**
 * 上一次「和服务器对齐」的内容快照。
 *
 * ★★ 用**内容比对**判断脏，而不是用一个 `dirty` 布尔量手动置位。
 *    手动置位的话，作者改了又改回来（或打一个字再删掉）仍然算脏 ——
 *    于是切节点时会白发一次保存请求，关闭弹窗时会白问一次
 *    「要放弃修改吗」，而其实什么都没改。
 *    内容比对天然处理了「改回原样」，代价只是两个字符串比较。
 */
const snapshot = ref({ title: '', content: '' })

const isDirty = computed(
  () => title.value !== snapshot.value.title || content.value !== snapshot.value.content,
)

/**
 * 字数 —— **必须和后端 `ChapterServiceImpl.countWords` 口径一致**。
 *
 * ★★ 这里是最容易写出偏差的一处：Java 的 `\s` 是 `[ \t\n\x0B\f\r]`，
 *    **只有 ASCII 空白**；而 JS 的 `\s` 还包含 U+3000（全角空格）、
 *    U+00A0 等等。中文作者用全角空格缩进是家常便饭 ——
 *    直接用 `/\s/g` 的话，作者看到「字数 1000」，服务端存的是 1200，
 *    两边永远对不上，而且只在用了全角空格的那些章节上出现。
 *
 * ★ `.length` 数的是 UTF-16 码元（和后端 `String.length()` 一致）：
 *   emoji 算 2。这是刻意的对齐，不是 bug —— 宁可两边都「错」得一样，
 *   也不要一边 1 一边 2。
 */
const wordCount = computed(() => content.value.replace(/[\t\n\x0B\f\r ]/g, '').length)

/** 待审核的章节存不了（后端 2003），提前把输入框封上，别让作者白写 */
const readOnly = computed(() => status.value === 1 && currentId.value !== null)

/**
 * 加载章节详情。
 *
 * ★ `detail.content ?? ''` —— 后端配了 `default-property-inclusion: non_null`，
 *   没写过正文时 `content` 这个 key **在 JSON 里根本不存在**，
 *   拿到的是 `undefined`。直接塞进 `<el-input v-model>` 会让它变成
 *   非受控组件（Vue 会警告，且输入行为异常）。
 */
async function load() {
  if (currentId.value === null) {
    // 新建：空白起点
    title.value = ''
    content.value = ''
    status.value = 0
    statusDesc.value = '草稿'
    snapshot.value = { title: '', content: '' }
    return
  }

  loading.value = true
  try {
    const detail = await getChapterDetail(currentId.value)
    title.value = detail.title ?? ''
    content.value = detail.content ?? ''
    status.value = detail.status ?? 0
    statusDesc.value = detail.statusDesc ?? ''
    snapshot.value = { title: title.value, content: content.value }
  } catch {
    // 拦截器已提示（3001 不存在 / 3002 不是自己的章节）
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * ★ `chapterId` 变了要重新加载。
 *
 *   工作台那边靠 `<router-view :key="nodeId">` 整个重建，走不到这里；
 *   但**弹窗用法走得到** —— 作者关掉一章再点开另一章时，
 *   组件实例是复用的（el-dialog 默认不销毁内容），
 *   不 watch 的话第二章显示的还是第一章的正文。
 */
watch(() => props.chapterId, (v) => {
  /*
   * ★★ `v === currentId.value` 这一句是必须的，不是防御性编程。
   *
   *   调用方（章节管理页）在编辑成功后会把 `editingId` 从 null 补成新 id，
   *   于是这个 watch 会被触发一次 —— 而它指向的**正是我们刚刚创建、
   *   内容就在手上**的那一章。不判断的话会白拉一次详情，
   *   并且把标题和正文用服务器的值覆盖一遍：作者在这一次往返期间
   *   敲的字会被静默抹掉。
   */
  if (v === currentId.value) return
  currentId.value = v
  load()
})

/* ==================== 保存 ==================== */

/**
 * 保存。返回 `true` 表示「现在可以安全离开」。
 *
 * ★ 返回布尔值而不是抛异常：调用方（弹窗的关闭按钮、路由守卫）
 *   需要的是一个可以直接判断的结果。让它们各自 try/catch 一遍，
 *   迟早有一处忘了 catch，然后那个未处理的 rejection 会变成一个
 *   控制台里的红字，而界面上看起来一切正常。
 *
 * ★★ 并发调用**共享同一个在途 Promise**，不是「第二次直接返回 false」。
 *    这个区别在路由守卫上是决定性的：作者改完最后一个字、
 *    30 秒的自动保存正好触发，此时他点了下一个节点 ——
 *    返回 false 会让守卫以为「保存失败」而拦住导航（弹一个
 *    「保存失败，仍要离开吗？」的框，而他什么都没做错）；
 *    共享 Promise 则是等这次保存自然结束，成功就放行。
 *
 * @param {object} [opts]
 * @param {boolean} [opts.silent] 成功时不弹提示（自动保存用）
 */
function save(opts = {}) {
  if (inflight) return inflight
  inflight = doSave(opts).finally(() => {
    inflight = null
  })
  return inflight
}

/** 在途的保存请求。`null` 表示当前没有正在进行的保存 */
let inflight = null

async function doSave(opts = {}) {
  if (!isDirty.value) return true

  // ★ 标题必填是后端的约束（@NotBlank）。在这里先拦一下，
  //   否则自动保存会在作者刚清空标题、还没打新字的那一刻发一个必然失败的请求。
  const trimmedTitle = title.value.trim()
  if (!trimmedTitle) {
    // ★ 自动保存不弹这个提示：作者正在清空标题准备重打，
    //   每 30 秒弹一次「标题不能为空」是在骂他。
    if (!opts.silent) ElMessage.warning('章节标题不能为空')
    return false
  }

  saving.value = true
  const previousStatus = status.value
  try {
    const payload = { title: trimmedTitle, content: content.value }

    if (currentId.value === null) {
      currentId.value = await createChapter(props.novelId, payload)
      emit('created', currentId.value)
      if (!opts.silent) ElMessage.success('章节已创建')
    } else {
      await saveChapter(currentId.value, payload)
      if (!opts.silent) ElMessage.success('已保存')
    }

    snapshot.value = { title: title.value, content: content.value }

    /*
     * ★★ 已发布的章节一保存就会被后端【自动退回草稿】
     *    （`ChapterServiceImpl.saveContent` —— 已发布的内容必须重走审核，
     *     否则读者正在看的正文会被静默改掉）。
     *
     * ★ 这里同步改本地状态而【不是】重新拉一次详情，是刻意的取舍：
     *   重新拉会把 content 也从服务器读回来覆盖掉，
     *   而作者在这一次往返期间完全可能又敲了几个字 —— 那些字会被抹掉，
     *   且没有任何提示。本地同步没有这个风险。
     *
     * ★★ 代价是这份判断和上面那个后端方法**耦合**了。
     *    如果哪天后端改成「已发布章节直接拒绝保存」，
     *    这一行必须跟着改 —— 所以把方法名写在这里，方便 grep。
     *    真正权威的来源是章节列表：调用方保存后一定会重新拉一次列表，
     *    列表上的状态才是服务器说的。
     */
    if (previousStatus === 2) {
      status.value = 0
      statusDesc.value = '草稿'
      ElMessage.warning('已发布的章节修改后会退回草稿，需要重新提交审核')
    }

    emit('saved', currentId.value)
    return true
  } catch {
    // ★ 拦截器已提示具体原因（2003 审核期间不能改 / 3005 正文为空等）
    return false
  } finally {
    saving.value = false
  }
}

/**
 * 给路由守卫 / 关闭按钮用：脏了就存，不脏就是空操作。
 *
 * ★ 不要写成 `isDirty.value ? save() : Promise.resolve(true)` ——
 *   那样会漏掉「不脏，但上一次保存还在飞」的情况，
 *   守卫会在保存真正落地之前就放行。
 *   `doSave` 自己第一行就是 `if (!isDirty) return true`，交给它判断就够了。
 */
function flush() {
  return save({ silent: true })
}

/* ==================== 自动保存 ==================== */

let timer = null

function clearTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

/*
 * ★★ 自动保存用 debounce（停手 30 秒才存），不是 throttle。
 *    throttle 会在作者连续打字的**过程中**定时发请求 ——
 *    写 1000 字大概会发 5~10 次，每次传一整章正文，
 *    而其中只有最后一次是有意义的。
 *
 * ★ 只在 `autosave` 打开时生效。弹窗用法下自动保存是**错**的：
 *   作者点「取消」的意思是放弃，而自动保存已经悄悄存进去了。
 */
watch([title, content], () => {
  if (!props.autosave || readOnly.value) return
  clearTimer()
  if (!isDirty.value) return
  timer = setTimeout(() => save({ silent: true }), AUTOSAVE_DELAY)
})

onBeforeUnmount(clearTimer)

/*
 * ★★ 这里【故意不做】「卸载前存一次」。
 *
 *   在 onBeforeUnmount 里发请求是错的：请求发得出去，但组件已经被销毁，
 *   失败与否没有任何人能收到 —— 作者会以为存上了。
 *   正确的位置是调用方的**路由离开守卫**（`onBeforeRouteLeave` 可以
 *   返回 Promise，router 会等它 resolve 再导航），见工作台的 NodeWorkspace。
 *
 *   所以这个组件只负责「把 flush 交出去」，什么时候调是调用方的事。
 */
/*
 * ★ `status` / `statusDesc` 也交出去，是给工作台用的。
 *
 *   已发布的章节一保存就会被后端自动退回草稿，这个组件自己会弹提示，
 *   但**大纲树上的图标读的是节点上的 `chapterStatusDesc`** ——
 *   不同步的话会同时看到「已退回草稿」的提示和「已发布」的图标，
 *   而那两个说法直接打架。调用方拿到这两个值去改节点就行了。
 *   （章节管理页不用它们 —— 那边保存之后会重新拉整个列表。）
 */
/* ==================== AI 面板的「采纳」落点 ==================== */

/**
 * 追加一段文字到正文末尾。**「续写正文」采纳时用这个。**
 *
 * ★ 中间补一个空行（`\n\n`）分段，因为阅读端就是按空行分段的
 *   （见模板里那个 placeholder）。不补的话，续写出来的第一句会
 *   直接粘在原文最后一个字后面，作者还得手动去敲一个回车。
 *   ★ 但原文本来就以换行结尾时不补 —— 否则会多出一个空段落。
 *
 * ★ 走 `content.value = ...` 而不是直接塞 textarea：那样绕过了 v-model，
 *   `isDirty` 会是 false（它是**内容比对**算出来的，见上面那段说明），
 *   于是这几十个字既不会被自动保存、切节点时也不会被 flush ——
 *   **作者看着字在屏幕上，一离开就没了。**
 *
 * @returns {boolean} 是否真的写进去了
 */
function appendText(text) {
  const t = String(text ?? '')
  if (!t) return false
  if (readOnly.value) {
    ElMessage.warning('这一章正在审核中，不能修改正文')
    return false
  }
  const cur = content.value
  content.value = !cur ? t : cur.endsWith('\n') ? `${cur}${t}` : `${cur}\n\n${t}`
  return true
}

/**
 * 用一段文字**替换用户选中的部分**。「润色段落」采纳时用这个。
 *
 * ★ 选区是在 textarea **失焦之后**仍然保留的（`selectionStart/End`
 *   是元素的属性，不是「有没有焦点」）。所以用户先选好一段、
 *   再去右边点 AI 面板的按钮，回来点「采纳」时选区还在。
 *
 * ★★ 换完之后要把光标移到替换段的末尾，否则它会被浏览器重置到 0 ——
 *   作者接着打字会打到最后一次保存前的位置（很可能是全文开头），
 *   而屏幕上看着一切正常。
 *
 * @returns {boolean} 是否真的替换了
 */
function replaceSelection(text) {
  const t = String(text ?? '')
  if (!t) return false
  if (readOnly.value) {
    ElMessage.warning('这一章正在审核中，不能修改正文')
    return false
  }

  const el = inputRef.value?.textarea
  if (!el) return false

  const start = el.selectionStart ?? 0
  const end = el.selectionEnd ?? 0
  if (start === end) {
    ElMessage.warning('请先在正文里选中要润色的那一段')
    return false
  }

  const cur = content.value
  content.value = cur.slice(0, start) + t + cur.slice(end)

  const caret = start + t.length
  // ★ 等 Vue 把新值刷进 DOM 之后再设光标，否则会被 DOM 更新覆盖掉
  nextTick(() => {
    el.focus()
    el.setSelectionRange(caret, caret)
  })
  return true
}

/**
 * 读用户当前选中的文本，没有选中时返回空串。
 *
 * ★ 给「润色」拼请求上下文用的 —— 润色的**输入就是选中的那段**，
 *   不是整章。作者选中一段点润色，拿到的是那一段的改写；
 *   把整章发过去的话，返回的会是整章的改写，替换进选区里就全乱了。
 */
function getSelection() {
  const el = inputRef.value?.textarea
  if (!el) return ''
  const start = el.selectionStart ?? 0
  const end = el.selectionEnd ?? 0
  if (start === end) return ''
  return content.value.slice(start, end)
}

defineExpose({
  save,
  flush,
  isDirty,
  currentId,
  status,
  statusDesc,
  /* ★ 下面这组是 S12 给 AI 面板加的。`content`/`title` 是当前值
     （defineExpose 会拆 ref，读到的是字符串不是 ref 对象），
     拼「续写」的上下文要用。 */
  content,
  title,
  appendText,
  replaceSelection,
  getSelection,
})
</script>

<template>
  <div v-loading="loading" class="chapter-editor">
    <el-alert
      v-if="readOnly"
      type="warning"
      show-icon
      :closable="false"
      class="mb-12"
      title="这一章正在审核中，不能修改"
    >
      审核员看的就是现在这一版。如果要改，请先去章节列表点「撤回提交」。
    </el-alert>

    <div class="title-row">
      <el-input
        v-model="title"
        :disabled="readOnly"
        maxlength="200"
        placeholder="章节标题，例如：第一章 雨夜来客"
        class="title-input"
      />
      <el-tag size="small" effect="plain">{{ statusDesc }}</el-tag>
    </div>

    <!-- ★ ref 是为了拿 textarea 上的 selectionStart/End（「润色」要用），
           见脚本里 inputRef 那段说明 -->
    <el-input
      ref="inputRef"
      v-model="content"
      :disabled="readOnly"
      type="textarea"
      :rows="18"
      resize="vertical"
      placeholder="正文。空行分段，和阅读端的显示一致。"
      class="content-input"
    />

    <div class="foot">
      <span class="count">{{ wordCount }} 字</span>
      <!--
        ★ 把「写的是什么」说清楚，因为大纲节点上也有一个 content
          （那是剧情笔记，不是正文）。作者在两个框里都写字的时候，
          不说明会混。
      -->
      <span class="hint">这里写的是读者看到的正文</span>
    </div>
  </div>
</template>

<style scoped>
.chapter-editor {
  min-height: 200px;
}

.mb-12 {
  margin-bottom: 12px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title-input {
  max-width: 480px;
}

.content-input :deep(textarea) {
  /* ★ 用等宽字体 + 稍大的行高：正文编辑需要能一眼看出段落和缩进，
     正文字体（宋体/黑体）在这里反而不如等宽好读 */
  font-family: Consolas, Menlo, monospace;
  font-size: 14px;
  line-height: 1.8;
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}

.count {
  font-variant-numeric: tabular-nums;
}

.hint {
  color: #c0c4cc;
}
</style>
