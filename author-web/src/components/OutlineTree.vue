<script setup>
/**
 * 大纲树 —— el-tree + 右键菜单 + 拖拽 + 状态图标。
 *
 * ============================================================
 * ★★★ 最重要的一条：这个组件【从不重拉整棵树】
 * ============================================================
 * 验收原话是「切换节点时大纲树纹丝不动」。但真正会把这条毁掉的
 * 不是切节点，而是**编辑之后重拉**：
 *
 *   element-plus 的 `tree-store.setData(newVal)` 在数据换引用时做的是
 *   `this.nodesMap = {}` + `root.setData(newVal)`，而 `Node.setData`
 *   会把 `childNodes` 清空重建 —— **所有 Node 实例换了一遍，
 *   展开状态全部归零**。只有 `currentNodeKey` 会被重新应用，
 *   `default-expanded-keys` 不会。
 *
 * 也就是说：作者建一个节点 → 整棵树哗地收起 → 他再一个个点开。
 * 每建一个节点就重来一次。
 *
 * 所以这里所有的写操作走的都是**就地改数据**：
 *   · create → 把后端返回的完整节点 push 进父节点的 children
 *   · rename → 改 node.title
 *   · delete → el-tree 的 Node.remove()（它自己会 splice 数据数组）
 *   · move   → ★ el-tree 拖拽时已经改完了，我们只需要调接口
 * 只有当接口**失败**时才 emit('resync') 让上层重拉（错误提示已经在
 * 屏幕上了，那一次折叠可以接受）。
 *
 * ★★ 这就是后端 `create` 必须返回**整个节点**而不是 id 的原因 ——
 *    只给 id 的话前端拼不出能渲染的节点（缺 `nodeTypeDesc`），
 *    除了重拉没有第二条路。
 *
 * ============================================================
 * ★★ 数据是「共享可变」的，这是刻意的
 * ============================================================
 * `props.roots` 是上层（WorkLayout）持有的那一个数组对象。这个组件
 * **就地修改它**（push / splice / 改字段），而不是 emit 一个副本上去。
 *
 * 这不是偷懒 —— el-tree 拖拽时本来就是这么干的（`insertChild` 里
 * `children.splice(...)`，`children` 就是我们的 `roots`）。既然树已经
 * 在就地改，再让 create/delete 走一条「emit 上去让父组件改」的路，
 * 就会出现两套改法，而它们迟早会打架。
 *
 * 好处是上层的 `currentNode` 计算属性会自动跟着变 —— 重命名当前节点、
 * 给它创建正文，右侧工作区立刻就是新的，不需要任何额外通知。
 */
import { ref, reactive, computed, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createOutlineNode,
  updateOutlineNode,
  deleteOutlineNode,
  moveOutlineNode,
  createChapterFromNode,
} from '@/api/outline'
import { nodeChapterTag, nodeTypeTag } from '@/utils/status'
import { useOutlineStore } from '@/stores/outline'

const props = defineProps({
  novelId: { type: Number, required: true },
  /** 树根数组。★ 会被本组件就地修改，见文件头 */
  roots: { type: Array, required: true },
  /** 当前选中的节点 id（由 URL 决定，不由内部 state 决定） */
  currentNodeId: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  failed: { type: Boolean, default: false },
})

const emit = defineEmits([
  /** 用户点了某个节点 → 上层去改 URL */
  'select',
  /** 新建了节点 → 上层跳到它 */
  'created',
  /** 删掉了子树 → 上层检查当前节点在不在里面 */
  'deleted',
  /** 节点关联的章节建好了 → 上层跳到它 */
  'open-chapter',
  /** 本地状态和服务器不一致了（写操作失败）→ 上层重拉 */
  'resync',
  /** 加载失败，用户点了重试 */
  'retry',
])

const outlineStore = useOutlineStore()

/**
 * 树深度上限。★ 和后端 `OutlineServiceImpl.MAX_DEPTH` 保持一致。
 *
 * 这是**第二份常量**，但它是必须的：这里拦一下是为了让作者在拖的
 * 时候就看到「放不进去」（el-tree 会显示禁止光标），而不是松手之后
 * 拿一个 3011 的报错。真正说话算数的是后端那一份 —— 前端这行写错了
 * 最坏是白拖一次，不会让数据坏掉。
 */
const MAX_DEPTH = 20

/** el-tree 的字段映射。★ 用常量而不是内联对象：内联对象每次渲染都是新引用，会触发不必要的 watcher */
const TREE_PROPS = { children: 'children', label: 'title' }

const treeRef = ref(null)
const menuRef = ref(null)

/** 有写请求在飞。期间禁用拖拽和菜单，避免两个写操作打架 */
const busy = ref(false)

/* ==================== 展开状态 ==================== */

