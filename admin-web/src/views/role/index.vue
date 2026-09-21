<script setup>
/**
 * 角色管理 —— 列表 + 增删改 + 给角色勾菜单。
 *
 * ============================================================
 * ★★ 全项目唯一一处「UI 操作能造成不可逆损害」的地方
 * ============================================================
 * 管理员编辑**自己所属角色**、取消勾选 `system:role:list` 或
 * `system:menu:list` → 保存成功 → 左侧菜单里「角色管理」「菜单管理」
 * **立刻消失**，而界面上再也没有地方能改回来，只能进 MySQL 手工 INSERT。
 *
 * 后端因此有一条精确的自锁防护（`1020`），它算的是
 * 「**改完之后你自己还剩哪些权限**」—— 会把你**别的**角色一起算进来，
 * 所以同时是 ADMIN 的人去掉别的角色上的这两个权限**是允许的**。
 *
 * ★★ 这一页【只提醒、不复刻那份判断】。
 *   前端能拿到的只有 localStorage 里那份 `permissions`（当前用户全量权限），
 *   要复刻「去掉这两个点之后我还剩什么」就得把「我每个角色各有哪些权限」
 *   也搬到前端来 —— 那是一整份服务端规则的影子实现，两份实现迟早分叉，
 *   而分叉的那天这个防护就失效了。**让后端当唯一的裁判**，前端只负责
 *   在管理员动手之前把话说到：下面 `selfLockHint` 那段提示。
 *
 * ============================================================
 * ★★ 「你正在改自己的权限」→ 保存成功 = 把你自己也踢下线
 * ============================================================
 * 后端 `assignMenus` 会对**这个角色下的所有人**调 `tokenStore.removeAll`，
 * 而它**不区分操作者**。所以管理员改自己所属角色的菜单，会连
 * **当前这个标签页**一起踢掉：这次请求成功返回（它已经通过了鉴权），
 * 但紧接着的「刷新列表」就会 401 跳登录页 —— 表现是
 * 「点了一下保存，闪了一下就回登录页了，一句提示都没有」。
 *
 * 所以下面有 `handleSelfKick()`：目标是自己所属的角色时，操作成功后
 * **主动**提示 + 主动跳登录页，把这件事讲明白，而不是让它看起来像 bug。
 * （同一个手法在用户管理页里已经用过一次。）
 *
 * ============================================================
 * ★★ 新建完一个角色，它【一个菜单都没有】
 * ============================================================
 * 后端建角色时不会顺手给它任何 `sys_role_menu` 行。挂上它的用户
 * 登录后左侧**只有「工作台」** —— 这不是 bug，是「权限还没配」。
 * 所以保存成功后会**直接打开权限弹窗**：这一步不做，管理员十有八九
 * 会以为「建完就能用了」，然后在用户管理页把它分配给人，
 * 再去排查「为什么他进来什么都没有」。
 *
 * ============================================================
 * ★★ 内置角色：按钮置灰是【预判】，真正的拦截在后端
 * ============================================================
 * `ADMIN / AUTHOR / READER` 三个内置角色：不能删、不能停用、不能改标识
 * （后端 `1018`）。删除按钮还要求 `userCount === 0`（后端 `1019`）。
 * 前端拿 `builtin` / `userCount` 把按钮置灰，只是为了**少一次注定失败的点击** ——
 * 它挡不住任何 HTTP 客户端，别把它当成安全措施。
 */
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getRoleList,
  createRole,
  updateRole,
  deleteRole,
  getRoleMenuIds,
  updateRoleMenus,
} from '@/api/role'
import { getMenuTree } from '@/api/menu'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { confirm, confirmDanger } from '@/utils/confirm'
import { redirectToReaderLogin } from '@/utils/auth'
import { roleStatusType, menuTypeTag, menuStatusType } from '@/utils/status'

const userStore = useUserStore()

/* ==================== 列表 ==================== */

const list = ref([])
const loading = ref(false)
/** ★ 区分「还没查过」和「查过但为空」，理由见 composables/usePagedList.js */
const loaded = ref(false)

