<script setup>
/**
 * 公告横幅（移动端）—— 挂在首页最上面的运营位。
 *
 * ★ 与 reader-web 的同名组件行为一致，三条约定照抄：
 *   ① **查不到 / 没数据时什么都不渲染**，不显示空态。
 *      另外三块（分类 / 热门 / 最近更新）是**内容区**，空了要说明为什么；
 *      横幅是个**运营位** —— 没有公告是常态，页面上不该出现「暂无公告」
 *      这种把版面往下顶、还让人以为坏了的东西。所以根节点是 v-if。
 *   ② **失败也不额外报错**：拦截器已经弹过了，保持空数组 ——
 *      公告拉不到不该让整个首页看起来像坏了。
 *   ③ **请求一次就够**：不做轮询、不做可见性重查。
 *
 * ------------------------------------------------------------
 * ★★ 移动端比桌面端多做的一件事：正文【截断到 3 行】，点击展开
 * ------------------------------------------------------------
 * 桌面端横幅一行放得下的正文，在 375px 宽的手机上会占三四行；
 * 三条公告就能把分类入口顶出首屏 —— 而那才是首页的主要功能。
 *
 * ★ 截断是**展示层**的行为，不是把数据丢掉：点一下展开看全文。
 *   ★ 没有做"公告详情页"：公告没有详情页，横幅上那句话就是它的全部内容
 *     —— 这是后端 `NoticeVO#forReader` 保留 `content` 的原因。
 *     所以展开就够了，不需要路由跳转。
 *   ★★ 也没有用 `-webkit-line-clamp`（CSS 多行截断）：它在各家内核上的
 *     支持度和表现不一致，而 uni-app 要覆盖小程序 / App / H5 三套渲染器。
 *     这里用"最多渲染 N 个字 + 展开"这种**不依赖 CSS** 的做法，
 *     在哪个端上行为都一样。
 */
import { computed, onMounted, ref } from 'vue'
import { getNoticeList } from '@/api/notice'
import { formatDate } from '@/utils/format'

/**
 * 最多显示几条。
 * ★ 3 而不是 5：横幅贴在首页最上面，超过三条就把分类入口顶到首屏之外了。
 * ★ 服务端本来也会夹到 1~20，这里的 3 是展示层的取舍，不是安全边界。
 */
const BANNER_LIMIT = 3

/** 折叠时最多渲染多少个字。★ 按字数而不是按行数 —— 见文件头最后一段 */
const COLLAPSED_CHARS = 42

const notices = ref([])
/** 哪些公告被展开了。用 id 作键，避免"展开一条全展开" */
const expanded = ref({})

onMounted(load)

async function load() {
  try {
    notices.value = (await getNoticeList(BANNER_LIMIT)) || []
  } catch {
    // 拦截器已提示。保持空数组，页面少一块而已
    notices.value = []
  }
}

/** 该不该显示「展开」—— 只有真的被截断了才显示，短公告不显示一个没用的按钮 */
function isTruncatable(n) {
  return (n.content || '').length > COLLAPSED_CHARS
}

function displayContent(n) {
  const text = n.content || ''
  if (expanded.value[n.id] || text.length <= COLLAPSED_CHARS) return text
  return `${text.slice(0, COLLAPSED_CHARS)}…`
}

function toggle(n) {
  if (!isTruncatable(n)) return
  expanded.value = { ...expanded.value, [n.id]: !expanded.value[n.id] }
}

/** ★ type === 2 是"通知"（橙），其余是"公告"（蓝）—— 与桌面端同一判据 */
const isInfo = (n) => n.type === 2
</script>

<template>
  <!-- ★ 没有公告时整个组件不存在，见文件头 -->
  <view v-if="notices.length" class="notice-list">
    <view
      v-for="n in notices"
      :key="n.id"
      class="notice"
      :class="isInfo(n) ? 'notice--info' : ''"
      @click="toggle(n)"
    >
      <view class="row">
        <text class="tag" :class="isInfo(n) ? 'tag--info' : ''">
          {{ isInfo(n) ? '通知' : '公告' }}
        </text>
        <text class="title">{{ n.title }}</text>
        <!--
          ★ 只到「天」。后端返回的是 "2026-09-21 10:30:00"，
            这里用 formatDate 截前 10 位 —— 理由见 utils/format.js
            （那个字符串在 Safari 上 new Date() 会得到 Invalid Date，
             而 App 端跑的正是 Safari 的引擎）。
          ★ 用 publishTime，不是 createTime —— 读者侧本来也不返回 createTime。
        -->
        <text class="time">{{ formatDate(n.publishTime) }}</text>
      </view>

      <text class="content">{{ displayContent(n) }}</text>

      <text v-if="isTruncatable(n)" class="more">
        {{ expanded[n.id] ? '收起' : '展开' }}
      </text>
    </view>
  </view>
</template>

<style scoped>
.notice-list {
  display: flex;
  flex-direction: column;
  margin-bottom: 24rpx;
}

/*
 * ★ 用「左边一条粗色带」而不是整块底色：
 *   整块高饱和底色贴在首页顶部会很吵，而且正文是长文本，底色深了不好读。
 */
.notice {
  padding: 20rpx 24rpx;
  margin-bottom: 12rpx;
  background: #eef4ff;
  border-left: 8rpx solid #2f6fed;
  border-radius: 8rpx;
}

/* 通知（type = 2）换成橙色系，和公告区分开 */
.notice--info {
  background: #fdf6ec;
  border-left-color: #e6a23c;
}

.row {
  display: flex;
  align-items: center;
}

.tag {
  flex: none;
  padding: 2rpx 10rpx;
  margin-right: 12rpx;
  border-radius: 6rpx;
  font-size: 20rpx;
  color: #fff;
  background: #2f6fed;
}

.tag--info {
  background: #e6a23c;
}

.title {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f2733;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  flex: none;
  margin-left: 12rpx;
  font-size: 22rpx;
  color: #9aa3b0;
}

/*
 * ★ word-break 兜住超长的不带空格的串（比如一条 URL），
 *   否则它会横向撑破卡片。★ 移动端这条更要紧：桌面上横向溢出只是难看，
 *   手机上会把整个页面撑出横向滚动条。
 */
.content {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #5a6472;
  line-height: 1.7;
  word-break: break-word;
}

.more {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
