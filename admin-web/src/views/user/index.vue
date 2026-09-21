<script setup>
/**
 * 用户管理 —— 列表 + 封禁/解封 + 改角色 + 重置密码。
 *
 * ============================================================
 * ★★ 三个写操作都会「把对方踢下线」，提示语里必须说出来
 * ============================================================
 * 后端在封禁 / 改角色 / 重置密码之后都会调 `tokenStore.removeAll(userId)`。
 * 这件事**管理员在界面上看不见** —— 他改完，对方浏览器里的旧页面还开着，
 * 管理员会以为「没生效」，然后再改一次。所以每一次成功之后，
 * 要么用后端返回的那句文案（`Result.success(message, null)` 里带的），
 * 要么在这里自己说清楚。
 *
 * ★★ <b>后端 `Result.success("...")` 里那句 message 在成功路径上【到不了前端】</b> ——
 *   见 request.js 的响应拦截器：`code === 200` 时它**只返回 `res.data`**，
 *   不弹任何提示（它只弹**错误**提示）。
 *   所以成功文案必须在页面里**自己再写一遍**，上面那三处 `ElMessage.success(...)`
 *   不是在「补上下文」，它们是这段文字**唯一**的出口。
 *   （后端那句不是白写的：它在 Swagger 上给 curl 用的人看。两处要一起改。）
 *
 * ============================================================
 * ★★ 改自己 = 把自己也踢下线（一个容易漏掉的连锁反应）
 * ============================================================
 * `removeAll(userId)` 是按 userId 删全部会话的，**不区分是谁在操作**。
 * 于是管理员给自己重置密码、或者给自己加一个角色，会连**自己当前这个标签页**
 * 一起踢掉：这次请求成功返回（它已经通过了鉴权），但紧接着的
 * 「刷新列表」就会 401 跳登录页 —— 表现是「点了一下，什么都没提示就跳走了」。
 *
 * 所以下面有 `handleSelfKick()`：**目标是自己**的时候，操作成功后
 * **主动**提示 + 主动跳登录页，把这件事讲明白，而不是让它看起来像 bug。
 * （封禁自己是被后端直接拒绝的，走不到这里。）
 *
 * ============================================================
 * ★★ 这一页【没有】「新增用户」，这是有意的，不要加
 * ============================================================
 * 注册是读者自己的事（阅读端的注册页）。管理端只处置已有账号。
 * 后端也**没有**建号接口 —— 理由见 AdminUserController 的类注释。
 * 别因为「列表页上面通常有个新增按钮」就去补一个。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getUserPage, changeUserStatus, changeUserRoles, resetUserPassword } from '@/api/user'
import { getRoleList } from '@/api/role'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { confirm, confirmDanger, prompt } from '@/utils/confirm'
import { redirectToReaderLogin } from '@/utils/auth'

const userStore = useUserStore()

/** 当前登录者的 id。用来做「不能封禁自己」「不能摘掉自己的 ADMIN」两处判断 */
const myId = computed(() => userStore.userInfo?.id)

/* ==================== 角色字典 ==================== */

/**
 * 角色清单。**筛选器的下拉框**和**编辑弹窗的多选框**共用这一份。
 *
 * ★ 含已停用的角色（后端就是这么返回的）。不要在这里 filter 掉 ——
 *   见 api/role.js 里的说明：某个用户可能还挂着一个已停用的角色，
 *   界面上不显示它，管理员一保存就会把这个关联悄悄删掉。
 */
const roles = ref([])
/** 角色清单是否取回来了。用来在弹窗里区分「角色加载失败」和「这个用户没角色」 */
const rolesLoaded = ref(false)

async function loadRoles() {
  try {
    roles.value = await getRoleList()
    rolesLoaded.value = true
  } catch {
    // 拦截器已经弹过错误提示。这里吞掉，让列表本身还能用 ——
    // 角色清单挂了不该连带整页白屏
    rolesLoaded.value = false
  }
}

