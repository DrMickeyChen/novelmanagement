<script setup>
/**
 * 搜索结果页 —— 按【书名或作者昵称】跨全站搜索。
 *
 * ------------------------------------------------------------
 * ★★ 与桌面端最大的一处差异：搜索框在这个页面上，不在顶栏里
 * ------------------------------------------------------------
 * reader-web 的搜索框在 BasicLayout 的导航栏里，所以它的关键词
 * **来自 URL**（`/search?keyword=xxx`），并且用 `watch(keyword)` 响应变化 ——
 * 那样做是因为「URL 是唯一的状态来源」，而且搜索结果页要能分享、能刷新。
 *
 * 移动端没有那条常驻顶栏（tabBar 下面是页面，页面上面只有导航条），
 * 所以搜索框只能长在这一页里。于是状态自然就落在组件里。
 *
 * ★★ 但由此多出一个桌面端不存在的问题：**输入框的值和"搜过的词"不是一回事**。
 *
 * 如果让输入框直接 v-model 到 `keyword`、再 watch 它去请求，
 * 那么用户每敲一个字就是一个请求 —— 打「斗破苍穹」发 4 次，
 * 而且返回顺序不保证。桌面端**天然没有这个问题**：那边是回车才改 URL。
 *
 * 所以这里把两个东西分开：
 *   - `input`   —— 输入框里现在是什么（每敲一下就变）
 *   - `keyword` —— 真正搜过的那个词（只在回车/点搜索时变）
 * 只有 `keyword` 参与请求。它们不同步恰恰是设计。
 *
 * ★ 也因此这里没有 watch —— 桌面端那个 watch 是为了"URL 被别处改了"，
 *   而移动端的输入框就在本页，没有"别处"。少一个 watch 是因为
 *   那个场景不存在，不是因为可以省。
 */
