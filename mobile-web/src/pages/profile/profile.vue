<script setup>
/**
 * 我的（tab 4）—— 头像 / 昵称 / 登录名 / 退出登录。
 *
 * ★ 与 reader-web/src/views/profile/index.vue 对应，展示了同样的信息。
 *
 * ------------------------------------------------------------
 * ★★ 头像上传这一段是【换实现】最彻底的一处
 * ------------------------------------------------------------
 * 桌面端：`<el-upload>` + `FormData` + `File`，走 axios。
 * 移动端：`uni.chooseImage`（相册/拍照）→ `uni.uploadFile`。
 *
 * `uni.uploadFile` 是全项目**唯一一条不走 `request()` 的请求通道**，
 * 于是 `request.js` 替我们做过的每一件事，这里都要自己做一遍：
 *   ① Authorization 头 —— 自己加（它不认我们那个封装）
 *   ② 响应体是**字符串**，不是解析好的对象 —— 自己 `JSON.parse`
 *   ③ 4xx/5xx 同样走 success（R5 在它身上照样成立）—— 自己判 statusCode
 *   ④ Content-Type **不要设** —— multipart 的 boundary 要由它自己生成，
 *      手写成 'multipart/form-data' 会让 boundary 丢掉，
 *      后端解析不出任何字段（★ 症状是「文件是 null」，不是一条明确的报错）
 *
 * ------------------------------------------------------------
 * ★★ 为什么不做「先上传拿 URL、再调接口存 URL」
 * ------------------------------------------------------------
 * 那样第二步就得把一个**调用方给的 URL 字符串**写进数据库，
 * 而服务端对它再也做不了任何校验（魔数校验发生在第一步）。
 * `uploadAvatar` 一步做完「校验 → 落盘 → 写库」，进库的 URL 永远是
 * 后端自己生成的那个。★ 两端的约定必须一致，移动端不为"复用 request.js"拆成两步。
 *
 * ★ 校验全在后端（扩展名白名单 / Content-Type / **文件头魔数**）。
 *   前端只做「大小和格式的即时提示」，真正的拒绝永远在服务端 ——
 *   所以下面 `beforeUpload` 挡不住的东西（比如把 .txt 改名成 .png）
 *   后端照样会拒，这是设计。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { getProfile, updateNickname } from '@/api/profile'
import { useUserStore } from '@/stores/user'
import { API_BASE_URL } from '@/utils/request'
import { assetUrl } from '@/utils/asset'
import { getToken } from '@/utils/auth'
import { confirm } from '@/utils/confirm'

const userStore = useUserStore()

/** 头像上限 2MB —— 与后端 `ImageKind.AVATAR` 一致，改后端时这里要跟着改 */
const MAX_MB = 2
/** ★ 与后端白名单一致。多了后端也会拒，但那样用户要等一次上传才被拒 */
const ACCEPT = ['jpg', 'jpeg', 'png', 'webp']

const loading = ref(false)
const loaded = ref(false)
const failed = ref(false)
const saving = ref(false)
const uploading = ref(false)

/** 可编辑的昵称 */
const nickname = ref('')
/** 不可编辑的字段（登录名 / 邮箱 / 头像 URL） */
const base = ref({})

