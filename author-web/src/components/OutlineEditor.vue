<script setup>
/**
 * 大纲节点编辑器 —— 节点标题 + 剧情笔记。
 *
 * ============================================================
 * ★ 它和 ChapterEditor 长得像，但有一处关键不同：**不需要拉数据**
 * ============================================================
 * 正文（`chapter.content`）在章节详情接口里，树上是没有的，所以要
 * 单独 GET 一次。而大纲节点的 `content` 就在树里（`OutlineNodeVO`
 * 带着它）—— 树是一次全量返回的。
 *
 * 所以这个组件是**纯受控**的：数据从 `props.node` 来，改完直接写回
 * `props.node` 上（那个对象是树里的同一个对象，上层的工作区标题、
 * 树上的文字会一起变），没有加载态、没有 404、没有「打开第二个节点
 * 时还显示着第一个」的竞态。
 *
 * ============================================================
 * ★★ `title` 和 `content` 必须【一起】发
 * ============================================================
 * 后端的 update 是全量覆盖：
 * `update(null, wrapper.set(title, ...).set(content, dto.getContent()))`
 * —— **content 无条件写入，传 null 就是清空**。
 * 只发 title 的话，这个节点上写满的剧情笔记会**静默消失**：
 * 保存返回 200、不报错、界面也没有任何提示。
 *
 * （后端刻意没用 `updateById`，就是为了让「清空」这件事能做到 ——
 *   它跳过 null 字段，「把笔记删光」在它手里根本做不到。
 *   代价就是每一次写都必须把两个字段都带上。）
 *
 * ============================================================
 * ★ 保存策略和 ChapterEditor 一致：组件管数据，调用方管时机
 * ============================================================
 * 30 秒 debounce 自动存 + 切节点前由 NodeWorkspace 强制 flush。
 * 详见 `ChapterEditor.vue` 的文件头，这里不重复。
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { updateOutlineNode } from '@/api/outline'
import { nodeChapterTag, nodeTypeTag } from '@/utils/status'

const props = defineProps({
  /** OutlineNodeVO。★ 会被就地修改（见文件头） */
  node: { type: Object, required: true },
  /** 是否开启 30 秒 debounce 自动保存 */
  autosave: { type: Boolean, default: true },
})

const emit = defineEmits(['saved'])

/** 和 ChapterEditor 保持一致 */
const AUTOSAVE_DELAY = 30_000

const title = ref('')
const content = ref('')

/** 已和服务器对齐的快照，用来判断脏 —— 见 ChapterEditor 里那段说明 */
const snapshot = ref({ title: '', content: '' })

const isDirty = computed(
  () => title.value !== snapshot.value.title || content.value !== snapshot.value.content,
)

const saving = ref(false)

const chapterTag = computed(() => nodeChapterTag(props.node))

/**
 * 从节点上取值。
 *
 * ★ `?? ''` —— 后端配了 `default-property-inclusion: non_null`，
 *   没写过笔记时 `content` 这个 key 在 JSON 里**根本不存在**，
 *   拿到的是 undefined。直接塞进 `<el-input v-model>` 会变成非受控组件。
 */
function load() {
  title.value = props.node.title ?? ''
  content.value = props.node.content ?? ''
  snapshot.value = { title: title.value, content: content.value }
}

onMounted(load)

/*
 * ★ 理论上 NodeWorkspace 带 `:key`，切节点会整个重建、走不到这里。
 *   但那是**上层的实现细节** —— 哪天有人把 key 去掉，或者把这个
 *   编辑器复用到别处（比如弹窗里），没有这个 watch 就会出现
 *   「换了个节点，输入框里还是上一个节点的内容」，而保存按钮
 *   会把 A 的笔记写到 B 上。加两行比事后排查便宜得多。
 */
watch(
  () => props.node?.id,
  () => load(),
)

/**
 * 空字符串转 `null`。
 *
 * ★ 和作品信息表单同一个理由：让「空」在库里只有一种表示（NULL），
 *   而不是 `''` 和 `NULL` 两种。否则每个读它的地方都要写
 *   `content || ''` 来兜，写成 `content ?? '默认值'` 的地方就会
 *   露出一个空串。
 */
function nullIfBlank(v) {
  const s = String(v ?? '').trim()
  return s === '' ? null : s
}

