<script setup>
/**
 * 个人中心 —— 头像 + 昵称。
 *
 * ★ 需要登录（后端 `/api/reader/**` 落在 `anyRequest().authenticated()` 上）。
 *   未登录的引导由路由守卫负责（`meta.requiresAuth`），
 *   所以这一页里【不写】「请先登录」的分支。
 *
 * ============================================================
 * ★★★ 改完之后必须【合并】写回 localStorage，不能整体替换
 * ============================================================
 * 后端返回的 `ProfileVO` 只有 `{id, username, nickname, avatar, email}` ——
 * **没有 `roles` / `role` / `permissions`**。
 *
 * 而 `localStorage[novel_user]` 里存的是完整的 `UserVO`，`role` 是
 * 【三个应用共用的】判断依据：创作中心和管理端的路由守卫都在读它。
 * 直接 `auth.setUser(res)` 会把角色从这份共享数据里抹掉 ——
 * 症状是「在阅读端改了个昵称，跳去创作中心被弹回登录页」，
 * 而且刷新一下又好了（`syncFromStorage` 重新读到的还是被抹掉的那份）。
 *
 * 所以只能 `{ ...auth.getUser(), nickname, avatar }`：
 * 把**新值盖上去**，其余原样保留。
 *
 * ============================================================
 * ★★ 为什么这里不用 `stores/user.js` 的 state 直接改
 * ============================================================
 * `stores/user.js` 是四个 md5 锁定文件之一（项目铁律 2），**一个字节都不许改**。
 * 但它有两个现成的、足够用的出口：
 *   `setLoginInfo(token, user)` —— 要一个 token，而这里手上没有
 *   `syncFromStorage()`        —— 从 localStorage 重新读一遍（★ 用它）
 * 于是路径是：写 localStorage（auth.setUser）→ 让 store 重读（syncFromStorage），
 * 导航栏右上角那个昵称标签立刻跟着变。锁定的文件一行没动。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfile, updateNickname, uploadAvatar } from '@/api/profile'
import { getUser, setUser } from '@/utils/auth'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
/** 只读部分（登录名 / 邮箱）—— 没有修改入口，取到之后就不动了 */
const base = reactive({ username: '', email: '' })

const formRef = ref()

const form = reactive({
  /*
   * ★ `''` 而不是 `null`：它绑在 `el-input` 上，null 会让输入框变成
   *   「非受控」再变「受控」，Vue 会警告一次。
   */
  nickname: '',
  /**
   * 头像 URL，形如 `/uploads/avatar/2026/09/21/ab12.jpg`；没传过是空串。
   *
   * ★★ 后端在没设过头像时会让这个 key **整个消失**
   *   （全局 `default-property-inclusion: non_null`），取出来是 `undefined`。
   *   所以赋值一律写 `res.avatar || ''` —— **不要写 `=== null` 判断**，
   *   那个分支在 `undefined` 下永远不成立。
   *   （同 `UserVO#avatar` 和 `CommentVO#nickname` 两条注释。）
   */
  avatar: '',
})

const rules = {
  nickname: [
    { required: true, message: '昵称不能为空', trigger: 'blur' },
    // ★ 20 和后端 `ProfileUpdateDTO` 的 @Size(max = 20) 一致。
    //   前端这条只是「不用等一次往返」，真正的拒绝在服务端。
    { max: 20, message: '昵称最长 20 个字符', trigger: 'blur' },
  ],
}

/** 头像上传中（按钮转圈 + 遮罩） */
const uploading = ref(false)

/**
 * 选图后立刻显示的本地 blob 地址。
 *
 * ★★ 它必须被 `revokeObjectURL` —— 这个 URL 会把整个文件钉在内存里，
 *   直到页面关闭才释放。上传成功后（服务端地址已经有了）立刻放掉；
 *   组件卸载时也放掉。
 *   （同 `author-web/src/components/NovelCoverUpload.vue` 里那段。
 *   阅读端【不】复用它：那个组件是 3:4 的竖版封面框 + 「移除封面」按钮，
 *   而这里是圆形头像、没有「移除」（`sys_user.avatar` 允许为 null，
 *   但没有一个「恢复成默认头像」的产品入口 —— 不提供不存在的操作按钮）。）
 */