/**
 * 角色列表**不分页** —— 角色是个位数量级的字典表，分页只会让
 * 「找个角色」变成「翻页找角色」。所以这里直接用 ref，
 * 不像其它列表页那样走 usePagedList。
 */
async function loadList() {
  loading.value = true
  try {
    list.value = await getRoleList()
    loaded.value = true
  } catch {
    // 拦截器已经弹过错误提示。这里吞掉，页面至少能显示空态而不是永远转圈
    loaded.value = false
  } finally {
    loading.value = false
  }
}

/**
 * 这个角色是不是**当前登录者自己所属**的。
 *
 * ★ 拿 `roleCode` 比对，不拿 `roleName` —— 名字是可以随便改的
 *   （内置角色也能改名字），码才是身份。`userStore.roles` 是登录接口
 *   返回的当前用户全部角色码。
 */
function isMyRole(role) {
  return !!role && userStore.roles.includes(role.roleCode)
}

onMounted(loadList)

/* ==================== 新建 / 编辑 ==================== */

const form = reactive({
  visible: false,
  /** 有 id = 编辑，没有 = 新建 */
  id: null,
  roleCode: '',
  roleName: '',
  remark: '',
  status: 1,
  saving: false,
})

/**
 * 打开弹窗时那一行的**原样快照**。判断「这次改动要不要踢人」要用它 ——
 * 只有**标识或状态**变了才会踢（改名字/备注不动在线会话）。
 */
let original = null

const isEdit = computed(() => !!form.id)
const isBuiltin = computed(() => !!original?.builtin)
/** 内置角色的标识和状态锁死（后端 1018） */
const identityLocked = computed(() => isEdit.value && isBuiltin.value)

const formRef = ref()

/**
 * 校验规则和后端 `RoleSaveDTO` **逐字一致** —— 不一致的话会出现
 * 「前端说可以、后端 400」或者反过来的情况，而用户看到的是同一句红字
 * 却不知道该信哪个。（`roleCode` 的长度上限写在正则的 `{0,49}` 里，
 * 后端也是这么写的：一条规则一个来源，避免一次报两条消息。）
 */
const rules = {
  roleCode: [
    { required: true, message: '角色标识不能为空', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]{0,49}$/,
      message: '角色标识必须以大写字母开头，只包含大写字母、数字和下划线，最长 50 位',
      trigger: 'blur',
    },
  ],
  roleName: [
    { required: true, message: '角色名不能为空', trigger: 'blur' },
    { max: 50, message: '角色名最长 50 个字符', trigger: 'blur' },
  ],
  remark: [{ max: 255, message: '备注最长 255 个字符', trigger: 'blur' }],
  status: [{ required: true, message: '请选择启用状态', trigger: 'change' }],
}

function openCreate() {
  original = null
  Object.assign(form, { id: null, roleCode: '', roleName: '', remark: '', status: 1 })
  form.visible = true
  nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row) {
  original = row
  Object.assign(form, {
    id: row.id,
    roleCode: row.roleCode,
    roleName: row.roleName,
    remark: row.remark ?? '',
    status: row.status,
  })
  form.visible = true
  nextTick(() => formRef.value?.clearValidate())
}

async function saveForm() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

  const payload = {
    roleCode: form.roleCode,
    roleName: form.roleName,
    /*
     * ★ 空串要转成 null：后端把 `remark` 的 null / 空串都当「清空备注」，
     *   但传空串进 JSON 之后是一个真实的 ""，语义上「没有备注」用 null 更准确。
     */
    remark: form.remark.trim() || null,
    status: form.status,
  }

  /*
   * ★ 只有**标识或状态**变了才会踢人（后端就是这么判断的）。
   *   前端在这里算一遍是为了知道**要不要提示** —— 不是为了替后端判断，
   *   后端该踢还是会踢（它只认自己的比较结果）。
   */
  const touchedIdentity =
    isEdit.value && (payload.roleCode !== original.roleCode || payload.status !== original.status)

  form.saving = true
  try {
    if (isEdit.value) {
      await updateRole(form.id, payload)
      form.visible = false
      ElMessage.success(
        touchedIdentity ? '角色已更新，该角色下的用户已被强制下线，需重新登录' : '角色已更新',
      )
      await loadList()

      if (touchedIdentity && isMyRole(original)) {
        handleSelfKick()
      }
      return
    }

    const newId = await createRole(payload)
    form.visible = false
    /*
     * ★★ 提示语里必须说「还没有任何菜单」—— 不说的话，管理员建完
     *   就去用户管理页把它分配给人，然后排查「为什么他进来什么都没有」。
     *   紧接着自动打开权限弹窗，把这个坑直接堵上。
     */
    ElMessage.success('角色已创建，但它还没有任何菜单权限，请接着勾选菜单')
    await loadList()
    if (newId) {
      await openPermById(newId)
    }
  } catch {
    // 拦截器已提示（1017 标识重复 / 1018 内置角色不能改标识或状态）
  } finally {
    form.saving = false
  }
}

