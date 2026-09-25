/**
 * 阅读器的本地存储：阅读进度 + 阅读设置（移动端）。
 *
 * ============================================================
 * ★★★ 本文件是判断 3 的落点：同一个 localStorage 里，进度【共用】、设置【隔离】★★★
 * ============================================================
 *
 * 桌面端和移动端现在共享同一个 origin 的 localStorage。里面有四类 key，
 * 各自的处置【不一样】，而且判据不是"都共用"或"都隔离"，
 * 而是 **「共用的收益是否大于互相踩的代价」**：
 *
 * | key                              | 处置        | 理由 |
 * |----------------------------------|-------------|------|
 * | `novel_token` / `novel_user`     | **共用** ✓  | 登录态。见 auth.js |
 * | `novel_reader_progress_{bookId}` | **共用** ✓  | 「读到哪」跨端一致**是优点**：手机上读到一半，电脑上接着读 |
 * | `novel_reader_settings`          | **隔离** ✗  | 见下 |
 *
 * ------------------------------------------------------------
 * ★★ 为什么设置必须隔离
 * ------------------------------------------------------------
 * 屏幕形态不同。手机是 375px 宽、单栏、手指操作；桌面是 1400px、多栏、鼠标。
 * 共用一份设置的后果是：「我在手机上把字号调大，**把电脑上的也改了**」——
 * 而且**没有任何提示**，用户在电脑前只会觉得"这字怎么突然变大了"。
 *
 * 所以移动端写在 `novel_reader_settings_mobile`。两边各调各的，互不干扰。
 *
 * ★ 隔离的代价要说清楚：用户在两端的观感**会不一致**（手机夜里模式、
 *   电脑白天模式）。这是对的 —— 它本来就该按设备走，而不是按账号走。
 *   （真要跨端同步阅读偏好，那属于"账号级偏好"，该进数据库，
 *     不该拿 localStorage 当同步通道。）
 *
 * ------------------------------------------------------------
 * ★★ 为什么进度必须共用，以及它的形状是【承重件】
 * ------------------------------------------------------------
 * 共用要求两边写出来的东西**逐字节同构**：同 key、同字段名、同单位、同类型。
 * 下面 loadProgress / saveProgress 的字段和单位一个字都不能改 ——
 * 改了这个文件而没改 reader-web 那份，症状是「换端之后读到的进度是错的」
 * 或者更糟：「移动端的进度被桌面端当成损坏数据清掉」。
 *
 * ★ 单位是【像素偏移 scrollTop】，不是百分比。两侧都是。
 *   桌面端用 scrollTop，移动端用 scroll-view 的 scroll-top ——
 *   两者数值上不等价（视口高度、行高都可能不同），
 *   但**这不影响共用**：它只是一个"尽量回到附近"的提示值，
 *   落到哪一行由各端自己的滚动容器决定。真正需要跨端一致的是
 *   「哪一章」—— 那是 chapterId，两边严格相同。
 *
 * ★ 进度【双写服务端】的分工（P3 决定 1）在这里同样成立：
 *   本地存章节 + 像素偏移，服务端存百分比。恢复时优先用本地的，
 *   本地对不上章节才退回服务端的百分比。这部分逻辑在阅读器页面里，
 *   本文件只负责本地那一半。
 */

import { getRaw, setRaw, removeRaw, allKeys } from './storage'

/** 进度 key 前缀。后面拼 novelId，一本书一个 key。★ 与桌面端逐字相同 —— 这是共用的一半 */
const PROGRESS_PREFIX = 'novel_reader_progress_'

/**
 * 阅读设置（字号 / 行距 / 背景）的 key。
 *
 * ★★★ 注意结尾的 `_mobile` —— 这是与桌面端【唯一】的一处刻意的 key 分歧，
 *    也是判断 3 的全部内容。改动前先回去读文件头那张表。
 */
export const SETTINGS_KEY = 'novel_reader_settings_mobile'

/**
 * 本地最多保留多少本书的进度。
 *
 * ★ 为什么要有上限：一本书一个 key，一个重度读者用一年能攒出几百上千个。
 *   配额撑满不现实（单条才 100 字节左右），真正的问题是
 *   【清理策略缺失时它会一直涨，而没有任何人会发现】。
 *
 * ★★ 这里和桌面端共用的是【同一批 key】，但【各自独立地淘汰】——
 *    两端的淘汰互不知情，各自按自己看到的时间戳排序。
 *    这不是 bug：淘汰本来就是"本设备最近读过的书"，
 *    而 updatedAt 是共用的，所以效果是"两边一起把最旧的挤掉"，
 *    恰好也是想要的行为。
 */
const PROGRESS_MAX_ENTRIES = 200

/* ==================== 通用读写 ==================== */

/**
 * 读一个 JSON 值。任何异常都当作「没有」。
 *
 * ★ 必须 try-catch：存储里可能是上一个版本的程序写的旧格式，
 *   也可能被用户手动改坏，还可能是【另一端】写的别的东西。
 *   JSON.parse 一抛，如果调用方在滚动回调里，整个阅读器就白屏了，
 *   而报错栈完全指不到是存储的问题。
 */