/** 角色码 → 中文名。查不到就退回显示角色码本身，绝不显示空白 */
function roleNameOf(code) {
  return roles.value.find((r) => r.roleCode === code)?.roleName || code
}

/* ==================== 列表 ==================== */

/**
 * 筛选条件。★ `null` = 不限（axios 跳过 null/undefined 参数，
 * 正好对上后端「不传 = 全部」）。不要用空字符串。
 */
const filter = ref({ status: null, roleCode: null, keyword: '' })

const paged = usePagedList(
  ({ page, size }) =>
    getUserPage({
      status: filter.value.status,
      roleCode: filter.value.roleCode,
      keyword: filter.value.keyword.trim() || null,
      page,
      size,
    }),
  { pageSize: 10 },
)

onMounted(() => {
  loadRoles()
  paged.load()
})

/** 换筛选条件。★ 走 reload（回第 1 页），见 usePagedList 的第 ③ 条 */
function search() {
  paged.reload()
}

function reset() {
  filter.value = { status: null, roleCode: null, keyword: '' }
  paged.reload()
}

/* ==================== 封禁 / 解封 ==================== */

/** 是不是当前登录者自己 */
function isSelf(row) {
  return !!myId.value && row.id === myId.value
}

/**
 * 封禁 / 解封。
 *
 * ★ 「封禁」和「解封」用同一个函数，但文案完全不同 —— 不是偷懒：
 *   两者的**参数**只差一个 status，分两个函数会让「点了解封结果传了 0」
 *   这种错误完全没有检查点。
 */
async function toggleStatus(row) {
  const disabling = row.status === 1

  if (disabling && isSelf(row)) {
    /*
     * ★ 兜底。按钮在界面上是禁用的，正常点不到这里 ——
     *   但后端也拦（1012），两边都拦是因为「前端拦」只是体验，
     *   真正不让它发生的是后端。
     */
    ElMessage.warning('不能封禁自己的账号，否则你将无法再登录管理端')
    return
  }

  /*
   * ★★ 确认框的正文是**纯文本**（dangerouslyUseHTMLString 默认 false），
   *   不能写 Markdown 的 ** 加粗 —— 会原样显示成星号。
   *   要强调就用引号或者断句。
   */
  const ok = disabling
    ? await confirmDanger(
        `封禁后「${row.nickname || row.username}」将无法登录，并且当前在线的所有设备会被立刻踢下线。`,
        '封禁用户',
        '确定封禁',
      )
    : await confirm(`解封后「${row.nickname || row.username}」可以重新登录。`, '解封用户', {
        type: 'info',
        confirmText: '确定解封',
      })
  if (!ok) return

  try {
    await changeUserStatus(row.id, disabling ? 0 : 1)
    /*
     * ★ 提示语说「已生效」而不是「已保存」。
     *   「保存」让人以为还要对方做什么（刷新？重登？），
     *   而封禁是真的**立刻**生效 —— 这正是 D-3 那句 removeAll 换来的。
     */
    ElMessage.success(disabling ? '已封禁，对方已立刻掉线' : '已解封，对方需要重新登录')
    await paged.load()
  } catch {
    // 拦截器已经弹过（比如并发下别人先把他删了 → 1001）
  }
}

/* ==================== 改角色 ==================== */

/**
 * 编辑角色弹窗。
 *
 * ★ `roleIds` 是**从 row 拷出来的新数组**，不是 row.roleIds 的引用 ——
 *   引用的话，用户在弹窗里勾勾选选，后面那个「取消」按钮就成了摆设：
 *   表格里的数据已经被改脏了（虽然没存库），关掉弹窗再打开会看到
 *   上次没保存的勾选状态。这类 bug 看起来像「缓存」，实际是共享引用。
 */
const roleDialog = reactive({
  visible: false,
  row: null,
  roleIds: [],
  saving: false,
})

function openRoleDialog(row) {
  roleDialog.row = row
  roleDialog.roleIds = [...(row.roleIds || [])]
  roleDialog.visible = true
}

