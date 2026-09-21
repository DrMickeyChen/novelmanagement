import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import AdminLayout from '@/layout/AdminLayout.vue'
import { getToken, getUser, redirectToReaderLogin, READER_WEB_URL } from '@/utils/auth'

/**
 * 管理端路由。
 *
 * 和创作中心一样，本应用【没有自己的登录页】——
 * 未登录时统一跳到阅读端的登录页（§7.5 第 3 条约定），
 * 避免出现三个登录入口。
 *
 * ★ 路由随 P5 的每一步增加，不是一次性列全的：
 *   提前写好指向还不存在的文件的 route，Vite 的构建期
 *   解析动态 import 时会直接失败 —— 也就是「页面还没做，
 *   但整个应用构建不过」。所以做一页加一条。
 */
const routes = [
  {
    path: '/',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台' },
      },
      {
        /*
         * 作品管理 —— 全部作品 + 筛选 + 上下架。
         * ★ 审核不在这里，见 views/novel/index.vue 的文件头。
         */
        path: 'novel',
        name: 'novelList',
        component: () => import('@/views/novel/index.vue'),
        meta: { title: '作品管理' },
      },
      {
        /*
         * 审核中心 —— **P5 的验收页**。
         * 两个 tab：待审作品 / 待审章节。作品的「审章节」从这里进下一层。
         */
        path: 'audit',
        name: 'auditCenter',
        component: () => import('@/views/audit/index.vue'),
        meta: { title: '审核中心' },
      },
      {
        /*
         * 某本书的章节审核 —— 验收主线的中间那一步。
         *
         * ★ `:novelId` 是**路径参数**而不是 query（`/audit?novelId=1`）：
         *   它在这条路径上是**必需**的、不是可选的筛选条件。
         *   用 query 的话，「/audit」和「/audit?novelId=1」会落到
         *   同一个组件实例上，Vue Router 不会重建组件 ——
         *   从一个 `novelId` 切到另一个时，`onMounted` 不再触发，
         *   页面显示的还是上一本书的章节。
         *   （真要留在这个组件里也行，但得给 `novelId` 加 watch 重新拉数据 ——
         *     路径参数没这个问题，因为路径不同 = 不同路由记录。）
         */
        path: 'audit/novel/:novelId',
        name: 'novelChapterAudit',
        component: () => import('@/views/chapter/audit.vue'),
        meta: { title: '章节审核' },
      },
      {
        /*
         * 分类管理 —— 增删改 + 启停。
         *
         * ★ 后端 AdminCategoryController 三个接口 P2 就写好了，
         *   这一页是纯前端 —— P5 里少见的「只写界面」的一步。
         */
        path: 'category',
        name: 'categoryList',
        component: () => import('@/views/category/index.vue'),
        meta: { title: '分类管理' },
      },
      {
        /*
         * 审核日志 —— 只读流水。
         *
         * ★ 它和上面几页是**两种东西**：上面几页在改数据，这一页只是看。
         *   界面上不会有任何写入口，以后也不该有（详见页面文件头）。
         */
        path: 'log',
        name: 'auditLog',
        component: () => import('@/views/log/index.vue'),
        meta: { title: '审核日志' },
      },
      {
        /*
         * 评论管理 —— 列表 + 筛选 + 审核（通过 / 隐藏）。
         *
         * ★★ 这一页【没有删除按钮】，后端也没有删评论的管理接口。
         *   管理员的处置动作是「隐藏」（可逆、留痕），不是删除。
         *   这是有意的，别顺手补一个 —— 理由见
         *   views/comment/index.vue 和 api/comment.js 的文件头。
         *
         * ★ 和「审核中心」是两个页面，别合并：
         *   那个审的是**作品的准入**（能不能上架），
         *   这个审的是**已发布内容的合规**（要不要藏起来）。
         *   两者的状态机、权限点、动作文案都不一样。
         */
        path: 'comment',
        name: 'commentList',
        component: () => import('@/views/comment/index.vue'),
        meta: { title: '评论管理' },
      },
      {
        /*
         * 用户管理 —— 列表 + 封禁/解封 + 改角色 + 重置密码。
         *
         * ★ 三个写操作都会**强制对方下线**（后端 tokenStore.removeAll），
         *   页面里对这件事有专门的处理，尤其是「把自己也踢掉」那一段，
         *   见 views/user/index.vue 的文件头。
         *
         * ★ 这一页没有「新增用户」—— 注册是阅读端的事，后端也没有建号接口。
         */
        path: 'user',
        name: 'userList',
        component: () => import('@/views/user/index.vue'),
        meta: { title: '用户列表' },
      },
      {
        /*
         * 作者申请审核 —— 待办队列 + 通过（授予 AUTHOR 角色）/ 驳回。
         *
         * ★★ 这一页的「通过」是真的改权限：改状态 + 写 sys_user_role +
         *   把申请人踢下线，三件事一个事务。
         * ★ 只有**待审**的行可审，非待审行的按钮是禁用的 ——
         *   理由（尤其是「为什么连幂等的重复通过也要禁」）写在
         *   views/authorApply/index.vue 的文件头。
         *
         * ★ 读者侧的**提交**入口不在这里，在阅读端（P6 做 UI，
         *   后端接口 P5 已经做到 curl 可验）。
         */
        path: 'author-apply',
        name: 'authorApplyList',
        component: () => import('@/views/authorApply/index.vue'),
        meta: { title: '作者申请' },
      },
      {
        /*
         * 角色管理 —— 列表 + 增删改 + 给角色勾菜单。
         *
         * ★★ 这是全项目唯一一处「UI 操作能造成不可逆损害」的入口：
         *   管理员去掉自己角色上的「角色管理 / 菜单管理」权限，就再也没有
         *   界面能改回来了（只能进 MySQL）。后端有一条精确的自锁防护（1020），
         *   页面里对这件事有专门的提醒，见 views/role/index.vue 的文件头。
         *
         * ★ 三个写操作都会**强制该角色下的用户下线**（后端 tokenStore.removeAll），
         *   而且它不区分操作者 —— 改自己所属的角色会把当前标签页一起踢掉。
         */
        path: 'role',
        name: 'roleList',
        component: () => import('@/views/role/index.vue'),
        meta: { title: '角色管理' },
      },
      {
        /*
         * 菜单管理 —— **只读**的权限清单。
         *
         * ★ 这一页没有写入口，后端也只有一个 GET：菜单树由 seed.sql 定义，
         *   改它等于改代码（每加一个权限点，前端路由和 AdminLayout 也要加）。
         *   给人感觉「能编辑」比不给编辑更糟 —— 改完以为生效了，实际没有。
         *
         * ★ 它同时是 `path` 那一列的核对入口：那一列长期没有消费者，
         *   曾经 12 个叶子里 11 个写错（`/novel/list` vs 真实的 `/novel`）。
         */
        path: 'menu',
        name: 'menuList',
        component: () => import('@/views/menu/index.vue'),
        meta: { title: '菜单管理' },
      },
      {
        /*
         * 公告管理 —— 列表 + 增删改 + 发布 / 下线。
         *
         * ★★ 这一页是**唯一一处会直接改变读者端首页**的管理页：
         *   发一条公告，读者端首页顶上立刻多一条横幅；下线，横幅立刻消失。
         *   所以页面里对「下线 vs 删除」这个区别有专门的三处文案
         *   （列表上方的说明 + 两个确认框），见 views/notice/index.vue。
         *
         * ★ 「发布 / 下线」没有独立接口，走的就是 `PUT` 全量覆盖 ——
         *   和分类管理页的「启用 / 停用」同一套做法（公告没有状态机）。
         */
        path: 'notice',
        name: 'noticeList',
        component: () => import('@/views/notice/index.vue'),
        meta: { title: '公告管理' },
      },
      {
        /*
         * ★ 连通性自检页 —— 原 P0 的工作台，P5 挪到这里。
         *
         * 它【不挂进左侧菜单】，全靠手敲 URL 进来：
         * 管理端的菜单要留给真实业务，自检是开发/排障工具。
         * 但它必须留着 —— 出问题时这是唯一能一眼分开
         * 「后端挂了」和「这个接口写错了」的地方。
         *
         * ★ path 不写前导斜杠（它是上面 '/' 的子路由），
         *   所以完整地址是 /admin/dev/ping。
         */
        path: 'dev/ping',
        name: 'devPing',
        component: () => import('@/views/dev/ping.vue'),
        meta: { title: '连通性自检' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/error/404.vue'),
  },
]

const router = createRouter({
  /**
   * ⚠️ history 模式的代价：生产环境的 Nginx 必须配
   *     try_files $uri $uri/ /admin/index.html;
   *    否则用户在 /admin/novel 上按 F5 会 404（因为那个路径在服务器上
   *    并不存在真实文件）。开发期不用管，Vite 已经处理好了。
   */
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,

  /**
   * 路由切换时的滚动行为（P5 补）。
   *
   * ★★ 补它之前 admin-web 是【完全没有】scrollBehavior 的，
   *   于是 Vue Router 的默认行为是「保持当前滚动位置」——
   *   从一个滚到一半的长列表切到另一页，新页面直接停在半中间，
   *   顶部的内容（页面标题、筛选栏）看不到，
   *   **看起来像点错了菜单**。
   *
   * ★ 管理端比阅读端更吃这个亏：管理端的表格动辄几十上百行，
   *   而且「滚到底部改完一条 → 点另一个菜单」是审核时的标准动作。
   *
   * ★ 返回 savedPosition（由浏览器提供）才是正确行为：
   *   点了浏览器返回键 → 回到上次那个位置；
   *   点了一个新的链接（push）→ savedPosition 是 null → 回到顶部。
   *
   * ★ 慢一点也无所谓：{ left: 0 } 是为了横向滚动条也归零，
   *   否则某些窄屏下会保留一个横向偏移 ——
   *   管理端的表格列多，横向滚动是常态，更容易碰上。
   */
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0, left: 0 }
  },
})