function readJson(key) {
  const raw = getRaw(key)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    // 坏了就删掉，下次当作没有 —— 比每次读都抛好
    removeRaw(key)
    return null
  }
}

/**
 * 写一个 JSON 值。
 *
 * ★ writeJson 一定先序列化再交给 setRaw —— 这是存储协议的要求，
 *   见 storage.js。这里不直接调 uni.setStorageSync。
 *
 * ★ 写失败返回 false 而不抛：进度上报是在滚动回调里调的，
 *   那里抛出去没人接，读者会看到「滚到一半页面就不动了」。
 *   写失败就静默放弃：进度记不住的代价，远小于阅读器崩掉。
 */
function writeJson(key, value) {
  try {
    return setRaw(key, JSON.stringify(value))
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
 *
 * ★ 返回前做一次【字段校验】而不是直接信任 JSON：
 *   一个 chapterId 是 undefined 的记录，会让阅读器跳到一个不存在的章节，
 *   而症状是「打开就报章节不存在」—— 读者完全不知道是自己浏览器里的旧数据
 *   在作怪。校验不过就当没有，让他从头读，代价最小。
 *
 * ★★ 这一侧的校验在移动端还有第二个作用：存储是两个端共写的，
 *    校验是移动端唯一能挡住"桌面端某个版本写歪了"的地方。
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
 * ★★ 写之前先校验 chapterId，不合法就【整个放弃这次写入】。
 *    读的一侧已经在校验了，如果写的一侧不校验，一个坏值就会
 *    【覆盖掉本来好好的记录】—— 下次打开连正确的旧位置也读不回来。
 *
 * ★★★ 而在移动端，"坏值"有一个比桌面端更常见的来源，必须点名：
 *    **scroll-view 的 scroll 事件对象**。
 *    `@scroll="onScroll"` 传进来的是 `{detail:{scrollTop, scrollLeft}}`，
 *    而 `@scrolltoupper` 之类的回调参数形状又不同。
 *    把这样一个对象当成 offset 传进来，Number(obj) 就是 NaN。
 *    所以调用方要传 `e.detail.scrollTop`，本函数的校验是最后一道闸。
 *    （桌面端踩过的同族事故：把带形参的函数直接注册成事件监听器，
 *      浏览器塞一个 Event 当第一个实参，Number(Event) === NaN，
 *      表现是「刷新之后进度永远回到开头」。见 reader-web 那份的注释。）
 *
 * ★ 不做防倒退：读者主动往回翻，他的当前位置就是前面那一章。
 */
export function saveProgress(novelId, chapterId, offset) {
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
 * ★ 不要为了「清理」而在退出登录时调它：进度是【设备】维度的，
 *   退出登录不该把本机记的东西抹掉，否则用户下次登录发现进度没了。
 */
export function clearProgress(novelId) {
  removeRaw(progressKey(novelId))
}

/**
 * 淘汰超出上限的进度记录，保留最近读的 {@link PROGRESS_MAX_ENTRIES} 本。
 *
 * ★ 遍历整个存储是有成本的，所以【不要每次滚动都调它】——
 *   它只在 saveProgress 之后跑一次，而 saveProgress 已被上层做了 1 秒防抖。
 *   两层叠加后，这个函数每秒最多跑一次，且绝大多数时候一进循环就发现没超限。
 *
 * ★ 这里用 storage.js 的 allKeys()（uni.getStorageInfoSync）而不是
 *   `Object.keys(localStorage)` —— 后者在小程序里根本不存在。
 */
function pruneProgress() {
  const entries = []

  for (const key of allKeys()) {
    if (!key.startsWith(PROGRESS_PREFIX)) continue

    const record = readJson(key)
    // 顺手把损坏的记录清掉：它永远读不出来，留着只是占地方
    if (!record || typeof record !== 'object') {
      removeRaw(key)
      continue
    }
    entries.push({ key, updatedAt: Number(record.updatedAt) || 0 })
  }

  if (entries.length <= PROGRESS_MAX_ENTRIES) return

  // 最旧的排前面，删掉多出来的那些
  entries.sort((a, b) => a.updatedAt - b.updatedAt)
  for (const item of entries.slice(0, entries.length - PROGRESS_MAX_ENTRIES)) {
    removeRaw(item.key)
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
 *
 * ★★ 这三张表 [与桌面端逐字相同]，别各调各的 ——
 *    它们会被写进同一个字段名（fontSize / lineHeight / theme），
 *    只是存在不同的 key 下。表不一样的话，用户换端之后
 *    那个 key 会落在"不认识的档位"上，被 loadSettings 回退成默认值，
 *    看起来就是「我明明设的是大号，换端变回中号了」。
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
 *   这三个设置是独立的，用户调好了字号和背景、只有行距那一项
 *   因为版本升级改了 key，没有理由把他的字号和背景一起重置掉。
 *
 * ★ 这个函数【永远返回一个完整的设置对象】，不会返回 null。
 *   调用方可以直接拿来当初值，不需要再判空 —— 返回 null 的话，
 *   每个用到它的地方都得写一遍 `?? DEFAULT_SETTINGS`，
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
