<script setup>
/**
 * 作者申请审核 —— 列表 + 筛选 + 通过（授予作者角色）/ 驳回。
 *
 * ============================================================
 * ★★ 这是一条【待办队列】，不是一个档案页
 * ============================================================
 * 后端返回的顺序是 **状态升序 + id 倒序**：待审的在最前面，
 * 同状态内最新提交的在前。所以**不筛选时的默认视图就是「今天有活吗」**——
 * 不需要额外加一个「只看待审」的开关（那会变成两个都能做的入口）。
 *
 * ★ 和「评论管理」是同一类页面但默认口径相反：评论那页默认看**全部**
 *   （它要能改判，翻旧账是常态），这一页默认也是全部，但排序把待审顶了上来。
 *
 * ============================================================
 * ★★ 「通过」是三件事，其中一件界面上完全看不见
 * ============================================================
 *   ① 改申请状态  ② 给这个人 AUTHOR 角色  ③ 把这个人踢下线
 * ③ 是最容易漏、也最必须说出来的：角色和权限是**登录时**写进 Redis 的，
 * 不踢的话申请人重新打开创作中心照样被拒，而他看到的提示是
 * 「你还不是作者」—— 管理员这边显示「已通过」，两边都没报错。
 *
 * 所以下面 `submitAudit` 里通过的成功提示**必须**带上「需重新登录才生效」。
 *
 * ============================================================
 * ★★ 只有【待审】的行可审 —— 两个按钮都禁用，不是只禁一个
 * ============================================================
 * 后端的规矩是：
 *   · 重复提交同一个结果 → **幂等**，返回 200（管理员连点两下是常态）
 *   · 终态之间互改（已驳回 → 已通过）→ **1016**，明确拒绝
 *
 * 但这一页把**非待审行的两个按钮都禁用**了。理由分两半：
 *
 * ① **改判那条（1016）**：它是一条真正的非法操作，界面上就不该提供。
 *    让用户点到一个必然报错的按钮，他会以为系统坏了。
 * ② **幂等那条（200）更微妙**：它不报错，所以留着看起来无害 ——
 *    但第二击**不会**再授角色、也**不会**再踢人（后端的幂等 return
 *    写在授权之前），却会弹出「已通过，该用户需重新登录才生效」。
 *    **那是一句假话**：这一次点击什么都没发生。
 *    宁可让按钮不可用，也不要让界面说一句不成立的话。
 *
 * ★ 那 1016 还测得到吗？——测得到，但它拦的是**另一个场景**：
 *   两个管理员同时打开同一行，A 先驳回了，B 那边页面还是旧的，
 *   B 点「通过」就会拿到 1016。**并发这件事前端拦不住**，
 *   所以后端那一条必须留着 —— 前端禁用按钮只是体验，不是防线。
 *
 * ============================================================
 * ★★ 驳回理由必填 —— 和评论那页【相反】，别抄混
 * ============================================================
 *   · 评论的 `auditRemark`：管理员写给自己看的（「疑似水军，留着观察」），
 *     评论人**看不到**（P5 没有反馈通道），所以**不必填**
 *   · 申请的 `auditRemark`：**就是写给申请人看的驳回理由**。
 *     读者端 `GET /api/reader/author-apply/mine` 会把它原样返回。
 *     不写的话申请人只知道「被拒了」，既不知道改什么、也不知道还能不能再申请。
 *
 * 同一个字段名，一边是内部备注、一边是要送达的通知 —— 这是 P5 里最
 * 容易照抄错的一处。所以这里 `remark-required` 用默认的 `true`，
 * 而且给了一句专门的 `remark-tip` 说清「他看得到」。
 *
 * ============================================================
 * ★★ 管理员审自己的申请 = 把自己踢下线
 * ============================================================
 * `removeAll(userId)` 按 userId 删全部会话，**不区分是谁在操作**。
 * 而管理员**是可以**提交申请的（`/api/reader/author-apply` 只要求登录，
 * 而 ADMIN 角色里并不含 AUTHOR），所以「管理员审自己的申请」这条路是通的：
 * 通过之后他自己被踢，紧接着的 `paged.load()` 拿到 401，
 * 被拦截器直接跳登录页 —— 表现是「点了一下，闪了一下就回登录页了」。
 *
 * 和 `views/user/index.vue` 里那处是同一个连锁反应，处理方式也一样：
 * 见下面的 `handleSelfKick()`。
 *
 * ============================================================
 * ★★ 中文文案全部来自后端，这一页【不写】0→'待审' 的映射表
 * ============================================================
 * 显示用 `row.statusDesc`，只有**颜色**用 `utils/status.js` 的
 * `applyStatusType(row.status)`。判断一律用数字 `row.status`。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuthorApplyPage, auditAuthorApply } from '@/api/authorApply'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { applyStatusType } from '@/utils/status'
import { redirectToReaderLogin } from '@/utils/auth'
import AuditDialog from '@/components/AuditDialog.vue'

const userStore = useUserStore()

/** 当前登录者的 id。★ 用来判断「这一份是不是管理员自己的申请」 */
const myId = computed(() => userStore.userInfo?.id)

