<script setup>
/**
 * 我的书架 —— 收藏过的书 + 每本书读到哪。
 *
 * ★ 需要登录（后端 `/api/reader/**` 落在 `anyRequest().authenticated()` 上）。
 *   未登录时的引导由路由守卫负责（见 `router/index.js` 的 `meta.requiresAuth`），
 *   所以这一页里【不写】「请先登录」的分支 —— 一个永远进不来的页面
 *   不需要为空态写一套逻辑。
 *
 * ------------------------------------------------------------
 * ★★ 为什么不复用 `NovelCard.vue`
 * ------------------------------------------------------------
 * 书架项**长得像**作品卡片，但它是另一种东西：这张卡片回答的是
 * 「我这本书读到哪了、怎么继续」，而 `NovelCard` 回答的是
 * 「这是一本什么书」。硬要复用的话，只有两条路，两条都不好：
 *
 *   ① 让 `NovelCard` 接 `ShelfItemVO` —— 但那个 VO **没有** `id`
 *      （主键叫 `novelId`）、没有 `isFinish`、没有 `wordCount`、
 *      没有 `viewCount`。这些字段在卡片上是无条件渲染的，
 *      复用的结果是每一张书架卡片都显示「连载 · 0 章 · 0 字 · 0 阅读」，
 *      ★ **不是缺字段，是显示了一个错的数字**，而且看起来很正常。
 *   ② 给 `NovelCard` 加一堆条件渲染 + 两个插槽 —— 那 4 个公开页面
 *      （首页/分类/榜单/搜索）就要为一个只有登录用户看得到的页面
 *      背上一整套它们用不到的分支。
 *
 * ★ 所以这里自己写卡片，但**版式照抄** `.grid` 那套（见文件末尾）——
 *   和分类页的列宽/间距一致，翻过来不会觉得像另一个站。
 */
import NovelCover from '@/components/NovelCover.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getShelfList, removeFromShelf } from '@/api/shelf'
import { usePagedList } from '@/composables/usePagedList'
import { formatAuthorName, formatDate } from '@/utils/format'

/**
 * 分页。★ 用项目统一的 `usePagedList`（它处理过「加载中和空态打架」
 * 「快速翻页时先发的响应后到」这些事，见它的文件头）。
 */
const { list, total, page, size, loading, loaded, isEmpty, failed, load, changePage, reload } =
  usePagedList(({ page, size }) => getShelfList({ page, size }), { pageSize: 12 })

onMounted(load)

/**
 * 失败态里那个「重试」。
 *
 * ★ 用 `reload()` 而不是 `load()`：失败时列表被清空、`total` 也是 0，
 *   分页条已经消失，此刻「第几页」没有意义 —— 回到第 1 页重查。
 * ★★ 必须在这里兜住 reject：`request.js` 的拦截器**提示完照样把
 *   Promise 拒出去**，模板里裸写 `@click="reload"` 就是一条
 *   unhandledrejection。（评论区 / 搜索页 / 分类页 / 历史页各有一份
 *   同样的 `retry()`，五处保持一致。）
 */
function retry() {
  reload().catch(() => {})
}

/**
 * 续读目标章节。
 *
 * ★★ **优先 `readChapterId`，没有才退到 `lastChapterId`** —— 顺序不能反：
 *   · `readChapterId` 是「我读到哪」，是这一页真正想要的东西；
 *   · `lastChapterId` 是**最新章节**（后端在章节状态流转时维护的），
 *     拿它当续读目标，用户会被扔到最新一章，也就是**剧透**。
 *
 * ★ `readChapterId` 会和 `readChapterNo` / `readChapterTitle` **同生共死**：
 *   读过的那一章被作者撤回之后三个一起变 null。这时退到 `lastChapterId`
 *   是对的 —— 至少还能读，而不是点一个必然打不开的章节（后端 `3001`）。
 *   两个都没有（这本书一章都没发布 / 我还没读过且没有已发布章节）→ null，
 *   按钮置灰。
 */
function continueChapterId(item) {
  return item.readChapterId ?? item.lastChapterId ?? null
}

/** 续读地址。★ null 表示这本书现在没有可读的章节，按钮据此置灰 */
function readPath(item) {
  const chapterId = continueChapterId(item)
  return chapterId ? `/read/${item.novelId}/${chapterId}` : null
}

