<script setup>
/**
 * 节点工作区 —— 右侧那一半。**三个分支，判据只有两个字段。**
 *
 * ============================================================
 * ★★ 三个分支
 * ============================================================
 *   ① `chapterId` 有值          → 正文编辑器（ChapterEditor）
 *                                  ↓ 下面挂一个可折叠的「剧情笔记」
 *   ② 没有正文，且 `nodeType==3` → 「这一章还没写正文」+ 一个创建按钮
 *   ③ 其余（全书 / 卷 / 细纲）   → 大纲编辑器（标题 + 剧情笔记）
 *
 * 判据里**没有** `novel_outline.status`。那一列是死列，P4 不写不读 ——
 * 分支和树上的图标读的都是**关联章节**的状态。详见 `utils/status.js`
 * 和 `OutlineNode` 的类注释。
 *
 * ★★ 为什么分支①要额外挂一个折叠面板（方案里只写了三个分支）
 *
 *   建了正文之后，这个节点的 `content`（剧情笔记）就**没有任何入口**
 *   能看到了 —— 分支被 `chapterId` 抢走了。而笔记恰恰是「写这一章
 *   之前列的点」，写作过程中最需要回头看的东西。
 *   折叠面板是代价最小的收口：默认在**有笔记时展开**，没笔记时收起，
 *   两条分支都不丢数据。
 *
 * ★ 分支②只对「章」节点出现，是因为「创建正文」这个动作的语义就是
 *   「这一章开始写了」。在「卷」节点上摆一个创建正文的按钮，
 *   点下去建出来的章节没有任何东西指向它在树上的位置（序号是建出来的
 *   顺序，不是树上的位置），作者会以为它挂错了地方。
 *   ★ 但这只是**界面**上的收敛，后端并不强制（决定 9）——
 *     所以右键菜单里对「卷」节点也不显示这一项，两边一致。
 *
 * ============================================================
 * ★★★ 这个组件会被整个销毁重建，所以「切节点前保存」必须在这里
 * ============================================================
 * 上层 WorkLayout 里写的是 `<router-view :key="nodeId ?? 'none'" />`，
 * 切节点时这个组件连同里面正在编辑的内容一起消失。
 *
 * 而保存**不能**写在 `onBeforeUnmount` 里：请求发得出去，但组件已经
 * 销毁，失败与否没有任何人能收到 —— 作者会以为存上了。
 * 正确的位置是路由的**异步**离开守卫：返回一个 Promise，
 * router 会等它 resolve 之后再导航。
 */
import { computed, ref, inject } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createChapterFromNode } from '@/api/outline'
import ChapterEditor from '@/components/ChapterEditor.vue'
import OutlineEditor from '@/components/OutlineEditor.vue'
import AiPanel from '@/components/AiPanel.vue'

const props = defineProps({
  novelId: { type: Number, required: true },
  /**
   * 当前节点（OutlineNodeVO）。
   * ★ 由 WorkLayout 通过 `<router-view :node="...">` 传进来 ——
   *   vue-router 会把 attrs 透传给匹配到的组件（它自己声明了
   *   inheritAttrs: false 并显式 `h(ViewComponent, {...attrs})`），
   *   所以写成一个普通的 prop 就能收到，不需要 provide/inject。
   */
  node: { type: Object, default: null },
})

/*
 * ★★ 两个 ref，不是一个。
 *
 *   分支①同时渲染了两个编辑器（正文 + 折叠里的笔记），
 *   用一个 ref 的话后挂载的那个会覆盖前一个 —— 而 el-collapse
 *   是用 v-show 隐藏内容的，两个**都在**，所以这个覆盖一定会发生。
 *   症状是「切节点时正文存了、笔记没存」，而且不报任何错。
 */
const chapterRef = ref(null)
const outlineRef = ref(null)

const creating = ref(false)

const branch = computed(() => {
  const n = props.node
  if (!n) return 'none'
  if (n.chapterId) return 'chapter'
  if (n.nodeType === 3) return 'empty-chapter'
  return 'outline'
})

/** 折叠面板的展开项。★ 有笔记就默认展开 —— 否则作者根本不知道那儿有东西 */
const noteOpen = ref(props.node?.content ? ['note'] : [])

/* ==================== 分支②：创建正文 ==================== */

