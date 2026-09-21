<script setup>
/**
 * 公告横幅 —— 挂在首页最上面的运营位。
 *
 * ============================================================
 * ★★ P5 里唯一一处读者侧 UI
 * ============================================================
 * 公告管理如果没有任何读者侧展示，这个功能是**自闭的** ——
 * 管理员在后台发了一条公告，然后没有任何地方能看到它。
 * 所以 P5 必须把它做出来，哪怕只是一条横幅。
 *
 * ★ 更完整的公告形态（列表页、详情页、已读标记）**不做**：
 *   公告没有详情页，横幅上那句话就是它的全部内容 ——
 *   这是后端 `NoticeVO#forReader` 保留 `content` 的原因，
 *   也是这里不需要 router-link 的原因。
 *
 * ============================================================
 * ★★ 查不到 / 没数据时【什么都不渲染】，不显示空态
 * ============================================================
 * 这是它和首页另外三块（分类 / 热门 / 最近更新，都带 `el-empty`）
 * 最不一样的地方：那三块是**内容区**，空了要说明为什么；
 * 横幅是个**运营位** —— 没有公告是常态，此时页面上不该出现
 * 「暂无公告」这种把版面往下顶、还让人以为坏了的东西。
 *
 * ★ 所以这个组件的根节点是 `v-if`。它渲染出来的时候，
 *   `.home` 里就只会多一张卡片；没有公告时它对布局零影响。
 *
 * ============================================================
 * ★ 失败也不额外报错
 * ============================================================
 * 和首页其它三块一样：拦截器已经弹过提示了，这里保持空数组 ——
 * 公告拉不到不该让整个首页看起来像坏了。
 *
 * ★ 请求一次就够：公告不是高频变化的数据，不做轮询、不做可见性重查。
 *   用户刷新页面自然会拿到新的。
 */
import { onMounted, ref } from 'vue'
import { getNoticeList } from '@/api/notice'
import { formatDate } from '@/utils/format'

/**
 * 最多显示几条。
 *
 * ★ 3 而不是 5：横幅是**贴在首页最上面**的，超过三条就把
 *   下面的分类入口顶到首屏之外了 —— 那才是首页的主要功能。
 * ★ 服务端本来也会夹到 1~20，这里的 3 是展示层的取舍，不是安全边界。
 */
const BANNER_LIMIT = 3

const notices = ref([])

onMounted(load)

async function load() {
  try {
    notices.value = (await getNoticeList(BANNER_LIMIT)) || []
  } catch {
    // 拦截器已提示。保持空数组，页面少一块而已
    notices.value = []
  }
}
</script>

<template>
  <!-- ★ 没有公告时整个组件不存在，见文件头 -->
  <div v-if="notices.length" class="notice-list">
    <div v-for="n in notices" :key="n.id" class="notice" :class="n.type === 2 ? 'is-info' : ''">
      <el-tag size="small" effect="dark" :type="n.type === 2 ? 'warning' : 'primary'" class="tag">
        {{ n.type === 2 ? '通知' : '公告' }}
      </el-tag>

      <div class="body">
        <div class="title">{{ n.title }}</div>
        <div class="content">{{ n.content }}</div>
      </div>

      <!--
        ★ 只到「天」。后端返回的是 "2026-09-21 10:30:00"，这里用
          formatDate 截前 10 位 —— 理由见 utils/format.js 的文件头
          （那个字符串在 Safari 上 new Date() 会得到 Invalid Date）。
        ★ 用 publishTime，不是 createTime —— 读者侧本来也不返回 createTime，
          而且「什么时候发的」才是读者关心的。
        ★ 它是后端盖的，所以一定有值（已发布的公告必然带 publishTime）；
          仍然走 formatDate 的空值兜底，避免将来有人改了后端时这里显示 "undefined"。
      -->
      <span class="time">{{ formatDate(n.publishTime) }}</span>
    </div>
  </div>
</template>

<style scoped>
.notice-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 和首页其它卡片一样：横幅之间留出间距 */
  margin-bottom: 16px;
}

/*
 * ★ 用「左边一条粗色带」而不是整块底色：
 *   整块高饱和底色贴在首页顶部会很吵，而且正文是长文本，底色深了不好读。
 */
.notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  background: #ecf5ff;
  border-left: 4px solid #409eff;
  border-radius: 4px;
  font-size: 14px;
}

/* 通知（type = 2）换成橙色系，和公告区分开 */
.notice.is-info {
  background: #fdf6ec;
  border-left-color: #e6a23c;
}

.tag {
  flex: none;
  /* 和标题的第一行对齐：标签自己有内边距，不抬高会显得偏上 */
  margin-top: 1px;
}

.body {
  flex: 1;
  min-width: 0;
}

.title {
  font-weight: 600;
  color: #303133;
}

/*
 * ★ 正文用 pre-wrap：公告正文里换行是运营手打的，压成一行读起来会变成
 *   一整段。同时用 word-break 兜住超长的不带空格的串（比如一条 URL），
 *   否则它会横向撑破卡片。
 */
.content {
  margin-top: 2px;
  color: #606266;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.time {
  flex: none;
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
</style>
