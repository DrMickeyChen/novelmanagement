/**
 * 阅读器的本地存储：阅读进度 + 阅读设置。
 *
 * ------------------------------------------------------------
 * ★ 和 utils/auth.js 的关系：同一个套路，不同的用途
 * ------------------------------------------------------------
 * 两者都是「纯函数 + 直接读写 localStorage」，都不依赖 Pinia。
 * 这么分是因为要在 Vue 组件树外面调用（见 auth.js 的说明），
 * 而且纯函数最好测。
 *
 * ★★ 但存储 key 的可见范围完全不同，别搞混：
 *   novel_token / novel_user        ← 三个应用【共用】，是 §7.5 的约定
 *   novel_reader_progress_*         ← 只有阅读端用，另外两个端不认
 *   novel_reader_settings           ← 只有阅读端用
 * 下面这些 key 【不需要】复制到 author-web / admin-web。
 * 换句话说：auth.js 改了要改三份，这个文件改了只改阅读端。
 *
 * ------------------------------------------------------------
 * ★★ 为什么进度要「本地 + 服务端」双写（P3 决定 1）
 * ------------------------------------------------------------
 *   服务端（read_history 表）：跨设备。换台电脑登录同一账号，进度还在。
 *   本地（localStorage）：  跨登录态 + 断网可用。游客没有服务端记录，
 *                           但一样要「记住上次读到哪」。
 * 两者各管一半，谁都不能省：
 *   - 只有服务端 → 游客完全记不住进度（而游客是读者端的主体）
 *   - 只有本地   → 换设备就丢，而且清一次浏览器数据全没了
 *
 * ★ 分工的细节（这是阅读器最容易做错的地方）：
 *   本地存【像素偏移】，服务端存【百分比】。
 *   恢复时优先用本地的像素偏移 —— 它精确到「那一行」；
 *   只有本地记录和当前章节对不上时（比如从分享链接直达），
 *   才退回用服务端的百分比估算。
 *   本地【不存百分比】：如果连章节都对不上，那个百分比指的是别的章，
 *   拿它来恢复只会停在一个莫名其妙的位置。
 */

/** 进度 key 前缀。后面拼 novelId，一本书一个 key */
const PROGRESS_PREFIX = 'novel_reader_progress_'

/** 阅读设置（字号 / 行距 / 背景）存这里，全局一份，不按书分 */
export const SETTINGS_KEY = 'novel_reader_settings'

/**
 * 本地最多保留多少本书的进度。
 *
 * ★ 为什么要有这个上限：一本书一个 key，一个重度读者用一年能攒出几百上千个。
 *   localStorage 的配额大约 5MB，单条进度才 100 字节左右，撑满配额不现实 ——
 *   真正的问题是【清理策略缺失时它会一直涨，而没有任何人会发现】。
 *   超过上限就按最后阅读时间淘汰最旧的几条，是个明确的有界行为。
 *
 * ★ 200 这个数字是「够用」和「别把整个 localStorage 当成自己的」之间的取舍：
 *   正常读者同时追的书不会有 200 本。
 */
const PROGRESS_MAX_ENTRIES = 200

/* ==================== 通用读写 ==================== */

/**
 * 读一个 JSON 值。任何异常都当作「没有」。
 *
 * ★ 必须 try-catch：localStorage 里存的可能是上一个版本的程序写的旧格式，
 *   也可能被用户手动改坏。JSON.parse 一抛，如果调用方在滚动回调里，
 *   整个阅读器就白屏了，而报错栈完全指不到是存储的问题。
 *   （auth.js 的 getUser 出于同样的理由也包了一层。）
 */
function readJson(key) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    // 坏了就删掉，下次当作没有 —— 比每次读都抛好
    try {
      localStorage.removeItem(key)
    } catch {
      /* 连删都失败（比如隐私模式），忽略：下面照样返回 null */
    }
    return null
  }
}

/**
 * 写一个 JSON 值。
 *
 * ★ 写也要 try-catch，而且比读更要紧：Safari 隐私模式、
 *   配额写满、企业策略禁用本地存储时，setItem 会【抛异常】。
 *   进度上报是在滚动回调里调的 —— 那里抛出去没人接，读者会看到
 *   「滚到一半页面就不动了」。
 * ★ 写失败就静默放弃：进度记不住的代价，远小于阅读器崩掉。
 */
function writeJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
    return true
  } catch {
    return false
  }
}

/* ==================== 阅读进度 ==================== */