const localPreview = ref('')

/**
 * 真正显示的那个 URL：**本地预览优先**。
 *
 * ★ 顺序很重要：上传中显示本地选的那张，上传成功后 `localPreview` 被放掉、
 *   自动切到服务端地址。整个过程预览框的内容不会闪一下空。
 */
function displayUrl() {
  return localPreview.value || form.avatar || ''
}

function revokeLocal() {
  if (localPreview.value) {
    URL.revokeObjectURL(localPreview.value)
    localPreview.value = ''
  }
}

/** 取资料。★ 失败由拦截器提示，这里只负责别变成 unhandledrejection */
async function load() {
  loading.value = true
  try {
    const res = await getProfile()
    base.username = res?.username || ''
    base.email = res?.email || ''
    form.nickname = res?.nickname || ''
    form.avatar = res?.avatar || ''
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * 把新资料同步到「导航栏 + 本地缓存」。
 *
 * ★ 这里就是文件头那段论证的落点：**合并**，不是替换。
 * ★ `auth.getUser()` 可能是 null（老用户手里那份 localStorage 是若干期之前写的），
 *   所以展开前先兜一个 `{}`。
 */
function syncLocal(patch) {
  setUser({ ...(getUser() || {}), ...patch })
  userStore.syncFromStorage()
}

/**
 * 保存昵称。
 *
 * ★ 只提交 `nickname` 一个字段。用 `formRef.validate()` 而不是自己判空：
 *   规矩写在 `rules` 里只有一处，自己判的话就是第二处，
 *   迟早出现「表单说合法、提交被 400」的不一致。
 */
async function handleSave() {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没过，el-form 已经在字段下面标红了
  }

  const nickname = form.nickname.trim()
  try {
    const res = await updateNickname(nickname)
    const saved = res?.nickname || nickname
    form.nickname = saved
    syncLocal({ nickname: saved })
    ElMessage.success('昵称已修改')
  } catch {
    /* 拦截器已提示 */
  }
}

/* ==================== 头像上传 ==================== */

/** 后端 `ImageKind.AVATAR` 的上限。★ 封面是 10MB，头像刻意更小 */
const MAX_MB = 2
const ACCEPT = '.jpg,.jpeg,.png,.webp'

/**
 * 选完文件、还没开始上传时调用。返回 `false` 取消这次上传。
 *
 * ★ **这两种校验的安全性为零**（扩展名和大小都是浏览器报给我们的），
 *   唯一价值是**省一次往返**。真正的防线是后端读**文件头魔数**。
 */
function beforeUpload(file) {
  const name = file.name || ''
  const i = name.lastIndexOf('.')
  const ext = i < 0 ? name.toLowerCase() : name.slice(i + 1).toLowerCase()
  if (!['jpg', 'jpeg', 'png', 'webp'].includes(ext)) {
    ElMessage.error(`不支持的格式 .${ext}，请用 JPG / PNG / WEBP`)
    return false
  }
  if (file.size > MAX_MB * 1024 * 1024) {
    ElMessage.error(`头像不能超过 ${MAX_MB}MB，当前 ${(file.size / 1024 / 1024).toFixed(1)}MB`)
    return false
  }
  return true
}

/**
 * 上传（覆盖 el-upload 默认行为）。
 *
 * ★★ 必须用 `:http-request` 自己发请求：el-upload 默认那个 XHR
 *   **不经过我们的 axios 实例** —— 没有 `Authorization` 头、
 *   不认 `{code, message, data}` 信封、超限的 400 会被丢掉只剩一句
 *   「上传失败」。作者端那个封面组件里写着同一段理由。
 *
 * ★ 接口是「上传即生效」的：返回体里已经带着新的 `avatar`，
 *   不需要再调一次保存。
 */
async function doUpload(option) {
  uploading.value = true
  revokeLocal() // 先把上一张预览放掉，免得它一直被引用着
  localPreview.value = URL.createObjectURL(option.file)
  try {
    const res = await uploadAvatar(option.file)
    const url = res?.avatar || ''
    form.avatar = url
    revokeLocal() // 服务端地址已经有了，本地预览完成使命
    syncLocal({ avatar: url })
    ElMessage.success('头像已更新')
  } catch (e) {
    // ★ 失败原因拦截器已经弹过（400 类型不符 / 401 / 网络），
    //   这里只做状态回滚 —— 预览退回上一张头像，用户看到的是
    //   「刚才那张没换成功」，而不是一个已经失效的空框。
    revokeLocal()
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="profile" v-loading="loading">
    <div class="page-head">
      <h1 class="title">个人中心</h1>
    </div>

    <el-card shadow="never" class="card">
      <div class="top">
        <!--
          ★ 头像。
          ★★ 用 el-avatar 而不是裸 <img>：图片 404（后端文件被清了、
             或者这是另一台机器上的库）时它会**自动退回默认插槽**，
             而裸 <img> 留给用户的是一张裂图。
          ★ 默认插槽放昵称首字，不需要任何图片资源。
          ★ `alt` 对装饰性头像没有意义，但漏了会有无障碍告警。
        -->
        <el-avatar :size="88" :src="displayUrl()" class="avatar" alt="头像">
          {{ (form.nickname || '读').charAt(0) }}
        </el-avatar>

        <div class="uploader">
          <el-upload
            :show-file-list="false"
            :accept="ACCEPT"
            :disabled="uploading"
            :before-upload="beforeUpload"
            :http-request="doUpload"
          >
            <el-button size="small" :loading="uploading">
              {{ form.avatar ? '更换头像' : '上传头像' }}
            </el-button>
          </el-upload>
          <div class="hint">JPG / PNG / WEBP，不超过 {{ MAX_MB }}MB</div>
          <!--
            ★ 这条提示不是凑数：后端**不做缩放**（引入图片解析器等于把
              「解析器漏洞」那条路重新打开），要小图只能前端自己先裁好。
              不写清楚的话，用户传一张 4000px 的原图，页面到处是大图，
              而他会以为「网站会自动处理」。
          -->
          <div class="hint">建议先裁成正方形再上传</div>
        </div>
      </div>

      <el-divider />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
        class="form"
        @submit.prevent
      >
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" maxlength="20" show-word-limit placeholder="你的昵称" />
          <div class="tip">
            ★ 昵称会显示在你的<b>书评、章评和回复</b>上，改完立刻生效（不用重登）。
          </div>
        </el-form-item>

        <el-form-item label="登录名">
          <!--
            ★ 只读但仍然显示：改完昵称之后，界面上就再也看不到自己的登录名了，
              而那是下次登录唯一要输入的东西。
            ★ 用 disabled 的 el-input 而不是纯文本：和上面的昵称输入框对齐，
              一眼能看出「这两个是同类东西，只是这个不能改」。
          -->
          <el-input :model-value="base.username" disabled />
          <div class="tip">登录名不可修改。</div>
        </el-form-item>

        <el-form-item label="邮箱">
          <el-input :model-value="base.email || '未填写'" disabled />
          <!--
            ★ 说清楚「为什么不能改」比只说「不能改」好：
              用户会一直找编辑入口，而这里根本没有后端流程支持它。
          -->
          <div class="tip">邮箱暂无修改入口（本项目没有邮箱验证流程）。</div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSave">保存昵称</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.profile {
  padding: 20px 0;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.title {
  margin: 0;
  font-size: 20px;
}

/*
 * ★ 卡片限宽：个人中心是一列很短的字段，拉满 960px 之后
 *   标签和输入框离得极远，读一行要在屏幕上横跳。
 */
.card {
  max-width: 560px;
}

.top {
  display: flex;
  align-items: center;
  gap: 20px;
}

.avatar {
  flex: none;
  /* ★ 没头像时的首字底色。用主题蓝的浅色，和导航栏的品牌色是同一族 */
  background: #ecf5ff;
  color: #409eff;
  font-size: 32px;
}

.uploader {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.form {
  max-width: 440px;
}

/*
 * ★ 字段下面的说明文字。
 *   它是 el-form-item 的直接子元素（不是 el-input 的一部分），
 *   所以宽度会跟着表单项走 —— 上面那条「el-input 默认 100% 宽」的规矩
 *   不会作用到它身上。
 */
.tip {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
