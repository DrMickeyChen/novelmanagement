/**
 * 分页列表范式（移动端）—— 首页 / 分类 / 搜索三处共用。
 *
 * ★ 移植自 reader-web/src/composables/usePagedList.js，逐条保留它的三件护具：
 *   ① `failed` / `isEmpty` 互斥（"暂时拿不到" ≠ "本来就没有"）
 *   ② 页码/每页条数由前端说了算，不信响应里的 current / size
 *   ③ `changeSize` 重置 page = 1
 *   以及那个看不见但最要紧的 **`seq` 序号（丢弃过期响应）**。
 *
 * ============================================================
 * ★★ 与桌面端的唯一差别：多了「追加」模式（上拉加载更多）
 * ============================================================
 *
 * 桌面端是**翻页**：第 2 页替换第 1 页，靠 el-pagination 跳。
 * 移动端是**无限滚动**：滚到底就往下接，列表只增不减 ——
 * 这是触屏上的通用形态，不是偷懒：手机上"翻页"意味着要瞄准一个小按钮，
 * 而"继续往下滑"是手指本来就在做的动作。
 *
 * 所以这里加了两个东西：
 *   - 选项 `append: true`
 *   - `finished`（没有更多了）与 `loadMore()`
 *
 * ★★ 追加模式带来一个**必须处理**的新情况：**加载下一页失败**。
 *    桌面端失败时清空 list 是对的（下一页替换上一页，留着上一页会
 *    让人以为"成功但没变化"）；但追加模式下清空 = 把用户已经滑过的
 *    十几条全抹掉，而他只是滑到底时网断了一下 —— 那是灾难性的。
 *    所以这里的规则是：**追加失败只置 error，绝不动已有的 list**。
 *    ★ 判据落在 `isAppend` 这个局部变量上，不是"看 list 空不空"。
 *
 * ------------------------------------------------------------
 * ★★ 「暂时拿不到」和「本来就没有」是两件事 —— 用 `failed` / `isEmpty` 分开
 * ------------------------------------------------------------
 * 起因是一次实测（M5）：停掉后端服务，搜索页显示的是
 * 【「没有找到相关的作品」】—— 一个彻头彻尾的谎话，而且它长得和
 * 「这个词真的搜不到东西」一模一样，**用户没有任何办法分辨**。
 * 修法不是在每个页面里写 `v-if="!error"` —— 调用点里迟早有一个忘掉
 * 那半个条件，而「忘掉」不会有任何报错，只是那个页面又开始说谎。
 * 所以这里直接给出**两个互斥的判据**，互斥由构造保证：
 *
 *     <Empty v-if="isEmpty" text="还没有作品" />
 *     <Empty v-else-if="failed" text="暂时加载失败" @retry="reload" />
 *
 * ★ 两个都带 `loaded`：它们回答的是「查完之后是什么结果」，
 *   首次进入（还没查）时两个都是 false，于是「加载中」不会和
 *   「没有数据」同时出现。
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
    /**
     * ★ 追加模式（移动端新增）：第 2 页往后是【接在】现有列表后面，
     *   而不是替换它。开启后请用 loadMore() 翻页，不要用 changePage()。
     */
    append = false,
  } = options

  const list = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  const loading = ref(false)

  /**
   * ★★ loaded 用来区分「还没查过」和「查过但为空」。
   *   少了它会发生什么：页面上一半写「加载中」、一半写 `v-if="!list.length"`
   *   显示空态 —— 首次进入时 loading 是 false（请求还没发）、list 是空的，
   *   于是【加载动画和「暂无数据」同时出现】，然后数据回来了再闪一下。
   */
  const loaded = ref(false)

  /**
   * ★ 追加模式专用：已经没有更多了。
   *
   * 判据是【拿到的条数】而不是"页码乘每页条数 ≥ total"——
   * 后者在 total 会变（新书上架）或者后端返回的 size 被夹过的时候会算错。
   * 拿到 0 条或不够一页，就说明到底了。
   */
  const finished = ref(false)

  /**
   * 最近一次失败的原因（request.js 已经把提示弹过了，
   * 这里留着是给**页面**用的 —— 它要据此决定渲染失败态而不是空态）。
   *
   * ★ 页面**不该直接读它**：直接读就要自己写 `loaded` 的判断，
   *   写漏了就退回到说谎的老样子。用下面那两个 computed。
   */
  const error = ref(null)

  /**
   * ★ 查完了、而且失败了。失败态与空态**互斥**。
   *
   * ★★ 它是 `loaded && error` 而不是只看 `error`：`error` 在每次
   *   `load()` 开头就被清成 null，但「清成 null」和「查成功了」
   *   在首次进入时长得一样 —— 少了 `loaded` 就会在请求发出前
   *   先闪一下失败态。
   *
   * ★★★ 追加模式下还有一个 **`list.length === 0`** 的条件，这一条
   *    是移动端特有的，很容易漏：
   *    用户滑到第 5 页时网断了 → error 非空 → 如果只看 `loaded && error`，
   *    **整个页面的内容会被替换成「暂时加载失败」**，他已经读过的
   *    50 条全没了。而事实是"前 50 条好好地在屏幕上，只是第 51 条没取到"。
   *    加上 list 非空才算失败态之后，那种情况由 `finished`/`loading`
   *    之外的第三条路处理 —— 见 `loadMoreFailed`。
   */
  const failed = computed(() => loaded.value && !!error.value && list.value.length === 0)

  /**
   * ★★ 追加那一页失败了，但列表里还有内容。
   *
   * 这是移动端独有的一种状态，桌面端的分页模型里不存在
   * （那边失败了就是整页失败，因为下一页本来就要替换上一页）。
   * 页面应该在列表**底部**渲染一个「加载失败，点击重试」，而不是
   * 把整页换掉 —— 上面那些内容是好的，别动它们。
   */
  const loadMoreFailed = computed(() => append && !!error.value && list.value.length > 0)

  /**
   * ★ 查完了、成功了、但是一条都没有 —— **「本来就没有」**。
   *
   * ★★ `!error` 这半个条件是它存在的理由：`catch` 里（非追加模式）
   *   `list` 被清空、于是「失败」和「空」的 `list.length` 都是 0。
   *   少了它，页面就会把失败说成「还没有作品」。
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
   *
   * ★★ 在移动端这个护具【更常用】：快速切分类、快速改关键词、
   *    快速下拉刷新，都会造出并发的请求。别把它删掉。
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

    /** ★ 第 1 页永远替换（它是"重新开始"），只有第 2 页往后才追加 */
    const isAppend = append && wantPage > 1

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

      const records = transform(data?.records ?? []) ?? []
      /*
       * ★★ 追加与替换的分岔口。
       *   用 concat 而不是 push(...records) —— 后者在几百条时会因为
       *   参数展开爆栈（`Maximum call stack size exceeded`），
       *   而"某个分类正好有 800 章"这种事迟早会发生。
       */
      list.value = isAppend ? list.value.concat(records) : records

      total.value = Number(data?.total) || 0
      // ★ 服务端确认后，页码以后端返回的为准再对齐一次自身的意图
      page.value = wantPage
      // ★ 不够一页就说明到底了（见 finished 的说明，不按 total 算）
      finished.value = records.length < size.value
      loaded.value = true
    } catch (e) {
      if (current !== seq) return

      /*
       * ★ 失败时也要把 loaded 置为 true。
       *   不置的话页面永远停在「加载中」的样子，而请求其实早就结束了。
       *
       * ★★ 但【只有非追加模式才清空 list】—— 见文件头那段。
       *    追加失败时把用户已经滑过的内容抹掉，是最糟的一种"处理"。
       */
      error.value = e
      if (!isAppend) {
        list.value = []
        total.value = 0
      }
      loaded.value = true
    } finally {
      // ★ 同样只在没有更新请求时才清 loading：
      //   否则先发的请求返回时会把后发请求的 loading 状态提前关掉
      if (current === seq) {
        loading.value = false
      }
    }
  }

  /**
   * 加载下一页（**追加模式用这个**）。
   *
   * ★ 三重保险，一个都不能少：
   *   `loading` —— 滚动事件一秒钟能触发几十次，不挡就会同时发十几个请求；
   *   `finished` —— 到底了还继续请求，会得到一个永远为空的循环；
   *   `loadMoreFailed` 的对应处理在页面里（点重试才继续），
   *   所以这里**不**因为 error 非空就永久卡死：用户点重试会再调一次。
   */
  function loadMore() {
    if (loading.value || finished.value) return
    return load(page.value + 1)
  }

  /** 跳页（**替换模式**用这个；追加模式请用 loadMore） */
  function changePage(targetPage) {
    return load(targetPage)
  }

  /**
   * 改每页条数，【并回到第 1 页】。
   *
   * ★ 重置 page 这一行是这个函数存在的全部理由。把它删掉不会报错，
   *   只会在「翻到后面几页再改每页条数」时出现一个空白列表。
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
   *   不能直接调 load() —— load() 会沿用当前页码。
   * ★★ 追加模式下它还有一个作用：**把 finished 重置掉**，
   *    否则换了个分类之后，上一轮的"到底了"会一直挂着，
   *    loadMore 永远直接返回，列表再也加载不出下一页。
   */
  function reload() {
    page.value = 1
    finished.value = false
    return load(1)
  }

  return {
    list,
    total,
    page,
    size,
    loading,
    loaded,
    /** ★ 追加模式专用：没有更多了 */
    finished,
    /** ★ 失败态（列表为空）。与下面的 isEmpty 互斥，页面用这两个 */
    failed,
    /** ★ 追加那一页失败了、但列表里还有内容。页面在列表底部渲染重试 */
    loadMoreFailed,
    /** ★ 空态（查成功了但没有数据） */
    isEmpty,
    /** 原始错误对象。★ 只有需要「按失败原因分流」的页面才读它 */
    error,
    load,
    loadMore,
    changePage,
    changeSize,
    reload,
  }
}