function progressKey(novelId) {
  return `${PROGRESS_PREFIX}${novelId}`
}

/**
 * 读某本书的本地进度。
 *
 * @param {number|string} novelId
 * @returns {{novelId: number, chapterId: number, offset: number, updatedAt: number}|null}
 *          没有记录、记录损坏、格式对不上，一律返回 null
 *
 * ★ 返回前做一次【字段校验】而不是直接信任 JSON：
 *   一个 chapterId 是 undefined 的记录，会让阅读器跳到一个不存在的章节，
 *   而症状是「打开就报章节不存在」—— 读者完全不知道是自己浏览器里的旧数据
 *   在作怪。校验不过就当没有，让他从头读，代价最小。
 */
export function loadProgress(novelId) {
  const raw = readJson(progressKey(novelId))
  if (!raw || typeof raw !== 'object') return null

  const chapterId = Number(raw.chapterId)
  const offset = Number(raw.offset)
  if (!Number.isFinite(chapterId) || chapterId <= 0) return null
  if (!Number.isFinite(offset) || offset < 0) return null

  return {
    novelId: Number(novelId),
    chapterId,
    offset,
    updatedAt: Number(raw.updatedAt) || 0,
  }
}

/**
 * 写某本书的本地进度。
 *
 * @param {number|string} novelId
 * @param {number} chapterId 当前章节 id
 * @param {number} offset    章内像素偏移（scrollTop），不是百分比
 *
 * ★ 这里【不做防倒退】：读者主动往回翻，他的当前位置就是前面那一章。
 *   「读过的最大进度」是另一个产品概念（P6 若要做「已读」标记再单开字段）。
 */
export function saveProgress(novelId, chapterId, offset) {
  /*
   * ★★ 写之前先校验 chapterId，不合法就【整个放弃这次写入】。
   *
   * 这不是多余的防御：读的一侧（loadProgress）已经在校验了，
   * 如果写的这一侧不校验，一个坏值就会【覆盖掉本来好好的记录】——
   * 下次打开这本书连正确的旧位置也读不回来了。
   * 而坏值并不需要什么离奇的条件就会产生：比如把带形参的函数
   * 直接注册成事件监听器（浏览器会塞一个 Event 对象当第一个实参），
   * Number(Event) 就是 NaN。P3 实施期间真的踩到了这一个，
   * 表现是「刷新之后进度永远回到开头」。
   *
   * ★ 宁可保留上一次的正确位置，也不要写进去一个读不出来的值。
   */
  const cid = Number(chapterId)
  if (!Number.isFinite(cid) || cid <= 0) return false

  const ok = writeJson(progressKey(novelId), {
    novelId: Number(novelId),
    chapterId: cid,
    // 取整：scrollTop 是小数（缩放、亚像素布局），存小数没意义还更占地方
    offset: Math.max(0, Math.round(Number(offset) || 0)),
    updatedAt: Date.now(),
  })

  // ★ 只在写成功后才淘汰：写失败说明存储已经不可用了，再去遍历只会更慢
  if (ok) pruneProgress()
  return ok
}

/**
 * 清掉一本书的本地进度。
 *
 * 目前没有调用方（P6 的「删除阅读记录」会用），先留着 ——
 * 但不要为了「清理」而在退出登录时调它：进度是【设备】维度的，
 * 退出登录不该把本机记的东西抹掉，否则用户下次登录发现进度没了。
 */
export function clearProgress(novelId) {
  try {
    localStorage.removeItem(progressKey(novelId))
  } catch {
    /* 见 writeJson 的说明：存储不可用时静默放弃 */
  }
}

/**
 * 淘汰超出上限的进度记录，保留最近读的 {@link PROGRESS_MAX_ENTRIES} 本。
 *
 * ★ 遍历整个 localStorage 是有成本的（几十上百个 key），
 *   所以【不要每次滚动都调它】—— 它只在 saveProgress 之后跑一次，
 *   而 saveProgress 已经被上层做了 1 秒防抖。这两层叠加之后，
 *   这个函数每秒最多跑一次，且绝大多数时候一进循环就发现没超限。
 */
