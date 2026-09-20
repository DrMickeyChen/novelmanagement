/**
 * SSE 流式客户端 —— 专供 AI 面板（`/api/ai/**`）。
 *
 * ============================================================
 * ★★★ 为什么是 fetch 而不是 EventSource（对大纲 §8.3 的刻意偏离）
 * ============================================================
 * 大纲 §8.3 建议「前端用 EventSource」，并自己点出了它的经典坑：
 * **EventSource 不支持自定义请求头**，所以 token 得走 query 参数或 Cookie。
 *
 * ★ 但本项目 §7.5 明文否掉了「把 token 放 URL」——
 *   URL 会留在浏览器历史记录、服务器 access log、Referer 头里。
 *   大纲里这两条直接打架，而 §7.5 是本项目的铁律，所以这里偏离 §8.3。
 *
 * 除了 token 这条，EventSource 还有两条硬伤（各自都足以否掉它）：
 *   ② 它**只有 GET** —— 而我们需要 POST 传 prompt / context
 *      （prompt 最长 2000 字，塞进 URL 会超长，也会进日志）
 *   ③ 它**不能中断** —— 而「点中断立刻停」是 P4 的验收项之一
 *
 * `fetch` 三条全都能做：自定义头 ✅、POST ✅、`AbortController` ✅，
 * 代价只是要自己解 SSE 帧 —— 就是这个文件干的事。
 * 后端仍然是 `SseEmitter`（§8.4 的选型不变），**后端一行都不用改**。
 *
 * ============================================================
 * ★★★ 解帧的三条规则，全部是在真实字节上验出来的，不是照抄规范
 * ============================================================
 * 先看后端实际吐出来的东西（`curl -N` 的原始字节，已实测）。
 * `MockAiStreamSource` 是**逐码点**推送的，所以下面每个帧对应一个字符：
 *
 *   内容字符   帧的原始字节        解析后怎么还原
 *   ────────  ─────────────────  ──────────────────────────────
 *   `t`       `data:t\n\n`        ['t']        → 't'
 *   ␣(空格)   `data: \n\n`        [' ']        → ' '
 *   `\r`      `data:\r\n\n`       ['\r']       → '\r'
 *   `\n`      `data:\ndata:\n\n`  ['', '']     → '\n'   ← ★★★
 *
 * ★★★ 第 4 行是这里最要命的一条。
 *   Spring 的 `SseEmitter` **会把含换行的 data 拆成多行 `data:`**
 *   （这是 SSE 协议规定的编码方式）。所以内容里一个 `\n` 会变成
 *   相邻两行 `data:`，而**只有把多个 `data:` 行的值用 `\n` 拼起来**
 *   才能还原它。
 *
 *   用 `datas[0]`、或者用 `''` 拼 —— 后果是：
 *     · OUTLINE 场景：JSON 变成一行（还能解析，但和 mock 产出的形状不符）
 *     · WRITE 场景：**散文的段落全部糊成一行**，而这是最常看的那个场景
 *   而且两种都不报错。所以这条是「看起来无关紧要、实际必错」的地方。
 *
 * ★★ 第 2 行：**`data:` 后面没有分隔空格**（Spring 不加），
 *   那一帧的载荷**本身就是空格**。所以：
 *     · **绝不能按 SSE 规范「去掉一个前导空格」** —— 去掉的是正文内容
 *     · **绝不能对值做 `.trim()`** —— 上面 `data:\r` 的值就是 `\r`，
 *       trim 掉就丢了字符
 *   只管 `line.slice(5)`，一个字符都不要多动。
 *
 * ★★ 第 3 行：**绝不能把 `\r\n` 归一化成 `\n`**（有人在解析 SSE 时
 *   习惯这么干，因为 HTTP 里 `\r\n` 是标准行尾）。但这里的 `\r\n`
 *   是「一个 `\r` 字符 + Spring 补的行尾 `\n`」，归一化会把
 *   `data:\r\n\n` 变成 `data:\n\n` → `\r` 当场丢失。
 *
 *   ★ 顺带一个只在 Windows 上出现的现象：OUTLINE 的 JSON 用的是
 *     `\r\n`（Jackson 的 `DefaultPrettyPrinter` 用 `System.lineSeparator()`），
 *     而 WRITE 的散文用的是 `\n`（mock 里写死的模板）。
 *     所以**不要在测试里断言具体空白字符**，换个平台就红。
 *
 * ============================================================
 * ★★ 返回值：一个「结果对象」，而不是抛异常
 * ============================================================
 * `{ ok, reason, message, text }`
 *   `reason` 的取值就是调用方需要区分的全部分支：
 *     'done'         正常收到 `[DONE]`
 *     'aborted'      用户点了中断 / 组件卸载时主动 abort（**不是错误**）
 *     'unauthorized' 401
 *     'forbidden'    403
 *     'http'         其它非 2xx
 *     'network'      连不上（后端没起 / 断网）
 *     'upstream'     响应不是 SSE（代理配错，返回了 index.html 之类）
 *     'incomplete'   ★ 流正常结束了，但**没收到 `[DONE]`**
 *
 * ★★ `incomplete` 是这里最容易漏、又一定会遇到的一种：
 *   后端 `SSE_TIMEOUT_MS = 3 分钟`，超时的时候**既不补 `[DONE]` 也不发错误帧**，
 *   就是**流平白无故断了**。如果只按「fetch 没报错」判成功，
 *   用户会看到一段半截的文字、面板显示成功、点采纳落一半的库。
 *
 * ★ 不抛异常是为了强制调用方把每个分支都写出来。
 *   抛异常的话，「401 和网络断了」在 catch 里长得一模一样，
 *   而它们的提示语完全不同（见下）。
 */