import { computed, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { searchNovels } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'
import StateHint from '@/components/StateHint.vue'

/** 输入框里正在打的字 */
const input = ref('')

/**
 * 真正搜过的关键词。
 *
 * ★ trim() 之后才用：带一个空格的关键词在后端会被当成空白关键词
 *   直接返回空列表，而用户看到的现象是「搜了个空格，结果页说没找到」。
 */
const keyword = ref('')

/** ★★ 没有关键词就【不发请求】—— 空关键词的查询 100% 返回 0 条，纯属浪费 */
const hasKeyword = computed(() => keyword.value.length > 0)

const {
  list,
  total,
  loaded,
  loading,
  isEmpty,
  failed,
  loadMoreFailed,
  finished,
  load,
  loadMore,
  reload,
} = usePagedList((params) => searchNovels(params), {
  pageSize: 10,
  append: true,
  params: () => ({ keyword: keyword.value }),
})

/**
 * ★ 从首页搜索入口进来时自动聚焦，键盘直接弹出来。
 *   ★ 首页跳过来时**不带关键词**（用户还没输入），所以这里是空的。
 */
const autoFocus = ref(false)

onLoad(() => {
  autoFocus.value = true
})

onReachBottom(() => {
  loadMore()
})

/**
 * 提交搜索（回车 或 点「搜索」）。
 *
 * ★★ 关键词没变就【不重搜】：点两次搜索按钮、或者输入框里回车，
 *    会发出两个一模一样的请求。后端多一次查询是小事，
 *    真正的代价是**第二次响应回来时会把列表重置到第 1 页** ——
 *    用户已经滑到第 3 页了，手指一抖点到搜索，又从头开始。
 */
function submit() {
  const kw = input.value.trim()
  if (kw === keyword.value) return

  keyword.value = kw

  if (!kw) {
    /*
     * ★ 关键词被清空时要把上一次的结果也清掉。
     *   留着的话页面显示的是引导语，下面却列着上一次搜出来的书。
     */
    list.value = []
    total.value = 0
    return
  }

  // ★ reload() 而不是 load()：换了关键词必须回到第 1 页，
  //   否则「上一本书搜到第 3 页、换本书」会得到一个空白列表
  reload()
}

/** 清空输入框并回到引导态 */
function clearInput() {
  input.value = ''
  if (keyword.value) {
    keyword.value = ''
    list.value = []
    total.value = 0
  }
}

/**
 * 重试。
 *
 * ★★ 必须在这里兜住 reject：`request.js` 提示完照样把 Promise 拒出去，
 *   模板里裸写 `@click="reload"` 就是一条 unhandledrejection。
 */
function retry() {
  reload().catch(() => {})
}

/** ★ 搜索页的结果为空时要带上关键词 —— 只说「暂无数据」用户会怀疑没搜出去 */
const emptyText = computed(() => `没有找到与「${keyword.value}」有关的作品，换个书名或作者名试试`)
</script>

<template>
  <view class="search">
    <!-- ============ 搜索框 ============ -->
    <view class="searchbar">
      <text class="searchbar__icon">🔍</text>
      <input
        v-model="input"
        class="searchbar__input"
        type="text"
        confirm-type="search"
        :focus="autoFocus"
        placeholder="搜书名、作者名"
        placeholder-class="ph"
        @confirm="submit"
      />
      <text v-if="input" class="searchbar__clear" @click="clearInput">✕</text>
      <text class="searchbar__btn" @click="submit">搜索</text>
    </view>

    <!--
      ★ 没有关键词的引导态。刻意不套 StateHint ——
        StateHint 的三支是「加载中 / 本来就没有 / 暂时拿不到」，
        而这里的状态是**"用户还没搜"**，它压根不是一次请求的结果。
        硬塞进去会得到一个"暂无数据"的空态，把"还没开始"说成"没有"。
    -->
    <view v-if="!hasKeyword" class="hint">
      <text class="hint__line">输入<text class="hint__strong">书名</text>或<text class="hint__strong">作者名</text>开始找书</text>
      <text class="hint__sub">搜索是跨全站的，不需要先选分类。</text>
    </view>

    <template v-else>
      <view class="head">
        <text class="head__title">「{{ keyword }}」的搜索结果</text>
        <!-- ★ loaded && !failed 才显示本数：失败时 total 被清成 0，
             否则会写着「共 0 本」—— 那是把失败说成了"真的没有" -->
        <text v-if="loaded && !failed" class="head__count">共 {{ total }} 本</text>
      </view>

      <view v-if="list.length" class="cards">
        <view v-for="novel in list" :key="novel.id" class="cards__item">
          <NovelCard :novel="novel" />
        </view>
      </view>

      <!--
        ★★ 空态和失败态是两支，文案明显不同。
          M5 实测过：把失败渲染成空态时，用户看到的是
          「没有找到与「x」有关的作品，换个书名或作者名试试」——
          一句语气笃定、还给了下一步建议的**谎话**。
          它不只是缺信息，它把用户支使去改关键词，而换个词当然还是搜不到。
          ★ 这是全项目最典型的一次「失败被渲染成空」，移动端照抄这套判据。
      -->
      <StateHint
        :loading="loading"
        :is-empty="isEmpty"
        :failed="failed"
        :empty-text="emptyText"
        failed-text="搜索暂时不可用"
        @retry="retry"
      />

      <!-- ★★ 底部提示条 —— 见首页同一段注释：追加失败绝不能清空已读内容 -->
      <view v-if="list.length" class="foot">
        <text v-if="loadMoreFailed" class="foot__retry" @click="loadMore">加载失败，点击重试</text>
        <text v-else-if="loading" class="foot__text">加载中…</text>
        <text v-else-if="finished" class="foot__text">没有更多了</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.search {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 搜索框 ---------- */
.searchbar {
  display: flex;
  align-items: center;
  height: 76rpx;
  padding: 0 8rpx 0 24rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 999rpx;
}

.searchbar__icon {
  flex: none;
  margin-right: 12rpx;
  font-size: 28rpx;
}

.searchbar__input {
  flex: 1;
  /* ★ 同 NovelCard 里那条：flex 子项不加 min-width:0 就压不下去 */
  min-width: 0;
  height: 76rpx;
  font-size: 28rpx;
  color: #1f2733;
}

.ph {
  color: #b6bdc7;
}

.searchbar__clear {
  flex: none;
  padding: 0 16rpx;
  font-size: 26rpx;
  color: #b6bdc7;
}

/*
 * ★ 搜索按钮做成一个胶囊而不是用 uni 的 <button>：
 *   button 在 uni 里自带一层默认样式（边框、::after 的 1px 描边、
 *   固定行高），要清掉得写一大段覆盖，且各端表现不一致。
 *   一个 text 加背景色就够了，而它点击事件的语义在这里完全够用
 *   （回车也能触发，见 @confirm）。
 */
.searchbar__btn {
  flex: none;
  padding: 10rpx 28rpx;
  font-size: 26rpx;
  color: #fff;
  background: #2f6fed;
  border-radius: 999rpx;
}

/* ---------- 引导态 ---------- */
.hint {
  display: flex;
  flex-direction: column;
  padding: 60rpx 32rpx;
  background: #fff;
  border-radius: 16rpx;
}

.hint__line {
  font-size: 28rpx;
  color: #5a6472;
}

.hint__strong {
  font-weight: 600;
  color: #2f6fed;
}

.hint__sub {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ---------- 结果 ---------- */
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8rpx 8rpx 20rpx;
}

.head__title {
  flex: 1;
  min-width: 0;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2733;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head__count {
  flex: none;
  margin-left: 12rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

.cards {
  display: flex;
  flex-direction: column;
}

.cards__item {
  margin-bottom: 16rpx;
}

/* ---------- 底部提示条 ---------- */
.foot {
  display: flex;
  justify-content: center;
  padding: 24rpx 0 8rpx;
}

.foot__text {
  font-size: 24rpx;
  color: #9aa3b0;
}

.foot__retry {
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
