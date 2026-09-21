/**
 * 状态 → 显示样式的映射。管理端要展示的状态比创作中心多，这里共八张表：
 * 作品 / 章节 / 评论 / 作者申请 / 用户 / 公告 / 角色 / 菜单。
 *
 * ------------------------------------------------------------
 * ★★ 这里【只有颜色】，没有文案
 * ------------------------------------------------------------
 * 文案（「草稿」「待审核」）一律用后端返回的 `statusDesc` / `typeDesc`，
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
 * ★★ 六张表的取值，别看串了
 * ------------------------------------------------------------
 *   作品 NovelStatus     0草稿 1待审核 2已上架 3已下架 4已驳回  ← **有 3**
 *   章节 ChapterStatus   0草稿 1待审核 2已发布 4已驳回          ← **没有 3**
 *   评论 CommentStatus   0待审核 1已显示 2已隐藏                ← **有 2 没有 3**
 *   作者申请 apply       0待审  1通过   2驳回
 *   用户 status          1启用  0禁用                          ← **1 才是启用**
 *   公告 status          1发布  0下线                          ← **1 才是发布**
 *   角色 status          1启用  0停用                          ← **1 才是启用**
 *   菜单 status          1启用  0停用                          ← **1 才是启用**
 *
 * ★ 章节没有「下架」这个概念（下架是作品级动作）。
 *   从作品那张表复制过来最省事，也最容易错 —— 所以分成两个函数
 *   而不是一个「通用」的 statusType()。
 *
 * ★★ 后面四张（用户 / 公告 / 角色 / 菜单）都是「1 = 好的」，
 *   而作品 / 章节 / 评论是「0 = 还没开始、2 = 好的」，作者申请又是它自己
 *   那一套「0待审 1通过 2驳回」。**同一个项目里三种相反的约定**，
 *   照抄相邻那张表几乎必错 —— 所以下面每个函数都单独写、单独注释。
 *
 * ★ 角色和菜单是 S14 加的：两张都是**1/0 开关**的字典表，和「用户启用/封禁」
 *   同形。它们的 VO 里**没有 `statusDesc`**（RoleVO / AdminUserVO / MenuVO
 *   都没有），所以那两个字由页面写死 —— 和用户管理页对「正常/已封禁」的
 *   处理一致。**别把这条和上面那条 convention 混起来**：有状态机的那几个
 *   才由后端给文案，因为它们的状态名是**可能改的**（「已上架」可能变成
 *   「连载中」），而这种开关叫「启用/停用」不会变。见 api/menu.js 的说明。
 */

/** Element Plus 的 el-tag type 取值：primary / success / info / warning / danger */

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
 * 评论状态。★ 只有三个值，**没有「已删除」**。
 *
 * 后端 CommentStatus 的类注释写得很清楚：「已隐藏」是管理员的操作（违规内容），
 * 「已删除」是用户自己删的，走的是表的 deleted 逻辑删除字段 ——
 * **两者是不同维度**，所以枚举里刻意没有 DELETED。
 *
 * ★ 所以管理端的评论列表要同时显示两个维度：
 *   「审核状态」看这一列，「是不是被作者自己删了」看 `deleted` 字段。
 *   把「已删除」当成第四种状态塞进这张表是错的 ——
 *   一条评论可以既是「已显示」又是「已删除」。
 */
const COMMENT_STATUS_TYPE = {
  0: 'warning', // 待审核
  1: 'success', // 已显示
  2: 'danger', // 已隐藏
}

/** 作者申请状态。★ 注意和用户状态不同：这里是 0待审 1通过 2驳回 */
const APPLY_STATUS_TYPE = {
  0: 'warning', // 待审
  1: 'success', // 通过
  2: 'danger', // 驳回
}

/**
 * 用户状态。★ 这张表是「1 = 好的」，和上面几张**相反**。
 *
 * 取值来自后端 CommonConstant.STATUS_ENABLED(1) / STATUS_DISABLED(0)。
 * 判据只有一处：{@code AuthServiceImpl} 登录时比对的也是这两个常量。
 * 这里跟着写 1/0 而不是 true/false —— 后端传过来就是数字，
 * 前端多做一次布尔转换，多一处可能转错的地方。
 */
