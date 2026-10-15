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
import { computed, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createChapterFromNode } from '@/api/outline'
import ChapterEditor from '@/components/ChapterEditor.vue'
import WritingAgentPanel from '@/components/writing/WritingAgentPanel.vue'
import OutlineEditor from '@/components/OutlineEditor.vue'
import PaneDivider from '@/components/PaneDivider.vue'
import { usePaneWidth } from '@/composables/usePaneWidth'

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
const agentOpen = ref(window.innerWidth >= 1200)
const nodeElement = ref(null)
const { width: agentWidth, maximum: agentMax, dragging: agentDragging, start: resizeAgent, nudge: nudgeAgent, reset: resetAgent } = usePaneWidth({ key: 'author-pane:writing-agent', initial: 380, min: 280, max: 640, reserve: size => size < 660 || window.innerWidth < 1200 ? 8 : 368, container: nodeElement, direction: -1 })

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

async function prepareWritingAgent(action) {
  if (!chapterRef.value || !props.node?.chapterId) throw new Error('请先创建本章正文')
  if (outlineRef.value?.flush && !await outlineRef.value.flush()) throw new Error('当前章节剧情笔记保存失败，请先保存剧情笔记')
  return chapterRef.value.withSavedLock(action)
}

function applyWritingAgent(result) {
  chapterRef.value?.applyAgentResult(result)
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
  <div ref="nodeElement" class="node-workspace" :class="{ 'chapter-workspace': branch === 'chapter' }" :style="{ '--agent-width': `${agentWidth}px` }">
    <!-- ① 正文（下面挂这个节点的剧情笔记） -->
    <template v-if="branch === 'chapter'">
      <div class="editor-column">
        <div class="editor-toolbar">
          <span>正文与剧情笔记</span>
          <el-button size="small" :aria-expanded="agentOpen" @click="agentOpen = !agentOpen">{{ agentOpen ? '收起写作助手' : '打开写作助手' }}</el-button>
        </div>
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
      </div>
      <PaneDivider v-show="agentOpen" class="agent-divider" label="写作助手宽度" :width="agentWidth" :min="280" :max="agentMax" :dragging="agentDragging" @start="resizeAgent" @nudge="nudgeAgent" @reset="resetAgent" />
      <aside v-show="agentOpen" class="agent-column" aria-label="本章写作助手">
        <WritingAgentPanel :key="`writing-${node.id}`" :novel-id="novelId" :node="node" :prepare="prepareWritingAgent" :apply="applyWritingAgent" @close="agentOpen = false" />
      </aside>
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

  </div>
</template>

<style scoped>
.node-workspace {
  height: 100%;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.chapter-workspace { display: flex; position: relative; padding: 0; overflow: hidden; }
.editor-column { flex: 1; min-width: 0; min-height: 0; padding: 16px; overflow-y: auto; overscroll-behavior-y: contain; }
.editor-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 12px; font-size: 13px; color: #909399; }
.agent-column { flex: 0 0 var(--agent-width); width: var(--agent-width); min-height: 0; border-left: 1px solid #e4e7ed; background: #fafbfe; }
@media (max-width: 1199px) {
  .agent-column { position: absolute; right: 0; top: 0; bottom: 0; width: min(var(--agent-width), 100%); z-index: 5; box-shadow: -6px 0 20px #00000012; }
  .agent-divider { position: absolute; right: min(var(--agent-width, 280px), 100%); top: 0; bottom: 0; z-index: 6; }
}
@container (max-width: 660px) {
  .agent-column { position: absolute; right: 0; top: 0; bottom: 0; width: min(var(--agent-width), 100%); z-index: 5; box-shadow: -6px 0 20px #00000012; }
  .agent-divider { position: absolute; right: min(var(--agent-width), 100%); top: 0; bottom: 0; z-index: 6; }
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