/**
 * 喂给 el-tree 的 `default-expanded-keys`。
 *
 * ★★ 它必须是一个**引用稳定的**数组，所以用 computed 而不是
 *    在模板里写 `outlineStore.expandedKeys(novelId)`。
 *    后者每次渲染都会产生一个新数组，而 el-tree 上有
 *    `watch(() => props.defaultExpandedKeys, ...)` —— 于是每次渲染
 *    都会触发一次「把这些 key 全部展开」。表现是
 *    **作者刚收起的节点立刻自己弹开**。
 *
 * ★ 反过来，computed 只在 store 真的变了或换了本书时才重新求值，
 *   而那时触发一次「展开这些 key」正是我们要的（比如重拉之后恢复）。
 */
const expandedKeys = computed(() => outlineStore.expandedKeys(props.novelId))

/*
 * ★ 展开/收起时把状态写进 store（→ localStorage）。
 *
 *   注意这里【只有存】，没有「让 el-tree 跟着变」—— el-tree 自己
 *   已经变完了。而 `setDefaultExpandedKeys` 只会调用 `expand()`、
 *   从不 `collapse()`，所以上面那个 computed 变化时不会把用户
 *   刚收起的节点又打开。
 *
 * ★ `Node.expand()` 不会 emit `node-expand`（那是 tree-node 的点击
 *   处理里发的），所以这里不存在「展开 → 存 → 触发展开」的循环。
 */
function handleExpand(data) {
  outlineStore.setExpanded(props.novelId, data.id, true)
}

function handleCollapse(data) {
  outlineStore.setExpanded(props.novelId, data.id, false)
}

/**
 * 选中节点时把它所在的路径展开。
 *
 * ★ 用 `flush: 'post'`：回调在组件重新渲染【之后】跑，
 *   这时 `getNode` 才拿得到节点（树刚拿到数据的那一次尤其重要）。
 *
 * ★ `node.expand()` 的 `expandParent` 默认跟着 `autoExpandParent`（true），
 *   会把祖先一路展开 —— 深度链接进来（`/novel/5/work/42`）时
 *   42 的父链是收着的，没有这一下作者会看到一个高亮节点在树里
 *   「找不到」。
 */
watch(
  [() => props.currentNodeId, () => props.roots],
  () => {
    if (props.currentNodeId === null) return
    treeRef.value?.getNode(props.currentNodeId)?.expand()
  },
  { flush: 'post' },
)

/* ==================== 点选 ==================== */

function handleNodeClick(data) {
  // ★ 已经是当前节点就不用再导航了。router.push 到同一个地址
  //   会返回一个 NavigationFailure，虽然不报错，但白写一次历史判断。
  if (data.id === props.currentNodeId) return
  emit('select', data.id)
}

/* ==================== 右键菜单 ==================== */

const menu = reactive({ visible: false, x: 0, y: 0, node: null })

/**
 * 当前菜单指向的节点能不能「创建正文」。
 *
 * ★ 只对「章」类型的节点显示这个菜单项，而且已有正文时不显示
 *   （已有正文的节点点一下就直接打开正文了，不需要菜单）。
 *
 * ★★ 这【不是】安全校验，只是不给作者一个必然失败的按钮。
 *    后端并不强制 `nodeType == 3` 才能建正文（决定 9）——
 *    能靠界面隐藏规避的规则，做成后端错误码只会拦住那些
 *    「就是想在卷下面直接写正文」的作者。
 */
const canCreateChapter = computed(
  () => menu.node?.data?.nodeType === 3 && !menu.node?.data?.chapterId,
)

async function handleContextMenu(event, data, node) {
  event.preventDefault()
  /*
   * ★★ `stopPropagation` 是必须的。
   *
   *   下面在 document 上挂了一个「右键点别处就关掉菜单」的监听。
   *   不拦住的话，这一次右键会继续冒泡到 document，
   *   于是菜单刚打开就被自己关掉了 —— 表现是「右键完全没反应」，
   *   而代码看起来每一步都对。
   *
   *   这里能拦住，是因为 `node-contextmenu` 是在 el-tree 自己的
   *   contextmenu 处理函数里**同步** emit 的，原生事件还在冒泡途中。
   */
  event.stopPropagation()
  if (busy.value) return

  menu.node = node
  menu.x = event.clientX
  menu.y = event.clientY
  menu.visible = true

  // 贴边翻转：菜单用 fixed 定位 + clientX/Y，窗口右下角右键时
  // 不修正就会有一半在视口外，而且没有滚动条能滚到它。
  await nextTick()
  const el = menuRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (menu.x + rect.width > window.innerWidth - 8) {
    menu.x = Math.max(8, window.innerWidth - rect.width - 8)
  }
  if (menu.y + rect.height > window.innerHeight - 8) {
    menu.y = Math.max(8, window.innerHeight - rect.height - 8)
  }
}

function closeMenu() {
  menu.visible = false
  menu.node = null
}

