/**
 * 评论接口 —— 对应后端的 `ReaderCommentController`。
 *
 * ★ 移植自 `reader-web/src/api/comment.js`，**逐条保留**它的三处警告。
 *   这一份不是"简化版"：评论这套契约里每一条注释都对应一个
 *   「写错了不报错、只是行为不对」的坑。
 *
 * ★★ 路径分散在三处，因为它们挂在不同的对象上，不是「一个模块一个前缀」：
 * ```
 *   书评：/api/novels/{novelId}/comments
 *   章评：/api/novels/{novelId}/chapters/{chapterId}/comments
 *   回复/点赞/删除：/api/comments/{id}/...     ← ★ 只有 id，没有 novelId
 * ```
 * 后两类之所以不重复带 novelId，是因为评论 id 已经是全局唯一的了，
 * 再带一个父级 id 就会出现「路径说 A、评论其实属于 B」的错配。
 * ★ 唯一的例外是**展开回复**那个接口 —— 它的路径里确实有 novelId，
 * 但后端注释里写了「仅用于路由，不参与查询」，那是为了让这一组
 * 读者端接口的路径形状保持一致。
 *
 * ★★ 评论对象用 `targetType` + `targetId` 表达（1 书评 / 2 章评），
 * **没有 `novelId` 字段**。理由见后端 `CommentVO` 的字段注释：
 * 一个有时装作品 id、有时装章节 id 的字段，前端 `if (row.novelId)`
 * 会在章评那半行上静默跳过。
 */
import request from '@/utils/request'

/* ==================== 读 ==================== */

/**
 * 书评列表（**匿名可读**）。
 *
 * @param {number|string} novelId
 * @param {{page?: number, size?: number}} params
 * @returns {Promise<{records: Array, total: number, current: number}>}
 *
 * ★★ 每条记录的字段（读者端**只有**这些，别指望更多）：
 * ```
 *   id / targetType / targetId / content / score / likeCount / createTime
 *   nickname / liked / mine / replyCount / replies
 * ```
 * 三条容易踩的：
 *   · `nickname` 为空时**整个 key 消失**（后端全局 non_null，评论人已注销）→
 *     写 `row.nickname || '已注销用户'`，**不要写 `=== null`**，那个永远不成立
 *   · `liked` / `mine` **匿名时是 null（key 消失）**，不是 false ——
 *     它们说的是「相对于**你**」，而匿名请求里根本不知道你是谁 →
 *     写 `row.liked ?? false`。★ 不能在后端填 false：
 *     那是在说「这个人没赞过」「这不是你写的」
 *   · `score` 只有书评有，章评恒为空 → 写 `row.score ?? null`
 *
 * ★ `replies` 是**截断过的**（最多 3 条），`replyCount` 才是总数。
 *   渲染「还有 N 条回复」用 `replyCount - replies.length`，
 *   拿 `replies.length` 当总数的话，按钮**永远不会出现**（长度永远 ≤ 3）。
 */
export function getNovelComments(novelId, params) {
  return request.get(`/novels/${novelId}/comments`, { params })
}

/**
 * 章评列表（**匿名可读**）。
 *
 * ★ 单独一个函数而不是给上面那个加个 chapterId 参数：两个 URL 的形状
 *   不一样（多一级 `/chapters/{id}`），拼在一个函数里就要写条件拼接，
 *   而条件拼接的路径在日志里最难看出来是哪一种。
 *
 * ★ 和书评同一条规矩：**不校验章节存不存在**，直接返回空列表。
 *   理由见后端 `CommentServiceImpl#readerPage` 的 javadoc ——
 *   校验与否的差别本身就是一份「某个 chapterId 存不存在」的探针，
 *   而这个接口是匿名的。
 */
export function getChapterComments(novelId, chapterId, params) {
  return request.get(`/novels/${novelId}/chapters/${chapterId}/comments`, { params })
}

/**
 * 展开一条一级评论的**全部**二级回复（分页，**匿名可读**）。
 *
 * @param {number|string} novelId ★ 路径里有它，但后端不用它查数据
 * @param {number|string} commentId 一级评论 id
 *
 * ★★ 为什么必须有这个接口：列表里的 `replies` 是**截断到 3 条**的。
 *   「还有 5 条回复」点开时要**重新请求**，不能在已经分页的接口上
 *   假装全都有 —— 一次把 200 条回复塞进列表响应，列表页就成了
 *   一条按回复数膨胀的查询。
 *
 * ★ 按**时间正序**（回复是对话，从早到晚读才对）—— 和一级评论的
 *   「最新在最上面」正好相反，两个都对，别统一。
 */
export function getReplies(novelId, commentId, params) {
  return request.get(`/novels/${novelId}/comments/${commentId}/replies`, { params })
}