/** 这一行的 ADMIN 勾选框要不要锁住（给自己改角色时） */
function adminLocked(row) {
  return isSelf(row)
}

async function saveRoles() {
  const row = roleDialog.row
  if (!row) return

  /*
   * ★ 本地先拦一次空集合，纯粹是为了体验：后端对空数组返回 400，
   *   红字提示也能看懂，但把弹窗关掉再报错、用户还得重新打开重勾一遍。
   *   **真正的拦截仍然在后端**（UserRolesDTO 上的 @NotEmpty）——
   *   这里挡不住任何 HTTP 客户端。
   */
  if (!roleDialog.roleIds.length) {
    ElMessage.warning('请至少给这个用户保留一个角色；要让某人无法使用，请改用「封禁」')
    return
  }

  const self = isSelf(row)
  const ok = await confirm(
    self
      ? '你正在修改「你自己」的角色。保存后你自己的登录会话也会被强制下线，需要重新登录。'
      : `将把「${row.nickname || row.username}」的角色改为：${roleDialog.roleIds
          .map((id) => roleNamesById(id))
          .join('、')}。该用户的在线会话会被强制下线，需重新登录。`,
    '修改角色',
    { type: 'warning', confirmText: '确定修改' },
  )
  if (!ok) return

  roleDialog.saving = true
  try {
    /*
     * ★ 传的是**全量**（多选框里勾着的全部），不是「这次改动的那个」。
     *   后端是覆盖式：传什么就是什么。传一个单元素数组会把人家原有角色
     *   全部清掉，而且返回成功 —— 见 api/user.js 上的警告。
     */
    await changeUserRoles(row.id, roleDialog.roleIds)
    roleDialog.visible = false
    ElMessage.success('角色已更新，该用户的在线会话已被强制下线')

    if (self) {
      handleSelfKick()
      return
    }
    await paged.load()
  } catch {
    // 拦截器已提示（1013 不能摘掉自己的 ADMIN / 1011 角色不存在）
  } finally {
    roleDialog.saving = false
  }
}

/** 角色 id → 中文名（拼确认框文案用） */
function roleNamesById(id) {
  return roles.value.find((r) => r.id === id)?.roleName || `角色 #${id}`
}

/* ==================== 重置密码 ==================== */

async function doResetPassword(row) {
  const self = isSelf(row)

  /*
   * ★ 用 utils/confirm.js 里的 prompt，不直接调 ElMessageBox.prompt ——
   *   后者是命令式 API，读不到 App.vue 里的中文语言包，
   *   两个按钮会变成英文的 OK / Cancel。
   *
   * ★ inputValidator 返回**字符串**（不是 false）时，Element Plus 会把
   *   这句话显示在输入框下方，并且**保持弹窗打开** —— 用户刚打的密码还在。
   *   校验规则和后端 ResetPasswordDTO 完全一致（6~32 位），
   *   不一致的话会出现「前端说可以、后端 400」或者反过来的情况。
   */
  const newPassword = await prompt(
    self
      ? '你正在重置「你自己」的密码。重置后你自己的登录会话也会被强制下线，需要重新登录。'
      : `为「${row.nickname || row.username}」设置一个新密码。该用户的全部设备会被强制下线，需要用新密码重新登录。`,
    '重置密码',
    {
      confirmText: '确定重置',
      inputPlaceholder: '新密码，6~32 位',
      inputValidator: (v) => {
        if (!v) return '请输入新密码'
        if (v.length < 6 || v.length > 32) return '密码长度必须是 6~32 位'
        return true
      },
    },
  )
  if (newPassword === null) return // 用户取消

  try {
    await resetUserPassword(row.id, newPassword)
    ElMessage.success('密码已重置，该用户的在线会话已被强制下线')

    if (self) {
      handleSelfKick()
      return
    }
    await paged.load()
  } catch {
    // 拦截器已提示
  }
}

/* ==================== 把自己踢下线之后 ==================== */

