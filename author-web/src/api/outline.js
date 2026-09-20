/**
 * 大纲树接口 —— 对应后端 AuthorOutlineController。
 *
 * ★ /author/novels/{novelId}/outline/nodes   建节点（大纲属于某本书，要带上书）
 *   /author/outline/nodes/{nodeId}/...       改 / 删 / 移动 / 建正文
 *
 * ★★ 这个文件里最值得注意的是【哪两个 id 在请求体里】：
 *   create 的 parentId、move 的 targetParentId。
 *   它们不像 URL 上的 id 那样一眼可见，但后端会校验它们属于同一本书，
 *   传错了会拿到 3007。前端要做的是：**永远从当前树节点上取值**，
 *   不要把用户输入或缓存里的 id 直接塞进去。
 */
import request from '@/utils/request'

/**
 * 取整棵大纲树。
 *
 * @returns {Promise<{roots: Array, totalNodes: number}>}
 *   ★ `roots` 是**数组**，正常长度为 1；`{roots: []}` 表示还没建大纲，
 *     不是错误。前端要按数组渲染（最坏情况渲染出两棵树，而不是打不开页面）。
 *   ★ 每个节点的 `children` **一定是数组**，叶子节点是 `[]` 不是 `null`
 *     （后端配了 non_null，null 字段会整个从 JSON 里消失，
 *      于是 `node.children.length` 会 TypeError → 整棵树白屏）。
 */
export function getOutlineTree(novelId) {
  return request.get(`/author/novels/${novelId}/outline`)
}

/**
 * 新建大纲节点。
 *
 * @param {number} novelId
 * @param {object} data
 * @param {number|null} [data.parentId] 不传 = 建**根节点**（全书大纲）。一本书只能有一个根，重复建返回 3008
 * @param {number} [data.nodeType] 1全书 2卷 3章 4细纲。不传时后端按父节点推导
 * @param {string} data.title 必填，最多 200 字
 * @param {string} [data.content] 剧情笔记
 * @returns {Promise<object>} **新建的节点本身**（形状和树里的节点一致：
 *   `{id, novelId, parentId, nodeType, nodeTypeDesc, title, content, sort,
 *     chapterId: null, children: []}`）
 *
 * ★★ 返回整个节点而不是 id，是**为了前端能不重拉树**：
 *   拿到 id 却拼不出能渲染的节点（缺 `nodeTypeDesc`，那个枚举的 desc
 *   只有后端有），就只能重拉整棵树 —— 而 `el-tree` 换数据引用时会把
 *   **所有节点的展开状态清空**，作者每建一个节点整棵树就哗地收起一次。
 *   → 调用方（OutlineTree.vue）直接把它 push 进父节点的 `children` 即可。
 *
 * ★ 不能传 sort —— 新节点一律追加到同级末尾，序号由后端分配。
 *   让前端传序号的话，两个标签页同时建节点会传出同一个值，
 *   而这张表上没有唯一键能拦住它。
 */
export function createOutlineNode(novelId, data) {
  return request.post(`/author/novels/${novelId}/outline/nodes`, data)
}

/**
 * 修改节点（标题和内容**全量覆盖**）。
 *
 * ★★ `content` 传 `null` 表示「**清空**」，不是「不改」——
 *   后端为此专门绕开了 MyBatis-Plus 的 updateById（它跳过 null 字段，
 *   用它的话「清空内容」保存后内容还在，而且不报任何错）。
 *   所以这里传 `null` 是有效的，不要在前端「顺手过滤掉 null」。
 *
 * ★ 改节点标题**不会**同步到已关联的章节标题，这是刻意的
 *   （章节标题的写入口带着「待审核期间不能改」的守卫，
 *    同步过去等于开一条绕过那个守卫的写路径）。
 *   → 界面上要说清这一点，否则作者会以为改了个假的。
 */
export function updateOutlineNode(nodeId, data) {
  return request.put(`/author/outline/nodes/${nodeId}`, data)
}

/**
 * 删除节点**及其整棵子树**。
 *
 * ★★ **绝不删除任何正文章节。** 返回值里的 `linkedChapterCount` 是
 *   「其中已有正文的节点数」，前端**必须**用它提示
 *   「其中 N 章正文未被删除，可在章节列表中找到」——
 *   用户的直觉一定是「删了大纲我的正文没了」。
 *
 * @returns {Promise<{deletedNodes: number, linkedChapterCount: number}>}
 */
export function deleteOutlineNode(nodeId) {
  return request.delete(`/author/outline/nodes/${nodeId}`)
}

/**
 * 拖拽移动 / 排序。
 *
 * @param {number} nodeId 被拖动的节点
 * @param {object} data
 * @param {number|null} data.targetParentId 目标父节点，`null` = 移到根层级
 * @param {number|null} data.beforeNodeId 插到这个兄弟**前面**，`null` = 追加到末尾
 *
 * ★★ 前端的三个 dropType 到入参的映射（在 OutlineTree.vue 里）：
 *
 *   | el-tree dropType | 入参 |
 *   |---|---|
 *   | `inner` 拖进节点里 | `{targetParentId: 目标id, beforeNodeId: null}` |
 *   | `after`            | `{targetParentId: 父id,   beforeNodeId: 下一个兄弟id 或 null}` |
 *   | `before`           | `{targetParentId: 父id,   beforeNodeId: 该节点id}` |
 *
 * ★★★ 一个必踩的坑：el-tree 根层级的 `data.parent.data` 是一个
 *   **包裹用的虚拟根，它的 id 是 `undefined`**。
 *   必须把它转成 `null` 再发 —— 直接发 `undefined` 的话，
 *   JSON.stringify 会把这个键**整个丢掉**（和传 null 的效果碰巧一样），
 *   但传 `null` 是明确的意图，而 `undefined` 是「忘了转」，
 *   两者在别的字段上（比如 beforeNodeId）表现完全不同。
 *   在 OutlineTree.vue 里统一用 toIdOrNull() 收口。
 *
 * ★ 为什么不用 position 索引：**没有任何 position 值能表达「追加到末尾」**，
 *   而「拖进节点里 = 追加为最后一个子节点」恰恰是最高频的操作。
 *
 * **错误码：** 3007 目标父节点不属于这本作品 / 3009 目标是自己或自己的子孙 /
 * 3010 库里本来就有环（★ 和 3009 分开：提示的是「数据坏了」，不是「你操作错了」）/
 * 3011 移动后深度超过 20 层。
 */
export function moveOutlineNode(nodeId, data) {
  return request.put(`/author/outline/nodes/${nodeId}/move`, data)
}

/**
 * 由大纲节点创建正文。**★★ 这个接口是幂等的。**
 *
 * @returns {Promise<{created: boolean, chapterId: number, chapterNo: number,
 *                    title: string, status: number, statusDesc: string, wordCount: number}>}
 *
 * ★★ 前端**必须**按 `created` 区分提示语：
 *   `created: true`  → 「已创建章节，正在打开」
 *   `created: false` → 「这一章已有正文，正在打开」
 *   不区分的话，用户会以为多建了一章。
 *   而**报错更糟** —— 他点第二下时意图和第一下完全相同，
 *   报错等于说「你想做的事已经做完了，这是个错误」。
 *
 * ★ 连点两下也只会建出**一章**：后端用 SELECT ... FOR UPDATE 串行化，
 *   后到的请求会阻塞，等第一个提交后走幂等分支。
 *   → 所以前端**不需要**为了这个接口做防连点，也不需要禁用按钮。
 */
export function createChapterFromNode(nodeId) {
  return request.post(`/author/outline/nodes/${nodeId}/chapter`)
}
