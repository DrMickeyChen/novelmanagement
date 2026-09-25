<script setup>
/**
 * 工作台 —— 三层嵌套路由的【中间层】（大纲 §7.3 ④）。
 *
 * ============================================================
 * ★★★ 这个组件存在的全部意义：切节点时【它不重建】
 * ============================================================
 * 验收原话是「切换节点时大纲树纹丝不动」。要达到这一条，只有一条路：
 * **把大纲树放在一个不会被切节点这件事重建的组件里**。
 *
 * 为什么放在这一层就成立：
 *   路由是 `/novel/:novelId/work/:nodeId`，从节点 10 切到节点 11 时，
 *   匹配到的**路由记录没有变**（还是同一条），变的只是参数 `nodeId`。
 *   vue-router 只在「匹配到的记录变了」时才销毁重建组件 ——
 *   所以 WorkLayout 实例被**复用**，它的 DOM、滚动位置、
 *   内部所有的 ref 状态全都原样留着。
 *
 * 而右边的工作区**必须**重建（否则切了节点内容还是上一章的），
 * 所以它在子路由里，并且带 `:key`。**一个要留、一个要换，
 * 这就是工作台必须是三层而不是两层的原因。**
 *
 * ★ 反过来说：**任何需要「切节点时保持不变」的状态都必须在这一层**。
 *   现在只有树；将来如果有「左侧面板的宽度」「上次看的是大纲还是正文」，
 *   也都属于这里，不属于 NodeWorkspace。
 *
 * ★★ 这件事是可验证的，`useMountProbe` 那个计数器就是为此存在的：
 *   在树里来回点节点，'WorkLayout' 的计数必须停在 1。
 *   变成 2、3、4 就说明这一层挂错位置了 —— 那正是最危险的那个坑
 *   （它不会报错，只是树会闪、滚动会跳、折叠会复位）。
 *   ★ 它当前【没有】挂在界面上 —— 徽章已按用户要求撤掉（P4 验收时读到的就是 1）。
 *     要复现：`const mountCounts = useMountProbe('WorkLayout')` + 一个渲染它的标签，两行。
 *
 * ============================================================
 * ★★ 树的数据由这一层持有，且【就地被改】
 * ============================================================
 * `tree.roots` 是这里 `ref` 里的那个数组，OutlineTree 直接在上面
 * push / splice / 改字段（el-tree 拖拽时本来就那么干）。
 *
 * 好处是 `currentNode` 这个计算属性会自动跟着变 —— 在树里把当前
 * 节点重命名、或者给它创建正文，右侧工作区立刻就是新的，
 * 不需要任何额外的事件或刷新。
 *
 * ★ 所以这里**没有**「树变了就重拉」的逻辑。重拉会重建 el-tree 的
 *   所有 Node 实例、清空展开状态（详见 OutlineTree 的文件头），
 *   而验收标准要的恰恰是"纹丝不动"。
 */
import { ref, computed, watch, onMounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOutlineTree } from '@/api/outline'
import OutlineTree from '@/components/OutlineTree.vue'

const route = useRoute()
const router = useRouter()

const novelId = computed(() => Number(route.params.novelId))
const nodeId = computed(() => {
  const raw = route.params.nodeId
  // ★ 可选参数：/novel/5/work（还没确定看哪个节点）时它是 undefined
  return raw === undefined || raw === '' ? null : Number(raw)
})

const tree = ref({ roots: [], totalNodes: 0 })
const loading = ref(false)
const failed = ref(false)

/** 树里第一个可用的节点（根）。没有大纲时为 null */
const firstNodeId = computed(() => tree.value.roots?.[0]?.id ?? null)

/**
 * 当前选中的节点对象。
 *
 * ★ 工作区要的是**节点本身**，不只是 id —— 它得知道 `chapterId`
 *   （决定显示正文还是大纲编辑器）、`nodeType`、`title`。
 *   从树里现取而不是另存一份副本：树里那个对象是唯一的真值，
 *   重命名/创建正文之后它会就变，工作区跟着变，没有"两份数据
 *   谁先更新"的问题。
 */
const currentNode = computed(() => (nodeId.value === null ? null : findNode(nodeId.value)))