/** 新建成功后按 id 找到刚建的角色，接着开权限弹窗 */
async function openPermById(id) {
  const row = list.value.find((r) => r.id === id)
  if (row) await openPerm(row)
}

/* ==================== 删除 ==================== */

/** 删除按钮能不能点。★ 只是预判，真正的拦截在后端（1018 / 1019） */
function canDelete(row) {
  return !row.builtin && row.userCount === 0
}

/** 删除按钮为什么是灰的 —— 光禁用不说话，管理员会以为按钮坏了 */
function deleteBlockReason(row) {
  if (row.builtin) return '内置角色不能删除 —— 删了会造成全局性故障'
  if (row.userCount > 0) return `还有 ${row.userCount} 个用户在用这个角色，先把它从那些人身上摘掉`
  return ''
}

async function removeRole(row) {
  const ok = await confirmDanger(
    `将删除角色「${row.roleName}」（${row.roleCode}），并清掉它已勾选的全部菜单权限。` +
      `这个操作不可撤销 —— 角色标识「${row.roleCode}」被它一直占着，删完也建不回来（唯一索引不含删除标记）。`,
    '删除角色',
    '确定删除',
  )
  if (!ok) return

  try {
    await deleteRole(row.id)
    ElMessage.success('角色已删除')
    await loadList()
  } catch {
    // 拦截器已提示（1018 内置角色 / 1019 角色下还有用户 / 1011 角色不存在）
  }
}

/* ==================== 菜单权限 ==================== */

const treeRef = ref()

const perm = reactive({
  visible: false,
  role: null,
  /** 该角色**已勾选**的菜单 id，来自后端，喂给 el-tree 的 default-checked-keys */
  menuIds: [],
  saving: false,
  /**
   * ★★ 两个「回显数据有没有拿到」的开关。
   *
   * 它们不是装饰：`getRoleMenuIds` 失败时 `menuIds` 是空数组，
   * 而空数组的树**看起来和「这个角色一个权限都没有」完全一样** ——
   * 管理员一点保存，这个角色的权限就被**清空了**，而且返回成功。
   * 所以任何一个没拿到，都直接禁用保存按钮并说明原因。
   * （和用户管理页里「角色清单没加载出来 → 不要保存」是同一个理由。）
   */
  idsLoaded: false,
  treeLoaded: false,
})

/** 菜单树。★ 只取一次，之后共用 —— 它是只读的种子数据，不会变 */
const menuTree = ref([])

async function loadMenuTree() {
  try {
    menuTree.value = await getMenuTree()
    perm.treeLoaded = true
  } catch {
    perm.treeLoaded = false
  }
}

async function openPerm(row) {
  perm.role = row
  perm.menuIds = []
  perm.idsLoaded = false
  perm.treeLoaded = !!menuTree.value.length
  perm.visible = true

  if (!perm.treeLoaded) {
    await loadMenuTree()
  }
  try {
    perm.menuIds = await getRoleMenuIds(row.id)
    perm.idsLoaded = true
  } catch {
    perm.idsLoaded = false
  }
}