/**
 * 全局守卫：管理端需要 ADMIN 角色。
 *
 * P0 阶段这里只检查「有没有 token」，角色不符只提示不拦截 ——
 * 那是为了先验收「三端共享 key」。P1 拿到真实角色后已改成硬拦截。
 *
 * ★ 和 author-web 一样，这层只是【前端体验层】的拦截，
 *   改 localStorage 就能绕过；真正的拦截在后端。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '管理端'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  if (!getToken()) {
    redirectToReaderLogin()
    return false
  }

  const user = getUser()
  if (!user) {
    // token 在但用户信息缺失/损坏，当未登录处理
    redirectToReaderLogin()
    return false
  }

  if (user.role !== 'ADMIN') {
    ElMessage.warning('管理端仅对管理员开放')

    /*
     * ★ 跳【阅读端首页】，不跳登录页 —— 理由和 author-web 的守卫完全相同：
     *   用户已经登录了，只是角色不对，走 redirectToReaderLogin()
     *   会在「登录页 ↔ 管理端」之间来回弹，永远出不去。
     *
     * ★ 延时 800ms 是为了让上面那句提示能被看见（立刻跳走的话
     *   ElMessage 会随页面卸载一起消失）。
     */
    setTimeout(() => {
      window.location.href = READER_WEB_URL
    }, 800)
    return false
  }

  return true
})

export default router