onMounted(() => {
  if (userStore.isLogin) load()
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    const data = await getProfile()
    base.value = data || {}
    nickname.value = base.value.nickname || ''
  } catch {
    // ★ 拦截器已提示。但页面不能停在"看起来像没登录"的样子上
    failed.value = true
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function retry() {
  load().catch(() => {})
}

/**
 * 头像地址。
 *
 * ★★ 后端给的是**相对路径**（`/uploads/avatar/xxx.png`），**必须补成绝对地址**，
 *    这件事交给 `assetUrl`（`utils/asset.js`）——
 *    ★★★ 这里曾经自己拼过一遍，拼法是「`API_BASE_URL` 去掉 `/api` 后缀再加路径」，
 *    而 `API_BASE_URL` 的默认值是相对值 `/api`，去掉后缀剩**空串**，
 *    于是补出来的还是相对路径，**等于没补**。
 *
 *    ★ 后果不是"没显示"，而是 uni-app 的 H5 运行时把根相对路径
 *    按 `router.base` 重写成了 `/mobile/uploads/xxx.png` → 404 →
 *    `@error` 触发 → `v-if` 把 `<image>` 摘掉换成首字占位。
 *    **完整机理与那段误导性的排查过程写在 `utils/asset.js` 的文件头**，
 *    那边是这条规则唯一该被解释的地方，这里只留指针。
 *
 * ★ 后端存的是完整 URL 还是相对路径，取决于上传时后端返回什么 ——
 *   两种 `assetUrl` 都处理：带协议头的原样放过，否则补 origin。
 */
const avatarUrl = computed(() => assetUrl(base.value.avatar))

/**
 * 头像加载失败。
 *
 * ★★ 为什么需要这个标志：后端的文件可能已经被清了（或者这是另一台机器上的库），
 *    那时 `image` 会渲染成一张**裂图**。桌面端用 `el-avatar` 自动退回默认插槽
 *    就是为了这件事，移动端得自己接 `@error` 事件。
 * ★ 换头像之后要重置它 —— 不重置的话，用户新传了一张好图，
 *   页面上还是那个"首字"占位（因为上一次的失败被记住了）。
 */
const avatarBroken = ref(false)

/** 没有头像时显示昵称首字 */
const avatarFallback = computed(() => (nickname.value || '读').charAt(0))

// ★ 头像地址一变就重新给它一次机会（见 avatarBroken 的说明）
watch(avatarUrl, () => {
  avatarBroken.value = false
})

/**
 * 挑图 + 上传。
 *
 * ★ `uni.chooseImage` 在 H5 上是「拉起系统文件选择框」，在小程序/App 上是
 *   相册或拍照 —— 这是它比 `<input type="file">` 强的地方，
 *   也正是"换端"要付的代价：**H5 上验不了小程序的行为**。
 */
function chooseAvatar() {
  if (uploading.value) return

  uni.chooseImage({
    count: 1,
    sizeType: ['compressed'],
    sourceType: ['album', 'camera'],
    success: (res) => {
      const file = res.tempFiles?.[0]
      if (!file) return

      /*
       * ★ 大小和格式只在这里做"即时提示"。真正的拒绝在后端 ——
       *   前端的扩展名判断是**看文件名**的，改个名就能绕过，它不是安全边界。
       *
       * ★★ `sizeType: ['compressed']` 在 H5 上是**空操作** ——
       *   读 uni-h5 的实现，`chooseImage` 的解构里 `sizeType` 被注释掉了，
       *   传进去根本没人用。所以"大于 2MB 就拒"这条在 H5 上是**真的**会发生的
       *   （手机拍的原图常常 3~5MB），而在小程序里它多半被压缩到线下。
       *   同一个上限，两端触发频率完全不同 —— 这不是配置问题，是平台差异。
       *
       * ★★★ 扩展名取 `file.name` 而**不是** `file.path` ——
       *   H5 上 `file.path` 是个 **blob: URL**（uni-h5 的 `fileToUrl` 就是
       *   `URL.createObjectURL`），`blob:http://localhost:3000/uuid` 里**没有扩展名**，
       *   按它取出来的是整条 URL，永远不在白名单里 →
       *   **每一张图都会被拒，且提示是「只支持 JPG / PNG / WEBP」**（彻头彻尾的假话）。
       *   `name` 是小程序/App 里没有的字段，所以这里两样都取，
       *   **取不到就放行**（交给后端按魔数判）——
       *   因为「读不出扩展名」不等于「格式不对」，
       *   拿它当拒绝的理由，就是把一次取不到信息翻译成了一句断言。
       */
      const sizeMB = (file.size || 0) / 1024 / 1024
      if (sizeMB > MAX_MB) {
        uni.showToast({ title: `图片不能超过 ${MAX_MB}MB`, icon: 'none' })
        return
      }
      const ext = /\.([a-z0-9]+)$/i.exec(file.name || '')?.[1]?.toLowerCase()
      if (ext && !ACCEPT.includes(ext)) {
        uni.showToast({ title: '只支持 JPG / PNG / WEBP', icon: 'none' })
        return
      }

      upload(file.path)
    },
    fail: () => {
      /* ★ 用户取消选图也会走 fail —— 这不是错误，什么都不做 */
    },
  })
}

function upload(filePath) {
  uploading.value = true

  uni.uploadFile({
    url: `${API_BASE_URL}/reader/profile/avatar`,
    filePath,
    // ★ 这个 name 必须和后端 @RequestParam("file") 对上，写错了后端拿到 null
    name: 'file',
    header: {
      // ★ ①：Authorization 自己加。★ 不动 Content-Type（见文件头）
      Authorization: `Bearer ${getToken()}`,
    },
    success: (res) => {
      /* ★ ③：R5 —— uni.uploadFile 同样把 4xx 放进 success */
      if (res.statusCode < 200 || res.statusCode >= 300) {
        uni.showToast({ title: `上传失败（HTTP ${res.statusCode}）`, icon: 'none' })
        return
      }

      /* ★ ②：响应体是字符串，要自己解析 */
      let body
      try {
        body = JSON.parse(res.data)
      } catch {
        uni.showToast({ title: '上传失败：服务器返回了无法解析的内容', icon: 'none' })
        return
      }

      // ★ 业务码：后端的规则是「业务失败也返回 HTTP 200」，所以这一层不能省
      if (body?.code !== 200) {
        uni.showToast({ title: body?.message || '上传失败', icon: 'none' })
        return
      }

      // 上传即生效：返回值里已经带着新的 avatar
      base.value = body.data || base.value
      nickname.value = base.value.nickname || nickname.value
      // ★ 同步到 store：顶栏 / 评论区的昵称要跟着变
      syncStore()
      uni.showToast({ title: '头像已更新', icon: 'none' })
    },
    fail: () => {
      uni.showToast({ title: '连接不上后端服务，请确认 backend 已在 8080 端口启动', icon: 'none' })
    },
    complete: () => {
      uploading.value = false
    },
  })
}

/** 保存昵称 */
async function handleSave() {
  const name = nickname.value.trim()
  if (!name) {
    uni.showToast({ title: '昵称不能为空', icon: 'none' })
    return
  }
  if (name === (base.value.nickname || '')) {
    uni.showToast({ title: '昵称没有变化', icon: 'none' })
    return
  }
  if (saving.value) return

  saving.value = true
  try {
    base.value = (await updateNickname(name)) || base.value
    nickname.value = base.value.nickname || name
    /*
     * ★★ 同步到 store —— 这一步不能省。
     *   昵称在【书评 / 章评 / 回复】上显示，而 store 里那份是登录时写的，
     *   不同步的话：改完昵称，评论列表里自己的名字还是旧的，
     *   而"我的"页面上已经是新的 —— 看起来像"只改了一半"。
     */
    syncStore()
    uni.showToast({ title: '昵称已更新', icon: 'none' })
  } catch {
    /* 失败原因由 request.js 提示过 */
  } finally {
    saving.value = false
  }
}

/**
 * 把最新的资料写回 store（不动 token）。
 *
 * ★★ 注意 `setLoginInfo` 的签名是**两个位置参数** `(token, user)`，
 *    不是 `({token, user})` —— 和桌面端一致（那边也是两个参数）。
 *    传对象进去不会报错，只会把 token 设成一个对象、user 设成 undefined，
 *    症状是"改完昵称之后整个登录态没了"。
 */
function syncStore() {
  const current = userStore.userInfo || {}
  userStore.setLoginInfo(getToken(), {
    ...current,
    nickname: base.value.nickname,
    avatar: base.value.avatar,
  })
}

/**
 * 退出登录。
 *
 * ★★ 必须问一句，而且要说清**四个端一起退**：这不是提示，是事实 ——
 *    四个端共用 `:3000` 这个 origin 上的同一份 localStorage。
 *    不写清楚的话，用户会以为自己只是从手机上退出来了。
 */
async function handleLogout() {
  const ok = await confirm('退出后四个端都需要重新登录。', '退出登录', { confirmText: '确定退出' })
  if (!ok) return

  try {
    await userStore.logout()
  } catch {
    /* 后端登出失败也要继续清本地 —— store.logout 里已经处理了 */
  }
  uni.showToast({ title: '已退出登录', icon: 'none' })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function goShelf() {
  // ★ tabBar 页面必须 switchTab（navigateTo 会静默失败）
  uni.switchTab({ url: '/pages/shelf/shelf' })
}

/** 阅读历史。★ 非 tabBar 页，走 navigateTo */
function goHistory() {
  uni.navigateTo({ url: '/pages/history/history' })
}

/** 申请成为作者。★ 非 tabBar 页 */
function goApplyAuthor() {
  uni.navigateTo({ url: '/pages/applyAuthor/applyAuthor' })
}
</script>

<template>
  <view class="profile">
    <!-- ============ 未登录 ============ -->
    <view v-if="!userStore.isLogin" class="gate">
      <text class="gate__icon">👤</text>
      <text class="gate__title">还没有登录</text>
      <text class="gate__sub">登录后可以收藏作品、发表书评、记录阅读进度。</text>
      <button class="gate__btn" @click="goLogin">去登录</button>
    </view>

    <!-- ============ 已登录 ============ -->
    <template v-else>
      <view class="card">
        <view class="top">
          <!--
            ★ 头像。
            ★★ 没有头像时**不显示裂图**，显示昵称首字 —— 这对应桌面端
              el-avatar 的默认插槽（那边注释里讲了为什么不用裸 <img>）。
              移动端这里用 image 组件的 error 事件兜住"后端文件被清了"那种情况。
          -->
          <image
            v-if="avatarUrl && !avatarBroken"
            class="avatar"
            :src="avatarUrl"
            mode="aspectFill"
            @error="avatarBroken = true"
          />
          <view v-else class="avatar avatar--text">
            <text class="avatar__char">{{ avatarFallback }}</text>
          </view>

          <view class="uploader">
            <text class="btn btn--primary" @click="chooseAvatar">
              {{ uploading ? '上传中…' : base.avatar ? '更换头像' : '上传头像' }}
            </text>
            <text class="hint">JPG / PNG / WEBP，不超过 {{ MAX_MB }}MB</text>
            <!--
              ★ 这条提示不是凑数：后端**不做缩放**（引入图片解析器等于把
                「解析器漏洞」那条路重新打开），要小图只能前端自己先裁好。
            -->
            <text class="hint">建议先裁成正方形再上传</text>
          </view>
        </view>
      </view>

      <!-- ============ 资料 ============ -->
      <view class="card">
        <view class="field">
          <text class="field__label">昵称</text>
          <input
            v-model="nickname"
            class="field__input"
            type="text"
            maxlength="20"
            placeholder="你的昵称"
            placeholder-class="ph"
          />
          <text class="field__tip">
            昵称会显示在你的<text class="strong">书评、章评和回复</text>上，改完立刻生效（不用重登）。
          </text>
        </view>

        <view class="field">
          <text class="field__label">登录名</text>
          <!--
            ★ 只读但仍然显示：改完昵称之后，界面上就再也看不到自己的登录名了，
              而那是下次登录唯一要输入的东西。
          -->
          <text class="field__readonly">{{ base.username }}</text>
          <text class="field__tip">登录名不可修改。</text>
        </view>

        <view class="field">
          <text class="field__label">邮箱</text>
          <text class="field__readonly">{{ base.email || '未填写' }}</text>
          <!-- ★ 说清"为什么不能改"比只说"不能改"好：用户会一直找编辑入口 -->
          <text class="field__tip">邮箱暂无修改入口（本项目没有邮箱验证流程）。</text>
        </view>

        <text class="btn btn--primary btn--block" @click="handleSave">
          {{ saving ? '保存中…' : '保存昵称' }}
        </text>
      </view>

      <!--
        ============ 入口 ============
        ★ 移动端没有桌面端那种顶部导航栏，所以「书架 / 历史 / 申请作者」
          这几个入口只能落在这里 —— 三个都是**非 tabBar 页**，
          必须 navigateTo（书架是 tabBar 页，必须 switchTab）。
      -->
      <view class="card card--list">
        <view class="entry" hover-class="entry--active" :hover-stay-time="80" @click="goShelf">
          <text class="entry__text">我的书架</text>
          <text class="entry__arrow">›</text>
        </view>

        <view class="entry" hover-class="entry--active" :hover-stay-time="80" @click="goHistory">
          <text class="entry__text">阅读历史</text>
          <text class="entry__arrow">›</text>
        </view>

        <!--
          ★★ 这里用 `userStore.isAuthor`（登录那一刻写下的角色）只做一个**标签**，
            真正的状态判断在 `applyAuthor` 页里（它拉 `/mine`，能看到「审核中」「已驳回 + 理由」）。
            这一页**刻意不拉** `/mine` —— 它是 tabBar 页，每次切过来都多一次请求，
            而换来的是一个标签的准确度。
          ★ 标签在本地角色过期时会不准（管理员刚手工加了 AUTHOR，而用户还没重登）——
            这是可接受的：点进去之后那一页会说真话。
        -->
        <view class="entry" hover-class="entry--active" :hover-stay-time="80" @click="goApplyAuthor">
          <text class="entry__text">申请成为作者</text>
          <text v-if="userStore.isAuthor" class="entry__tag">已是作者</text>
          <text class="entry__arrow">›</text>
        </view>
      </view>

      <text class="logout" @click="handleLogout">退出登录</text>
    </template>
  </view>
</template>

<style scoped>
.profile {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 未登录引导 ---------- */
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
  padding: 28rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 16rpx;
}

/* 列表型卡片自己不带内边距，由每一行带 —— 否则按压高亮只覆盖中间一条 */
.card--list {
  padding: 0;
}

.top {
  display: flex;
  align-items: center;
}

.avatar {
  flex: none;
  width: 140rpx;
  height: 140rpx;
  border-radius: 50%;
  background: #eef2f8;
}

.avatar--text {
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar__char {
  font-size: 56rpx;
  color: #7d8a9c;
}

.uploader {
  flex: 1;
  /* ★ 同 NovelCard 那条：flex 子项不加 min-width:0 压不下去 */
  min-width: 0;
  margin-left: 28rpx;
}

.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #9aa3b0;
}

/* ---------- 表单 ---------- */
.field {
  margin-bottom: 32rpx;
}

.field__label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 26rpx;
  color: #5a6472;
}

.field__input {
  height: 84rpx;
  padding: 0 24rpx;
  background: #f5f6f8;
  border-radius: 12rpx;
  font-size: 30rpx;
  color: #1f2733;
}

.ph {
  color: #b6bdc7;
}

/*
 * ★ 只读字段故意长得和输入框不一样（没有底色、颜色浅）——
 *   「不能改」这件事应该在**看到的一瞬间**就成立，
 *   而不是等用户点上去发现没反应。
 */
.field__readonly {
  display: block;
  padding: 18rpx 0;
  font-size: 30rpx;
  color: #8a93a0;
}

.field__tip {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #9aa3b0;
  line-height: 1.6;
}

.strong {
  color: #5a6472;
}

/* ---------- 按钮 ---------- */
.btn {
  display: inline-block;
  padding: 16rpx 40rpx;
  border-radius: 12rpx;
  font-size: 28rpx;
  color: #5a6472;
  background: #f5f6f8;
}

.btn--primary {
  color: #fff;
  background: #2f6fed;
}

.btn--block {
  display: block;
  text-align: center;
}

/* ---------- 入口行 ---------- */
.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 30rpx 28rpx;
}

.entry--active {
  background: #f2f5fa;
}

.entry__text {
  font-size: 30rpx;
  color: #1f2733;
}

/* ★ 「已是作者」标签：只作提示，点进去那一页才是判断的地方（见模板里的说明） */
.entry__tag {
  flex-shrink: 0;
  margin-left: auto;
  margin-right: 12rpx;
  padding: 2rpx 14rpx;
  border-radius: 6rpx;
  font-size: 20rpx;
  color: #2f6fed;
  background: #eef3ff;
}

.entry__arrow {
  font-size: 36rpx;
  color: #c8ced8;
}

/* ---------- 退出 ---------- */
.logout {
  display: block;
  padding: 28rpx 0;
  margin-top: 20rpx;
  text-align: center;
  font-size: 30rpx;
  color: #d9534f;
  background: #fff;
  border-radius: 16rpx;
}
</style>