/* ==================== 写 ==================== */

/**
 * 发表书评（**需登录**）。
 *
 * @param {number|string} novelId
 * @param {{content: string, score?: number}} data
 *        `score` 1~5，**不传 = 不打分**。★ 越界（0 或 99）会被后端 **400** 拒掉，
 *        不是夹到边界 —— 夹一下就把一个错误的分数洗成了合法的分数，
 *        而它会进 `novel.score` 那个平均分（见 `CommentCreateDTO` 的注释）。
 * @returns {Promise<number>} 新评论的 id
 *
 * ★★ **发完不会立刻出现在列表里**：新评论是「待审核」，
 *   而读者端列表只返回 `status = 1`。所以提示语必须写清
 *   「已提交，通过审核后显示」—— 只说「发表成功」的话，
 *   用户会以为评论丢了，然后再发一条（第二条撞上 10 秒频控，4004）。
 *
 * ★ **10 秒频控**（`4004`）是**按用户**的，不是按 IP、也不是按作品：
 *   同一本书连发两条会撞，换一本书连发也会撞。
 */
export function createNovelComment(novelId, data) {
  return request.post(`/novels/${novelId}/comments`, data)
}

/**
 * 发表章评（**需登录**）。
 *
 * ★ **没有 `score`** —— 章评不打分。评分是对整本书的，挂到单章上
 *   就会出现「第 3 章 5 分、第 7 章 1 分」，而这两个数最后都会进
 *   同一个 `novel.score`，把整本书的均分搅乱。
 *   （后端 `CommentCreateDTO` 是共用的，所以字段在，但章评这条路
 *   不传它 —— 服务端也不看。）
 */
export function createChapterComment(novelId, chapterId, data) {
  return request.post(`/novels/${novelId}/chapters/${chapterId}/comments`, data)
}

/**
 * 回复一条评论（**需登录**）。
 *
 * @param {number|string} parentId 被回复的那条评论 id
 * @param {{content: string}} data ★ 没有 score
 *
 * ★★ `targetType` / `targetId` **由服务端从父评论继承**，调用方不传也传不了。
 *   自己填的话会出现「父是书评、子填了章评」这种行，而它的表现是
 *   **这条回复根本不出现** —— 不报错、不提示，就是没有。
 *
 * ★★ **只支持两级**：回复一条回复返回 `4006`。所以「回复」按钮
 *   只应该出现在**一级评论**上（二级回复上不显示「回复」，否则
 *   那个按钮点了必然报错）。
 *   ★ 但**点赞**按钮两级都有。
 *
 * ★ 父评论不可见（不存在 / 已删除 / 待审核 / 已隐藏）统一返回 `4001`，
 *   四种情况刻意不区分（区分开等于告诉调用方「这个 id 是真的，只是还没过审」）。
 */
export function replyComment(parentId, data) {
  return request.post(`/comments/${parentId}/replies`, data)
}

/**
 * 点赞（**需登录**，**幂等**）。
 *
 * @returns {Promise<number>} 点赞后的**新总数**，前端直接拿它更新界面
 *
 * ★ 返回总数而不是让前端 `+1`：界面上那个数字必须和服务端一致，
 *   而「我刚才到底赞没赞过」在连点、多标签页、换设备时是不确定的。
 */
export function likeComment(id) {
  return request.post(`/comments/${id}/like`)
}

/**
 * 取消点赞（**需登录**，**幂等**）。
 *
 * ★★ **路径和「删除评论」只差一个 `/like` 后缀**（`DELETE /comments/{id}`），
 *   这是本模块最容易误伤的地方：少写一个后缀就从「取消点赞」变成
 *   **真的把评论删了**，而且两者都返回成功。改这里时请务必看清。
 *
 * ★ 没赞过也返回成功（幂等）：用户的意图是「让它变成未赞」，已经是了。
 */
export function unlikeComment(id) {
  return request.delete(`/comments/${id}/like`)
}

/**
 * 删除自己的评论（**需登录**，逻辑删）。
 *
 * ★ 删一级评论会**连带删掉它的全部二级回复**（后端同一事务里做的）——
 *   否则子回复变成孤儿：页面上永远不显示、却一直占着库。
 *
 * ★ 失败码有三种，**前端只需要区分出一种**：
 *   · `4001` 不存在（可能链接是旧的，刷新页面就行）
 *   · `4002` 只能删自己的 ← ★ 唯一的「用户做错了」，
 *     但界面上**不该收到它**：删除按钮只长在 `mine === true` 的行上
 *   · `4003` 已经被删过了（上一次点击其实成功了，不需要再做什么）
 *   三种都由 `request.js` 的拦截器弹提示，调用方 catch 住即可。
 */
export function deleteComment(id) {
  return request.delete(`/comments/${id}`)
}
