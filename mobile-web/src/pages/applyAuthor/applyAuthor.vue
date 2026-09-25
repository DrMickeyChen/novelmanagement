<script setup>
/**
 * 申请成为作者 —— 对应 `reader-web/src/views/applyAuthor/index.vue`。
 *
 * ★ 需要登录（后端 `/api/reader/**` 落在 `anyRequest().authenticated()` 上）。
 *   未登录时在这一页里渲染引导，**不是**跳走（同书架页/历史页那段理由）。
 *
 * ============================================================
 * ★★ 这个页面全部难点在「按什么顺序判断该显示哪一块」
 * ============================================================
 * 五块互斥的界面，判据来自**两个不同的地方**：
 *
 *   ┌──────────────────────┬───────────────────────────────────────┐
 *   │ 显示什么             │ 判据                                  │
 *   ├──────────────────────┼───────────────────────────────────────┤
 *   │ 你已经是作者了       │ `userStore.isAuthor`（★ 登录态里的角色）│
 *   │ 审核中               │ `apply.status === 0`                  │
 *   │ 已驳回 + 理由        │ `apply.status === 2`                  │
 *   │ 表单（第一次申请）   │ `apply === null`                      │
 *   │ 表单（重新申请）     │ 驳回 + 点了「重新申请」                │
 *   └──────────────────────┴───────────────────────────────────────┘
 *
 * ★★ **`isAuthor` 必须排在最前面**，不能只判 `apply.status`。
 *   边界情况真实存在：管理员在用户管理页**手工**给某人加了 AUTHOR 角色，
 *   而这个人**从来没申请过** → `/mine` 返回 null → 只判 status 的话
 *   会给他一个表单，他一填就撞 `1015`「你已经是作者了」。
 *   后端 `ReaderAuthorApplyController#mine` 的注释专门点了这个边界。
 *
 * ★ 为什么不把「表单」和「状态卡」同时显示、用禁用态切换：
 *   那样用户在「审核中」时对着一个填不了的框发呆，而**看不到自己提交过什么**。
 *   状态卡把上次的笔名和说明原样带出来，那是他唯一能核对的地方。
 *
 * ============================================================
 * ★★★ 驳回理由是这一页存在的意义
 * ============================================================
 * `auditRemark` 在这里是**给申请人看的驳回理由**，后端特意返回它 ——
 * 和评论的 `auditRemark`（管理员自留备注、读者看不到）**正好相反**。
 * 照抄评论那边的写法会把这个字段藏起来，而那正是「驳回」这个功能的意义。
 *
 * ★ `|| '（管理员未填写理由）'` 那个兜底不是装饰：后端**刻意不强制**填理由
 *   （「驳回必须填理由」是前端 `AuditDialog` 的规则），所以空理由是一条
 *   真实存在的路径，不是防御性编程。
 */
import { computed, reactive, ref, watch } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getMyAuthorApply, submitAuthorApply } from '@/api/authorApply'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
/** `/mine` 的结果。★ `null` = 从来没申请过 */
const apply = ref(null)

/** 被驳回之后是否展开「重新申请」表单 */
const resubmitting = ref(false)
const submitting = ref(false)

const form = reactive({ penName: '', intro: '' })
/** 表单的本地校验信息。★ 一次只显示第一条 —— 移动端没有 el-form 那种逐字段标红 */
const formError = ref('')

/**
 * ★★ 50 / 500 和后端 `AuthorApplyCreateDTO` 的 `@Size` 一致。
 *   前端拦只是「不用等一次往返」，**真正的拒绝在服务端** ——
 *   所以下面提交失败时照样要处理业务码，不能因为"我验过了"就假定不会失败。
 */
const PEN_NAME_MAX = 50
const INTRO_MAX = 500

/**
 * 拉当前申请状态。
 *
 * ★ `?? null`：没申请过时后端那个 key **整个消失**（`non_null` 配置），
 *   拿到的是 `undefined`。写 `=== null` 判断会漏掉。
 * ★ 失败时**不动** `apply`（保持上一次的值）：清成 null 会让页面从
 *   「审核中」突然变成一张空白表单，而那只是网络抖了一下。
 */
async function load() {
  if (!userStore.isLogin) return
  loading.value = true
  try {
    apply.value = (await getMyAuthorApply()) ?? null
  } catch {
    /* 失败原因由 request.js 的拦截器提示 */
  } finally {
    loading.value = false
  }
}

/**
 * ★★ 登录态变化时重新拉 —— 和 `shelf.vue` 同一手法。
 *   用户完全可能**在这一页上**点「去登录」→ 登录页 → 回来，
 *   那种情况下页面不会重新挂载，少了这个 watch 他会看到一个空表单，
 *   而他上一次提交的申请其实还在。
 * ★ `immediate: true` 让首次进入也走同一条路：一份逻辑两个入口，
 *   不会出现「首屏的判法和登录回来的判法不一致」。
 */
