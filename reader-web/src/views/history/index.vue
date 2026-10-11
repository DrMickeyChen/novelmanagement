<script setup>
/**
 * 阅读历史 —— 我最近读过的书，按最后阅读时间倒序。
 *
 * ★ 需要登录。未登录的引导在路由守卫里（见 `router/index.js`），
 *   这一页自己不写「请先登录」的分支。
 *
 * ------------------------------------------------------------
 * ★★ 和书架页的分工（两页看着像，回答的问题不一样）
 * ------------------------------------------------------------
 *   书架   = 「我**收藏**了什么」，排序依据是**加入时间**（不会被重读推前）
 *   历史   = 「我**最近在读**什么」，排序依据是**最后阅读时间**（重读就置顶）
 * 所以同一本书可以在两页都出现，而且**位置很可能不同**。
 * 这不是 bug，是两张表（`user_shelf` / `read_history`）各自的时间列。
 *
 * ★ 历史列表**没有**「移除」：后端没做删除接口（大纲没要求，
 *   且 `read_history` 没有 `deleted` 列，做了就只能物理删，
 *   「误删历史」不可恢复）。要做的话是「按需」清单里的事。
 */
import NovelCover from '@/components/NovelCover.vue'
import { onMounted } from 'vue'
import { getHistoryList } from '@/api/reader'
import { usePagedList } from '@/composables/usePagedList'
import { formatAuthorName, formatDateTime } from '@/utils/format'

const { list, total, page, size, loading, loaded, isEmpty, failed, load, changePage, reload } =
  usePagedList(({ page, size }) => getHistoryList({ page, size }), { pageSize: 20 })

onMounted(load)

/**
 * 失败态里那个「重试」。
 *
 * ★ 用 `reload()` 而不是 `load()`：失败时列表被清空、`total` 也是 0，
 *   分页条已经消失，此刻「第几页」没有意义 —— 回到第 1 页重查。
 * ★★ 必须在这里兜住 reject：`request.js` 的拦截器**提示完照样把
 *   Promise 拒出去**，模板里裸写 `@click="reload"` 就是一条
 *   unhandledrejection。（评论区 / 搜索页 / 分类页各有一份同样的
 *   `retry()`，四处保持一致。）
 */
function retry() {
  reload().catch(() => {})
}

/**
 * 「继续阅读」的地址。**取不到就是 null**，模板据此换一个入口。
 *
 * ★★ 这里和书架页有一个**真实的不对称**，不是漏写：
 *   书架项带 `lastChapterId`（最新章节），章节被撤回时能退到那里；
 *   历史项**没有这个字段** —— 它只有「我读到的那一章」。
 *
 *   所以历史页的三种情况是：
 *     ① `available && chapterId` → 「继续阅读」跳到那一章
 *     ② `available && !chapterId` → 读过的那章被作者撤回了，
 *        我们**不知道**该跳到哪一章（退到最新章就是剧透）。
 *        给「查看目录」跳到详情页，由用户自己挑 —— 那是唯一不撒谎的选项。
 *     ③ `!available` → 书下架了，什么按钮都不给。
 */
function readPath(item) {
  return item.chapterId ? `/read/${item.novelId}/${item.chapterId}` : null
}

/**
 * 进度文案。★ 判 `!= null` 而不是真值判断：**0% 是真实进度**
 * （打开过、一个字没滚），显示成「还没开始读」是错的。
 */
function progressText(item) {
  const percent = item.progress != null ? `${item.progress}%` : ''
  if (item.chapterNo != null) {
    const chapter = `第 ${item.chapterNo} 章`
    return percent ? `${chapter} · 已读 ${percent}` : chapter
  }
  return percent ? `已读 ${percent}` : ''
}

/** 章节标题。★ 和 chapterNo 同生共死，取不到就整段不显示 */
function chapterLabel(item) {
  if (item.chapterNo == null) return ''
  return item.chapterTitle ? `第 ${item.chapterNo} 章 · ${item.chapterTitle}` : `第 ${item.chapterNo} 章`
}
</script>