function save(opts = {}) {
  if (inflight) return inflight
  inflight = doSave(opts).finally(() => {
    inflight = null
  })
  return inflight
}

let inflight = null

async function doSave(opts = {}) {
  if (!isDirty.value) return true

  const trimmedTitle = title.value.trim()
  if (!trimmedTitle) {
    // 后端 @NotBlank。先拦一下，免得自动保存在作者清空标题准备重打的
    // 那一刻发一个必然失败的请求 —— 而且静默时连提示都不弹，
    // 表现是「保存就是没反应」。
    if (!opts.silent) ElMessage.warning('节点标题不能为空')
    return false
  }

  saving.value = true
  try {
    const nextContent = nullIfBlank(content.value)
    // ★★ 两个字段都要带上，见文件头
    await updateOutlineNode(props.node.id, { title: trimmedTitle, content: nextContent })

    /*
     * ★ 就地写回树节点。
     *
     *   写回的是 `nullIfBlank` 之后的值，不是输入框里的原始字符串 ——
     *   否则「作者打了几个空格然后保存」，本地是空格、服务器是 null，
     *   下一次进来看见空格还在，会以为保存没生效。
     *
     *   改了 title 之后树上的文字会立刻跟着变（同一个对象），
     *   右侧工作区的标题也是 —— 不用刷新、不用重拉。
     */
    props.node.title = trimmedTitle
    props.node.content = nextContent

    snapshot.value = { title: trimmedTitle, content: nextContent }
    if (!opts.silent) ElMessage.success('已保存')
    emit('saved', props.node.id)
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    saving.value = false
  }
}

/** 给路由守卫用：脏了就存，不脏就是空操作（含「不脏但上一次保存还在飞」） */
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

// ★ debounce 不是 throttle，理由同 ChapterEditor：
//   throttle 会在作者连续打字的过程中反复发全量覆盖请求。
watch([title, content], () => {
  if (!props.autosave) return
  clearTimer()
  if (!isDirty.value) return
  timer = setTimeout(() => save({ silent: true }), AUTOSAVE_DELAY)
})

onBeforeUnmount(clearTimer)

defineExpose({
  save,
  flush,
  isDirty,

})
</script>

<template>
  <div class="outline-editor">
    <div class="head">
      <el-tag :type="nodeTypeTag(node.nodeType)" size="small" effect="plain">
        {{ node.nodeTypeDesc }}
      </el-tag>
      <el-input
        v-model="title"
        maxlength="200"
        placeholder="节点标题"
        class="title-input"
      />
      <el-tag :type="chapterTag.type" size="small" effect="plain">{{ chapterTag.text }}</el-tag>
    </div>

    <!--
      ★★ 这一句只在「这个节点已经有正文」时出现，那不是废话：
        节点标题改了【不会】同步到已关联章节的标题 —— 章节标题的写入口
        带着「待审核期间不能改」的守卫，同步过去等于开一条绕过它的写路径。
        不说明的话，作者改完节点标题、切到正文一看标题还是旧的，
        会以为「保存失败了」，然后再改一遍。

      ★ 这个分支只有在「章节点已建正文」时才会走到（那时本组件被挂在
        正文编辑器下面的折叠面板里，见 NodeWorkspace 的说明）。
        换成 el-alert 反而多余 —— 正文就在上面一行，不需要再给链接。
    -->
    <div v-if="node.chapterId" class="hint sync-hint">
      改这里的标题<b>不会</b>同步到正文的章节标题，正文标题请在正文编辑器里改。
    </div>

    <el-input
      v-model="content"
      type="textarea"
      :rows="14"
      resize="vertical"
      maxlength="2000"
      show-word-limit
      placeholder="这一段打算写什么。这是给你自己看的笔记，读者看不到。"
      class="content-input"
    />

    <div class="foot">
      <el-button type="primary" size="small" :loading="saving" @click="save()">保存</el-button>
      <span class="hint">
        这里写的是<b>剧情笔记</b>，不是正文
      </span>
    </div>
  </div>
</template>

<style scoped>
.outline-editor {
  padding: 4px;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title-input {
  max-width: 420px;
}

.sync-hint {
  margin: -4px 0 12px;
}

.content-input :deep(textarea) {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.8;
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.hint {
  font-size: 12px;
  color: #909399;
}
</style>