watch(
  () => userStore.isLogin,
  (isLogin) => {
    if (isLogin) {
      load()
    } else {
      apply.value = null
      resubmitting.value = false
    }
  },
  { immediate: true },
)

/**
 * ★ 从别的页面切回来时静默刷新。
 *   这一页不常变，但审批**会给申请人发通知**，用户看完通知切回来时
 *   状态可能已经变了 —— 不刷的话他会一直盯着那句「审核中」。
 */
onShow(() => {
  if (userStore.isLogin && apply.value !== null) load()
})

/** 提交过了、正在等审核 */
const isPending = computed(() => apply.value?.status === 0)
/** 被驳回了。★ 判的是数字（`statusDesc` 只用于显示，判断一律用数字） */
const isRejected = computed(() => apply.value?.status === 2)
/** 审核通过了。★ approve 会**把申请人踢下线**（后端 `tokenStore.removeAll`），
 *   所以这个状态基本只在他重新登录之后才看得到 —— 见状态卡里的提示 */
const isApproved = computed(() => apply.value?.status === 1)

/**
 * 是否显示表单。
 *
 * ★ `!userStore.isAuthor` 是**兜底**不是重复判断：审批通过时后端清掉了
 *   这个人的全部登录态，所以他下次进来角色是新的；但如果管理员是
 *   **在他登录期间**手工加的角色，本地 `userStore.roles` 还是登录那一刻的旧值。
 *   那种情况下提交会被后端 `1015` 挡掉，前端挡一道只是省一次往返。
 */
const showForm = computed(() => {
  if (userStore.isAuthor) return false
  if (apply.value == null) return true
  return isRejected.value && resubmitting.value
})

/** 点了「重新申请」—— 把上次提交的内容预填回去 */
function startResubmit() {
  form.penName = apply.value?.penName || ''
  form.intro = apply.value?.intro || ''
  formError.value = ''
  resubmitting.value = true
}

function cancelResubmit() {
  resubmitting.value = false
  formError.value = ''
}

/**
 * 提交前校验。
 *
 * ★ 用 `.trim()` 之后再判长度：**后端拿到的是 trim 之后的值**
 *   （下面提交时也 trim）。不 trim 的话，50 个空格 + 1 个字在本地能过、
 *   到服务端变成一个空笔名 —— 而错误提示在下一个往返才回来。
 */
function validate() {
  const penName = form.penName.trim()
  if (!penName) return '请填写笔名'
  if (penName.length > PEN_NAME_MAX) return `笔名最多 ${PEN_NAME_MAX} 个字`
  if (form.intro.trim().length > INTRO_MAX) return `创作说明最多 ${INTRO_MAX} 个字`
  return ''
}

/**
 * 提交。
 *
 * ★ 成功后**重新拉一次 `/mine`**，而不是把本地状态改成「审核中」：
 *   这样页面显示的一定是服务端的真实状态（含提交时间）。
 *   自己拼的话，「提交成功但状态不对」这类问题永远不会被发现。
 *
 * ★★ 失败分支里**也要 `load()`** —— 两个业务失败码（HTTP 200 + code，
 *   拦截器已经弹过提示了）都指向「服务端状态和本地不一致」：
 *     `1009` 已经有一份在审核中 → 刷新一下就变成「审核中」；
 *     `1015` 你已经是作者了     → 本地角色过期，得重新登录才会更新。
 *   不刷的话用户会对着一个填了必然再失败的按钮反复点。
 */
async function handleSubmit() {
  const err = validate()
  if (err) {
    formError.value = err
    return
  }
  formError.value = ''
  submitting.value = true
  try {
    await submitAuthorApply({
      penName: form.penName.trim(),
      // ★ 空说明传 undefined（省略这个 key），不传空串 ——
      //   和后端「没填」的语义保持一致，也省得库里存一堆 ""
      intro: form.intro.trim() || undefined,
    })
    uni.showToast({ title: '申请已提交，请等待审核', icon: 'none' })
    resubmitting.value = false
    await load()
  } catch {
    await load()
  } finally {
    submitting.value = false
  }
}

/**
 * 进入创作中心。
 *
 * ★★★ 这是本页**唯一一处 `#ifdef H5`**，它是一笔真实的跨端代价、不是脏东西：
 *   移动端只做读者端（本期已拍板），创作中心在 `author-web` 这个**独立的
 *   桌面应用**里，小程序/App 里没有它的位置 —— 那里根本没有浏览器地址栏，
 *   也没有第二个 H5 应用可以跳过去。
 *   `#ifdef H5` 分支里跳的是同源的 `/author/`（dev 走 `:3000` 的 dev-proxy，
 *   线上走 Nginx，两条路都能到）；非 H5 分支就**如实说明这里做不到**，
 *   而不是给一个点了没反应的按钮。
 * ★ 不用 `window.location.href` 之外的写法：这里在 `<script setup>` 里、
 *   不在模板里（模板里没有任何全局对象可用）。
 */
