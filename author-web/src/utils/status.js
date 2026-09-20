/**
 * 状态 → 显示样式的映射。作品 / 章节 / 大纲节点类型三张表。
 *
 * ------------------------------------------------------------
 * ★★ 这里【只有颜色】，没有文案
 * ------------------------------------------------------------
 * 文案（「草稿」「待审核」）一律用后端返回的 `statusDesc` / `nodeTypeDesc`，
 * 前端**不维护第二份** `0 → '草稿'` 的映射表。
 *
 * 理由：那份映射表改起来要改三处（三个前端），而且改了不会有任何报错 ——
 * 只是某个端上显示成旧文案。更要紧的是，文案是**会变的**
 * （「已上架」可能改成「连载中」），一旦前端也有一份，
 * 两边不同步的第一个症状就是「同一个东西在两个页面叫两个名字」。
 *
 * ★ 但**颜色**留在前端是对的：它是纯粹的视觉决定，
 *   后端不该关心 `#e6a23c` 和「warning」哪个是橙色。
 *
 * ------------------------------------------------------------
 * ★★ 三张表对应的状态码，别看串了
 * ------------------------------------------------------------
 *   作品 NovelStatus   0草稿 1待审核 2已上架 3已下架 4已驳回   ← **有 3**
 *   章节 ChapterStatus 0草稿 1待审核 2已发布 4已驳回          ← **没有 3**
 *
 * 章节没有「下架」这个概念（下架是作品级动作），所以 3 是空的。
 * 从作品那张表复制过来最省事，也最容易错 —— 于是下面分成两个函数，
 * 而不是一个「通用」的 statusType()。
 */

/** Element Plus 的 el-tag type 取值 */
const NOVEL_STATUS_TYPE = {
  0: 'info', // 草稿
  1: 'warning', // 待审核
  2: 'success', // 已上架
  3: 'info', // 已下架
  4: 'danger', // 已驳回
}

const CHAPTER_STATUS_TYPE = {
  0: 'info', // 草稿
  1: 'warning', // 待审核
  2: 'success', // 已发布
  4: 'danger', // 已驳回
  // 3 —— 不存在。章节不能单独下架
}

/**
 * 作品的 el-tag type。
 * ★ 兜底给 'info'：后端将来加一个状态码时，前端显示成灰标签而不是 undefined
 *   （`type="undefined"` 会让 el-tag 回退到默认蓝色，看起来像是「有特殊含义」）。
 */
export function novelStatusType(status) {
  return NOVEL_STATUS_TYPE[status] ?? 'info'
}

/** 章节的 el-tag type。★ 注意它和作品那张表不是同一张，别合并 */
export function chapterStatusType(status) {
  return CHAPTER_STATUS_TYPE[status] ?? 'info'
}

/* ==================== 大纲节点类型 ==================== */

/**
 * 大纲节点类型：1全书 2卷 3章 4细纲。
 *
 * ★ 这里**只有颜色**，文案用后端给的 `nodeTypeDesc`（「卷」「章」……）。
 * ★ 四级的颜色刻意从浅到深（蓝 → 绿 → 橙 → 紫），
 *   因为树是缩进的，同一层级用同色反而看不出层级；
 *   不同类型的颜色差异能让「这一行是章还是细纲」一眼可见。
 */
const NODE_TYPE_TAG = {
  1: 'primary', // 全书
  2: 'success', // 卷
  3: 'warning', // 章
  4: 'info', // 细纲
}

export function nodeTypeTag(nodeType) {
  return NODE_TYPE_TAG[nodeType] ?? 'info'
}

/**
 * 大纲树上的状态图标 —— **一行判断，不需要第二份映射表**。
 *
 * ★★ 这里【不读 `novel_outline.status`】—— 那一列是**死列**，P4 不写不读。
 *   图标一律显示**关联章节**的状态，外加「未创建正文」这一个派生状态。
 *   后端同时给了 `chapterStatusDesc`，所以连章节状态的中文都不用前端拼。
 *
 * 之所以能这么简单，是因为 `chapterId == null` 恰好就是「未创建正文」，
 * 而这一条又能推出「草案/写作中」的全部取值 —— 三个字段本来就冗余。
 *
 * @param {object} node OutlineNodeVO
 * @returns {{text: string, type: string}} 直接喂给 <el-tag :type="..." >
 */
export function nodeChapterTag(node) {
  if (!node || !node.chapterId) {
    return { text: '未创建正文', type: 'info' }
  }
  return {
    text: node.chapterStatusDesc || '已有正文',
    type: chapterStatusType(node.chapterStatus),
  }
}
