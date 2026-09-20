<script setup>
/**
 * 作品详情页 —— 简介 + 目录 + 「开始/继续阅读」。
 *
 * ★ 目录是【一次性全量】返回的（NovelDetailVO.chapters），不单独请求。
 *   种子书只有 3~8 章，为一屏目录多发一个请求不划算。
 *   章数上来之后要改成分页目录，这是记在 P3 文档里的遗留项。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getNovelDetail, reportNovelView } from '@/api/novel'
import { formatDate, formatWordCount } from '@/utils/format'
import { loadProgress } from '@/utils/readerStorage'

const route = useRoute()

const novelId = computed(() => route.params.id)

const detail = ref(null)
const loading = ref(false)
/** 加载失败。★ 只有 true / false，不区分原因（见下面 load 的说明） */
const failed = ref(false)

/** 目录。★ detail 还没回来时给空数组，模板里的 v-for 就不用再判空 */
const chapters = computed(() => detail.value?.chapters ?? [])

/**
 * 本机记录的阅读进度（只有 novelId 对得上才有）。
 *
 * ★ 这是「继续阅读」按钮【唯一】正确的数据来源。
 *   ★★ 不能用 detail.value.lastChapterId：那个字段的语义是
 *   「最新章节」（由后端 syncNovelStat 在章节状态流转时维护），
 *   不是「读者读到哪一章」—— 拿它当续读目标，用户会被扔到最新一章，
 *   也就是【剧透】。而且它可能为 null。
 *   NovelDetailVO 里甚至根本没暴露这个字段，就是为了防这个误用。
 */
const localProgress = ref(null)

/**
 * 续读目标章节。
 *
 * ★ 三级回退，顺序不能换：
 *   ① 本机有记录、且那一章确实还在目录里 → 用它
 *      （「还在目录里」这个判断不能省：章节被作者删掉 / 被驳回下架之后，
 *        本机记录就指向一个不存在的 id，直接跳过去会报「章节不存在」）
 *   ② 没有记录 → 目录第一章
 *   ③ 目录是空的（一本书一个已发布章节都没有）→ null，按钮置灰
 */
const continueChapter = computed(() => {
  if (!chapters.value.length) return null

  const recorded = localProgress.value
  if (recorded) {
    const hit = chapters.value.find((c) => c.id === recorded.chapterId)
    if (hit) return hit
  }
  return chapters.value[0]
})

/** 有本机记录且能对上时，按钮文案才是「继续阅读」 */
const isResuming = computed(
  () =>
    !!localProgress.value &&
    chapters.value.some((c) => c.id === localProgress.value.chapterId),
)

/** 阅读地址。★ continueChapter 为 null 时返回 null，按钮据此置灰 */
const readPath = computed(() =>
  continueChapter.value ? `/read/${novelId.value}/${continueChapter.value.id}` : null,
)

/** 目录里标记「读到这里」的那一章 */
const readToChapterId = computed(() => (isResuming.value ? localProgress.value.chapterId : null))