function gotoAuthorApp() {
  // #ifdef H5
  window.location.href = '/author/'
  // #endif
  // #ifndef H5
  uni.showToast({ title: '创作中心请在电脑浏览器中打开', icon: 'none' })
  // #endif
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** 提交时间 / 审核时间。★ 用 formatDateTime（带时分），这两处用户要看具体时刻 */
function timeText(value) {
  return formatDateTime(value)
}
</script>

<template>
  <view class="apply">
    <!-- ============ 未登录 ============ -->
    <view v-if="!userStore.isLogin" class="gate">
      <text class="gate__icon">✍️</text>
      <text class="gate__title">登录后可以申请成为作者</text>
      <text class="gate__sub">通过之后就能在创作中心发布自己的作品。</text>
      <button class="gate__btn" @click="goLogin">去登录</button>
    </view>

    <template v-else>
      <!--
        ★★ 顺序：isAuthor 在最前，然后才是 apply.status。
          理由见文件头 —— 反过来会给「管理员手工加过角色」的人
          一个填了就报 1015 的表单。
      -->
      <view v-if="userStore.isAuthor" class="card card--state">
        <text class="state__icon">🎉</text>
        <text class="state__title">你已经是作者了</text>
        <text class="state__sub">现在就可以去创作中心写作了。</text>
        <text class="btn btn--primary" @click="gotoAuthorApp">进入创作中心</text>
      </view>

      <view v-else-if="isPending" class="card card--state">
        <text class="state__icon">⏳</text>
        <text class="state__title">申请审核中</text>
        <text v-if="timeText(apply.createTime)" class="state__sub">
          提交于 {{ timeText(apply.createTime) }}
        </text>

        <!--
          ★ 把提交的内容原样显示出来：这是用户**唯一能核对自己写了什么**的地方
            （「我的申请」只此一处）。不给看的话，被驳回时他会连
            「我上次写的笔名是什么」都想不起来。
        -->
        <view class="detail">
          <view class="detail__row">
            <text class="detail__label">笔名</text>
            <text class="detail__value">{{ apply.penName }}</text>
          </view>
          <view class="detail__row">
            <text class="detail__label">创作说明</text>
            <text class="detail__value">{{ apply.intro || '（未填写）' }}</text>
          </view>
        </view>
      </view>

      <view v-else-if="isRejected && !resubmitting" class="card card--state">
        <text class="state__icon">⚠️</text>
        <text class="state__title">申请未通过</text>
        <text v-if="timeText(apply.auditTime)" class="state__sub">
          审核于 {{ timeText(apply.auditTime) }}
        </text>

        <!--
          ★★★ 驳回理由是这一整页存在的意义，必须显示。
            标红的框 + 「驳回理由」四个字：它是**下一句提问的答案**
            （「我为什么没通过」），不是一条可有可无的备注。
        -->
        <view class="reason">
          <text class="reason__label">驳回理由</text>
          <text class="reason__text">{{ apply.auditRemark || '（管理员未填写理由）' }}</text>
        </view>

        <view class="detail">
          <view class="detail__row">
            <text class="detail__label">笔名</text>
            <text class="detail__value">{{ apply.penName }}</text>
          </view>
          <view class="detail__row">
            <text class="detail__label">创作说明</text>
            <text class="detail__value">{{ apply.intro || '（未填写）' }}</text>
          </view>
        </view>

        <text class="btn btn--primary" @click="startResubmit">重新申请</text>
      </view>

      <!-- ============ 表单 ============ -->
      <view v-if="showForm" class="card">
        <text class="card__title">{{ isRejected ? '重新提交申请' : '填写申请' }}</text>

        <!--
          ★ 被驳回之后重新提交，交的是【一份新记录】而不是改旧的 ——
            不说清楚的话，用户会以为上次那份被改掉了
            （其实它在管理端还查得到，这是后端 `mine` 只取最新一条带来的错觉）。
        -->
        <view v-if="isRejected" class="note">
          <text class="note__text">
            重新提交会产生一份新申请记录，上一次那份会作为历史保留。
          </text>
        </view>

        <view class="field">
          <text class="field__label">笔名</text>
          <input
            v-model="form.penName"
            class="field__input"
            :maxlength="PEN_NAME_MAX"
            placeholder="你在作品上署的名字"
            placeholder-class="field__ph"
          />
          <!--
            ★ 说清笔名和昵称的区别。这不是废话：读者端到处显示的是**昵称**，
              而作品上署的是**笔名**，用户第一次看到这个框一定会问「和昵称一样吗」。
          -->
          <text class="field__tip">笔名会署在你的作品上，和读者端的昵称是两回事，可以不一样。</text>
        </view>

        <view class="field">
          <text class="field__label">创作说明</text>
          <textarea
            v-model="form.intro"
            class="field__textarea"
            :maxlength="INTRO_MAX"
            placeholder="打算写什么？有过哪些创作经历？（选填）"
            placeholder-class="field__ph"
          />
          <text class="field__count">{{ form.intro.length }}/{{ INTRO_MAX }}</text>
        </view>

        <!-- ★ 本地校验信息。★ 前后端都会拦，但提示只有这一处（服务端回来的是 code） -->
        <text v-if="formError" class="field__err">{{ formError }}</text>

        <view class="field__ops">
          <text v-if="!submitting" class="btn btn--primary" @click="handleSubmit">提交申请</text>
          <text v-else class="btn btn--off">提交中…</text>
          <text v-if="isRejected" class="btn" @click="cancelResubmit">取消</text>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.apply {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 未登录引导（与书架页/历史页同形） ---------- */
.gate {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 140rpx 48rpx;
  background: #fff;
  border-radius: 16rpx;
}

.gate__icon {
  font-size: 96rpx;
  line-height: 1;
}

.gate__title {
  margin-top: 24rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
}

.gate__sub {
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #9aa3b0;
  text-align: center;
  line-height: 1.6;
}

.gate__btn {
  margin-top: 40rpx;
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 12rpx;
  background: #2f6fed;
  color: #fff;
  font-size: 30rpx;
}

/* ---------- 卡片 ---------- */
.card {
  padding: 32rpx 28rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 16rpx;
}

.card--state {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.card__title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
}

.state__icon {
  font-size: 88rpx;
  line-height: 1;
}

.state__title {
  margin-top: 20rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
}

.state__sub {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ---------- 明细 ---------- */
.detail {
  width: 100%;
  margin-top: 28rpx;
  padding: 8rpx 20rpx;
  background: #f5f6f8;
  border-radius: 12rpx;
}

.detail__row {
  display: flex;
  padding: 16rpx 0;
  border-bottom: 2rpx solid #eaecf0;
}

.detail__row:last-child {
  border-bottom: none;
}

.detail__label {
  flex-shrink: 0;
  width: 140rpx;
  font-size: 26rpx;
  color: #8a93a0;
}

.detail__value {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  color: #1f2733;
  word-break: break-all;
}

/* ---------- 驳回理由 ---------- */
.reason {
  width: 100%;
  margin-top: 28rpx;
  padding: 20rpx 24rpx;
  background: #fef2f2;
  border-radius: 12rpx;
}

.reason__label {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #d92d20;
}

.reason__text {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #7a271a;
  line-height: 1.6;
  word-break: break-all;
}

/* ---------- 表单 ---------- */
.note {
  margin-top: 20rpx;
  padding: 16rpx 20rpx;
  background: #f0f4ff;
  border-radius: 12rpx;
}

.note__text {
  font-size: 24rpx;
  color: #5a6472;
  line-height: 1.6;
}

.field {
  margin-top: 28rpx;
}

.field__label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 28rpx;
  color: #1f2733;
}

.field__input {
  height: 80rpx;
  padding: 0 20rpx;
  background: #f5f6f8;
  border-radius: 12rpx;
  font-size: 28rpx;
  color: #1f2733;
}

.field__textarea {
  width: 100%;
  height: 220rpx;
  padding: 16rpx 20rpx;
  box-sizing: border-box;
  background: #f5f6f8;
  border-radius: 12rpx;
  font-size: 28rpx;
  color: #1f2733;
}

.field__ph {
  color: #b6bdc7;
}

.field__tip {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #9aa3b0;
  line-height: 1.6;
}

.field__count {
  display: block;
  margin-top: 8rpx;
  text-align: right;
  font-size: 22rpx;
  color: #b6bdc7;
}

.field__err {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #d92d20;
}

.field__ops {
  display: flex;
  align-items: center;
  margin-top: 32rpx;
}

/* ---------- 按钮 ---------- */
.btn {
  padding: 16rpx 44rpx;
  margin-right: 20rpx;
  border-radius: 999rpx;
  font-size: 28rpx;
  color: #5a6472;
  background: #f5f6f8;
}

.btn--primary {
  color: #fff;
  background: #2f6fed;
}

/* ★ 置灰态用颜色 + 不绑定事件，不是 disabled 属性（text 组件没有 disabled） */
.btn--off {
  color: #b6bdc7;
  background: #f0f2f5;
}
</style>
