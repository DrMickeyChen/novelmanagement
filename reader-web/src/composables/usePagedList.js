/**
 * 分页列表范式 —— 首页 / 分类 / 榜单 / 搜索四处共用。
 *
 * 这是全项目第一个分页 composable（项目大纲 §6.1 一直点名的 useTable 就是它）。
 * 之所以要抽出来，是因为「分页列表」看着简单，但下面这几件事每一件都踩过：
 * 加载态和空态打架、切每页条数之后列表空白、快速翻页时先发的请求后到、
 * 筛选条件变了页码还停在旧位置。四处各写一遍，就是四份不一样的 bug。
 *
 * ------------------------------------------------------------
 * ★ 用法
 * ------------------------------------------------------------
 *   const { list, total, page, size, loading, loaded, isEmpty, failed,
 *           load, changePage, changeSize, reload }
 *     = usePagedList(
 *         ({ page, size }) => getNovelList({ ...route.query, page, size }),
 *         { pageSize: 12, params: () => ({ sort: sort.value }) },
 *       )
 *   onMounted(load)
 *
 * ------------------------------------------------------------
 * ★★ 「暂时拿不到」和「本来就没有」是两件事 —— 用 `failed` / `isEmpty` 分开
 * ------------------------------------------------------------
 * 这一条是 M5 加的，起因是一次实测：停掉后端服务，搜索页显示的是
 * 【「没有找到相关的作品」】—— 一个彻头彻尾的谎话，而且它长得和
 * 「这个词真的搜不到东西」一模一样，**用户没有任何办法分辨**。
 *
 * 根源是 `catch` 把失败写成了「空」（`list=[]`），而页面只看 `list.length`。
 * 修法不是在每个页面里写 `v-if="!error"` —— 49 个调用点里迟早有一个忘掉
 * 那半个条件，而「忘掉」不会有任何报错，只是那个页面又开始说谎。
 * 所以这里直接给出**两个互斥的判据**：
 *
 *     <el-empty v-if="isEmpty" description="还没有作品" />
 *     <el-empty v-else-if="failed" description="暂时加载失败">
 *       <el-button @click="reload">重试</el-button>
 *     </el-empty>
 *
 * ★ 互斥是**由构造保证**的（`isEmpty` 里带了 `!failed`），不是靠调用方自觉。
 * ★ 两个都带 `loaded`：它们回答的是「查完之后是什么结果」，
 *   首次进入（还没查）时两个都是 false，于是「加载中」不会和
 *   「没有数据」同时出现 —— 和 `loaded` 本身存在的理由是同一条。
 *
 * ★★ 这不是「降级」—— `usePagedList` 管的都是**主数据**（作品列表、
 *   评论列表），主数据没有「降级值」可给，只能如实显示失败。
 *   真正的降级（拿不到就少显示一个字段）在后端做，见 M5 的降级判据。
 *
 * ------------------------------------------------------------
 * ★ 设计上刻意做的三个决定
 * ------------------------------------------------------------
 *
 * ① 【不自动请求】，由页面自己调 load()。
 *    看起来多写一行 onMounted(load)，但换来「第一次请求什么时候发」
 *    这个问题可以靠读页面回答。搜索页尤其需要这个：它必须等用户
 *    输入关键词，自动请求会先发一个空关键词的查询出去。
 *    composable 里藏 onMounted 会让请求来源变得不可见 ——
 *    排查「怎么多了一个请求」时，你得先想到去看 composable 内部。
 *
 * ② 【页码和每页条数由前端说了算，不信响应里的 current / size】。
 *    后端 PageResult.empty() 返回的是写死的 current=1 / size=10 ——
 *    空结果时如果照抄响应，用户选了「每页 20 条」，搜一个没结果的词，
 *    分页组件就会跳回 10 条，看起来像后端把参数吃了。
 *    服务端只负责告诉我们 total，其余是客户端的展示状态。
 *
 * ③ 【changeSize 必须重置 page = 1】。
 *    不重置的话：在第 13 页把「每页 10 条」切成「每页 50 条」，
 *    请求会带着 page=13 发出去，而总数据只有 15 页 → 第 13 页空了，
 *    列表空白但总数显示得很大，第一反应是「后端分页算错了」。
 */
import { computed, ref } from 'vue'

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
   *     <el-empty v-if="isEmpty" />        ← ★ M5 起改用 isEmpty，见文件头
   *     <el-empty v-else-if="failed">…重试…</el-empty>
   */
  const loaded = ref(false)

  /**
   * 最近一次失败的原因（`request.js` 的拦截器已经把提示弹过了，
   * 这里留着是给**页面**用的 —— 它要据此决定渲染失败态而不是空态）。
   *
   * ★ M5 之前这个 ref 已经存在，但三端 49 个调用点**零消费者** ——
   *   注释写着「页面可以据此显示重试按钮」，而没有一个页面这么做。
   *   「加了一个 ref 但没人用」和「没有这道护栏」是等价的。
   *   ★ 现在的消费方式是下面那两个 computed，页面**不该直接读它**：
   *   直接读就要自己写 `loaded` 的判断，写漏了就退回到说谎的老样子。
   */
  const error = ref(null)

  /**
   * ★ 查完了、而且失败了。失败态与空态**互斥**，见文件头。
   *
   * ★★ 它是 `loaded && error` 而不是只看 `error`：`error` 在每次
   *   `load()` 开头就被清成 null，但「清成 null」和「查成功了」
   *   在首次进入时长得一样 —— 少了 `loaded` 就会在请求发出前
   *   先闪一下失败态。
   */
  const failed = computed(() => loaded.value && !!error.value)

  /**
   * ★ 查完了、成功了、但是一条都没有 —— **「本来就没有」**。
   *
   * ★★ `!error` 这半个条件是它存在的理由：`catch` 里 `list` 被清空、
   *   于是「失败」和「空」的 `list.length` 都是 0。少了它，
   *   页面就会把失败说成「还没有作品」—— 正是 M5 要修的那个谎话。
   *   把它写在这里而不是让 49 个调用点各写一遍，是因为
   *   **忘写不会有任何报错**，只会有某个页面悄悄开始说谎。
   */
  const isEmpty = computed(() => loaded.value && !error.value && list.value.length === 0)

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
       *
       * ★★ 但「走到空态」这句话在 M5 之前是**字面意思** —— 页面只有
       *   `list.length` 可看，于是它渲染出来的是「还没有作品」。
       *   现在 `error` 非空 → `failed` 为 true → 页面渲染失败态。
       *   ★ 清空 list 这件事**保留**：`failed` 与「残留着上一页数据」
       *   同时成立会让每个页面都要多写一层判断。代价是失败时看不到
       *   上一页的内容 —— 但那是**诚实**的代价，不是 bug。
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
   * ★ 筛选条件变了（换了分类、换了排序、改了关键词）必须走这个，
   *   不能直接调 load() —— load() 会沿用当前页码，
   *   而在第 5 页换一个只有 2 页数据的筛选条件，结果就是一片空白。
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
    /** ★ 失败态。与下面的 isEmpty 互斥，页面用这两个，别直接用 error */
    failed,
    /** ★ 空态（查成功了但没有数据）。见文件头那张写法 */
    isEmpty,
    /** 原始错误对象。★ 只有需要「按失败原因分流」的页面才读它（如搜索页） */
    error,
    load,
    changePage,
    changeSize,
    reload,
  }
}