/*
 * ★ 关闭用 `mousedown` 而不是 `click`，而且是 **capture** 阶段。
 *
 *   mousedown 比 click 先发生。菜单项自己的处理器挂在 click 上，
 *   所以「按在菜单上」时这个 capture 监听会先跑 —— 里面那句
 *   `menuRef.contains(target)` 让它跳过，菜单不会被自己关掉，
 *   随后的 click 才轮得到菜单项。
 *   换成 click 阶段的话，菜单项一点就被关了，处理器都不一定跑得到。
 */
function onDocMouseDown(e) {
  if (menuRef.value?.contains(e.target)) return
  closeMenu()
}

/** 右键点别处（不在树上、也不在菜单上）→ 关掉。树上的那一份已经被 stopPropagation 拦住了 */
function onDocContextMenu(e) {
  if (menuRef.value?.contains(e.target)) return
  closeMenu()
}

function onKeydown(e) {
  if (e.key === 'Escape') closeMenu()
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMouseDown, true)
  document.addEventListener('contextmenu', onDocContextMenu)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', closeMenu)
  // ★ 第三个参数 true = 捕获阶段。滚动事件不冒泡，而树是在一个
  //   自己的滚动容器里的 —— 不写 true 的话滚轮滚树，菜单会飘在原地。
  window.addEventListener('scroll', closeMenu, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMouseDown, true)
  document.removeEventListener('contextmenu', onDocContextMenu)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
})

/* ==================== 新建 ==================== */

/**
 * 弹一个只问标题的输入框。
 *
 * @returns {Promise<string|null>} 用户取消时返回 null（`ElMessageBox` 取消是 reject，不是 resolve）
 */