/**
 * 进度文案。
 *
 * ★ 三种情况的说法不一样，不能合成一句：
 *   · 有章号 → 「读到 第 3 章 · 60%」
 *   · 有进度但章节信息没了 → 只说「已读 60%」（章名取不到就别显示章号）
 *   · 完全没读过 → 「还没开始读」
 *
 * ★ `progress` 判的是 `!= null` 而不是真值判断：**0% 是一个真实的进度**
 *   （打开过第一章、一个字没滚），显示成「还没开始读」是错的。
 */
function progressText(item) {
  const percent = item.progress != null ? `已读 ${item.progress}%` : ''
  if (item.readChapterNo != null) {
    return percent ? `读到 第 ${item.readChapterNo} 章 · ${percent}` : `读到 第 ${item.readChapterNo} 章`
  }
  return percent || '还没开始读'
}

/** 加入时间（只到天）。★ `formatDate` 对空值返回空串，所以模板里要判一下 */
function joinedText(item) {
  const date = formatDate(item.createTime)
  return date ? `加入于 ${date}` : ''
}

const removing = ref(null)

/**
 * 移出书架。
 *
 * ★ **必须问一句**：`user_shelf` 没有 `deleted` 列，移出是**物理删除**，
 *   没有撤销。而且用户很容易点错 —— 这一页上「继续阅读」和「移除」
 *   是并排的两个按钮。
 *
 * ★ 确认框的三个约定（`CommentItem.vue` 里写过一遍，这里是第二处消费者）：
 *   ① `ElMessageBox.confirm` 是命令式 API，**读不到 Element 的语言包** ——
 *      按钮文字必须手写「确定」/「取消」，不写就是英文的 OK/Cancel
 *   ② 它 **reject**（不是返回 false），点「取消」走 catch 分支 ——
 *      不接住就是一个未处理异常
 *   ③ 它需要 `message-box` 的样式（`main.js` 里补的那一行）——
 *      不引就是个没有遮罩、按钮错位的裸框
 */