async function savePerm() {
  /*
   * ★ 收集的是**完整选中集合**，不是「这次点的那几个」——
   *   后端是覆盖式：传什么就是什么。少传一个 = 那个权限被撤掉。
   * ★ `check-strictly = true` 时没有「半选」状态，getCheckedKeys()
   *   拿到的就是用户勾的那些节点本身。
   */
  const menuIds = treeRef.value?.getCheckedKeys() ?? []

  const ok = await confirm(
    `将把「${perm.role.roleName}」的菜单权限设为：${
      menuIds.length ? menuIds.length + ' 项' : '（空，这个角色将看不到任何业务菜单）'
    }。` +
      (isMyRole(perm.role)
        ? '★ 这是你自己所属的角色 —— 保存后你自己的登录会话也会被强制下线，需要重新登录。'
        : '该角色下所有用户的在线会话会被强制下线，需重新登录。'),
    '保存菜单权限',
    { type: 'warning', confirmText: '确定保存' },
  )
  if (!ok) return

  perm.saving = true
  try {
    const kicked = await updateRoleMenus(perm.role.id, menuIds)
    perm.visible = false
    /*
     * ★ 后端返回的是**用户数**（不是会话数，也不是登录态条数）。
     *   0 是完全正常的结果（这个角色下当前没人在线），
     *   不能说成「没生效」—— 权限已经写库了。
     * ★ 成功文案在这里是**唯一**的出口：request.js 的成功路径
     *   不弹后端 `Result.success(message)` 里那句话（见 its 拦截器）。
     */
    ElMessage.success(
      kicked > 0 ? `菜单权限已更新，${kicked} 名用户已被强制下线，需重新登录` : '菜单权限已更新',
    )

    const self = isMyRole(perm.role)
    await loadList()
    if (self) {
      handleSelfKick()
    }
  } catch {
    // 拦截器已提示（1020 自锁防护 / 1021 菜单不存在 / 1011 角色不存在）
  } finally {
    perm.saving = false
  }
}

/**
 * 自锁提示的文案。
 *
 * ★ 只在「改的是自己所属角色」时出现 —— 别人的角色怎么改都不会锁死自己。
 *   具体哪几个权限点不能去掉由后端判断（理由见文件头），
 *   这里把可能的结果说清楚就够了。
 */
const selfLockHint = computed(() =>
  isMyRole(perm.role)
    ? '这是你自己所属的角色。如果去掉「角色管理」或「菜单管理」这两项，保存会被后端拒绝（1020）—— ' +
      '因为那会让你把这两个页面一起弄丢，从此在界面上再也改不回来。' +
      '（除非你还有别的角色也带着这两个权限 —— 判断由后端做，这里只提醒。）'
    : '',
)

/* ==================== 把自己踢下线之后 ==================== */

/**
 * 目标是自己所属角色时，操作成功之后的收尾。
 *
 * ★ `removeAll` 按角色下**所有人**清会话，不区分操作者，
 *   所以「改自己角色的权限」等于把自己也踢了。
 *   不处理的话，下面那句 `loadList()` 会拿到 401，被 request.js
 *   直接跳登录页 —— 用户看到的是「点了一下保存，闪了一下就回登录页了」。
 *
 * ★ 主动提示 + 延时跳转，把「为什么掉线」讲清楚。
 *   延时是为了让那句话能被看见（立刻跳走的话 ElMessage 会随页面卸载一起消失）。
 */
function handleSelfKick() {
  ElMessage.warning('已生效。你自己的登录会话也已被强制下线，即将跳转到登录页')

  setTimeout(() => {
    redirectToReaderLogin()
  }, 1200)
}
</script>

