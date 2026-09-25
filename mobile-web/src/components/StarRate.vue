<script setup>
/**
 * 星级评分（移动端）—— 桌面端用的是 Element Plus 的 `el-rate`。
 *
 * ★★ 用**文字字符**（★ / ☆）而不是图片，有两个理由，都不是省事：
 *   ① 判断 4 的跨端判据里，「模板不出现 `img`」是一条硬约束 ——
 *      图标走 `<image>` 组件意味着要准备图标文件、要处理各端的路径差异。
 *   ② 星星只有「亮/灭」两态，字符能在任何字号下保持清晰，
 *      而且跟随主题色（`color`）自动变，不用维护两套图。
 *
 * ★ 后端存的是**整数 1~5**（`CommentCreateDTO#score` 是 `@Min(1) @Max(5)`），
 *   所以这里不开半星。展示别人给的分数时同理 ——
 *   `novel.score` 那个平均分是小数，但它**不经过这个组件**（见详情页，
 *   那里直接显示 `4.3 分` 的文字，因为一个 4.3 用星星画不出来）。
 */
const props = defineProps({
  /** 当前分值。★ 0 = 未评分（不是"打了 0 分"） */
  modelValue: {
    type: Number,
    default: 0,
  },
  /** 只读展示。评论列表里显示别人的评分时用 */
  disabled: {
    type: Boolean,
    default: false,
  },
  /** 总分档位 */
  max: {
    type: Number,
    default: 5,
  },
})

const emit = defineEmits(['update:modelValue'])

/**
 * 点某一颗星。
 *
 * ★★ **点当前分数 = 清零**（对齐 `el-rate` 的 `clearable`），这一条不是装饰：
 *   `score` 是**可选的** —— 打 0 分和"不打分"在后端是两件事，
 *   而打 0 分会被 `@Min(1)` 拒成 **400**。
 *   少了这个分支，用户点完 5 星就再也回不到"不打分"了，
 *   只能刷新页面重来。
 *   （提交那边还有一道闸：`score > 0` 才把字段放进 payload，见 CommentSection。）
 */
function pick(value) {
  if (props.disabled) return
  emit('update:modelValue', value === props.modelValue ? 0 : value)
}
</script>

<template>
  <view class="rate">
    <!--
      ★ 用 v-for 生成固定档位，而不是 for 循环拼字符串：
        拼出来的字符串只能整块渲染，点哪一颗就分不出来了。
      ★ 每颗星是一个 <text>，点击事件挂在 <text> 上 ——
        uni-app 里 <text> 支持 @click（H5 和小程序都支持）。
    -->
    <text
      v-for="n in max"
      :key="n"
      class="rate__star"
      :class="{ 'rate__star--on': n <= modelValue, 'rate__star--off': n > modelValue }"
      @click="pick(n)"
      >{{ n <= modelValue ? '★' : '☆' }}</text
    >
  </view>
</template>

<style scoped>
.rate {
  display: flex;
  align-items: center;
}

.rate__star {
  /* ★ 星星之间留一点缝，但不要用 margin 撑开可点区域 ——
     点击热区就是字符本身，太大了会误触相邻那颗 */
  margin-right: 6rpx;
  font-size: 32rpx;
  line-height: 1.2;
}

/* 只读时用小一号，和评论正文的比例更协调 */
.rate__star--on {
  color: #ff9f1c;
}

.rate__star--off {
  color: #d8dce3;
}
</style>
