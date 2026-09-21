/**
 * 分页列表范式 —— 管理端每个列表页共用（作品/章节/评论/用户/申请/公告/日志…）。
 *
 * ★ 这份是从 reader-web/src/composables/usePagedList.js 照抄过来的，
 *   【不是重写】。管理端列表比阅读端多得多（十来个页面），更需要它 ——
 *   每个页面各写一遍分页，就是十几份不一样的 bug。
 *
 * ------------------------------------------------------------
 * ★ 用法
 * ------------------------------------------------------------
 *   const { list, total, page, size, loading, loaded, load, changePage, changeSize, reload }
 *     = usePagedList(
 *         ({ page, size }) => getNovelPage({ ...filter.value, page, size }),
 *         { pageSize: 10 },
 *       )
 *   onMounted(load)
 *
 * ------------------------------------------------------------
 * ★ 设计上刻意做的三个决定
 * ------------------------------------------------------------
 *
 * ① 【不自动请求】，由页面自己调 load()。
 *    看起来多写一行 onMounted(load)，但换来「第一次请求什么时候发」
 *    这个问题可以靠读页面回答。composable 里藏 onMounted 会让请求来源
 *    变得不可见 —— 排查「怎么多了一个请求」时，你得先想到去看内部。
 *
 * ② 【页码和每页条数由前端说了算，不信响应里的 current / size】。
 *    后端 PageResult.empty() 返回的是写死的 current=1 / size=10 ——
 *    空结果时如果照抄响应，用户选了「每页 20 条」，筛一个没结果的词，
 *    分页组件就会跳回 10 条，看起来像后端把参数吃了。
 *    服务端只负责告诉我们 total，其余是客户端的展示状态。
 *
 * ③ 【changeSize 必须重置 page = 1】。
 *    不重置的话：在第 13 页把「每页 10 条」切成「每页 50 条」，
 *    请求会带着 page=13 发出去，而总数据只有 15 页 → 第 13 页空了，
 *    列表空白但总数显示得很大，第一反应是「后端分页算错了」。
 */
import { ref } from 'vue'

export function usePagedList(fetcher, options = {}) {
  const {
    /** 每页条数初值 */
    pageSize = 10,
    /**
     * 额外查询参数，每次请求前现取一次值。
     * ★ 传函数而不是对象：这样页面里的筛选条件变了，只要调 reload()，
     *   拿到的一定是最新值。传对象的话快照是创建时的，改不动。
     */
    params = () => ({}),
    /** 拿到 records 之后的加工（比如补一个计算字段），默认原样返回 */
    transform = (records) => records,
  } = options

  const list = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  const loading = ref(false)

  /**
   * ★★ loaded 用来区分「还没查过」和「查过但为空」。
   *
   * 少了它会发生什么：页面上一半写 `v-loading="loading"`、
   * 一半写 `v-if="!list.length"` 显示 el-empty —— 首次进入时
   * loading 是 false（请求还没发）、list 是空的，于是【加载动画和
   * 「暂无数据」同时出现】，然后数据回来了再闪一下。
   * 这是分页列表最常见的一个视觉 bug，根源就是把「空」当成了
   * 「没有数据」而不是「还不知道」。
   *
   * 所以页面的写法应该是：
   *     v-loading="loading"
   *     <el-empty v-if="loaded && !list.length" />
   */
  const loaded = ref(false)

  /** 最近一次失败。页面可以据此显示重试按钮（拦截器已经弹过提示了） */
  const error = ref(null)

  /**
   * 请求序号，用于丢弃【过期响应】。
   *
   * ★ 场景：用户快速点了「第 3 页」又点「第 2 页」。两个请求并发，
   *   如果第 3 页那个先发、后返回（完全正常，网络就是这样），
   *   它会把第 2 页的数据覆盖掉 —— 用户看到的是第 3 页的内容，
   *   而分页组件高亮在第 2 页。而且它只在网络慢的时候出现，
   *   本地开发几乎测不出来。
   *   每次请求递增这个序号，回来时对不上就直接丢弃。
   */
  let seq = 0

  /**
   * 取一页数据。
   *
   * @param {number} [targetPage] 要取的页码，默认当前页
   */
  async function load(targetPage) {
    const wantPage = Number(targetPage) || page.value
    const current = ++seq

    loading.value = true
    error.value = null

    try {
      const data = await fetcher({
        page: wantPage,
        size: size.value,
        ...params(),
      })

      // 已经有更新的请求发出去了，这次的响应作废
      if (current !== seq) return

      const records = data?.records ?? []
      list.value = transform(records) ?? []
      total.value = Number(data?.total) || 0
      // ★ 服务端确认后，页码以后端返回的为准再对齐一次自身的意图，
      //   正常情况下两者相等；这里不采信 data.size，理由见上面第 ② 条
      page.value = wantPage
      loaded.value = true
    } catch (e) {
      if (current !== seq) return
      /*
       * ★ 失败时也要把 loaded 置为 true。
       *   不置的话页面永远停在「加载中」的样子，而请求其实早就结束了 ——
       *   用户看到的是一个永远转圈的页面，没有任何办法继续。
       *   置为 true 之后页面至少会走到空态，配上拦截器弹过的错误提示，
       *   用户知道是失败了而不是还在加载。
       *   （list 清空：上一次的数据留着会让「失败」看起来像「成功了但没变化」。）
       */
      error.value = e
      list.value = []
      total.value = 0
      loaded.value = true
    } finally {
      // ★ 同样只在没有更新请求时才清 loading：
      //   否则先发的请求返回时会把后发请求的 loading 状态提前关掉
      if (current === seq) {
        loading.value = false
      }
    }
  }

  /** 跳页。页码越界不做处理（分页组件已经限制了范围），交给后端返回空列表 */
  function changePage(targetPage) {
    return load(targetPage)
  }

  /**
   * 改每页条数，【并回到第 1 页】。
   *
   * ★ 重置 page 这一行是这个函数存在的全部理由，见文件头的第 ③ 条。
   *   把它删掉不会报错，只会在「翻到后面几页再改每页条数」时出现一个空白列表。
   */
  function changeSize(newSize) {
    size.value = Number(newSize) || pageSize
    page.value = 1
    return load(1)
  }

  /**
   * 回到第 1 页重新查。
   *
   * ★ 筛选条件变了（换了状态、换了分类、改了关键词）必须走这个，
   *   不能直接调 load() —— load() 会沿用当前页码，
   *   而在第 5 页换一个只有 2 页数据的筛选条件，结果就是一片空白。
   *   管理端的筛选比阅读端多得多，这个错误会出现得更频繁。
   */
  function reload() {
    page.value = 1
    return load(1)
  }

  return {
    list,
    total,
    page,
    size,
    loading,
    loaded,
    error,
    load,
    changePage,
    changeSize,
    reload,
  }
}