<template>
  <div>
    <!-- ==================== 说明 + 新建 ==================== -->
    <el-card shadow="never" class="head-card">
      <div class="head">
        <el-button type="primary" @click="openCreate">新建角色</el-button>
        <el-button @click="loadList">刷新</el-button>
      </div>

      <!--
        ★ 模板里的纯文本按原样渲染，**不能写 Markdown 的 ** 加粗**（会显示成星号）。
          要强调就用「」或者换行。
      -->
      <p class="hint">
        角色 = 一组菜单权限的集合。改动角色权限后，该角色下的用户会被强制下线，需要重新登录才生效
        —— 权限是登录时算出来存进 Redis 的，不踢的话对方界面看起来毫无变化。
        内置角色（ADMIN / AUTHOR / READER）不能删除、停用或改标识；被用户使用中的角色也不能删除，
        要收回权限请去用户管理页改那个人的角色。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" row-key="id" stripe>
        <el-table-column prop="id" label="ID" width="70" />

        <el-table-column label="角色标识" min-width="140">
          <template #default="{ row }">
            <!-- 码用等宽字体，和中文名区分开 —— 排查时经常要对照后端里的 code -->
            <span class="code">{{ row.roleCode }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="roleName" label="角色名" min-width="130" show-overflow-tooltip />

        <el-table-column label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 备注可以为空，给个浅色占位而不是留白 -->
            <span v-if="row.remark">{{ row.remark }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="用户数" width="90" align="center">
          <template #default="{ row }">
            <span :class="{ none: !row.userCount }">{{ row.userCount }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <!--
              ★ 文案（启用/停用）在前端写死：RoleVO 没有 statusDesc。
                这是有意的边界 —— 有状态机的那几个（作品/章节/评论/申请）
                才由后端给文案，1/0 开关不配一个。理由见 utils/status.js 文件头。
            -->
            <el-tag :type="roleStatusType(row.status)" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.builtin" size="small" type="warning" effect="plain">内置</el-tag>
            <span v-else class="none">自定义</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openPerm(row)">菜单权限</el-button>

            <!--
              ★★ 禁用的按钮不触发鼠标事件，所以必须包一层 <span> ——
                直接给 el-button 套 el-tooltip 是弹不出来的。
              ★ 光置灰不说话，管理员会以为「按钮坏了」，所以 reason 要说清原因。
            -->
            <el-tooltip :disabled="canDelete(row)" :content="deleteBlockReason(row)" placement="top">
              <span>
                <el-button link type="danger" :disabled="!canDelete(row)" @click="removeRole(row)">
                  删除
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="loaded" description="没有角色" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ==================== 新建 / 编辑 ==================== -->
    <el-dialog
      v-model="form.visible"
      :title="isEdit ? '编辑角色' : '新建角色'"
      width="480px"
      @closed="original = null"
    >
      <el-alert
        v-if="identityLocked"
        type="info"
        :closable="false"
        show-icon
        title="这是内置角色"
        description="只能改「角色名」和「备注」。标识和状态被锁住 —— 停用 ADMIN 会让所有管理员同时失去权限，改掉它的标识会让代码里所有按角色码判断的地方同时落空，两者都不可逆。"
        class="alert"
      />

      <el-alert
        v-if="isEdit && isMyRole(original)"
        type="warning"
        :closable="false"
        show-icon
        title="你正在修改自己所属的角色"
        description="如果改了标识或状态，保存后你自己的登录会话也会被强制下线，需要重新登录。"
        class="alert"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="角色标识" prop="roleCode">
          <el-input
            v-model="form.roleCode"
            :disabled="identityLocked"
            placeholder="大写字母开头，如 EDITOR"
          />
          <p class="tip">
            代码里按这个码判断身份，所以格式是硬要求：大写字母开头，只含大写字母、数字、下划线。
            存进去一个小写的 admin 也存得下，但它永远匹配不上任何判断 ——
            症状是「权限配好了，那个人进来什么都没有」，且不报错。
          </p>
        </el-form-item>

        <el-form-item label="角色名" prop="roleName">
          <el-input v-model="form.roleName" placeholder="给人看的名字，如 责任编辑" />
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="这个角色是干什么的（清空即删除备注）"
          />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status" :disabled="identityLocked">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
          <p class="tip">
            停用不影响已有的授权关系：还挂着这个角色的用户仍在用它，只是这个角色不再被推荐分配。
          </p>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" @click="saveForm">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 菜单权限 ==================== -->
    <!--
      ★ `destroy-on-close` 是这里的关键：树每次打开都重建，
        于是 `default-checked-keys` 每次都会重新应用一遍。
        不重建的话只能靠 `setCheckedKeys()` 手动回显，而它必须在
        「弹窗渲染完 + 数据已就位」之后调 —— 调早了静默失效，
        表现是「树上什么都没勾」，管理员一保存就把权限清空了。
        用声明式的 default-checked-keys 就没有这个时序问题。
    -->
    <el-dialog
      v-model="perm.visible"
      :title="perm.role ? `菜单权限 —— ${perm.role.roleName}` : '菜单权限'"
      width="560px"
      destroy-on-close
    >
      <template v-if="perm.role">
        <p class="who">
          <span class="code">{{ perm.role.roleCode }}</span>
          <span class="uid">#{{ perm.role.id }}</span>
          <span v-if="perm.role.userCount" class="uid">
            · 该角色下有 {{ perm.role.userCount }} 个用户
          </span>
        </p>

        <el-alert
          v-if="selfLockHint"
          type="warning"
          :closable="false"
          show-icon
          title="这是你自己所属的角色"
          :description="selfLockHint"
          class="alert"
        />

        <!--
          ★★ 两个数据源任一没拿到就【不许保存】：空数组的树和
             「这个角色一个权限都没有」长得一模一样，一保存就是静默清空。
        -->
        <el-alert
          v-if="!perm.treeLoaded || !perm.idsLoaded"
          type="error"
          :closable="false"
          show-icon
          title="权限数据没加载完整，不要保存"
          :description="
            !perm.treeLoaded
              ? '菜单树没取到，下面的列表是空的。请关掉重开，或刷新页面。'
              : '这个角色已勾选的菜单没取到 —— 现在保存会把它的权限清空。请关掉重开，或刷新页面。'
          "
          class="alert"
        />

        <!--
          ★★ `check-strictly = true`：父子【不联动】。
            联动（默认的 false）会让「只想给一个叶子」变成「顺带授了整个父目录」，
            而目录行本身没有权限点 —— 界面显示的勾选比实际授予的多，
            换句话说**这棵树在骗人**。
          ★ 勾选状态也不影响左侧菜单的显示：AdminLayout 是按**叶子**的
            perms 过滤的，父目录勾不勾都一样。
        -->
        <el-tree
          ref="treeRef"
          :data="menuTree"
          node-key="id"
          show-checkbox
          :check-strictly="true"
          default-expand-all
          :default-checked-keys="perm.menuIds"
          :props="{ label: 'name', children: 'children' }"
          class="tree"
        >
          <template #default="{ data }">
            <span class="node">
              <span>{{ data.name }}</span>

              <!-- 目录行的 perms 是 null（它本身不是权限点），显示成「—」而不是空白 -->
              <span v-if="data.perms" class="perms">{{ data.perms }}</span>
              <span v-else class="perms none">—</span>

              <el-tag size="small" effect="plain" :type="menuTypeTag(data.type)">
                {{ data.type === 1 ? '目录' : data.type === 3 ? '按钮' : '菜单' }}
              </el-tag>

              <!--
                ★ 停用的菜单必须**看得见**，只是标灰 —— 藏起来的话
                  「某个角色为什么会有这个权限」在界面上就成了无法回答的问题。
              -->
              <el-tag v-if="data.status === 0" size="small" :type="menuStatusType(data.status)">
                已停用
              </el-tag>
            </span>
          </template>
        </el-tree>

        <p class="tip">
          勾选的是这个角色**最终应当拥有**的全部菜单，不是「这次新增的」。取消勾选 = 撤掉那个权限。
          目录只是分组，勾不勾都不影响左侧菜单的显示 —— 真正决定显示的是叶子的权限点。
        </p>
      </template>

      <template #footer>
        <el-button @click="perm.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="perm.saving"
          :disabled="!perm.treeLoaded || !perm.idsLoaded"
          @click="savePerm"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

/* 角色码（后端里的标识）用等宽浅色，和中文名区分开 */
.code {
  font-family: Consolas, Monaco, monospace;
  color: #606266;
}

.none {
  color: #c0c4cc;
}

.alert {
  margin-bottom: 14px;
}

.dialog-body .who,
.who {
  margin: 0 0 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.uid {
  color: #909399;
  font-size: 12px;
}

.tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.tree {
  margin-top: 4px;
  max-height: 46vh;
  overflow-y: auto;
}

.node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.perms {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #a8abb2;
}
</style>
