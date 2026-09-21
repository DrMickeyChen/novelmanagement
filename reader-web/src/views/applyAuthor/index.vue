<script setup>
/**
 * 申请成为作者 —— 表单 + 状态回显。
 *
 * ★ 需要登录（后端 `/api/reader/**` 落在 `anyRequest().authenticated()` 上）。
 *
 * ============================================================
 * ★★ 这个页面的难点全在「按什么顺序判断该显示哪一块」
 * ============================================================
 * 页面上其实有【五个】互斥的状态，而且它们的判据来自两个不同的地方：
 *
 *   ┌──────────────────────┬──────────────────────────────────────┐
 *   │ 显示什么             │ 判据                                 │
 *   ├──────────────────────┼──────────────────────────────────────┤
 *   │ 你已经是作者了       │ userStore.isAuthor（★ 登录态里的角色）│
 *   │ 审核中               │ apply.status === 0                   │
 *   │ 已驳回 + 理由        │ apply.status === 2                   │
 *   │ 表单（第一次申请）   │ apply === null                       │
 *   │ 表单（重新申请）     │ apply.status === 2 且点了按钮        │
 *   └──────────────────────┴──────────────────────────────────────┘
 *
 * ★★ **`isAuthor` 必须排在最前面**，不能只判 `apply.status`。
 *   边界情况是真实存在的：管理员在用户管理页**手工给某人加了 AUTHOR 角色**，
 *   而这个人从来没申请过 → `/mine` 返回 `null` → 只判 status 的话会给他
 *   一个表单，他一填就撞 `1015`「你已经是作者了」。
 *   后端 `ReaderAuthorApplyController#mine` 的注释专门点了这个边界。
 *
 * ★ 为什么不做成「表单和状态卡同时显示、用 disabled 切换」：
 *   那样用户会在「审核中」的时候对着一个填不了的框发呆，而**看不到自己
 *   提交了什么**。状态卡把上次提交的笔名和说明原样带出来，这是他能核对的唯一地方。
 *
 * ★ 为什么不用路由 query 区分「查看状态 / 填写表单」两种模式：
 *   那会多出两个可分享的 URL，而它们对别人毫无意义（每个人看到的都是自己的申请）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyAuthorApply, submitAuthorApply } from '@/api/authorApply'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { AUTHOR_WEB_URL } from '@/utils/auth'

const userStore = useUserStore()

const loading = ref(false)
/** `/mine` 的结果；`null` = 从来没申请过 */
const apply = ref(null)

/** 被驳回之后是否展开「重新申请」表单 */
const resubmitting = ref(false)

const formRef = ref()
const submitting = ref(false)

const form = reactive({
  penName: '',
  intro: '',
})