function pruneProgress() {
  let keys
  try {
    keys = Object.keys(localStorage)
  } catch {
    return
  }

  const entries = []
  for (const key of keys) {
    if (!key.startsWith(PROGRESS_PREFIX)) continue
    const record = readJson(key)
    // 顺手把损坏的记录清掉：它永远读不出来，留着只是占地方
    if (!record || typeof record !== 'object') {
      try {
        localStorage.removeItem(key)
      } catch {
        /* 忽略 */
      }
      continue
    }
    entries.push({ key, updatedAt: Number(record.updatedAt) || 0 })
  }

  if (entries.length <= PROGRESS_MAX_ENTRIES) return

  // 最旧的排前面，删掉多出来的那些
  entries.sort((a, b) => a.updatedAt - b.updatedAt)
  for (const item of entries.slice(0, entries.length - PROGRESS_MAX_ENTRIES)) {
    try {
      localStorage.removeItem(item.key)
    } catch {
      /* 忽略 */
    }
  }
}

/* ==================== 阅读设置 ==================== */

/**
 * ★ 设置的三个可选项表放在这里，而不是散在页面组件里：
 *   它们是【纯数据】，且 stores/reader.js 和阅读器页面都要用。
 *   放在组件里的话，store 就得反过来 import 组件 —— 依赖方向是错的。
 *
 * ★ 值用的是 CSS 能直接吃的字符串，不是 'small'/'medium' 这种语义名。
 *   多一层映射（small → 16px）只会让「改了不生效」多一个排查环节。
 */
export const FONT_SIZES = [
  { key: 'sm', label: '小', value: '15px' },
  { key: 'md', label: '中', value: '17px' },
  { key: 'lg', label: '大', value: '20px' },
  { key: 'xl', label: '特大', value: '23px' },
]

export const LINE_HEIGHTS = [
  { key: 'tight', label: '紧凑', value: '1.6' },
  { key: 'normal', label: '适中', value: '1.9' },
  { key: 'loose', label: '宽松', value: '2.3' },
]

/**
 * 背景主题。★ 前景色和背景色是【成对】定义的，不能只存背景色 ——
 * 否则深色背景配默认的 #303133 正文，在夜里几乎看不见字。
 */
export const THEMES = [
  { key: 'light', label: '白', bg: '#ffffff', fg: '#303133' },
  { key: 'sepia', label: '米黄', bg: '#f5ecd9', fg: '#5b4636' },
  { key: 'green', label: '豆绿', bg: '#cce8cf', fg: '#2f4f37' },
  { key: 'dark', label: '夜间', bg: '#1f1f1f', fg: '#a8a8a8' },
]

/** 默认设置：取中间档，和「不设置」时的观感一致 */
export const DEFAULT_SETTINGS = {
  fontSize: 'md',
  lineHeight: 'normal',
  theme: 'light',
}

/** 各档位的 key，用于校验读回来的值还认不认得 */
const FONT_SIZE_KEYS = FONT_SIZES.map((o) => o.key)
const LINE_HEIGHT_KEYS = LINE_HEIGHTS.map((o) => o.key)
const THEME_KEYS = THEMES.map((o) => o.key)

/**
 * 读阅读设置。
 *
 * ★ 逐项校验、逐项回退到默认值，而不是「有一项不合法就整份丢掉」。
 *   原因：这三个设置是独立的，用户调好了字号和背景、只有行距那一项
 *   因为版本升级改了 key，没有理由把他的字号和背景一起重置掉。
 *
 * ★ 这个函数【永远返回一个完整的设置对象】，不会返回 null。
 *   调用方（Pinia store）可以直接拿来当初值，不需要再判空 ——
 *   返回 null 的话，每个用到它的地方都得写一遍 `?? DEFAULT_SETTINGS`，
 *   漏一处就是一个 undefined 塞进 CSS 变量、设置静默失效。
 */
export function loadSettings() {
  const raw = readJson(SETTINGS_KEY)
  const source = raw && typeof raw === 'object' ? raw : {}

  return {
    fontSize: FONT_SIZE_KEYS.includes(source.fontSize) ? source.fontSize : DEFAULT_SETTINGS.fontSize,
    lineHeight: LINE_HEIGHT_KEYS.includes(source.lineHeight)
      ? source.lineHeight
      : DEFAULT_SETTINGS.lineHeight,
    theme: THEME_KEYS.includes(source.theme) ? source.theme : DEFAULT_SETTINGS.theme,
  }
}

/** 保存阅读设置（只存 key，不存 CSS 值 —— 值改了要让老数据也跟着变） */
export function saveSettings(settings) {
  return writeJson(SETTINGS_KEY, {
    fontSize: settings?.fontSize,
    lineHeight: settings?.lineHeight,
    theme: settings?.theme,
  })
}