/* ★ 与 utils/request.js 的 baseURL 保持一致（那边是 '/api'）。
   写成常量而不是从 request.js 里 import —— request.js 是三端逐字节
   相同的文件，为了这一个值去动它，会破坏那条 md5 约束。 */
const BASE_URL = '/api'

/** ★ 后端约定的结束哨兵（OpenAI 兼容协议同款）。E1 换真实模型时不变。 */
const DONE_SENTINEL = '[DONE]'

/** 401 的提示语。★ 见下面 `unauthorized` 分支的说明 —— 这里刻意不跳登录页。 */
const MSG_UNAUTHORIZED = '登录已过期，请刷新页面后重试'

/**
 * 发起一次流式请求。
 *
 * @param {string} path  形如 `/ai/write`（**不含** `/api` 前缀）
 * @param {object} body  请求体，形如 `{ prompt, context }`
 * @param {object} [opts]
 * @param {(text: string) => void} [opts.onChunk] 每收到一段**增量**文本回调一次
 * @param {AbortSignal} [opts.signal]             调用方持有 controller 并负责 abort
 * @returns {Promise<{ok: boolean, reason: string, message: string, text: string}>}
 *          `text` 是本次收到的**全部**增量拼起来的完整文本（中断时是半截的）
 */
export async function streamPost(path, body, { onChunk, signal } = {}) {
  const token = getTokenSafe()

  let response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        /*
         * ★★★ 这里必须**同时**接受 JSON，而且必须给 SSE 更高的优先级。
         *
         *   `Accept: text/event-stream` 单独一条看着更「准确」（我们确实只要流），
         *   实测却是错的 —— 它会让**所有错误响应变成空 body**：
         *
         *     400 + Accept: text/event-stream        → Content-Length: 0
         *     400 + Accept: text/event-stream, json  → {"code":400,"message":"…"}
         *
         *   原因是 Spring 的内容协商：参数校验失败走的是
         *   `GlobalExceptionHandler`（`@RestControllerAdvice`），
         *   它产出的类型是 `application/json`，而客户端「只接受」
         *   `text/event-stream` —— **没有任何可写的表示形式**，
         *   于是 body 被整个丢掉，只剩状态码。
         *
         *   ★ 后果就是下面那段「把后端那句话捞出来」的逻辑**永远捞不到**，
         *     退出成「请求失败（HTTP 400）」。而它看起来像是后端没给原因。
         *     （我第一版甚至没查到这里，先去改字段名 `msg`→`message` 了；
         *      改成什么都一样，body 是空的。）
         *
         *   ★ q=0.9 是关键，不是装饰：它让两个类型都可产出时**优先选流**。
         *     实测两种 Accept 下成功路径吐出的字节**完全相同**，
         *     所以这条退路对正常的流没有任何影响。
         *     ★ 别写成 `Accept: text/event-stream, application/json`
         *       （等权重）—— 那时候选哪个由服务端可产出类型的顺序决定，
         *       等于把「成功路径拿到的是流」这件事交给运气。
         */
        Accept: 'text/event-stream, application/json;q=0.9',
        // ★★ token 走请求头，不放 URL（§7.5）
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body ?? {}),
      signal,
    })
  } catch (err) {
    // ★ abort 也是一种「失败」，但它不是错误 —— 是用户自己按的。
    //   区分不开的话，用户一点中断就会看到一句红色的报错。
    if (err?.name === 'AbortError') {
      return { ok: false, reason: 'aborted', message: '已中断', text: '' }
    }
    return {
      ok: false,
      reason: 'network',
      message: '无法连接到服务器，请确认后端已启动',
      text: '',
    }
  }

  /* ==================== 先处理「根本没开始流」的几种情况 ==================== */

  if (response.status === 401) {
    /*
     * ★★★ 刻意**不**调 clearAuth()、**不**跳登录页。
     *
     *   这与 utils/request.js 里对 401 的处理**故意不同**，不是漏了：
     *   此刻作者正在创作中心写东西，一跳走就等于丢稿
     *   （§7.5 那条「401 统一跳阅读端登录页」针对的是「打开页面时
     *     就没有登录态」，不是「写着写着 token 过期了」）。
     *
     *   所以这里只把话说清楚，让作者自己决定什么时候刷新。
     *   ★ 也不清 localStorage —— 刷新页面后只要 token 还有效就还能用，
     *     清了反而要重新登录一次。
     */
    return { ok: false, reason: 'unauthorized', message: MSG_UNAUTHORIZED, text: '' }
  }

  if (response.status === 403) {
    return {
      ok: false,
      reason: 'forbidden',
      message: '当前账号没有使用 AI 助手的权限',
      text: '',
    }
  }

  if (!response.ok) {
    /*
     * ★★ 尽量把后端 `Result` 里的那句话捞出来。
     *
     *   非流式的失败（400 参数校验、500 内部错误）返回的是普通 JSON，
     *   里面那句话是**唯一**能说明原因的东西 ——
     *   不读它的话，作者看到的就是「请求失败（HTTP 400）」，
     *   而真实原因是「prompt 长度不能超过 2000」。
     *
     * ★★★ 字段名是 `message`，**不是 `msg`**。
     *   这是实测出来的：`Result` 类里那个字段就叫 `message`
     *   （`private String message;`），响应体长这样
     *     {"code":200,"message":"操作成功","data":{...}}
     *   `utils/request.js` 读的也是同一个名字（`res.data?.message`）。
     *
     *   ★ 我第一版写的是 `data?.msg` —— 一个**永远不会命中**的属性。
     *     它的失效方式最难发现：不报错、不抛异常，只是那句提示
     *     悄悄退回成「请求失败（HTTP 400）」，看起来像是后端没给原因。
     *     断言这种「兜底掩盖了失败」的代码，只能靠真机看一眼提示语。
     */
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const data = await response.json()
      if (data?.message) message = data.message
    } catch {
      /* 不是 JSON（比如代理返回的 HTML 错误页），用上面那句 */
    }
    return { ok: false, reason: 'http', message, text: '' }
  }

  /*
   * ★★ 校验响应类型，这条看着多余，实际能省掉一次很难查的排查。
   *
   *   开发期三端走 dev-proxy（:3000 → :8080）。代理一旦配错
   *   （比如只配了 `/api` 却没配 `changeOrigin`、或者路径改写写错），
   *   返回的会是**前端的 index.html**，状态码还是 200。
   *   于是解析流的时候拿到一堆 `<`，报一个和 AI 毫无关系的错。
   *   这里拦一道，提示直接指向真正的原因。
   */
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    return {
      ok: false,
      reason: 'upstream',
      message: `响应不是事件流（Content-Type: ${contentType || '空'}），请检查代理配置`,
      text: '',
    }
  }

  if (!response.body) {
    // 老浏览器 / 极个别环境下 fetch 拿不到流。本项目只跑现代浏览器，理论上到不了这里。
    return { ok: false, reason: 'upstream', message: '当前浏览器不支持流式响应', text: '' }
  }

  /* ==================== 正式读流 ==================== */

  const reader = response.body.getReader()
  // ★ stream: true 必须有：中文一个字 3 字节，网络分片会从中间切开，
  //   不开这个选项会在切点处解出乱码（一个汉字变成两个 U+FFFD）。
  const decoder = new TextDecoder('utf-8')

  let buffer = ''
  const collected = []
  let sawDone = false
  let failure = null

  try {
    for (;;) {
      /* eslint-disable no-await-in-loop */
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // ★ 只在缓冲区里找分隔符，不归一化行尾 —— 见文件头第 3 条。
      let sep
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frameText = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)

        const verdict = consumeFrame(frameText, collected, onChunk)
        if (verdict === 'done') {
          sawDone = true
          break
        }
        if (verdict) {
          failure = verdict
          break
        }
      }

      if (sawDone || failure) break
    }
  } catch (err) {
    if (err?.name === 'AbortError') {
      return { ok: false, reason: 'aborted', message: '已中断', text: collected.join('') }
    }
    return {
      ok: false,
      reason: 'network',
      message: '连接意外中断',
      text: collected.join(''),
    }
  } finally {
    /*
     * ★ 主动关掉连接。
     *   「收到 [DONE]」「遇到错误帧」「读完了」三种情况下循环都会退出，
     *   但前两种时**服务端可能还在发**（它并不知道我们不听了）。
     *   不 cancel 的话这条连接会一直挂着占资源，直到服务端自己写完。
     *   已经结束的流上再 cancel 是安全的（幂等）。
     */
    try {
      await reader.cancel()
    } catch {
      /* 已经关掉了，忽略 */
    }
  }

  const text = collected.join('')

  if (failure) {
    return { ok: false, reason: failure.reason, message: failure.message, text }
  }

  if (!sawDone) {
    /*
     * ★★ 流平白无故断了 —— 没有 [DONE]，也没有错误帧。
     *   这是后端 3 分钟超时的**唯一表现**，也可能是网络断了。
     *   详见文件头对 `incomplete` 的说明：绝不能当成成功。
     */
    return {
      ok: false,
      reason: 'incomplete',
      message: '生成中断，内容可能不完整',
      text,
    }
  }

  return { ok: true, reason: 'done', message: '', text }
}

