/**
 * 大纲树的**展开状态**（Pinia + localStorage）。
 *
 * ============================================================
 * ★★ 为什么只有展开状态进 store，正文内容不进
 * ============================================================
 * 判据是「这个状态的读者有几个」：
 *
 *   · 展开状态：读者是「同一本书的下一次访问」—— 关掉浏览器、
 *     换一个标签页、明天再来，都要求树还是上次那个样子。
 *     它必须活得比组件久，所以要有 localStorage。
 *
 *   · 正文内容：读者**只有当前这个工作区**。作者切到别的节点，
 *     上一章就该从界面上消失（数据在服务端，切回来重新拉）。
 *     把它放进全局 store 的代价是：**每敲一个字都触发一次
 *     全站响应式更新** —— 树、导航、面包屑全部重新求值。
 *     这和 reader-web 里「阅读进度不进 Pinia」是同一条理由。
 *
 * ============================================================
 * ★★ 为什么按 novelId 分桶，而不是存一个全局数组
 * ============================================================
 * 节点 id 是全局唯一的，看起来可以只存一个扁平数组。但那样
 * 有两个问题：① 数组会随作者看过的书无限增长，而且没有任何
 * 自然的清理时机（他删掉一本书，那些 id 就永远留在里面了）；
 * ② 「清空这本书的展开状态」变成了一个需要遍历的操作。
 * 按 novelId 分桶之后，删书时 `forget(novelId)` 就够了。
 *
 * ============================================================
 * ★★ 为什么这里的 localStorage 全部包在 try/catch 里
 * ============================================================
 * localStorage 在**隐私模式、禁用了站点数据、配额写满**这三种
 * 情况下会**抛异常**（不是返回 null）。而展开状态是纯粹的
 * 体验优化 —— 为了它让整个工作台打不开，是完全不成比例的。
 * 所以读失败当「没有记录」，写失败当「这次没存上」，都不上报。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

/** ★ 加前缀，和其它应用的 key 区分开（三个前端共用 localhost 这一个 origin 下不同的端口，但 localStorage 是按端口隔离的，前缀只是给 DevTools 里好认） */
const STORAGE_KEY = 'author:outline:expanded'

export const useOutlineStore = defineStore('outline', () => {
  /**
   * 形状：`{ [novelId: string]: number[] }`
   *
   * ★ key 是字符串：localStorage 只能存字符串，JSON 往返一趟之后
   *   数字键会变成字符串键。**读的时候也要按字符串查**（`state[String(novelId)]`），
   *   否则 `state[5]` 和 `state['5']` 看起来一样、实际取不到 ——
   *   而这个 bug 的表现只是「展开状态没保存」，不会报任何错。
   */
  const expanded = ref(readAll())

  function readAll() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return {}
      const parsed = JSON.parse(raw)
      // ★ 存进去的东西是上一个版本写的，可能不是对象（被手工改过、或
      //   曾经用别的形状存过）。这里不做结构校验的话，`state[id]` 会在
      //   一个字符串或数字上取值，得到 undefined —— 不报错，但展开状态全丢。
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
    } catch {
      return {}
    }
  }

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(expanded.value))
    } catch {
      // ★ 配额满 / 隐私模式。展开状态丢了就丢了，不影响任何功能
    }
  }

  /**
   * 这本书当前展开了哪些节点。
   *
   * ★ 返回的是**存储里的那个数组本身**（不是拷贝）—— 调用方只把它
   *   喂给 el-tree 的 `default-expanded-keys`，不会改它。
   *   拷贝一份的话，每次 `@node-expand` 都会因为引用变了而触发
   *   el-tree 的 defaultExpandedKeys watcher，把用户刚**收起**的
   *   节点又展开回来。
   */
  function expandedKeys(novelId) {
    return expanded.value[String(novelId)] ?? []
  }

  /** 有没有这本书的记录。用来区分「从没打开过」和「打开过但全部收起了」 */
  function hasRecord(novelId) {
    return Array.isArray(expanded.value[String(novelId)])
  }

  /**
   * 记下一个节点的展开/收起。
   *
   * ★★ **替换数组而不是原地 push/splice**。el-tree 的
   *   `default-expanded-keys` 是靠 `watch` 感知变化的，
   *   原地改数组引用不变，watcher **不会触发** ——
   *   表现是「这一层展开状态存进去了，但界面没反应」。
   */
  function setExpanded(novelId, nodeId, isExpanded) {
    const key = String(novelId)
    const current = expanded.value[key] ?? []
    const has = current.includes(nodeId)

    // ★ 状态没变就不写：el-tree 在初始化时会为每个默认展开的节点
    //   触发一次 node-expand，不判断的话「打开页面」这个动作本身
    //   就会写一次 localStorage。
    if (has === isExpanded) return

    const next = isExpanded ? [...current, nodeId] : current.filter((id) => id !== nodeId)
    expanded.value = { ...expanded.value, [key]: next }
    persist()
  }

  /**
   * 把一批节点一次性标成展开（不触发逐条写入）。
   *
   * ★ 用在「第一次打开这本书」的默认展开上：那时要展开的是
   *   当前节点往上的一整条路径，逐个调 setExpanded 会写 N 次
   *   localStorage，而每次都要 JSON.stringify 整个对象。
   */
  function expandAll(novelId, nodeIds) {
    const key = String(novelId)
    const current = expanded.value[key] ?? []
    // ★ 用 Set 去重：路径上可能有已经在里面的节点
    const next = [...new Set([...current, ...nodeIds])]
    if (next.length === current.length) return
    expanded.value = { ...expanded.value, [key]: next }
    persist()
  }

  /** 忘掉这本书（删书时调，非必须 —— 留着也只是几条永远不会再命中的 id） */
  function forget(novelId) {
    const key = String(novelId)
    if (!(key in expanded.value)) return
    const next = { ...expanded.value }
    delete next[key]
    expanded.value = next
    persist()
  }

  return { expandedKeys, hasRecord, setExpanded, expandAll, forget }
})