/* ==================== 状态常量 ==================== */

/**
 * ★ 用常量而不是裸数字 —— `status` 出现在筛选器、标签、按钮禁用、
 *   提交逻辑四处，裸写 `1` 迟早有一处写成 `2`，而它不报错，
 *   只是「通过」变成了「驳回」（或者反过来）。
 *   取值来自后端 `AuditStatus`：0待审 1已通过 2已驳回。
 */
const STATUS_PENDING = 0
const STATUS_APPROVED = 1
const STATUS_REJECTED = 2

/* ==================== 列表 ==================== */

/**
 * 筛选条件。★ `null` = 不限（axios 跳过 null/undefined 参数，
 * 正好对上后端「不传 = 全部」）。不要用空字符串。
 */
const filter = ref({ status: null, keyword: '' })

const paged = usePagedList(
  ({ page, size }) =>
    getAuthorApplyPage({
      status: filter.value.status,
      // ★ 空串要转成 null：`?keyword=` 后端收到的是空串，
      //   而空关键词会匹配所有人（LIKE '%%'）—— 看起来和「没筛」一样，
      //   但它会走一遍申请人 id 的反查，白花一次全表扫描。
      keyword: filter.value.keyword.trim() || null,
      page,
      size,
    }),
  { pageSize: 10 },
)

onMounted(() => {
  paged.load()
})

/** 换筛选条件。★ 走 reload（回第 1 页），见 usePagedList 的第 ③ 条 */
function search() {
  paged.reload()
}

function reset() {
  filter.value = { status: null, keyword: '' }
  paged.reload()
}

/* ==================== 审核 ==================== */

const auditVisible = ref(false)
const auditLoading = ref(false)
/** 当前被审的申请。整行都留着 —— 按钮禁用和「是不是我自己的」都要看它 */
const auditTarget = ref(null)

/**
 * 打开审核弹窗。
 *
 * ★ 和评论审核一样**不需要再调一次详情接口**：列表返回的 `AuthorApplyVO`
 *   已经带了笔名和创作说明的全文（说明最多 500 字），那就是审核员要看的
 *   全部内容。（作品/章节不行 —— 它们的正文在另一张表里，列表 VO 不返回。）
 */
function openAudit(row) {
  auditTarget.value = row
  auditVisible.value = true
}

/** 这一行能不能审。不能的理由见文件头：只有待审的行可审 */
function auditable(row) {
  return row.status === STATUS_PENDING
}

/** 不能审的原因，给 tooltip 用。★ 分状态说清楚，不能只有一句「不可用」 */
function auditDisabledReason(row) {
  if (row.status === STATUS_APPROVED) {
    return '这份申请已经通过了 —— 重复点「通过」不会再授一次角色，但会弹一句「需重新登录才生效」，而那句话不成立。要看是谁审的，去审核日志页'
  }
  if (row.status === STATUS_REJECTED) {
    return '这份申请已经驳回了。终态之间不能互改（后端返回 1016）—— 要让这个人成为作者，请他重新提交一份申请'
  }
  return ''
}