onMounted(() => {
  load()
  /*
   * ★ 埋点是「打开详情页就记一次」，和读没读正文无关。
   *   它是 silent 的（见 api/novel.js）：不弹提示、401 不跳登录页。
   *
   * ★★ 但 silent 【不等于】不会 reject —— 拦截器照样把 Promise 拒出去，
   *   只是拒得悄无声息。所以这里必须自己兜住：
   *   `void` 一个裸 Promise 会变成 unhandledrejection，控制台一片红，
   *   接了 Sentry 之类的上报工具就会被当成真错误报上去，
   *   而它其实只是「阅读量少记了一次」这种可以完全忽略的事。
   *   （阅读器里上报进度那处也是这么兜的，两处保持一致。）
   */
  reportNovelView(novelId.value).catch(() => {})
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    detail.value = await getNovelDetail(novelId.value)
    // 详情拿到之后再读进度：两者互不依赖，但先有目录才有意义
    localProgress.value = loadProgress(novelId.value)
  } catch {
    /*
     * ★ 这里【不区分失败原因】，因为拿不到区分所需的依据：
     *   request.js 的响应拦截器把失败统一成 `new Error(后端给的 message)`，
     *   业务码（2001 作品不存在）和网络错误在调用方看来长得一模一样。
     *   所以页面只显示一句中性的「没找到这本书」+ 回首页，
     *   具体原因由拦截器弹出的那条提示告诉用户（比如「连接不上后端服务」）。
     *
     * ★ 也可以改 request.js 让 reject 出来的 Error 带上 code，
     *   但那个文件三端逐字节相同，动它要三个一起改、还要重跑 P2 验收 ——
     *   为一个错误页的文案不值得。记在遗留里。
     */
    failed.value = true
    detail.value = null
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="novel-detail">
    <el-empty v-if="failed" description="没有找到这本书，可能已下架">
      <router-link to="/">
        <el-button type="primary">回到首页</el-button>
      </router-link>
    </el-empty>

    <template v-else-if="detail">
      <!-- ============ 头部：书名 / 元信息 / 操作按钮 ============ -->
      <el-card shadow="never" class="head">
        <div class="title-row">
          <h1 class="title">{{ detail.title }}</h1>
          <el-tag :type="detail.isFinish === 1 ? 'success' : 'warning'" effect="plain" size="small">
            {{ detail.isFinish === 1 ? '已完结' : '连载中' }}
          </el-tag>
        </div>

        <div class="meta">
          <span class="author">{{ detail.authorName || '佚名' }}</span>
          <el-divider direction="vertical" />
          <router-link :to="`/category/${detail.categoryId}`" class="link">
            {{ detail.categoryName || '未分类' }}
          </router-link>
          <el-divider direction="vertical" />
          <span>{{ detail.chapterCount || 0 }} 章</span>
          <el-divider direction="vertical" />
          <span>{{ formatWordCount(detail.wordCount) }}</span>
          <el-divider direction="vertical" />
          <span>{{ detail.viewCount || 0 }} 阅读</span>
          <template v-if="detail.lastUpdateTime">
            <el-divider direction="vertical" />
            <span>更新于 {{ formatDate(detail.lastUpdateTime) }}</span>
          </template>
        </div>

        <!--
          ★ 标签：后端给的是逗号分隔的一个字符串（"热血,升级流"），
            不是数组，所以这里 split 一下。加 filter(Boolean) 是因为
            结尾多一个逗号会 split 出一个空串，渲染成一个空白的小标签。
        -->
        <div v-if="detail.tags" class="tags">
          <el-tag v-for="tag in detail.tags.split(',').filter(Boolean)" :key="tag" size="small" type="info" effect="plain">
            {{ tag }}
          </el-tag>
        </div>

        <div class="actions">
          <router-link v-if="readPath" :to="readPath">
            <el-button type="primary" size="large">
              {{ isResuming ? '继续阅读' : '开始阅读' }}
            </el-button>
          </router-link>
          <el-button v-else size="large" disabled>暂无章节</el-button>

          <span v-if="isResuming" class="resume-hint">
            上次读到 第 {{ continueChapter.chapterNo }} 章
          </span>
        </div>
      </el-card>

      <!-- ============ 简介 ============ -->
      <el-card shadow="never" class="section">
        <template #header><span class="section-title">简介</span></template>
        <!--
          ★ 用 {{ }} 而不是 v-html。简介是作者填的自由文本，
            v-html 会把它当 HTML 解析 —— 轻则排版被标签冲乱，
            重则是一个 XSS 入口（作者的输入直接进所有读者的浏览器）。
            换行靠 CSS 的 white-space: pre-line，够用且安全。
        -->
        <p class="intro">{{ detail.intro || '作者还没有填写简介。' }}</p>
      </el-card>

      <!-- ============ 目录 ============ -->
      <el-card shadow="never" class="section">
        <template #header>
          <span class="section-title">目录</span>
          <span class="section-sub">共 {{ chapters.length }} 章</span>
        </template>

        <div class="chapters">
          <router-link
            v-for="chapter in chapters"
            :key="chapter.id"
            :to="`/read/${novelId}/${chapter.id}`"
            class="chapter"
            :class="{ current: chapter.id === readToChapterId }"
          >
            <span class="chapter-no">第 {{ chapter.chapterNo }} 章</span>
            <span class="chapter-title">{{ chapter.title }}</span>
            <span v-if="chapter.id === readToChapterId" class="badge">读到这里</span>
          </router-link>
        </div>

        <el-empty v-if="!chapters.length" description="还没有已发布的章节" :image-size="60" />
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.novel-detail {
  padding: 20px 0;
}

.head {
  margin-bottom: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  font-size: 13px;
  color: #909399;
}

.author {
  color: #606266;
}

.link {
  color: #409eff;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

.resume-hint {
  font-size: 13px;
  color: #909399;
}

.section {
  margin-bottom: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
}

.section-sub {
  margin-left: 8px;
  font-size: 13px;
  color: #909399;
}

.intro {
  margin: 0;
  line-height: 1.9;
  color: #303133;
  /* ★ 保留作者写的换行，同时不解析任何 HTML */
  white-space: pre-line;
}

.chapters {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
}

.chapter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 14px;
  color: #606266;
}

.chapter:hover {
  background: #f5f7fa;
  color: #409eff;
}

/* 「读到这里」的那一章：左侧一条竖线，扫一眼就能找到 */
.chapter.current {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.chapter-no {
  flex: none;
  font-size: 13px;
  color: #909399;
}

.chapter.current .chapter-no {
  color: #409eff;
}

.chapter-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  flex: none;
  margin-left: auto;
  font-size: 12px;
  color: #67c23a;
}
</style>