/**
 * 处理一帧。
 *
 * @returns {null | 'done' | {reason: string, message: string}}
 *          `null` = 继续；`'done'` = 收到哨兵；对象 = 失败
 */
function consumeFrame(frameText, collected, onChunk) {
  // 空帧（连着两个空行）直接跳过。不是错误。
  if (!frameText) return null

  let eventName = ''
  const dataLines = []

  for (const line of frameText.split('\n')) {
    // SSE 里以 ':' 开头的是注释行，常被用作心跳保活。本项目后端不发，但顺手支持。
    if (line.startsWith(':')) continue

    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
      continue
    }

    if (line.startsWith('data:')) {
      /*
       * ★★★ 只切掉 'data:' 这 5 个字符，**不多做任何事**：
       *   不去前导空格、不 trim。见文件头第 2 条 ——
       *   `data: ` 的载荷就是一个空格。
       */
      dataLines.push(line.slice(5))
    }
    // id: / retry: 本项目不用，静默忽略
  }

  /* ★★★ 多行用 '\n' 拼 —— 这是还原内容里换行的唯一方式，见文件头第 4 行。 */
  const payload = dataLines.join('\n')

  // ★ 后端的失败帧是 `event:error\ndata:生成失败，请稍后重试`（实测）。
  //   它**不保证**是流的最后一帧之后的 —— 实测它出现在流的中途。
  if (eventName === 'error') {
    return { reason: 'upstream', message: payload || '生成失败，请稍后重试' }
  }

  if (payload === DONE_SENTINEL) return 'done'

  /*
   * ★ 其它 event（含空 event）一律当正文。
   *   后端现在只发无 event 的默认帧和 error 帧，但 E1 换真实实现时
   *   可能加 `event:delta` 之类 —— 那时候这里不用改，它本来就会当正文收下。
   */
  if (payload) {
    collected.push(payload)
    onChunk?.(payload)
  }

  return null
}

/**
 * 读 token。
 *
 * ★ 单独包一层是为了**不 import auth.js** —— 那个文件里有一堆
 *   `window.location` 相关的代码，而这个文件将来如果要挪到
 *   worker 里跑（或者被单测直接 import），拖进 auth.js 会连带
 *   拖进浏览器 API。这里只读一个 localStorage key，行为完全一致。
 *
 * ★ 也做了 try-catch：隐私模式下 localStorage 读取会抛异常。
 *   拿不到 token 就当未登录，让后端返回 401，走上面那条分支 ——
 *   比在这里抛出去导致整个面板白屏好。
 */
function getTokenSafe() {
  try {
    return localStorage.getItem('novel_token') || ''
  } catch {
    return ''
  }
}