/**
 * 节点总数 —— **在本地数，不读接口返回的 `totalNodes`**。
 *
 * ★ 接口确实返回了 `totalNodes`（`OutlineTreeVO` 里那个字段），
 *   但它在这个页面停留期间**一定会过期**：这个工作台的所有写操作
 *   （新建 / 删除 / 拖拽）都刻意**不重拉树**（见 OutlineTree 的文件头），
 *   所以那个数字永远停在「刚进页面时」。
 *   表现是：作者建了一个节点，树上明明多了一个，
 *   左上角的「8 个节点」纹丝不动 —— 一个看得见的、一直在骗人的数字。
 *
 * ★ 递归数几百个节点是微秒级的事，换来的是它**永远是真的**。
 *   接口那个字段留着（它是契约的一部分，别的调用方可能要用），
 *   但这个面板不读它 —— 读了两边就会打架。
 *
 * ★ 根数组这里要 `|| []`：`loadTree` 失败时 `roots` 仍是 `[]`，
 *   但 `tree.value` 本身在第一帧还没被赋值过。
 */
const totalNodes = computed(() => {
  const walk = (nodes) => nodes.reduce((sum, n) => sum + 1 + walk(n.children || []), 0)
  return walk(tree.value.roots || [])
})

/** 当前节点在树上的路径，用来渲染面包屑 */
const nodePath = computed(() => {
  const target = nodeId.value
  if (target === null) return []
  const walk = (nodes, trail) => {
    for (const n of nodes) {
      const next = [...trail, n]
      if (n.id === target) return next
      const hit = walk(n.children || [], next)
      if (hit) return hit
    }
    return null
  }
  return walk(tree.value.roots, []) || []
})

/**
 * ★★ 这个 watch 是 `:nodeId?` 那个可选参数的另一半。
 *
 *   要处理的三种情况，全都在这里收口：
 *     ① URL 里没有 nodeId（从左侧栏点进来）→ 补成根节点
 *     ② URL 里的 nodeId 在树上不存在（节点被删了 / 别人发来的旧链接）
 *        → 补成根节点，而不是让右边一直转圈
 *     ③ 大纲是空的（roots 为空）→ 什么都不做，显示「创建大纲」
 *
 *   ★ 用 `replace` 不是 `push`：这是「把地址修正成它本来的样子」，
 *     不是一次导航。用 push 的话浏览器历史里会多一条，
 *     用户按返回键会回到那个「还没有 nodeId」的地址，然后又立刻被修正 ——
 *     表现为「返回键点了没反应」，要多按一次才能真正退出去。
 */
function syncNodeId() {
  if (loading.value) return // 树还没到，别急着判断 URL 里的 id 对不对
  const target = firstNodeId.value
  if (target === null) return // 没有大纲，保持原样

  if (nodeId.value === null || !findNode(nodeId.value)) {
    router.replace(`/novel/${novelId.value}/work/${target}`)
  }
}

function findNode(id) {
  const walk = (nodes) => {
    for (const n of nodes) {
      if (n.id === id) return n
      const hit = walk(n.children || [])
      if (hit) return hit
    }
    return null
  }
  return walk(tree.value.roots)
}

async function loadTree() {
  loading.value = true
  failed.value = false
  try {
    const data = await getOutlineTree(novelId.value)
    tree.value = data || { roots: [], totalNodes: 0 }
  } catch {
    // 拦截器已经提示过原因（2002 不是自己的书 / 2001 不存在）
    failed.value = true
    tree.value = { roots: [], totalNodes: 0 }
  } finally {
    loading.value = false
    syncNodeId()
  }
}

onMounted(loadTree)

/**
 * ★ 只在 novelId 真的变了的时候重拉树。
 *
 *   切节点也会触发这个 watch（因为 route 变了），但那时 novelId 没变 ——
 *   不做这个判断的话，**每次点节点都会重新拉一次整棵树**：
 *   树会闪、请求会翻倍，而且"切节点"这个动作会变慢。
 *   这是一个看起来无害、实际上把「不重建」这个优化整个抵消掉的错误。
 */
watch(novelId, loadTree)

function openNode(id) {
  router.push(`/novel/${novelId.value}/work/${id}`)
}

/* ==================== S12：把「往树里批量加节点」递给 AI 面板 ==================== */

/** OutlineTree 组件实例。★ 只为了调它 `defineExpose` 出来的 `addTree` */
const treeComp = ref(null)