/**
 * 两个按钮的禁用条件**是同一个**。
 *
 * ★ 故意写成一个函数给两个 prop 用，而不是写两遍 `status !== 0`：
 *   这一页里「能不能通过」和「能不能驳回」的答案必须一模一样，
 *   分开写就有机会只改一处，于是出现「通过能点、驳回不能点」这种
 *   谁也说不清的状态。
 */
function auditDisabled() {
  const row = auditTarget.value
  return !row || !auditable(row)
}

/** 通过之后会发生什么。★ 必须说全三件事，尤其是「会被踢下线」 */
function passHint() {
  const row = auditTarget.value
  if (!row) return ''
  const who = row.nickname || row.username || '该用户'
  return `通过后「${who}」会立刻获得作者权限（AUTHOR 角色），并且会被强制下线 —— 需要重新登录才能进入创作中心`
}

/** 驳回之后会发生什么 */
function rejectHint() {
  const row = auditTarget.value
  if (!row) return ''
  return '驳回后申请人能在阅读端看到这条理由，并可以修改后重新提交一份新的申请'
}

/**
 * 提交审核结果。
 *
 * ★ 弹窗只负责「通过还是驳回 + 理由」，**接口调用在这里** ——
 *   只有这一层知道当前行是谁、是不是管理员自己。
 *
 * ★★ 通过之后要处理的两种收尾，顺序不能反：
 *   先判「是不是我自己的申请」（要提示 + 主动跳登录页），
 *   再 `paged.load()`（**不能**在这里 load —— 自己的会话已经没了，
 *   load 会拿到 401 被拦截器跳走，用户看到的是一句提示都没有的闪退）。
 */
async function submitAudit({ pass, remark }) {
  const row = auditTarget.value
  if (!row) return

  auditLoading.value = true
  try {
    await auditAuthorApply(row.id, pass, remark)

    /*
     * ★★ 成功提示里**必须**有「需重新登录才生效」。
     *
     * 后端 `Result.success(...)` 里那句文案在成功路径上**到不了前端**
     * （request.js 的拦截器对 code 200 只返回 data、不弹提示），
     * 所以这句话得在这里再写一遍。两处要一起改。
     *
     * ★ 驳回那半边不提「重新登录」—— 驳回没改任何人的角色，
     *   也就没踢任何人。多写一句会让管理员以为申请人被强制下线了。
     */
    ElMessage.success(
      pass
        ? '已通过。该用户已获得作者权限，需重新登录后生效'
        : '已驳回。申请人可在阅读端看到这条理由并重新提交',
    )
    auditVisible.value = false

    // ★ 改的是**我自己**的申请 → 我自己的会话刚被踢掉，见函数注释
    if (pass && isSelf(row)) {
      handleSelfKick()
      return
    }

    await paged.load()
  } catch {
    /*
     * 拦截器已提示（1016 状态被别人改过 / 1014 申请不存在 / 网络错误）。
     * ★ 弹窗**保持打开** —— 关掉的话审核员刚写的驳回理由就没了，
     *   而他多半会想「那我重试一次」。
     */
  } finally {
    auditLoading.value = false
  }
}

/* ==================== 把自己踢下线之后 ==================== */

/** 这一份申请是不是当前登录的管理员自己的 */
function isSelf(row) {
  return !!myId.value && row.userId === myId.value
}

/**
 * 目标是自己时，通过之后的收尾。
 *
 * ★★ 为什么必须有：`removeAll` 不区分操作者，管理员通过**自己的**申请
 *   会连当前这个标签页一起踢掉。不处理的话下面那句 `paged.load()` 会拿到 401，
 *   被 request.js 的拦截器直接跳登录页 —— 用户看到的是
 *   「点了一下通过，界面闪了一下就回登录页了，一句提示都没有」。
 *
 * ★ 提示语要说清**为什么**掉线。只说「请重新登录」的话，管理员会以为
 *   是自己登录过期了，而不会联想到「我刚给自己授了个角色」。
 *
 * ★ 延时 1200ms 是为了让 `ElMessage.success` 和这句 warning 都能被看见
 *   （立刻跳走的话它们会随页面卸载一起消失）。
 *   `views/user/index.vue` 里那处用的是同一个手法。
 */