/**
 * 目标是自己时，操作成功之后的收尾。
 *
 * ★★ 为什么必须有这个函数：`removeAll` 不区分操作者，
 *   给自己改角色/重置密码会连**当前这个标签页**一起踢掉。
 *   不处理的话，下面那句 `paged.load()` 会拿到 401，
 *   被 request.js 的拦截器直接跳登录页 —— 用户看到的是
 *   「点了一下确定，界面闪了一下就回登录页了，一句提示都没有」，
 *   第一反应是「这功能有 bug」。
 *
 * ★ 主动提示 + 延时跳转 = 把「为什么掉线」讲清楚。
 *   延时是为了让那句话能被看见（立刻跳走的话 ElMessage 随页面卸载一起消失，
 *   路由守卫里跳阅读端首页那处用的是同一个手法）。
 */
function handleSelfKick() {
  ElMessage.warning('已生效。你自己的登录会话也已被强制下线，即将跳转到登录页')

  setTimeout(() => {
    /*
     * ★ 用 utils/auth.js 的 redirectToReaderLogin()，不自己拼 URL ——
     *   跳转地址、参数、清 localStorage 这几件事都在那个函数里，
     *   三个前端必须一致。（这个文件不许改，但可以调。）
     */
    redirectToReaderLogin()
  }, 1200)
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.status" style="width: 130px" @change="search">
          <el-option label="全部状态" :value="null" />
          <el-option label="正常" :value="1" />
          <el-option label="已封禁" :value="0" />
        </el-select>

        <!--
          ★ 下拉框的 value 是 roleCode（角色码），不是 id ——
            后端的筛选参数就是码。编辑弹窗里用的才是 id。
        -->
        <el-select
          v-model="filter.roleCode"
          style="width: 150px"
          clearable
          placeholder="全部角色"
          @change="search"
        >
          <el-option
            v-for="r in roles"
            :key="r.id"
            :label="r.status === 0 ? `${r.roleName}（已停用）` : r.roleName"
            :value="r.roleCode"
          />
        </el-select>

        <el-input
          v-model="filter.keyword"
          style="width: 200px"
          placeholder="用户名或昵称"
          clearable
          @keyup.enter="search"
          @clear="search"
        />

        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <!--
        ★ 这里【不能】写 Markdown 的 ** 加粗 —— 模板里的纯文本按原样渲染，
          星号会直接显示出来。要强调就用「」或者换行。
      -->
      <p class="hint">
        管理端不新建账号 —— 用户是自己在阅读端注册的，这里只做处置：封禁 / 解封、改角色、重置密码。
        三个操作都会把该用户的「在线会话」强制清掉，他需要重新登录；这是有意的，否则封禁对已经登录的人无效。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column prop="id" label="ID" width="80" />

        <el-table-column prop="username" label="用户名" min-width="130" show-overflow-tooltip />

        <el-table-column label="昵称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 昵称可以为空（注册时没填），给个浅色占位而不是留白 -->
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <!--
              ★ row.roles 是**角色码数组**（后端给的是码，不是名字）。
                名字要从角色清单里查 —— 查不到就退回显示码本身：
                角色被删掉之后这里还能看出「他原本是什么角色」，
                显示空白反而更难排查。
            -->
            <el-tag
              v-for="code in row.roles"
              :key="code"
              size="small"
              effect="plain"
              class="role-tag"
            >
              {{ roleNameOf(code) }}
            </el-tag>
            <!--
              ★ 一个角色都没有的用户：能登录但什么页面都进不去。
                这是数据坏了（正常流程至少有个 READER），
                所以用 danger 标出来，而不是显示空白。
            -->
            <el-tag v-if="!row.roles || !row.roles.length" size="small" type="danger">
              无角色（异常）
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '已封禁' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <!--
              ★★ 自己那一行的「封禁」按钮是**禁用**的，并且外面套一层
                 el-tooltip 解释原因。两个细节：
                 ① 禁用的按钮不触发鼠标事件，所以必须包一层 <span> ——
                    直接给 el-button 套 tooltip 是弹不出来的。
                 ② 光禁用不说话，管理员会以为「按钮坏了」。
               ★ 后端也拦（1012），这里只是不让他在界面上白点一次。
            -->
            <el-tooltip
              :disabled="!isSelf(row) || row.status !== 1"
              content="不能封禁自己 —— 封了就再也登不回来"
              placement="top"
            >
              <span>
                <el-button
                  link
                  :type="row.status === 1 ? 'danger' : 'success'"
                  :disabled="isSelf(row) && row.status === 1"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 1 ? '封禁' : '解封' }}
                </el-button>
              </span>
            </el-tooltip>

            <el-button link type="primary" @click="openRoleDialog(row)">编辑角色</el-button>
            <el-button link type="warning" @click="doResetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="paged.loaded.value" description="没有符合条件的用户" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-if="paged.total.value > 0"
        class="pager"
        :current-page="paged.page.value"
        :page-size="paged.size.value"
        :total="paged.total.value"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="paged.changePage"
        @size-change="paged.changeSize"
      />
    </el-card>

    <!-- ==================== 编辑角色 ==================== -->
    <el-dialog v-model="roleDialog.visible" title="编辑角色" width="480px">
      <div v-if="roleDialog.row" class="dialog-body">
        <p class="who">
          {{ roleDialog.row.nickname || roleDialog.row.username }}
          <span class="uid">#{{ roleDialog.row.id }}</span>
        </p>

        <el-alert
          v-if="isSelf(roleDialog.row)"
          type="warning"
          :closable="false"
          show-icon
          title="你正在修改自己的角色"
          description="保存后你自己的登录会话会被强制下线，需要重新登录。管理员角色不能取消 —— 取消了就再也没有界面能加回来。"
        />

        <el-alert
          v-if="!rolesLoaded"
          type="error"
          :closable="false"
          show-icon
          title="角色清单没加载出来"
          description="下面的勾选框是空的。请刷新页面重试 —— 在这之前不要保存，否则会把该用户的角色清空。"
        />

        <!--
          ★★ 这里的多选框是**覆盖式**的：勾成什么样，库里就是什么样。
            没有「添加」和「移除」两个动作，只有一个「最终集合」。

          ★ 父子联动在这里不适用（角色之间没有层级），
            真正要注意的是**已停用的角色也要列出来**：
            见下面 option 上的「（已停用）」后缀。
        -->
        <el-checkbox-group v-model="roleDialog.roleIds" class="roles">
          <el-checkbox
            v-for="r in roles"
            :key="r.id"
            :value="r.id"
            :disabled="adminLocked(roleDialog.row) && r.roleCode === 'ADMIN'"
          >
            {{ r.roleName }}
            <span class="code">{{ r.roleCode }}</span>
            <!--
              ★ 已停用的角色必须显示、且必须能看见「已停用」三个字。
                用户可能正挂着这个角色，藏起来的话管理员一保存就把它删了。
            -->
            <el-tag v-if="r.status === 0" size="small" type="info" effect="plain">已停用</el-tag>
          </el-checkbox>
        </el-checkbox-group>

        <p class="tip">
          勾选框里是这个用户应有的「全部」角色，不是「要新增的那个」。取消勾选 = 移除该角色。
        </p>
      </div>

      <template #footer>
        <el-button @click="roleDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="roleDialog.saving" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

/* 一个用户可能有多个角色，标签之间要留缝，否则连成一片 */
.role-tag {
  margin-right: 4px;
}

.none {
  color: #c0c4cc;
}

.dialog-body .who {
  margin: 0 0 12px;
  font-weight: 600;
}

.dialog-body .uid {
  color: #909399;
  font-weight: 400;
  font-size: 12px;
}

.roles {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
}

/* 角色码用等宽浅色，和中文名区分开 —— 排查时经常要对照后端里的 code */
.code {
  margin-left: 6px;
  color: #a8abb2;
  font-size: 12px;
}

.tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