/*
 * ★★ 这里为什么必须用 provide/inject，而不能用 props + emit。
 *
 *   `AiPanel` 是 `NodeWorkspace` 的子组件，而 `NodeWorkspace` 是
 *   **`<router-view>` 渲染出来的** —— 也就是说它在这个模板里
 *   根本没有一个可以写 `@adopt="..."` 的位置（写了也会被 router-view
 *   当成 attrs 透传给路由组件，那是另一回事）。
 *
 *   要让它把事件传上来，得让 NodeWorkspace 再 emit 一次，而
 *   router-view 上挂 `@created` 之类能不能收到 —— 能，但那是
 *   「靠 attrs 透传传事件」，写起来和读起来都很难相信它是对的。
 *
 *   provide/inject 走的是**组件树**，不是模板里的父子关系 ——
 *   中间隔着 `<router-view>` 一样能 inject 到。
 *
 * ★ 这也是**唯一**一处让 AI 面板碰树的地方：它只拿到一个
 *   `addTree(parentId, draft)`，拿不到 treeRef，也就没法绕过
 *   OutlineTree 内部的 `busy` 闸和那套「必须走 append」的规矩。
 */
provide('outlineOps', {
  /**
   * 在某个节点下批量建出 AI 生成的那棵子树。
   *
   * ★ `?.` 短路了整个调用表达式，所以树还没挂载（比如这本书
   *   还没有大纲、el-tree 根本没渲染）时 `??` 会兜住，
   *   返回一个和成功同形状的结果 —— 调用方不用写 `?.` 和 `?? {}`。
   */
  addTree: (parentId, draft) =>
    treeComp.value?.addTree(parentId, draft) ?? Promise.resolve({ created: 0, failed: [] }),
})

/* ==================== 树发上来的事件 ==================== */

/** 点了节点 → 换 URL（工作区因此重建，树不动） */
function handleSelect(id) {
  openNode(id)
}

/**
 * 新建了节点 → 直接跳过去。
 * ★ 不这么做的话，作者右键建完一个节点，界面上什么都不动
 *   （新节点在收起的父节点里，或者干脆在屏幕外），
 *   他会以为没建成，然后再建一次。
 */
function handleCreated(node) {
  openNode(node.id)
}

/**
 * 删掉了一棵子树。
 *
 * ★ 只有「当前正在看的节点在删除范围里」才需要动。
 *   删的是别的分支时，URL 还是有效的，工作区不该被打断。
 *
 * ★ 去哪：优先去它原来的父节点（就在眼前，符合直觉），
 *   父节点也没了（删的是根）才退回第一个根。
 *   整个大纲被删光时 `firstNodeId` 是 null，退回 `/novel/:id/work` ——
 *   那个地址是合法的（`:nodeId?` 是可选参数），会显示「创建大纲」。
 */
function handleDeleted({ ids, fallbackId }) {
  if (nodeId.value === null || !ids.includes(nodeId.value)) return

  const parentStillThere = fallbackId !== null && findNode(fallbackId)
  const target = parentStillThere ? fallbackId : firstNodeId.value

  router.replace(
    target === null
      ? `/novel/${novelId.value}/work`
      : `/novel/${novelId.value}/work/${target}`,
  )
}

/**
 * 写操作失败 → 本地和服务器可能已经不一致了，重拉一次。
 *
 * ★ 这是本组件**唯一**会重拉树的时机，代价是展开状态会闪一下 ——
 *   但那是失败路径，提示已经在屏幕上了，比留着一棵和服务器不符的树好。
 */
function handleResync() {
  loadTree()
}
</script>