async function promptTitle(title, message, defaultValue = '') {
  try {
    const { value } = await ElMessageBox.prompt(message, title, {
      inputValue: defaultValue,
      inputPlaceholder: '最多 200 字',
      // ★ 校验放这里，而不是在提交前再 if 一遍 —— 后者会让对话框先关掉、
      //   再弹一句「标题不能为空」，作者得重新打开菜单再输一次。
      inputValidator: (v) => (String(v ?? '').trim() ? true : '标题不能为空'),
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    return value.trim()
  } catch {
    return null
  }
}

/** 新节点的 children 一定是 []（后端保证），但手滑改坏了也不该让 push 抛错 */
function ensureChildren(data) {
  if (!Array.isArray(data.children)) data.children = []
  return data.children
}

async function doCreate(body, parentNode) {
  if (busy.value) return
  busy.value = true
  try {
    const node = await createOutlineNode(props.novelId, body)

    /*
     * ★★★ 必须走 el-tree 的公开 `append`，**绝对不能**
     *     `parentNode.data.children.push(node)`。
     *
     *   el-tree 渲染的是 `Node.childNodes`，而它对数据只有一个监听点：
     *   根数组 `props.data` 的**引用**变化（`watch(() => props.data)`
     *   → `setData`，那会重建全部节点、把展开状态清空 —— 正是
     *   本文件头要避免的）。
     *
     *   所以在一个**嵌套的** `children` 数组上 push：数据确实进去了，
     *   屏幕上什么都不出现，**而且不报任何错**。
     *   （根层级会「碰巧」正常，因为 `props.roots` 非空时 el-tree
     *     才挂载 —— 也就是说这个 bug 只在建子节点时出现，
     *     而「建子节点」恰恰是最高频的操作。）
     *
     *   `append` 内部走 `insertChild({ data })`，它同时更新
     *   `data.children`（push）和 `childNodes`，还会调
     *   `node.initialize()` → `store.registerNode()` 把新节点登记进
     *   `nodesMap` —— 少了这一步，`:current-node-key` 会因为
     *   `getNode(新id)` 返回 null 而**不高亮刚建出来的节点**。
     *
     *   ★ 第二个参数传 `undefined`（而不是不传或传别的）时，
     *     `isPropAbsent`（= `isNil`）会让它退到虚拟根 = 根层级，
     *     这正是「建根节点」要的行为。父节点传的是 el-tree 的
     *     Node 实例，`getNode` 第一行 `data instanceof Node` 直接返回它。
     */
    if (treeRef.value) {
      treeRef.value.append(node, parentNode ?? undefined)
    } else {
      /*
       * ★★ 这条分支是必需的，不是防御性编程。
       *
       *   模板里 el-tree 挂在 `v-else` 上，上面还有一个
       *   `v-else-if="!loading && !roots.length"` 的空状态分支 ——
       *   也就是说**这本书还没有大纲时，el-tree 根本没挂载**，
       *   `treeRef.value` 是 null。此时 `treeRef.value?.append(...)`
       *   会被 `?.` 静默吞掉：接口返回 200、`emit('created')` 照发，
       *   但屏幕上「点了创建大纲，什么都没发生」，也不报错。
       *
       *   这时没有 `childNodes` 需要同步，直接进数据数组就是对的：
       *   `roots` 一变非空，el-tree 会以**全新状态**挂载并自己建节点。
       *   （只有建【根】节点会走到这里 —— 能右键的节点必定在树上。）
       */
      const siblings = parentNode ? ensureChildren(parentNode.data) : props.roots
      siblings.push(node)
    }

    // 新建的子节点在收起的父节点里是看不见的，顺手展开
    parentNode?.expand()

    emit('created', node)
  } catch {
    // ★ 常见失败：3008 已经有根节点了 / 3007 父节点不属于这本书 / 3011 太深
    //   拦截器已经弹过具体原因
  } finally {
    busy.value = false
  }
}

async function handleCreateRoot() {
  closeMenu()
  const title = await promptTitle('创建大纲', '给这本作品建一个总纲节点', '全书大纲')
  if (title === null) return
  await doCreate({ title }, null)
}

async function handleCreateChild() {
  const parent = menu.node
  closeMenu()
  if (!parent) return
  const title = await promptTitle('新建子节点', `在「${parent.data.title}」下面新建一个节点`)
  if (title === null) return
  await doCreate({ parentId: parent.data.id, title }, parent)
}

/* ==================== 重命名 ==================== */

async function handleRename() {
  const node = menu.node?.data
  closeMenu()
  if (!node) return

  const title = await promptTitle('重命名', '修改节点标题', node.title)
  if (title === null || title === node.title) return

  if (busy.value) return
  busy.value = true
  try {
    /*
     * ★★★ `content` 必须一起发，哪怕只改了标题。
     *
     *   后端的 update 是【全量覆盖】：它用的是
     *   `update(null, wrapper.set(title, ...).set(content, dto.getContent()))`，
     *   也就是 **content 无条件写入**，传 null 就是「清空」。
     *
     *   写成 `updateOutlineNode(id, { title })` 的后果是：
     *   作者给一个写满剧情笔记的节点改个名字，笔记**静默消失** ——
     *   保存返回 200、不报任何错、界面上也没有任何提示。
     *   而他发现的时候，已经想不起来是哪一次操作弄丢的了。
     *
     *   （后端刻意没用 updateById 就是为了让「清空」能做到，
     *     代价就是每一次写都必须把两个字段都带上。同一个坑
     *     ChapterEditor 的保存路径上也记着一份。）
     */
    await updateOutlineNode(node.id, { title, content: node.content ?? null })
    node.title = title
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

/* ==================== 删除 ==================== */

/** 子树节点数（含自己） */
function subtreeCount(data) {
  return 1 + (data.children || []).reduce((sum, c) => sum + subtreeCount(c), 0)
}

/** 子树里已有正文的节点数 —— 用来告诉作者「你要删的东西里有多少是有正文的」 */
function linkedChapterCount(data) {
  const self = data.chapterId ? 1 : 0
  return self + (data.children || []).reduce((sum, c) => sum + linkedChapterCount(c), 0)
}

function collectIds(data, out = []) {
  out.push(data.id)
  for (const c of data.children || []) collectIds(c, out)
  return out
}

async function handleDelete() {
  const node = menu.node
  closeMenu()
  if (!node) return

  const data = node.data
  const total = subtreeCount(data)
  const linked = linkedChapterCount(data)

  /*
   * ★★ 「正文不会被删除」这句是【必须】的，不是客套话。
   *
   *   作者的直觉一定是「删了大纲我的正文就没了」—— 而实际上
   *   后端只删 novel_outline 里的节点（决定 5），chapter 一行不动，
   *   只是那些章节从此没有大纲节点指向它们了。
   *   不把这句说清楚，他会去章节列表里翻半天，然后以为稿子丢了。
   */
  const detail = linked
    ? `「${data.title}」及其 ${total - 1} 个子节点将被删除。\n` +
      `其中 ${linked} 个节点已有正文，**正文不会被删除**，可以在「章节管理」里找到。`
    : `「${data.title}」及其 ${total - 1} 个子节点将被删除。`

  try {
    await ElMessageBox.confirm(detail, '删除大纲节点', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      // ★ 让上面的换行真的生效（默认是把文本塞进一个 p 里）
      dangerouslyUseHTMLString: false,
    })
  } catch {
    return // 取消
  }

  if (busy.value) return
  busy.value = true
  try {
    const ids = collectIds(data)
    // ★ 删之前先记下「删完之后该去哪」：父节点，没有父节点就去根
    const fallbackId = toIdOrNull(node.parent?.data?.id)

    await deleteOutlineNode(data.id)

    /*
     * ★ 就地摘除，不重拉。
     *
     *   `tree.remove(data)` 是 el-tree 的公开 API，内部
     *   `removeChild` 会同时把 `childNodes` 和数据数组里的那一项
     *   splice 掉（根层级取到的就是我们的 `roots`）——
     *   整棵子树随它一起消失，正好是我们要的语义。
     *
     * ★ 用 `tree.remove(data)`（按 id 查）而不是上面捕获的
     *   `node.remove()`（按实例）。两者结果一样，但按 id 的那条
     *   多做了两件事：清掉 `store.currentNode`、并且不依赖
     *   「这个 Node 实例在 await 期间没被换掉」——
     *   建/删/拖都会让 el-tree 重建实例（见 handleDrop 里的说明），
     *   捕获实例是能少用就少用的写法。
     */
    treeRef.value?.remove(data)

    emit('deleted', { ids, fallbackId })
  } catch {
    // 拦截器已提示。接口失败说明本地和服务器可能已经不一致了
    emit('resync')
  } finally {
    busy.value = false
  }
}

/* ==================== 创建正文 ==================== */

async function handleCreateChapter() {
  const node = menu.node?.data
  closeMenu()
  if (!node) return

  if (busy.value) return
  busy.value = true
  try {
    const r = await createChapterFromNode(node.id)

    /*
     * ★★ 把 OutlineChapterVO 的字段搬到 OutlineNodeVO 上。
     *
     *   两个 VO 说的其实是同一件事，但字段名不同（章节那个叫 `title`，
     *   节点上叫 `chapterTitle`）。后端 `createChapter` 返回的是
     *   `OutlineChapterVO`，所以这一小段映射省不掉。
     *
     * ★ 就地改 node 的字段，右侧工作区会**自动**从「未创建正文」
     *   切到正文编辑器 —— 因为它的分支判断读的就是这个对象
     *   （上层那个 `currentNode` 计算属性直接指向树里的同一个对象）。
     *   不需要额外的通知，也不需要给 router-view 换 key。
     */
    node.chapterId = r.chapterId
    node.chapterNo = r.chapterNo
    node.chapterTitle = r.title
    node.chapterStatus = r.status
    node.chapterStatusDesc = r.statusDesc

    /*
     * ★★ 这句提示必须按 `created` 分岔。
     *   这个接口是幂等的：已有正文时返回 `created: false` 而不是报错
     *   （用户点第二下时意图和第一下完全相同，报错等于说
     *    「你想做的事已经做完了，这是个错误」）。
     *   但如果不分岔，用户会以为自己又建了一章。
     */
    ElMessage.success(r.created ? '已创建章节，正在打开' : '这一章已有正文，正在为你打开')

    emit('open-chapter', node.id)
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

/* ==================== 拖拽 ==================== */

/**
 * 子树高度（叶子是 1）。
 * ★ 和 `subtreeCount` 不同：这里数的是**层数**，深度校验用的。
 */
function subtreeHeight(data) {
  const kids = data?.children || []
  if (!kids.length) return 1
  return 1 + Math.max(...kids.map(subtreeHeight))
}

/**
 * `undefined` → `null`。
 *
 * ★★ el-tree 根层级的 `dropNode.parent.data` 是一个**包裹用的虚拟根**，
 *    它根本不是我们的节点对象（它就是我们传进去的 roots 数组本身），
 *    所以 `data.id` 是 `undefined`。
 *
 *    不转的话，`JSON.stringify({targetParentId: undefined})` 会把这个键
 *    **整个丢掉** —— 后端收到的是「没传」，和传 null 效果碰巧一样，
 *    但那是运气。而 `beforeNodeId: undefined` 丢掉之后语义完全不同
 *    （「插到这个兄弟前面」变成「追加到末尾」），且不报错。
 *    统一在这里收口，别处不许直接取 `.data.id` 当父 id 用。
 */
function toIdOrNull(id) {
  return id === undefined || id === null ? null : id
}

function allowDrag() {
  return !busy.value
}

/**
 * 拦下会超过深度上限的拖拽。
 *
 * ★★ 注意 `type` 是 `'prev' | 'inner' | 'next'` —— **不是** `node-drop`
 *    那个 `'before' | 'after' | 'inner'`。两套名字，长得像，写混了
 *    不会报错，只会让某一种拖法永远被拦或者永远放行。
 *    （element-plus 内部把 prev/next 翻译成 before/after 之后才发 node-drop。）
 *
 * ★ 环（把自己拖进自己的子孙）**不用在这里防** —— el-tree 自己就不允许：
 *   `handleDragOver` 里有一条
 *   `if (draggingNode === dropNode || draggingNode.contains(dropNode)) 三个方向全部置 false`。
 *   后端仍有一道 `requireNoCycle` 兜底（数据可能是别处改坏的）。
 *
 * ★★ 落点在**根层级**（level 1）时一律拒绝 —— 那里只有根节点自己配待着。
 *
 *   拖到根节点的上边 / 下边（`prev` / `next`）时，`dropNode.parent` 是那个
 *   **包裹用的虚拟根**，`toIdOrNull` 算出来的 `targetParentId` 就是 `null`，
 *   后端会把被拖的节点 `parent_id` 直接写成 NULL —— 于是**长出了第二个根**。
 *   而 `create` 那条路径是用 3008 明令禁止第二个根的，这个不变式不该
 *   被一次拖拽绕过去（后端现在也补了兜底，这里是第一道）。
 *
 *   顺带把「拖到空白处」也盖住了：el-tree 在树容器上触发 dragover 时
 *   传的 `dropNode` 就是虚拟根（`level === 0`），`inner` 算出来同样是 level 1。
 *
 *   ★ 这是负向用例 17 发现的：拖之前 roots 是 1，拖完变成 2，
 *     树上多出一棵顶层树，而「全书大纲」不再包含全部内容。
 *
 * ★ 这里【不弹提示】：allowDrop 在拖拽过程中会被高频调用（悬停一个节点
 *   就调三次），弹提示会瞬间刷屏。禁止光标本身就是反馈。
 */
function allowDrop(dragNode, dropNode, type) {
  const topLevel = type === 'inner' ? dropNode.level + 1 : dropNode.level

  // ★ 落点会是顶层 → 会造出第二个根，禁止
  if (topLevel <= 1) return false

  return topLevel + subtreeHeight(dragNode.data) - 1 <= MAX_DEPTH
}

/**
 * 拖拽落位。
 *
 * ★★★ 这个函数是在 **el-tree 已经改完数据之后**才被调用的
 *     （`useDragNode` 里先 `remove()` + `insertBefore/After/Child`，
 *      再 `emit('node-drop')`）。所以：
 *       · 成功时**什么都不用做** —— 数据数组已经是新顺序了
 *       · 失败时**必须重拉** —— 本地已经改了，服务器没有
 */
async function handleDrop(dragNode, dropNode, dropType) {
  const nodeId = dragNode.data.id

  let body
  if (dropType === 'inner') {
    // 拖进节点里 = 追加为最后一个子节点 → beforeNodeId 为 null
    body = { targetParentId: toIdOrNull(dropNode.data.id), beforeNodeId: null }
  } else {
    // before / after 都是「还在同一个父节点下，只改顺序」
    const parentEl = dropNode.parent
    const parentId = toIdOrNull(parentEl?.data?.id)

    /*
     * ★★★ 取「拖完之后紧跟在被拖节点后面的那个兄弟」，
     *     而不是「dropNode 后面的那个」。
     *
     *     因为数据已经被改过了：
     *       after  → 顺序是 [..., dropNode, dragNode, next, ...]
     *                 取 dropNode 的下一个会拿到 dragNode 自己，是错的
     *       before → 顺序是 [..., dragNode, dropNode, ...]
     *                 取 dropNode 的下一个会漏掉一个位置
     *     统一取「dragNode 的下一个」，两种情况都对
     *     （after 时它就是 next；before 时它就是 dropNode）。
     *
     * ★★★ 但**不能在 `childNodes` 里 indexOf(dragNode)** —— 这是本文件
     *     最阴的一处，写错了不报错，只是「拖到中间会跑到末尾去」。
     *
     *     `useDragNode.treeNodeDragEnd` 落位时造的是一个**普通对象**
     *     `const draggingNodeCopy = { data: draggingNode.node.data }`，
     *     然后 `insertChild` 里 `reactive(new Node(child))` 造了一个
     *     **新实例**塞进 childNodes，最后才
     *     `emit('node-drop', draggingNode.node, ...)` —— 发出来的是
     *     那个**旧实例**：`removeChild` 已经把它 `parent` 置空、
     *     从旧父的 childNodes 里 splice 掉了。
     *     所以 `childNodes.indexOf(dragNode)` **恒为 -1**，
     *     于是 `next` 恒为 null → 「插到这个兄弟前面」静默变成「追加到末尾」。
     *
     * ★ 权威来源是**数据数组**（`data.children`，根层级就是 `props.roots`）：
     *   `removeChild` 和 `insertChild` 都按同一个下标 splice 了它，
     *   落位完成时它已经是新顺序了。按 **id** 找，不碰 Node 实例。
     */
    const siblings = parentEl
      ? parentEl.level === 0
        ? props.roots
        : ensureChildren(parentEl.data)
      : []

    const i = siblings.findIndex((d) => d.id === nodeId)
    const next = i >= 0 ? siblings[i + 1] : null
    body = { targetParentId: parentId, beforeNodeId: next ? next.id : null }
  }

  busy.value = true
  try {
    await moveOutlineNode(nodeId, body)
    // ★ 本地已经是对的，什么都不用做
  } catch {
    // ★ 常见失败：3009 拖进了自己的子孙 / 3010 库里本来就有环 /
    //   3011 超过 20 层 / 3007 目标父节点不属于这本书
    ElMessage.warning('移动没有保存成功，正在恢复')
    emit('resync')
  } finally {
    busy.value = false
  }
}

/* ==================== S12：AI 大纲的「采纳」 ==================== */

/**
 * 在一棵已有节点下面**批量**建出 AI 生成的那棵子树。
 *
 * ★★ 这是本文件唯一一个对外暴露的写操作（`defineExpose`），
 *    调用链是：`AiPanel` → `NodeWorkspace` → `WorkLayout` 的
 *    `provide('outlineOps')` → 这里。绕这一圈的原因见
 *    `WorkLayout` 里那段 provide 的说明 —— 一句话：树在这个组件里，
 *    而 AI 面板在另一个组件里，中间隔着 `<router-view>`。
 *
 * ------------------------------------------------------------
 * ★★ 为什么是「前端循环发单条请求」而不是一个批量接口（决定 10）
 * ------------------------------------------------------------
 * 批量接口的**部分失败**语义很难做对：第 3 个节点建失败时，
 * 前面 2 个回滚吗？回滚的话作者看到「生成失败」但树已经变了；
 * 不回滚的话要返回一份「哪些成了哪些没成」的清单，而前端还得
 * 把它渲染成作者能看懂的话。前端循环只需要把失败的那些标题记下来，
 * 最后说一句「有 2 个没建上」，语义天然是对的，而且**每个节点
 * 走的都是那条已经被验证过的单条创建路径**。
 *
 * 代价是 n 次往返。n 是 AI 生成的大纲节点数（十几个），
 * 而且是用户主动点「采纳」触发的一次性动作，不是每次打字都发生。
 *
 * ------------------------------------------------------------
 * ★★ 关于草稿自己那个 `title`
 * ------------------------------------------------------------
 * `MockAiStreamSource` 的产出形状是
 *   `{"title": "…", "children": [{type, title, content}, …]}`
 * 那个顶层 `title` 是**这一份大纲的名字**（它是对 prompt 的复述），
 * 不是要建出来的节点 —— 所以这里只取 `children`，把整棵树挂在
 * **当前选中的节点**下面。
 *   ★ 不建顶层节点的话，当前节点（比如「全书大纲」）正好就是
 *     它的天然位置，不会多出一层。
 *
 * @param {number|null} parentId 挂到哪个节点下（null = 根层级）
 * @param {object} draft AI 产出的那个对象（**不是字符串**，调用方已 JSON.parse）
 * @returns {Promise<{created: number, failed: string[]}>}
 */
async function addTree(parentId, draft) {
  const items = Array.isArray(draft?.children) ? draft.children : []
  if (!items.length) {
    ElMessage.warning('这份大纲是空的，没有可以采纳的节点')
    return { created: 0, failed: [] }
  }
  // ★ 复用 busy：期间拖拽和右键菜单都会禁用，避免两个写操作打架
  if (busy.value) return { created: 0, failed: [] }

  busy.value = true
  let created = 0
  const failed = []

  /**
   * 把一个新建的节点插进树。
   * ★ 和 `doCreate` 里那段是同一个道理（见那边的长注释）：
   *   必须走 el-tree 的 `append`，直接 push 进 `data.children`
   *   在嵌套层级上不会显示、也不报错。
   */
  function attach(node, parentEl) {
    if (treeRef.value) {
      treeRef.value.append(node, parentEl ?? undefined)
    } else {
      ;(parentEl ? ensureChildren(parentEl.data) : props.roots).push(node)
    }
  }

  async function walk(list, parentEl) {
    for (const item of list) {
      const title = String(item?.title ?? '').trim()
      /*
       * ★ 空标题直接跳过，不发请求。
       *   后端 `OutlineNodeCreateDTO.title` 是 `@NotBlank`，
       *   发过去必然 400。而在一个「AI 生成的内容」的批次里，
       *   一条 400 会被作者理解成「AI 坏了」，其实是它少写了个标题。
       */
      if (!title) continue

      try {
        const node = await createOutlineNode(props.novelId, {
          // ★ 根层级要发 null 不是 undefined —— 见 api/outline.js 里那段
          parentId: parentEl ? parentEl.data.id : null,
          // ★ `type` 就是 OutlineNodeType 的 code（3=章 4=细纲），
          //   后端 mock 的注释里点名了这一点，可以原样透传
          nodeType: item.type,
          // ★ 后端 @Size(max = 200)。AI 偶尔会写出超长的标题，
          //   截断比整条失败好 —— 作者能在树上直接改。
          title: title.slice(0, 200),
          content: item.content ?? null,
        })

        attach(node, parentEl)
        created++

        const kids = Array.isArray(item.children) ? item.children : []
        if (kids.length) {
          /*
           * ★★ `getNode` 必须在 `append` **之后**调。
           *   `insertChild` 里那句 `node.initialize()` 才会把新节点
           *   登记进 `store.nodesMap` —— 在那之前 `getNode(新id)`
           *   返回 null，孙节点就会因为拿不到父实例而**全部挂到根上**，
           *   而且不报错。
           */
          const el = treeRef.value?.getNode(node.id)
          if (el) await walk(kids, el)
        }
      } catch {
        // 拦截器已经弹过具体原因（3007 父节点不属于这本书 / 3011 太深 …）
        failed.push(title)
      }
    }
  }

  try {
    const parentEl = parentId == null ? null : treeRef.value?.getNode(parentId) ?? null
    await walk(items, parentEl)

    // 新建的子节点在收起的父节点里看不见 —— 顺手展开
    parentEl?.expand()

    if (created && !failed.length) {
      ElMessage.success(`已创建 ${created} 个节点`)
    } else if (created) {
      ElMessage.warning(`创建了 ${created} 个节点，有 ${failed.length} 个没成功`)
    } else {
      ElMessage.error('一个节点都没能创建，请稍后重试')
    }
  } finally {
    busy.value = false
  }

  return { created, failed }
}

/*
 * ★ 只暴露 `addTree` 这一个写操作。
 *   树自己那套右键菜单 / 拖拽是组件内部的交互，外部不需要调 ——
 *   暴露得越多，外部就越有可能绕过 `busy` 那道闸。
 */
defineExpose({ addTree })
</script>

<template>
  <div class="outline-tree">
    <div v-if="failed" class="tip">
      大纲加载失败，<el-link type="primary" @click="emit('retry')">重试</el-link>
    </div>

    <el-empty
      v-else-if="!loading && !roots.length"
      :image-size="60"
      description="还没有大纲"
    >
      <el-button type="primary" size="small" @click="handleCreateRoot">创建大纲</el-button>
      <div class="tip">大纲是这本书的目录骨架，正文挂在「章」节点上</div>
    </el-empty>

    <el-tree
      v-else
      ref="treeRef"
      :data="roots"
      node-key="id"
      :props="TREE_PROPS"
      :default-expanded-keys="expandedKeys"
      :current-node-key="currentNodeId"
      :expand-on-click-node="false"
      highlight-current
      draggable
      :allow-drag="allowDrag"
      :allow-drop="allowDrop"
      @node-click="handleNodeClick"
      @node-contextmenu="handleContextMenu"
      @node-expand="handleExpand"
      @node-collapse="handleCollapse"
      @node-drop="handleDrop"
    >
      <template #default="{ data }">
        <span class="node-row">
          <el-tag :type="nodeTypeTag(data.nodeType)" size="small" effect="plain">
            {{ data.nodeTypeDesc }}
          </el-tag>
          <span class="node-title" :title="data.title">{{ data.title }}</span>
          <!--
            ★ 这个图标读的是**关联章节**的状态，不是 novel_outline.status
              （那一列是死列，P4 不写不读，见 utils/status.js）。
              所以它和右侧工作区显示的永远是同一个状态。
          -->
          <el-tag :type="nodeChapterTag(data).type" size="small" effect="plain">
            {{ nodeChapterTag(data).text }}
          </el-tag>
        </span>
      </template>
    </el-tree>

    <!--
      ★ 菜单 teleport 到 body：树的容器是 overflow: auto 的，
        留在里面的话菜单会被裁掉（而且 z-index 再高也没用）。
    -->
    <teleport to="body">
      <div
        v-if="menu.visible"
        ref="menuRef"
        class="ctx-menu"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      >
        <div class="ctx-item" @click="handleCreateChild">新建子节点</div>
        <div class="ctx-item" @click="handleRename">重命名</div>
        <div v-if="canCreateChapter" class="ctx-item" @click="handleCreateChapter">
          创建正文
        </div>
        <div class="ctx-divider"></div>
        <div class="ctx-item is-danger" @click="handleDelete">删除</div>
      </div>
    </teleport>
  </div>
</template>

<style scoped>
.outline-tree {
  /* ★ 不用 height: 100% —— 树的滚动由外层 .tree-body 负责，
     这里写死高度会让超出部分直接溢出容器而不是产生滚动条。 */
  min-height: 100%;
}

.tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
}

.node-row {
  display: flex;
  align-items: center;
  gap: 6px;
  /* ★ 树节点是 flex 容器里被压扁的常客：不给 min-width: 0 的话
     长标题会把 el-tag 挤变形，而不是省略号截断 */
  min-width: 0;
  width: 100%;
  padding-right: 8px;
}

.node-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* el-tree 的行高默认偏松，节点多的时候一屏看不了几个 */
:deep(.el-tree-node__content) {
  height: 30px;
}
</style>

<style>
/* ★ 右键菜单不用 scoped：它被 teleport 到了 body 上，
   scoped 的属性选择器作用不到它。类名带 ctx- 前缀避免误伤。 */
.ctx-menu {
  position: fixed;
  z-index: 3000;
  min-width: 132px;
  padding: 4px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
  font-size: 13px;
  user-select: none;
}

.ctx-item {
  padding: 7px 16px;
  color: #303133;
  cursor: pointer;
}

.ctx-item:hover {
  background: #ecf5ff;
  color: #409eff;
}

.ctx-item.is-danger:hover {
  background: #fef0f0;
  color: #f56c6c;
}

.ctx-divider {
  height: 1px;
  margin: 4px 0;
  background: #f0f2f5;
}
</style>
