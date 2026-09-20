<script setup>
/**
 * 封面选择器：一个预览框 + 一个「选择图片」按钮。
 *
 * ============================================================
 * ★★★ 为什么要覆盖 el-upload 的上传行为（`:http-request`）
 * ============================================================
 * el-upload 默认会自己起一个 XMLHttpRequest 去打 `action` 那个地址。
 * 那个请求**不经过我们的 axios 实例**，于是：
 *   ① 没有 `Authorization` 头 → 后端 401 → 用户看到「登录已过期」（他明明登录着）
 *   ② 不认后端的 `{code, message, data}` 信封 → 上传成功也当失败
 *   ③ 大小超限时后端返回的 400 会把整个响应体丢掉，只剩一句「上传失败」
 *
 * `:http-request` 就是官方给的覆盖点：我们自己去调 `api/upload.js`，
 * 走和其它所有请求**完全相同**的那一条链路。
 *
 * ============================================================
 * ★★ 前端的校验只是【提示】，不是【校验】
 * ============================================================
 * 下面 `beforeUpload` 里那两个判断（扩展名、大小）**安全性为零**：
 *   扩展名和文件大小都是浏览器报给我们的，改一下 DevTools 就绕过去了。
 * 它们的唯一价值是**省一次往返** —— 让作者在选错文件的当下就知道，
 * 而不是传完 10MB 再被拒绝。
 *
 * 真正的防线全在后端 `AuthorUploadController`：**读文件头魔数**。
 * `shell.jsp` 改名成 `a.jpg` 能骗过这里的每一个判断，但骗不过 `FF D8 FF`。
 * ★ 这一点在面试里是要能讲出来的：**只校验扩展名等于没校验**。
 */
import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadCover } from '@/api/upload'

const props = defineProps({
  /** 封面 URL。空字符串和 null 都表示「没有封面」 */
  modelValue: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

/** 和后端 `multipart.max-file-size` 保持一致。不一致的话这里会拦下后端本来能收的文件 */
const MAX_MB = 10
const ACCEPT = '.jpg,.jpeg,.png,.webp'

const uploading = ref(false)

/**
 * 本地即时预览的 blob 地址。
 *
 * ★★ 它必须被 `revokeObjectURL` —— 这个 URL 会把整个文件**钉在内存里**，
 *    直到页面关闭才释放。作者换五次封面就是 50MB 常驻内存，
 *    而且 DevTools 里看不出任何异常（blob 不算 JS 堆）。
 *
 * ★ 用 `localPreview || modelValue` 的顺序：本地预览优先。
 *   这样「选好图 → 上传中 → 上传成功」整个过程里预览框的内容不会闪。
 *   上传一失败就立刻 revoke，预览自动退回上一次的封面 ——
 *   作者看到的是「刚才那张没换成功」，而不是一个下面已经失效的空框。
 */
const localPreview = ref('')

const displayUrl = computed(() => localPreview.value || props.modelValue || '')

function revokeLocal() {
  if (localPreview.value) {
    URL.revokeObjectURL(localPreview.value)
    localPreview.value = ''
  }
}

// ★ 组件卸载（比如作者没保存就退出去了）也要还回去
onBeforeUnmount(revokeLocal)

/** 扩展名 —— 对「最后一段点号之后」取小写。没有点号就整个当扩展名，必然不在白名单里 */
function extensionOf(name) {
  const i = name.lastIndexOf('.')
  return i < 0 ? name.toLowerCase() : name.slice(i + 1).toLowerCase()
}

/**
 * 选完文件、还没开始上传时调用。返回 `false` 会取消这次上传。
 *
 * ★ 两个判断都要给**具体**的原因。只说「文件不合法」的话，
 *   作者会反复换图试，不知道到底哪一条不满足。
 */
function beforeUpload(file) {
  const ext = extensionOf(file.name)
  if (!['jpg', 'jpeg', 'png', 'webp'].includes(ext)) {
    ElMessage.error(`不支持的格式 .${ext}，请用 JPG / PNG / WEBP`)
    return false
  }
  if (file.size > MAX_MB * 1024 * 1024) {
    ElMessage.error(`图片不能超过 ${MAX_MB}MB，当前 ${(file.size / 1024 / 1024).toFixed(1)}MB`)
    return false
  }
  return true
}

/**
 * 真正上传。
 *
 * ★ 返回 Promise 是 el-upload 的约定：resolve → 走 onSuccess，
 *   reject → 走 onError。**这里 reject 之后 el-upload 自己不会弹任何提示**，
 *   所以失败提示要我们自己发（下面 catch 里那句）。
 *
 * ★ 用 try/catch 而不是 `.then/.catch`：`finally` 里那行「清上传中状态」
 *   在两个分支里都要执行，写两遍迟早漏一个，漏了的症状是按钮永远转圈。
 */
async function doUpload(option) {
  uploading.value = true
  revokeLocal() // 先把上一张预览放掉，免得它一直被引用着
  localPreview.value = URL.createObjectURL(option.file)
  try {
    const url = await uploadCover(option.file)
    emit('update:modelValue', url)
    revokeLocal() // 服务端地址已经有了，本地预览完成使命
    ElMessage.success('封面已上传')
    return url
  } catch (e) {
    // ★ 拦截器已经弹过具体原因（400 超限 / 401 / 网络）。这里再弹一次就重复了，
    //   所以只做状态回滚 —— 预览退回上一张封面。
    revokeLocal()
    throw e
  } finally {
    uploading.value = false
  }
}

function clear() {
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="cover-upload">
    <div class="preview" :class="{ empty: !displayUrl }">
      <img v-if="displayUrl" :src="displayUrl" alt="封面预览" />
      <span v-else class="placeholder">暂无封面</span>

      <div v-if="uploading" class="mask">上传中…</div>
    </div>

    <div class="side">
      <el-upload
        :show-file-list="false"
        :accept="ACCEPT"
        :disabled="disabled || uploading"
        :before-upload="beforeUpload"
        :http-request="doUpload"
      >
        <el-button size="small" :loading="uploading" :disabled="disabled">
          {{ modelValue ? '更换封面' : '选择图片' }}
        </el-button>
      </el-upload>

      <el-button v-if="modelValue && !disabled" size="small" link @click="clear">
        移除封面
      </el-button>

      <div class="hint">JPG / PNG / WEBP，不超过 {{ MAX_MB }}MB</div>
      <!--
        ★ 尺寸提示不是废话：封面在作品列表里是 72×96 的小图，
          作者传一张 4000px 的横版图进来，缩略之后会糊得看不清人脸。
          真正的裁剪压缩是 P4 明确不做的（见方案「不做」清单），
          所以这里只能用文字把预期说清楚。
      -->
      <div class="hint">建议竖版，比例 3:4</div>
    </div>
  </div>
</template>

<style scoped>
.cover-upload {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.preview {
  position: relative;
  width: 108px;
  height: 144px;
  border-radius: 4px;
  overflow: hidden;
  background: #f5f7fa;
  border: 1px dashed #dcdfe6;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview.empty {
  border-style: dashed;
}

.preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.placeholder {
  font-size: 12px;
  color: #c0c4cc;
}

.mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.side {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding-top: 2px;
}

.hint {
  font-size: 12px;
  color: #909399;
}
</style>