async function handleRemove(item) {
  const name = item.title || '这本书'
  try {
    await ElMessageBox.confirm(`确定把《${name}》移出书架吗？`, '移出书架', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户点了取消
  }

  removing.value = item.novelId
  try {
    await removeFromShelf(item.novelId)
    ElMessage.success('已移出书架')
    /*
     * ★ 重查**当前页**，不是 `reload()` —— 那个会跳回第 1 页，
     *   而用户可能正在第 3 页清书架。
     *   （第 3 页删到只剩最后一条时，这一页会变空 —— 可以接受：
     *   分页组件还在，点「上一页」就回去了。自动回退上一页反而会让
     *   连点「移除」的人被莫名其妙地弹走。）
     */
    await load()
  } catch {
    /* 失败原因由 request.js 的拦截器提示，这里只需要别变成 unhandledrejection */
  } finally {
    removing.value = null
  }
}

/** 「共 N 本」只在拿到数据之后显示，否则首屏会先闪一个「共 0 本」 */
const totalText = computed(() => (loaded.value ? `共 ${total.value} 本` : ''))
</script>

<template>
  <div class="shelf">
    <div class="page-head">
      <h1 class="title">我的书架</h1>
      <span class="count">{{ totalText }}</span>
    </div>

    <div v-loading="loading" class="grid">
      <div v-for="item in list" :key="item.novelId" class="shelf-card">
        <!--
          ★★ 下架的书【不能整行过滤掉】：读者会以为自己的书架丢了东西，
            而真实原因只是那本书下架了。渲染成占位卡，并且照样给「移除」。
        -->
        <NovelCover v-if="item.available" :cover="item.cover" :title="item.title" class="list-cover" />
        <template v-if="item.available">
          <router-link :to="`/novel/${item.novelId}`" class="card-title">
            {{ item.title }}
          </router-link>

          <div class="meta">
            <!-- ★ M5：`—` 而不是「佚名」—— 判据见 utils/format.js 的 formatAuthorName -->
            <span class="author">{{ formatAuthorName(item.authorName) }}</span>
            <el-divider direction="vertical" />
            <span>{{ item.categoryName || '未分类' }}</span>
            <el-divider direction="vertical" />
            <span>{{ item.chapterCount || 0 }} 章</span>
          </div>

          <div class="progress">{{ progressText(item) }}</div>
        </template>

        <template v-else>
          <span class="card-title is-gone">{{ item.title || '已下架' }}</span>
          <div class="meta"><span class="gone-tip">这本书已下架或已删除</span></div>
          <!-- ★ 进度仍然显示：章节那三个字段可能没了，但 progress 是独立的 -->
          <div class="progress">{{ progressText(item) }}</div>
        </template>

        <div class="ops">
          <router-link v-if="item.available && readPath(item)" :to="readPath(item)">
            <el-button type="primary" size="small">继续阅读</el-button>
          </router-link>
          <!-- ★ 已上架但没有可读章节（作者还没发正文）→ 置灰，不要给一个点了就报错的按钮 -->
          <el-button v-else-if="item.available" size="small" disabled>暂无可读章节</el-button>

          <!--
            ★ 下架的书【也要】有移除按钮 —— 服务端的移除接口刻意不校验上架状态，
              就是为了让这一行能被清理掉。少写这个按钮，那一行就永远删不掉了。
          -->
          <el-button
            size="small"
            :loading="removing === item.novelId"
            @click="handleRemove(item)"
          >
            移除
          </el-button>
        </div>

        <div v-if="joinedText(item)" class="joined">{{ joinedText(item) }}</div>
      </div>
    </div>

    <!--
      ★ 必须等 loaded —— 只用 !list.length 的话首屏会「加载动画 + 空书架」同时出现

      ★★ M5：失败态单独一支。M5 之前失败和空态共用下面那一行，于是
        停掉 content 之后读者看到的是【「书架还是空的」】——
        而他的收藏一本都没少。这和详情页那个收藏按钮是同一个谎话
        （那边是显示成「加入书架」），两处一起改掉了。
        `isEmpty` 里含 `!failed`，两支由构造互斥（见 usePagedList 文件头）。
    -->
    <el-empty v-if="isEmpty" description="书架还是空的">
      <router-link to="/">
        <el-button type="primary">去逛逛</el-button>
      </router-link>
    </el-empty>
    <el-empty v-else-if="failed" description="书架暂时加载失败">
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
.list-cover { margin-bottom: 12px; }
/*
 * ★ 版式和分类页（`views/category/index.vue`）一致：同样的 `.grid` 列宽、
 *   同样的 `.pager`、同样的 `.page-head`。书架是另一个列表页，
 *   翻过来不该觉得像换了个站。
 */
.shelf {
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

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  /* ★ 给 v-loading 的遮罩留高度，否则内容为空时遮罩没有高度、看不见 */
  min-height: 80px;
}

.shelf-card {
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.shelf-card:hover {
  border-color: #c6e2ff;
  box-shadow: 0 2px 8px rgb(64 158 255 / 12%);
}

.card-title {
  /*
   * ★ display: block 是必须的：书名有时候是 <router-link>（可点），
   *   有时候是 <span>（已下架，不可点）。前者默认是 inline，
   *   而 inline 元素上的 margin-bottom **不生效** ——
   *   表现是「能读的书标题和下划线贴在一起，下架的书正常」，
   *   同一张卡片里两种间距，且只在部分卡片上出现。
   */
  display: block;
  margin-bottom: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  /* ★ 书名可能很长，超出省略：不处理的话卡片高度会被撑高一行，网格里就错位了 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 下架的书名不可点，颜色压下去，和能读的书一眼分得开 */
.card-title.is-gone {
  color: #c0c4cc;
}

.meta {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  color: #909399;
}

.author {
  color: #606266;
}

.gone-tip {
  color: #e6a23c;
}

.progress {
  margin-bottom: 12px;
  font-size: 13px;
  color: #409eff;
}

/* ★ margin-top: auto 把按钮推到底 —— 同一行里几张卡片高度不同时，
   按钮仍然对齐在底部，而不是跟着文字长度上下浮动 */
.ops {
  display: flex;
  gap: 8px;
  margin-top: auto;
}

.joined {
  margin-top: 10px;
  font-size: 12px;
  color: #c0c4cc;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