async function handleCreateChapter() {
  if (creating.value) return
  creating.value = true
  try {
    const r = await createChapterFromNode(props.node.id)

    /*
     * ★ 就地写回，分支会自己切到 'chapter' —— 因为 `chapterId` 是
     *   `branch` 的计算源，而 `props.node` 是树里的同一个对象。
     *   不需要 router.push（URL 早就指向这个节点了），
     *   也不需要给 router-view 换 key。
     *
     * ★ 字段名要搬一次：接口返回的是 OutlineChapterVO（`title`/`status`），
     *   节点上是 OutlineNodeVO（`chapterTitle`/`chapterStatus`）。
     *   OutlineTree 里那份一模一样，两处都改了才算改完。
     */
    props.node.chapterId = r.chapterId
    props.node.chapterNo = r.chapterNo
    props.node.chapterTitle = r.title
    props.node.chapterStatus = r.status
    props.node.chapterStatusDesc = r.statusDesc

    // ★ 幂等接口：已有正文时返回 created: false，不是错误。
    //   不分岔的话作者会以为自己又建了一章。
    ElMessage.success(r.created ? '已创建章节' : '这一章已有正文')
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

/**
 * 正文保存成功后，把状态同步到**大纲节点**上。
 *
 * ★★ 为什么需要这一步：已发布的章节一保存，后端会把它**自动退回草稿**
 *    （已发布的内容必须重走审核，否则读者正在看的正文会被静默改掉）。
 *    这个动作是在 `ChapterEditor` 内部完成的，它会弹一句
 *    「已发布的章节修改后会退回草稿」——
 *    而树上那个图标读的是节点上的 `chapterStatusDesc`。
 *
 *    不同步的话，屏幕上会**同时**出现「已退回草稿」的提示和
 *    「已发布」的图标。两个说法直接打架，作者不知道该信哪个。
 *
 * ★ 只改这两个字段，不重拉树 —— 重拉会清空展开状态（见 OutlineTree 文件头）。
 *   章节的其它字段（标题、字数）在这个界面上不显示，不需要跟着变。
 */
function handleChapterSaved() {
  const editor = chapterRef.value
  if (!editor || !props.node) return
  props.node.chapterStatus = editor.status
  props.node.chapterStatusDesc = editor.statusDesc
}

/* ==================== S12：AI 面板 ==================== */

/**
 * 往大纲树里批量建节点的能力。
 *
 * ★ 树在 `OutlineTree` 里、这个组件在 `<router-view>` 后面，
 *   中间隔着两层 —— 所以走 `provide/inject`（沿**组件树**传播，
 *   不受模板层级影响）。详见 `WorkLayout` 里 `provide` 那段说明。
 */
const outlineOps = inject('outlineOps', null)

/**
 * 哪些 AI 动作在当前分支下有意义。
 *
 * ★★ 这个判断**只能**在这一层做：它同时知道「分支是什么」和
 *    「哪个编辑器挂着」，而 AI 面板两样都不知道。
 *
 * ★ 与其让作者等它生成完几百字再说「这里放不下」，不如事先禁用 ——
 *   一个点了没反应的按钮比一个灰掉的按钮更让人困惑。
 *
 * ★ `inspire` 只在有「剧情笔记」输入框的分支出现：
 *   分支①②里那个折叠面板是**挂着**的（el-collapse 用 v-show），
 *   所以 branch === 'chapter' 时它可用；分支②（还没建正文）
 *   整个是 el-empty，没有笔记框。
 */
const aiScenes = computed(() => {
  switch (branch.value) {
    case 'chapter':
      return ['outline', 'write', 'inspire', 'polish']
    case 'outline':
      return ['outline', 'inspire']
    case 'empty-chapter':
      return ['outline']
    default:
      return []
  }
})

/** 取一段文本的**末尾**若干字，尽量从段落边界开始 */
function tailOf(text, max) {
  const t = String(text ?? '')
  if (t.length <= max) return t
  const cut = t.slice(-max)
  // ★ 从第一个换行之后开始切 —— 否则发给 AI 的第一句是半截的，
  //   它多半会把那半句当成开头照抄回来
  const nl = cut.indexOf('\n')
  return nl >= 0 ? cut.slice(nl + 1) : cut
}

/**
 * 拼一次 AI 请求。
 *
 * ★★ 这个函数放在这里而不是 AiPanel 里，是因为它要的东西
 *    （正文内容、用户选中的那段）**只有这个组件拿得到**。
 *    面板拿到的只是 `{prompt, context}` 两个字符串 ——
 *    它甚至不知道 context 是从哪来的。
 *
 * ★ 返回 `{error}` 而不是抛异常：「没选中就点润色」是**操作顺序**
 *   问题，不是程序错误；提示语由最懂上下文的人（这里）来写。
 */
function gatherAiRequest(scene) {
  const n = props.node
  if (!n) return { error: '请先在左侧选中一个节点' }

  // ★ 节点上的剧情笔记就是这一段的写作意图，是「生成大纲」和
  //   「提供灵感」最好的上下文。截到 2000 字 —— 后端 context 上限是 8000，
  //   留出富余给别的内容。
  const note = String(n.content ?? '').slice(0, 2000)

  if (scene === 'outline') {
    return {
      prompt: `为「${n.title}」生成下一级大纲`,
      context: note || undefined,
    }
  }

  if (scene === 'write') {
    /*
     * ★★ 续写的上下文是**正文的末尾**，不是全文 ——
     *   后端 context 上限 8000 字，而一章轻松超过它。
     *   截断时保留末尾（越靠近要接的地方越相关），
     *   和 `tailOf` 里那段「从段落边界开始」是同一个考虑。
     */
    const tail = tailOf(chapterRef.value?.content, 2000)
    if (!tail) return { error: '正文还是空的，先写几句再让 AI 接着写' }
    return {
      prompt: `接着往下写「${chapterRef.value?.title || n.title}」`,
      context: tail,
    }
  }

  if (scene === 'polish') {
    /*
     * ★★ 润色的输入是**用户选中的那段**，不是整章。
     *   发整章过去的话，返回的会是整章的改写，而「采纳」只会
     *   把它塞进那个小小的选区里 —— 上下文和落点对不上，
     *   出来的是彻底的乱码。
     */
    const selected = chapterRef.value?.getSelection?.() ?? ''
    if (!selected) return { error: '请先在正文里选中要润色的那一段，再点「润色段落」' }
    return { prompt: '润色下面这段文字', context: selected }
  }

  if (scene === 'inspire') {
    return {
      prompt: `为「${n.title}」提供几个剧情走向`,
      context: note || undefined,
    }
  }

  return { error: '未知的 AI 操作' }
}

/**
 * 采纳 AI 的产出 —— 四个场景四个落点。
 *
 * ★★ outline 走的是**批量建节点**，和另外三个完全不同：
 *   它改的是树，不是当前编辑器。而树在另一个组件里，
 *   所以只能经 `outlineOps`（来自 WorkLayout 的 provide）转过去。
 *
 * ★ 这里的每个分支都已经在 `aiScenes` 里保证了「目标存在」，
 *   所以 `?.` 后面那个兜底提示理论上到不了 —— 但保留它，
 *   因为 `aiScenes` 和这个函数分处两处，将来改了一处忘了另一处时，
 *   静默什么都不发生是最糟的结果。
 */
async function handleAdopt({ scene, text, payload }) {
  if (scene === 'outline') {
    await outlineOps?.addTree(props.node?.id ?? null, payload)
    return
  }

  if (scene === 'write') {
    if (!chapterRef.value?.appendText?.(text)) {
      ElMessage.warning('这段正文现在不能修改')
    }
    return
  }

  if (scene === 'polish') {
    // ★ replaceSelection 自己会在「没选中」时提示并返回 false，
    //   这里不重复提示 —— 那是它更清楚的事
    chapterRef.value?.replaceSelection?.(text)
    return
  }

  if (scene === 'inspire') {
    if (!outlineRef.value?.appendText?.(text)) {
      ElMessage.warning('这个节点现在没有可以写笔记的地方')
      return
    }
    /*
     * ★ 笔记框在折叠面板里，而它默认只在**本来就有笔记时**展开。
     *   不展开的话，作者点了采纳，屏幕上什么都不动 ——
     *   他只能认为没生效，然后再点一次（于是笔记里多了一份重复的）。
     */
    noteOpen.value = ['note']
    return
  }
}

/* ==================== 离开前保存 ==================== */

/**
 * ★★ `onBeforeRouteLeave` 可以返回 Promise，router 会等它 ——
 *    这就是「切节点前强制 flush」的正确实现位置。
 *
 * ★ 两个编辑器**并发** flush，不是串行：它们是两份独立的数据，
 *   串起来只是白白多等一个往返（和作品信息表单里
 *   「分类和详情并发加载」是同一条理由）。
 *
 * ★ `?? true` 而不是省略：分支②③下另一个 ref 是 null，
 *   `Promise.all` 里出现 undefined 不影响结果，但读代码的人
 *   会以为漏了一个。给个显式的「没有东西要存」。
 *
 * ★ `includes(false)` 而不是 `!every(Boolean)`：「没保存成功」的
 *   判据只有 `false` 这一个值（两个编辑器的 flush 都返回布尔）。
 */
onBeforeRouteLeave(async () => {
  const results = await Promise.all([
    chapterRef.value?.flush?.() ?? true,
    outlineRef.value?.flush?.() ?? true,
  ])
  if (!results.includes(false)) return true

  /*
   * ★ 保存失败时**不直接返回 false**：那会让「点下一章」这个动作
   *   莫名其妙地点不动，而作者根本不知道自己做错了什么。
   *   用 confirm 把选择权交回去，并把后果写清楚。
   */
  try {
    await ElMessageBox.confirm(
      '这一段的内容没有保存成功（原因见上一条提示）。现在离开，未保存的修改会丢失。',
      '保存失败',
      { type: 'warning', confirmButtonText: '仍要离开', cancelButtonText: '留在本页' },
    )
    return true
  } catch {
    return false
  }
})

/*
 * ★ 这里【不做】`beforeunload`（关标签页 / 刷新时保存）。
 *   浏览器的 beforeunload 里没法等待异步请求，能做的只有
 *   「弹一个『确定要离开吗』」—— 而对一个每 30 秒自动保存一次的
 *   编辑器来说那是纯骚扰：作者刚打完字刷新页面，看到「确定离开吗」，
 *   第一反应是「我稿子没存上」。30 秒的 debounce 已经把丢失窗口
 *   压得很小了，这个取舍比弹一个每次都出现的框划算。
 */
</script>

<template>
  <div class="node-workspace">
    <!-- ① 正文（下面挂这个节点的剧情笔记） -->
    <template v-if="branch === 'chapter'">
      <ChapterEditor
        ref="chapterRef"
        :novel-id="novelId"
        :chapter-id="node.chapterId"
        autosave
        @saved="handleChapterSaved"
      />

      <el-collapse v-model="noteOpen" class="note-collapse">
        <el-collapse-item name="note">
          <template #title>
            <span class="note-title">
              剧情笔记
              <span class="note-sub">（大纲节点上的，不是正文）</span>
            </span>
          </template>
          <OutlineEditor ref="outlineRef" :node="node" />
        </el-collapse-item>
      </el-collapse>
    </template>

    <!-- ② 还没写正文的「章」节点 -->
    <el-empty
      v-else-if="branch === 'empty-chapter'"
      :image-size="90"
      description="这一章还没有正文"
    >
      <el-button type="primary" :loading="creating" @click="handleCreateChapter">
        创建正文
      </el-button>
      <!--
        ★ 说清楚「正文」和「笔记」是两样东西。这个节点上可能已经写了
          一大段剧情笔记，不说的话作者会以为那些字就是正文，
          然后疑惑「为什么还要再创建一次」。
      -->
      <div class="tip">
        创建后会生成一个空章节，标题取自这个节点。<br />
        你已经写的剧情笔记<b>不会</b>变成正文，它是给你自己看的。
      </div>
    </el-empty>

    <!-- ③ 全书 / 卷 / 细纲 -->
    <OutlineEditor v-else-if="branch === 'outline'" ref="outlineRef" :node="node" />

    <!-- 没有选中节点（这本书还没有大纲） -->
    <el-empty v-else :image-size="90" description="左侧选择一个大纲节点" />

    <!--
      ★★ AI 面板挂在**所有分支的下面**，不在任何一个分支里面。
        因为它对四个分支的行为是一样的（只是可用的动作不同），
        写进分支里就要复制四份。

      ★ `v-if="aiScenes.length"` 而不是 `v-if="node"`：
        没有节点时它是空的，渲染出来只是一个标题加一句「作用于：未选中节点」，
        不如整块不出现。

      ★ `:key="node.id"` 是必须的 —— 见 AiPanel 里那个 watch(output)
        的说明之外的另一半：换节点时如果不重建，上一次的生成结果
        （`output` / `result` / `scene`）会**留在面板上**，
        作者在新节点上看到的是上一个节点的 AI 输出，而「采纳」
        会把它塞进**新节点**的正文里。这个 bug 不报错，只是内容串了。
        （正常情况下 NodeWorkspace 整个会被 router-view 的 key 重建，
          轮不到这里生效 —— 但只要有人把那个 key 去掉，这里就是兜底。）
    -->
    <AiPanel
      v-if="aiScenes.length"
      :key="node?.id ?? 'none'"
      :node="node"
      :scenes="aiScenes"
      :gather="gatherAiRequest"
      @adopt="handleAdopt"
    />
  </div>
</template>

<style scoped>
.node-workspace {
  /* ★ 不写 height: 100%。它在一个 overflow: auto 的滚动容器里，
     撑满高度会让长正文被截在容器高度上（滚动条反而消失）。
     滚动归外层管，这里只管内容。 */
  padding: 16px;
}

.note-collapse {
  margin-top: 16px;
  border-top: 1px solid #f0f2f5;
}

.note-title {
  font-size: 13px;
  color: #606266;
}

.note-sub {
  color: #c0c4cc;
}

.tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.9;
}
</style>