<template>
  <div class="history">
    <div class="page-head">
      <h1 class="title">阅读历史</h1>
      <span class="count">{{ loaded ? `共 ${total} 本` : '' }}</span>
    </div>

    <div v-loading="loading" class="list">
      <div v-for="item in list" :key="item.novelId" class="row">
        <NovelCover v-if="item.available" :cover="item.cover" :title="item.title" class="list-cover" />
        <div class="info">
          <!-- ★ 下架的书不整行删掉，渲染成占位 —— 理由同书架页 -->
          <router-link v-if="item.available" :to="`/novel/${item.novelId}`" class="book-title">
            {{ item.title }}
          </router-link>
          <span v-else class="book-title is-gone">{{ item.title || '已下架' }}</span>

          <div class="meta">
            <template v-if="item.available">
              <!-- ★ M5：`—` 而不是「佚名」—— 判据见 utils/format.js 的 formatAuthorName -->
              <span class="author">{{ formatAuthorName(item.authorName) }}</span>
              <el-divider direction="vertical" />
              <span>{{ item.categoryName || '未分类' }}</span>
              <template v-if="chapterLabel(item)">
                <el-divider direction="vertical" />
                <span class="chapter">{{ chapterLabel(item) }}</span>
              </template>
            </template>
            <span v-else class="gone-tip">这本书已下架或已删除</span>
          </div>

          <!-- ★ 进度条是纯展示（后端给的 0~100），不参与任何计算 -->
          <div v-if="item.progress != null" class="bar">
            <div class="bar-inner" :style="{ width: `${item.progress}%` }" />
          </div>
        </div>

        <div class="right">
          <span class="time">{{ formatDateTime(item.updateTime) }}</span>
          <div class="ops">
            <router-link v-if="item.available && readPath(item)" :to="readPath(item)">
              <el-button type="primary" size="small">继续阅读</el-button>
            </router-link>
            <!--
              ★ 读过的那一章被撤回了 → 我们不知道该跳到哪，
                退到详情页让用户自己挑目录。见上面 readPath 的说明。
            -->
            <router-link v-else-if="item.available" :to="`/novel/${item.novelId}`">
              <el-button size="small">查看目录</el-button>
            </router-link>
          </div>
        </div>
      </div>
    </div>

    <!--
      ★★ M5：失败态单独一支。M5 之前失败和空态共用下面那一行，于是
        停掉 content 之后读者看到的是【「还没有阅读记录」】加一个
        「去逛逛」按钮 —— 而他的阅读记录一条都没少，是服务挂了。
        ★ 更糟的是那句「去逛逛」：它把用户支使去首页，
          等于用一个错误的原因改变了用户的行为。
        `isEmpty` 里含 `!failed`，两支由构造互斥（见 usePagedList 文件头）。
    -->
    <el-empty v-if="isEmpty" description="还没有阅读记录">
      <router-link to="/">
        <el-button type="primary">去逛逛</el-button>
      </router-link>
    </el-empty>
    <el-empty v-else-if="failed" description="阅读记录暂时加载失败">
      <el-button type="primary" plain @click="retry">重试</el-button>
    </el-empty>

    <div v-if="total > size" class="pager">
      <el-pagination
        layout="prev, pager, next"
        background
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="changePage"
      />
    </div>
  </div>
</template>

<style scoped>
.list-cover { margin: 0; }
.history {
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

.count {
  font-size: 13px;
  color: #909399;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* ★ 给 v-loading 的遮罩留高度，否则内容为空时遮罩没有高度、看不见 */
  min-height: 80px;
}

/*
 * ★ 一行 = 左边信息 + 右边时间和按钮。
 *   `align-items: center` 让按钮在垂直方向居中，而不是跟着信息区的高度浮动。
 */
.row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.row:hover {
  border-color: #c6e2ff;
  box-shadow: 0 2px 8px rgb(64 158 255 / 12%);
}

.info {
  flex: 1;
  /*
   * ★ 少了它，长书名会把 flex 子项撑破，整页出现横向滚动条。
   *   （评论组件的 `.body` 里踩过同一个坑。）
   */
  min-width: 0;
}

.book-title {
  display: block;
  margin-bottom: 6px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.book-title.is-gone {
  color: #c0c4cc;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: #909399;
}

.author {
  color: #606266;
}

.chapter {
  color: #606266;
}

.gone-tip {
  color: #e6a23c;
}

/* 进度条：细一条，只用来「扫一眼看出读了多久」，不放数字（数字在右边） */
.bar {
  height: 4px;
  background: #f2f3f5;
  border-radius: 2px;
  overflow: hidden;
}

.bar-inner {
  height: 100%;
  background: #409eff;
}

.right {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.time {
  font-size: 12px;
  color: #c0c4cc;
  white-space: nowrap;
}

.ops {
  display: flex;
  gap: 8px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