const rules = {
  penName: [
    { required: true, message: '请填写笔名', trigger: 'blur' },
    // ★ 50 / 500 和后端 `AuthorApplyCreateDTO` 的 @Size 一致。
    //   前端这两条只是「不用等一次往返」，真正的拒绝在服务端。
    { max: 50, message: '笔名最多 50 个字', trigger: 'blur' },
  ],
  intro: [{ max: 500, message: '创作说明最多 500 个字', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    // ★ `?? null` —— 没申请过时后端那个 key 整个消失，拿到的是 undefined。
    //   页面下面全部用 `apply.value?.xxx` 取值，两种空值表现一致。
    apply.value = (await getMyAuthorApply()) ?? null
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 提交过了、正在等审核 */
const isPending = computed(() => apply.value?.status === 0)
/** 被驳回了 */
const isRejected = computed(() => apply.value?.status === 2)
/** 审核通过了（★ 注意：approve 会**把申请人踢下线**，所以这个状态基本只在
 *   他重新登录之后才看得到 —— 见下面状态卡里的提示） */
const isApproved = computed(() => apply.value?.status === 1)

/**
 * 是否显示表单。
 *
 * ★★ 三个条件里 `!userStore.isAuthor` 是**兜底**，不是重复判断：
 *   审批通过的那一刻后端会清掉这个人的全部登录态（`grantAuthorRole` 里
 *   的 `tokenStore.removeAll`），所以他下次进来时角色是新的 ——
 *   但如果管理员是**在他登录期间**手工加的角色，本地 `userStore` 里的
 *   `roles` 还是登录那一刻的旧值，`isAuthor` 会是 `false`。
 *   这种情况下提交会被后端 `1015` 挡掉，前端挡一道只是省一次往返。
 */
const showForm = computed(() => {
  if (userStore.isAuthor) return false
  if (apply.value == null) return true
  return isRejected.value && resubmitting.value
})

/** 点了「重新申请」——把上次提交的内容预填回去 */
function startResubmit() {
  form.penName = apply.value?.penName || ''
  form.intro = apply.value?.intro || ''
  resubmitting.value = true
}

function cancelResubmit() {
  resubmitting.value = false
  formRef.value?.clearValidate()
}

/** 去创作中心。★ 跨应用跳转，要用 location.href（同 BasicLayout 里那段理由） */
function gotoAuthorApp() {
  window.location.href = AUTHOR_WEB_URL
}

/**
 * 提交。
 *
 * ★ 提交成功后**重新拉一次 `/mine`**，而不是把本地状态改成「审核中」：
 *   这样页面显示的一定是服务端的真实状态（含提交时间）。
 *   自己拼的话，「提交成功但状态不对」这类问题永远不会被发现。
 *
 * ★ 后端两个业务失败码都要接住（HTTP 200 + code，拦截器已经弹过提示了）：
 *   `1009` 已经有一份在审核中 → 刷新一下就变成「审核中」
 *   `1015` 你已经是作者了   → 本地角色过期了，得重新登录才会更新
 *   所以失败分支里也要 `load()` 一次 —— 让页面跟上服务端的真实状态。
 */
async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没过，el-form 已经在字段下面标红了
  }

  submitting.value = true
  try {
    await submitAuthorApply({
      penName: form.penName.trim(),
      intro: form.intro.trim() || undefined,
    })
    ElMessage.success('申请已提交，请等待管理员审核')
    resubmitting.value = false
    await load()
  } catch {
    await load()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="apply">
    <div class="page-head">
      <h1 class="title">申请成为作者</h1>
    </div>

    <!--
      ★★ 顺序：isAuthor 在最前，然后才是 apply.status。
        理由见文件头那张表 —— 反过来会给「管理员手工加过角色」的人
        一个填了就报 1015 的表单。
    -->
    <el-card v-if="userStore.isAuthor" shadow="never" class="card">
      <el-result icon="success" title="你已经是作者了" sub-title="现在就可以去创作中心写作了">
        <template #extra>
          <el-button type="primary" @click="gotoAuthorApp">进入创作中心</el-button>
        </template>
      </el-result>
    </el-card>

    <el-card v-else-if="isPending" shadow="never" class="card">
      <el-result
        icon="info"
        title="申请审核中"
        :sub-title="`提交于 ${formatDateTime(apply.createTime)}`"
      >
        <template #extra>
          <!--
            ★ 把提交的内容原样显示出来：这是用户**唯一能核对自己写了什么**的地方
              （「我的申请」只此一处）。不给看的话，被驳回时他会连
              「我上次写的笔名是什么」都想不起来。
          -->
          <el-descriptions :column="1" border class="detail">
            <el-descriptions-item label="笔名">{{ apply.penName }}</el-descriptions-item>
            <el-descriptions-item label="创作说明">
              {{ apply.intro || '（未填写）' }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
      </el-result>
    </el-card>

    <el-card v-else-if="isRejected && !resubmitting" shadow="never" class="card">
      <el-result icon="warning" title="申请未通过" :sub-title="`审核于 ${formatDateTime(apply.auditTime)}`">
        <template #extra>
          <!--
            ★★★ 驳回理由是这一整页存在的意义，**必须显示**。
              后端 `AuthorApplyVO.auditRemark` 特意把它返回给申请人
              （和评论的 `auditRemark` 正好相反 —— 那个是管理员自留备注）。
              `|| '（管理员未填写理由）'` 那个兜底不是装饰：后端**刻意不强制
              填理由**（前端 `AuditDialog` 才拦），所以空理由是一条真实存在的路径。
          -->
          <el-alert type="error" :closable="false" show-icon class="reason">
            <template #title>驳回理由</template>
            {{ apply.auditRemark || '（管理员未填写理由）' }}
          </el-alert>

          <el-descriptions :column="1" border class="detail">
            <el-descriptions-item label="笔名">{{ apply.penName }}</el-descriptions-item>
            <el-descriptions-item label="创作说明">
              {{ apply.intro || '（未填写）' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-button type="primary" @click="startResubmit">重新申请</el-button>
        </template>
      </el-result>
    </el-card>

    <el-card v-if="showForm" shadow="never" class="card">
      <template #header>
        <span>{{ isRejected ? '重新提交申请' : '填写申请' }}</span>
      </template>

      <!--
        ★ 被驳回之后重新提交，说明这里交的是【一份新记录】而不是改旧的 ——
          不说清楚的话，用户会以为上次那份被改掉了（其实它在管理端还查得到）。
      -->
      <el-alert v-if="isRejected" type="info" :closable="false" show-icon class="note">
        重新提交会产生一份<b>新的申请记录</b>，上一次那份会作为历史保留。
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
        @submit.prevent
      >
        <el-form-item label="笔名" prop="penName">
          <el-input v-model="form.penName" maxlength="50" show-word-limit placeholder="你在作品上署的名字" />
          <!--
            ★ 说清楚笔名和昵称的区别。这不是废话：读者端到处显示的是昵称，
              而作品上署的是笔名，用户第一次看到这个框一定会问「和昵称一样吗」。
          -->
          <div class="tip">
            笔名会署在你的作品上，和读者端的<b>昵称</b>是两回事，可以不一样。
          </div>
        </el-form-item>

        <el-form-item label="创作说明" prop="intro">
          <el-input
            v-model="form.intro"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="打算写什么？有过哪些创作经历？（选填）"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">提交申请</el-button>
          <el-button v-if="isRejected" @click="cancelResubmit">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.apply {
  padding: 20px 0;
}

.page-head {
  margin-bottom: 14px;
}

.title {
  margin: 0;
  font-size: 20px;
}

/* ★ 限宽：这一页最长的一行是「创作说明」，拉满 960px 会读得很累 */
.card {
  max-width: 620px;
}

/*
 * ★ el-result 的 #extra 区域默认是行内居中排布，塞表格进去会很难看。
 *   这里让它的子元素各自占满一行、左对齐 —— 状态卡里要放「理由 + 明细 + 按钮」
 *   三样东西，它们是纵向的段落关系，不是一行字。
 */
.card :deep(.el-result__extra) {
  width: 100%;
}

.detail {
  margin-bottom: 16px;
  text-align: left;
}

.reason {
  margin-bottom: 16px;
  text-align: left;
}

.note {
  margin-bottom: 16px;
}

.tip {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