const USER_STATUS_TYPE = {
  1: 'success', // 启用
  0: 'danger', // 禁用（封禁）
}

/** 公告状态。★ 也是「1 = 好的」：1发布 0下线 */
const NOTICE_STATUS_TYPE = {
  1: 'success', // 发布
  0: 'info', // 下线
}

/**
 * 角色状态。★ 也是「1 = 好的」，和用户那张同形。
 *
 * 停用用 'info'（灰）而不是 'danger'（红）：停用一个角色是**正常的运营动作**
 * （这个岗位暂时不设了），不是事故。红色留给「封禁用户」那种处置性动作 ——
 * 满屏红标签会让真正需要注意的那一行失去对比度。
 */
const ROLE_STATUS_TYPE = {
  1: 'success', // 启用
  0: 'info', // 停用
}

/** 菜单状态。★ 同角色：1启用 0停用，停用是灰的 */
const MENU_STATUS_TYPE = {
  1: 'success', // 启用
  0: 'info', // 停用
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

/** 评论的 el-tag type */
export function commentStatusType(status) {
  return COMMENT_STATUS_TYPE[status] ?? 'info'
}

/** 作者申请的 el-tag type */
export function applyStatusType(status) {
  return APPLY_STATUS_TYPE[status] ?? 'info'
}

/** 用户的 el-tag type。★ 1=启用（绿），0=禁用（红） */
export function userStatusType(status) {
  return USER_STATUS_TYPE[status] ?? 'info'
}

/** 公告的 el-tag type。★ 1=发布（绿），0=下线（灰） */
export function noticeStatusType(status) {
  return NOTICE_STATUS_TYPE[status] ?? 'info'
}

/** 角色的 el-tag type。★ 1=启用（绿），0=停用（灰） */
export function roleStatusType(status) {
  return ROLE_STATUS_TYPE[status] ?? 'info'
}

/** 菜单的 el-tag type。★ 1=启用（绿），0=停用（灰） */
export function menuStatusType(status) {
  return MENU_STATUS_TYPE[status] ?? 'info'
}

/* ==================== 纯展示型的映射（没有「状态机」含义） ==================== */

/**
 * 评论类型：1书评 2章评。文案同样用后端的 `targetTypeDesc`，这里只给颜色。
 * ★ 用 primary/info 区分：书评是「对整本书」，章评是「对某一章」，
 *   管理端一眼要能分出哪些是书评（书评才带评分）。
 */
const COMMENT_TARGET_TAG = {
  1: 'primary', // 书评
  2: 'info', // 章评
}

export function commentTargetTag(targetType) {
  return COMMENT_TARGET_TAG[targetType] ?? 'info'
}

/**
 * 公告类型：1公告 2通知。
 * ★ 这两个的区别是**语气**：公告是站方正式发布（改规则、维护通知），
 *   通知是轻量的提醒。用 primary / info 区分，读者端横幅也复用这一张表。
 */
const NOTICE_TYPE_TAG = {
  1: 'primary', // 公告
  2: 'info', // 通知
}

export function noticeTypeTag(type) {
  return NOTICE_TYPE_TAG[type] ?? 'info'
}

/**
 * 菜单类型：1目录 2菜单 3按钮。
 * ★ 同样是「纯展示」—— 它没有状态机含义，一个节点是目录还是菜单不会变。
 * ★ 文案（「目录」「菜单」）在页面里写死：`MenuVO` 没有 `typeDesc`，
 *   理由见文件头最后一条和 api/menu.js。
 * ★ `type = 3`（按钮）种子里没有，但这里留着 —— 后端把 3 定为合法取值，
 *   真出现时给个灰标签，比 undefined 落到 el-tag 的默认蓝色好。
 */
const MENU_TYPE_TAG = {
  1: 'info', // 目录
  2: 'primary', // 菜单
  3: 'success', // 按钮
}

export function menuTypeTag(type) {
  return MENU_TYPE_TAG[type] ?? 'info'
}