<template>
  <div class="work-layout">
    <!-- ============ 左：大纲树 ============ -->
    <div class="tree-panel">
      <div class="panel-head">
        <span class="panel-title">大纲</span>
        <span v-if="totalNodes" class="panel-count">{{ totalNodes }} 个节点</span>
        <!--
          ★ 这里原本挂着一个「WorkLayout 挂载 N 次」的验收徽章（见 composables/useMountProbe.js）。
            已按用户要求从界面上撤掉 —— 它对读者没有价值，而它要证明的事（切节点时本层不重建）
            在 P4 验收时读到的数字是 1，即不变量成立。工具本身留着，重新挂回只需两行。
        -->
      </div>

      <div class="tree-body">
        <OutlineTree
          ref="treeComp"
          :novel-id="novelId"
          :roots="tree.roots"
          :current-node-id="nodeId"
          :loading="loading"
          :failed="failed"
          @select="handleSelect"
          @created="handleCreated"
          @deleted="handleDeleted"
          @open-chapter="openNode"
          @resync="handleResync"
          @retry="loadTree"
        />
      </div>

      <div class="panel-foot">
        <el-button link size="small" @click="router.push(`/novel/${novelId}/chapters`)">
          章节管理
        </el-button>
        <el-button link size="small" @click="router.push(`/novel/${novelId}/edit`)">
          作品信息
        </el-button>
        <el-button link size="small" @click="router.push('/works')">返回列表</el-button>
      </div>
    </div>

    <!-- ============ 右：工作区 ============ -->
    <div class="workspace">
      <!--
        ★ 面包屑放在 router-view **外面**：它在切节点时不该重建
          （重建会有一个瞬间的空窗，而它的内容本来就是立刻可算的）。
      -->
      <div v-if="nodePath.length" class="ws-head">
        <template v-for="(n, i) in nodePath" :key="n.id">
          <span v-if="i" class="crumb-sep">/</span>
          <span class="crumb" :class="{ current: i === nodePath.length - 1 }">
            {{ n.title }}
          </span>
        </template>
      </div>

      <!--
        ★★★ 这个 :key 是【必须】的，而且它和 WorkLayout「不要重建」
            是两件方向相反的事，别搞混：

          没有 key → vue-router 复用同一个组件实例 → 切节点时
          NodeWorkspace 里的 ref 全都保留着上一个节点的值
          → 右边显示的还是上一章的内容，而且**不报任何错**。

        ★ key 用 nodeId 而不是整个 route.fullPath：
          用 fullPath 的话，只要 query 变一下（比如加个 ?tab=ai）
          工作区就被整个重建一次，正在编辑的内容会丢。

        ★ `:node` 能当 prop 传进去，是因为 vue-router 会把 attrs
          透传给匹配到的组件（RouterView 自己声明的 inheritAttrs: false，
          然后显式 `h(ViewComponent, {...attrs})`）。
          所以不需要 provide/inject 那一套。
      -->
      <!--
        ★ 再包一层滚动容器。工作区是 flex column（上面面包屑固定），
          而 router-view 渲染出来的组件是它的**直接子元素** ——
          不包的话那个组件自己得处理 flex:1 / min-height:0 / overflow，
          等于把「工作区怎么滚」这件事散到每个工作区子组件里去写。
          ★ `min-height: 0` 不能省：flex 子项的默认 `min-height: auto`
            会让它拒绝收缩到内容高度以下，于是 overflow 永远不生效，
            整个页面跟着一起滚 —— reader-web 阅读器踩过同一个坑。
      -->
      <div class="ws-body">
        <!--
          ★★ `:novel-id` 必须显式传，不能指望 router 自动给。
             vue-router 只在路由记录上写了 `props: true` 时才把
             `route.params` 当 props 传给路由组件 —— 这里没写，
             所以 `NodeWorkspace` 的 `novelId` 会是 `undefined`。
             它一路传到 `ChapterEditor`，最后变成
             `POST /api/author/novels/undefined/chapters`。

          ★ 不改成在子路由上加 `props: true` 的原因：那传进来的是
             **字符串** `"1017"`，而两边的 prop 都声明成 `Number` ——
             类型校验会一直报警告，而且「novelId 是数字」这个约定
             在两个组件里就变成假的了。这里显式传的是一个数字。
        -->
        <router-view
          :key="nodeId ?? 'none'"
          :novel-id="novelId"
          :node="currentNode"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ★ 高度用 calc 减掉顶部导航的 56px，让工作台恰好占满剩余视口 ——
   不然两侧的 overflow: auto 都不会生效，整个页面会跟着一起滚，
   左侧的树和右侧的正文就会互相带着滚。
   （这是 reader-web 阅读器那条「滚动容器必须是自己」的同一条经验。） */
.work-layout {
  display: flex;
  gap: 12px;
  height: calc(100vh - 56px - 40px);
}

.tree-panel {
  width: 320px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f2f5;
}

.panel-title {
  font-weight: 600;
  color: #303133;
}

.panel-count {
  flex: 1;
  font-size: 12px;
  color: #c0c4cc;
}

.tree-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.panel-foot {
  border-top: 1px solid #f0f2f5;
  padding: 8px 12px;
  display: flex;
  gap: 4px;
}

.workspace {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ★ 面包屑自己固定，只有下面那半滚 —— 否则正文长了之后
   面包屑会被滚出视口，而那正是最需要它的时候 */
.ws-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 9px 16px;
  border-bottom: 1px solid #f0f2f5;
  font-size: 13px;
  color: #909399;
  /* ★ 路径长的时候不能换行，否则会把工作区顶下去 */
  white-space: nowrap;
  overflow: hidden;
}

.crumb {
  overflow: hidden;
  text-overflow: ellipsis;
}

.crumb.current {
  color: #303133;
  font-weight: 600;
}

.crumb-sep {
  margin: 0 6px;
  color: #dcdfe6;
}

.ws-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