function handleSelfKick() {
  ElMessage.warning('你自己的登录会话也已被强制下线（你给自己授了作者角色），即将跳转到登录页')

  setTimeout(() => {
    /*
     * ★ 用 utils/auth.js 的 redirectToReaderLogin()，不自己拼 URL ——
     *   跳转地址、参数、清 localStorage 这几件事都在那个函数里，
     *   三个前端必须一致。（那个文件不许改，但可以调。）
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
        <el-select v-model="filter.status" style="width: 140px" @change="search">
          <el-option label="全部状态" :value="null" />
          <!-- ★ 文案写死在这里是**对的**：这是筛选条件（管理员给自己选的），
               不是某条数据的状态。它和后端的 statusDesc 是两回事 ——
               后端哪天把「待审核」改成「待审」，这个下拉框不会跟着变，
               但那不影响正确性：管理员选的是「状态 = 0」，看到的是自己写的标签。 -->
          <el-option label="待审核" :value="STATUS_PENDING" />
          <el-option label="已通过" :value="STATUS_APPROVED" />
          <el-option label="已驳回" :value="STATUS_REJECTED" />
        </el-select>

        <el-input
          v-model="filter.keyword"
          style="width: 240px"
          placeholder="笔名 / 申请人用户名 / 昵称"
          clearable
          @keyup.enter="search"
          @clear="search"
        />

        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <p class="hint">
        <b>这是一个待办队列</b>：待审的申请永远排在最前面（状态升序 + id 倒序），
        所以不筛选时看到的就是「还有多少活」。
        <br />
        通过 = 改状态 <b>+ 给这个人 AUTHOR 角色 + 把他踢下线</b>（三件事一起做）。
        被踢下线是因为角色权限是登录时写进缓存的 —— 不踢的话他重新打开创作中心照样进不去。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column prop="id" label="ID" width="70" />

        <el-table-column label="申请人" min-width="140">
          <template #default="{ row }">
            <!--
              ★ 昵称和登录名都给：昵称可以重复、可以改、可以为空，
                登录名不会。审核员要判断「这个人是不是刚被驳回又交了一份」，
                昵称在这个场景里不足以定位人。
            -->
            <template v-if="row.username">
              <div class="who">{{ row.nickname || row.username }}</div>
              <div class="who-sub">@{{ row.username }}</div>
            </template>
            <!--
              ★ 两个都为空 = 申请人已注销。
                判定必须用「假值」而不是 === null：后端配了
                default-property-inclusion: non_null，为空时这两个 key
                **整个不存在**，`row.username === null` 永远不成立。
            -->
            <span v-else class="none">已注销</span>
          </template>
        </el-table-column>

        <el-table-column label="笔名" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="pen">{{ row.penName }}</span>
          </template>
        </el-table-column>

        <el-table-column label="创作说明" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.intro">{{ row.intro }}</span>
            <span v-else class="none">未填写</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <!-- ★ 文案来自后端 statusDesc，这里只给颜色。见文件头 -->
            <el-tag :type="applyStatusType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="审核时间" width="150">
          <template #default="{ row }">
            <!-- 未审核时 auditTime 为空 → key 消失 → 取到 undefined -->
            <span v-if="row.auditTime">{{ formatDateTime(row.auditTime) }}</span>
            <span v-else class="none">未审核</span>
          </template>
        </el-table-column>

        <el-table-column label="驳回理由" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <!--
              ★ 列名叫「驳回理由」而不是「审核备注」—— 这一列的内容
                **申请人看得到**（读者端会返回它），和评论管理页那列同名不同物。
                叫「备注」的话，审核员会以为这是自己看的，随手写点内部判断。
            -->
            <span v-if="row.auditRemark">{{ row.auditRemark }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <!--
              ★ 非待审的行禁用审核，并且要说明原因。
                两个细节（和用户管理页「不能封禁自己」那处一样）：
                ① 禁用的按钮不触发鼠标事件，所以必须包一层 <span> ——
                   直接给 el-button 套 el-tooltip 是弹不出来的
                ② 光禁用不说话，管理员会以为「按钮坏了」
            -->
            <el-tooltip
              :disabled="auditable(row)"
              :content="auditDisabledReason(row)"
              placement="top"
            >
              <span>
                <el-button link type="primary" :disabled="!auditable(row)" @click="openAudit(row)">
                  审核
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            v-if="paged.loaded.value"
            description="没有符合条件的作者申请"
            :image-size="80"
          />
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

    <!-- ==================== 审核弹窗 ==================== -->
    <!--
      ★ 复用 AuditDialog（作品 / 章节 / 评论 / 作者申请四处共用）。
        这里的差异：
          · 通过按钮的文案点明「授予作者角色」—— 它真的会改权限，
            不能只说「通过」（那样审核员以为只是盖个章）
          · 驳回理由**必填**（默认值就是 true，不传这个 prop）——
            和评论那页相反，理由见文件头
          · 提示语有两条（通过一条、驳回一条）—— AuditDialog 只给了
            一个 passHint（它显示在页脚，紧挨着按钮，那是「通过」的后果），
            驳回那句放在弹窗内的预览区里，见下面那个 preview-note
    -->
    <AuditDialog
      v-model="auditVisible"
      title="审核作者申请"
      :target-name="auditTarget ? `申请 #${auditTarget.id}` : ''"
      pass-text="通过并授予作者角色"
      reject-text="驳回"
      :pass-hint="passHint()"
      :pass-disabled="auditDisabled()"
      :reject-disabled="auditDisabled()"
      remark-tip="★ 驳回时必须填写 —— 这条理由申请人看得到（阅读端会显示），他会据此修改后重新提交"
      :loading="auditLoading"
      @submit="submitAudit"
    >
      <div v-if="auditTarget" class="preview-body">
        <p class="preview-meta">
          {{ auditTarget.nickname || auditTarget.username || '已注销用户' }}
          <template v-if="auditTarget.username"> · @{{ auditTarget.username }}</template>
          · 提交于 {{ formatDateTime(auditTarget.createTime) }}
        </p>

        <p class="preview-label">笔名</p>
        <p class="preview-pen">{{ auditTarget.penName }}</p>

        <p class="preview-label">创作说明</p>
        <p v-if="auditTarget.intro" class="preview-content">{{ auditTarget.intro }}</p>
        <p v-else class="none">申请人没有填写创作说明</p>

        <!--
          ★ 驳回之后会发生什么，写在弹窗里而不是靠外面的提示 ——
            审核员要在**点按钮之前**知道后果，不是点完之后才知道。
        -->
        <p v-if="!auditDisabled()" class="preview-note">{{ rejectHint() }}</p>
      </div>
    </AuditDialog>
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
  line-height: 1.8;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.who {
  color: #303133;
}

.who-sub {
  font-size: 12px;
  color: #909399;
  font-family: Consolas, Monaco, monospace;
}

/* ★ 笔名加粗：它是这份申请的「名字」，审核员主要靠它辨认 */
.pen {
  font-weight: 600;
  color: #303133;
}

.none {
  color: #c0c4cc;
}

.preview-meta {
  margin: 0 0 12px;
  font-size: 12px;
  color: #909399;
}

.preview-label {
  margin: 0 0 4px;
  font-size: 12px;
  color: #909399;
}

.preview-pen {
  margin: 0 0 12px;
  font-weight: 600;
  font-size: 15px;
}

.preview-content {
  margin: 0;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}

/*
 * ★ 后果说明用浅色底 + 左边框，和正文分开：
 *   它和「创作说明」都是灰底上的文字，不区分的话会被当成申请内容的一部分
 *   读进去 —— 而那句是系统说的话，不是申请人写的。
 */
.preview-note {
  margin: 14px 0 0;
  padding: 8px 10px;
  background: #f4f4f5;
  border-left: 3px solid #c0c4cc;
  border-radius: 3px;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
</style>
