/*
 * 移动端「跨端约束」的静态自检。
 *
 * ============================================================================
 * ★★★ 这个脚本存在的理由，比它检查的规则更重要
 * ============================================================================
 *
 * 本机**没有微信开发者工具、没有 appid**，所以「小程序端能跑」这句话
 * 在本项目里是**不可验收**的。E3 交付的只有 H5。
 *
 * 于是「跨端」这件事只剩一种诚实的说法：**源码遵守了跨端约束**。
 * 而那是一个**静态判据** —— 判据一旦是静态的，就必然踩铁律 8：
 * **一条不会失败的护栏比没有护栏更糟。**
 *
 * 所以这个脚本的每一组检查都**自带阳性对照**：
 * 它会先把一段人造的违规代码喂给同一套检查逻辑，断言**检查确实报了错**，
 * 然后才去扫真实源码。对照跑不过，脚本整体就算失败 ——
 * 因为那意味着底下的判据压根没在工作（`grep` 写错了、正则漏了、扫错目录了），
 * 而「没报错」在这种情况下的含义是**零**。
 *
 * ★ 特别注意「反面对照」那一组：它故意喂进去**不该被报**的东西
 *   （`<video>` / `<scroll-view>` / 注释里的 `<div>`），
 *   用来证明这个判据不是「见到尖括号就报」——**会误报的判据和不会报的判据一样没用**，
 *   只是错的方向相反。
 *
 * 用法：
 *   node docs/tools/check-mobile-crossplatform.cjs
 *   node docs/tools/check-mobile-crossplatform.cjs --list   # 额外打印标签清单
 *
 * 退出码 0 = 全部通过（含阳性对照）。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..', '..')
const MOBILE = path.join(ROOT, 'mobile-web')
const SRC = path.join(MOBILE, 'src')

/* ==================== 判据本体 ==================== */

/**
 * 判据 A：模板里不许出现这六个 HTML 标签。
 *
 * ★ 为什么是这六个：它们是**桌面端最常用、而 uni-app 各端都不认**的那一批。
 *   `div/span/p/ul/li` 在小程序里没有对应组件（要用 `view`/`text`），
 *   `img` 要用 `image`。写错了在 H5 上**照样能跑**（H5 跑在浏览器里，
 *   浏览器认识 div）—— 所以这个错误**只在换端时暴露**，
 *   而本项目换端时没有工具链可验 → 只能在这里拦。
 */
const BANNED_TAGS = ['div', 'span', 'img', 'p', 'ul', 'li']

/**
 * 判据 B：不许依赖这三个包。
 *
 * ★ `axios` 在小程序里没有 XMLHttpRequest；`vue-router` 的路由表是本地的，
 *   而 uni-app 的路由表来自 `pages.json`（两套路由不能共存）；
 *   `element-plus` 是 DOM 组件库，小程序里没有 DOM。
 * ★ 判据要看 `package.json` **和** `src/` 两处：只查依赖清单的话，
 *   有人直接 `import 'axios'` 而它恰好是某个依赖的传递依赖时就漏了。
 */
const BANNED_PACKAGES = ['axios', 'vue-router', 'element-plus']

/**
 * 取出 `.vue` 里某个**顶层块**的内容（`template` / `script`）。
 *
 * ★★ 为什么要按块取，而不是整文件扫：整文件扫的判据会因为**无关的内容**忽真忽假，
 *   最后没人再信它。具体到本仓库有两条硬理由：
 *
 *   ① 注释密度高（这是本项目的既定风格，不是意外）。`src/api/profile.js` 里就有一句
 *      「桌面端是 `FormData` + `File`，走 axios」—— 那是在解释**为什么这里不用它**，
 *      而整文件 grep 会把它读成「这里用了 axios」。★ 实测：第一版判据报出来的
 *      **6 处命中全是注释**，真依赖清单一个都没有。
 *      这与 M5 记的 `grep -rn "novel-content"` 是**同一个形状**（铁律 8 的第三种形状）：
 *      **判据被自己的说明文字推翻，且每写一次说明就离通过更远一步。**
 *   ② 模板里有中文文本和 HTML，`'` `/` 这些字符会让朴素的字符串/注释剥离跑偏。
 *
 * ★ 起点必须**顶格**（行首就是 `<`）：本项目连 `App.vue` 的注释里都写着
 *   「唯一【不需要 <template>】的组件」—— 用 `indexOf('<template>')` 会命中那句注释，
 *   于是 `App.vue` 被当成"有模板块"，扫出来的第一项是 `style`（把 `<style>` 也算成了标签）。
 *   这个假阳性不致命，但它说明判据正被自己的说明文字污染。
 */
function extractBlock(source, block) {
  const openRe = new RegExp(`^<${block}(\\s[^>]*)?>`, 'm')
  const m = openRe.exec(source)
  if (!m) return null

  // ★ 从开标签起数同名的嵌套：`<template v-if>` 是合法且常用的写法，
  //   简单地找第一个 `</template>` 会在那种文件上**截断**，
  //   漏掉的后半段就是一段**永远不被检查**的代码（= 假通过）。
  const re = new RegExp(`<${block}(\\s[^>]*)?>|</${block}>`, 'g')
  re.lastIndex = m.index
  let depth = 0
  let hit
  while ((hit = re.exec(source)) !== null) {
    depth += hit[0].startsWith('</') ? -1 : 1
    if (depth === 0) return source.slice(m.index, hit.index + hit[0].length)
  }
  return source.slice(m.index)
}

const extractTemplate = (source) => extractBlock(source, 'template')
const extractScript = (source) => extractBlock(source, 'script')

/**
 * 剥掉 JS 注释（`//` 与 `/* *​/`），**保留字符串字面量**。
 *
 * ★ 为什么必须保留字符串：`import axios from 'axios'` 里的包名就在字符串里，
 *   把字符串一起剥掉的话，这条判据会**漏掉真正的违规** —— 那就成了
 *   铁律 8 最反对的那种「不会失败的护栏」。
 *   所以这是个状态机（字符串 / 注释 / 模板串三态），不是两条正则。
 *
 * ★ 已知的边界：正则字面量（`/\d+/`）不做特殊处理。
 *   一个以星号开头的正则（`/ *` 紧跟着内容，即"斜杠 星号"开头的正则）
 *   会被误判成块注释开头 —— 本项目源码里没有这种写法，
 *   真出现了也是"少扫一点"，不会造成误报。**边界写在这里，而不是假装没有。**
 *
 * ★★ 写这段注释本身踩了一个坑，记下来因为它太典型了：
 *    这一行原本是**逐字写出那个正则**的，而那个正则的字面量里必然含
 *    "星号 + 斜杠" 两个字符 —— 于是**注释在它自己解释"什么会提前结束注释"的那一句上提前结束了**，
 *    后面几行注释全变成了代码，`node` 报的错在 20 行之外的一个反引号上。
 *    **一句话：这段文档把这段代码写坏了。** 与本文件判据 B 踩的是同一个形状 ——
 *    文字与代码混在同一个解析上下文里时，文字会参与解析。
 *    （同类事故在本项目已经出过两次：`{{ '{{x}}' }}` 让 `.vue` 编译不过、
 *      M5 那条被自己的说明文字推翻的 grep。）
 */
function stripJsComments(src) {
  let out = ''
  let i = 0
  while (i < src.length) {
    const c = src[i]
    const d = src[i + 1]
    if (c === '/' && d === '/') {
      while (i < src.length && src[i] !== '\n') i++
      continue
    }
    if (c === '/' && d === '*') {
      i += 2
      while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) i++
      i += 2
      continue
    }
    if (c === '"' || c === "'" || c === '`') {
      out += c
      i++
      while (i < src.length) {
        if (src[i] === '\\') {
          out += src[i] + (src[i + 1] || '')
          i += 2
          continue
        }
        out += src[i]
        if (src[i] === c) {
          i++
          break
        }
        i++
      }
      continue
    }
    out += c
    i++
  }
  return out
}

/**
 * 去掉 HTML 注释。
 *
 * ★ 注释里的 `<div>` 是**允许的**：本项目注释密度很高，
 *   注释里经常要举反例（"别写 `<div>`"）。把注释也算违规的话，
 *   这条判据会逼着人**不敢在注释里举例** —— 那是在惩罚好习惯。
 *   ★ 反面对照里专门有一条喂注释进去，确保这不是嘴上说说。
 */
const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, '')

/** 取出模板里出现的所有标签名（小写、去重、计数） */
function tagInventory(templateHtml) {
  const html = stripComments(templateHtml)
  const counts = new Map()
  const re = /<([a-zA-Z][a-zA-Z0-9-]*)/g
  let m
  while ((m = re.exec(html)) !== null) {
    const name = m[1].toLowerCase()
    counts.set(name, (counts.get(name) || 0) + 1)
  }
  return counts
}

/** 递归列出目录下所有匹配的文件 */
function walk(dir, filter, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, filter, acc)
    else if (filter(entry.name)) acc.push(full)
  }
  return acc
}

/** 相对仓库根的短路径，报错时更好读 */
const rel = (p) => path.relative(ROOT, p).replace(/\\/g, '/')

/* ==================== 检查逻辑（对「一份文件清单」通用） ==================== */

/**
 * ★ 把检查写成「吃一份 {路径: 内容} 的映射」的纯函数，
 *   是为了让阳性对照能喂**人造内容**进来跑同一套逻辑。
 *   如果检查直接读磁盘，对照就只能靠"临时改真文件再改回来"——
 *   那种做法一旦中途失败就会把仓库留在脏状态。
 */
function checkTemplates(files) {
  const hits = []
  const inventory = new Map()
  for (const [name, source] of Object.entries(files)) {
    const tpl = extractTemplate(source)
    if (tpl === null) continue
    const counts = tagInventory(tpl)
    for (const [tag, n] of counts) {
      inventory.set(tag, (inventory.get(tag) || 0) + n)
      if (BANNED_TAGS.includes(tag)) hits.push({ file: name, tag, count: n })
    }
  }
  return { hits, inventory }
}

function checkPackages(files) {
  const hits = []
  for (const [name, source] of Object.entries(files)) {
    // ★ `.vue` 只看 `<script>` 块；`.js` / `.json` 看整个文件。
    //   理由见 extractBlock 的注释 ②：模板和样式里出现这些词都不是"依赖"。
    const body = name.endsWith('.vue') ? extractScript(source) : source
    if (body === null) continue
    const code = stripJsComments(body)
    for (const pkg of BANNED_PACKAGES) {
      // ★ 词边界：裸 `includes('axios')` 会把 `myAxiosWrapper` 也算成违规。
      //   会误报的判据和不会报的判据一样没用，只是错的方向相反。
      const re = new RegExp(`(^|[^a-zA-Z0-9_$/-])${pkg.replace(/-/g, '\\-')}([^a-zA-Z0-9_$-]|$)`)
      if (re.test(code)) {
        const line = code.split(/\r?\n/).findIndex((l) => re.test(l)) + 1
        hits.push({ file: name, pkg, line })
      }
    }
  }
  return { hits }
}

/* ==================== 阳性对照 + 反面对照 ==================== */

/**
 * ★★ 阳性对照：喂进去**必定违规**的东西，断言判据能发现。
 *    跑不过就说明判据是死的 —— 那比没有判据更糟，因为它会让人以为验过了。
 */
function selfTest() {
  const problems = []

  // 判据 A 的阳性对照：六个标签各来一个，另加一个自闭合的 `<img/>`
  const bad = { '人造-违规.vue': '<template><div><span>x</span><img src="a"><p>y</p><ul><li>z</li></ul></div></template>' }
  const a = checkTemplates(bad)
  const found = new Set(a.hits.map((h) => h.tag))
  for (const t of BANNED_TAGS) {
    if (!found.has(t)) problems.push(`阳性对照失效：模板判据没发现 <${t}>`)
  }

  // 判据 A 的反面对照：这些**都不该**被报
  const good = {
    '人造-合规.vue': [
      '<template>',
      '<view class="a"><text>b</text><image src="c" /><scroll-view><video /></scroll-view></view>',
      '<!-- 注释里写 <div> 是允许的：本项目要在注释里举反例 -->',
      '</template>',
      '<script>',
      "// 这一段里出现 div / span / img / axios 都【不该】被模板判据报出来",
      "const s = 'div span img'",
      '</script>',
    ].join('\n'),
  }
  const b = checkTemplates(good)
  if (b.hits.length) {
    problems.push('反面对照失效：合规内容被误报 → ' + JSON.stringify(b.hits))
  }
  // ★ 顺带断言"嵌套 template 不会被截断"——这是 extractTemplate 里最容易写错的地方
  const nested = { '人造-嵌套.vue': '<template><view><template v-if="x"><span>s</span></template></view></template>' }
  if (!checkTemplates(nested).hits.some((h) => h.tag === 'span')) {
    problems.push('嵌套 <template> 的分支失效：后半段被截断，span 没被发现（= 假通过）')
  }
  // ★ 另一条反面对照，**来自真实事故**：注释里写着 `<template>` 的文件
  //   （本项目 `App.vue` 的注释就有）不该被当成"有模板块"。
  //   第一版判据在这里栽了：它把 `App.vue` 的 `<style>` 也数进了标签清单。
  const commentMentionsTemplate = {
    '人造-注释提到template.vue': [
      '<script setup>',
      '/* App.vue 是唯一【不需要 <template>】的组件 */',
      'const a = 1',
      '</script>',
    ].join('\n'),
  }
  const cm = checkTemplates(commentMentionsTemplate)
  if (cm.hits.length || cm.inventory.size) {
    problems.push('反面对照失效：注释里的 <template> 被当成了模板块 → ' + JSON.stringify([...cm.inventory]))
  }

  // 判据 B 的阳性对照
  const pkgBad = { '人造-依赖.json': '{"dependencies":{"axios":"^1.0.0"}}' }
  if (!checkPackages(pkgBad).hits.some((h) => h.pkg === 'axios')) {
    problems.push('阳性对照失效：依赖判据没发现 axios')
  }
  // ★★ 判据 B 的**关键**反面对照：注释里提到包名不该被报。
  //    这一条不是假想的 —— 第一版判据在真实源码上报出来的 6 处**全是注释**，
  //    包括「桌面端走 axios」这种**在解释为什么这里不用它**的句子。
  //    没有这条对照，剥注释的代码删掉了都不会有人发现。
  const commentOnly = {
    '人造-只提不用.js': [
      '// 桌面端是 FormData + File，走 axios',
      '/* 这一条对应 vue-router 的 catch-all */',
      "import { ref } from 'vue'",
      '// element-plus 是 DOM 组件库，小程序里没有 DOM',
    ].join('\n'),
  }
  if (checkPackages(commentOnly).hits.length) {
    problems.push('反面对照失效：注释里提到包名被当成依赖 → ' + JSON.stringify(checkPackages(commentOnly).hits))
  }
  // ★ 但**字符串里的包名必须照样被抓**（注释剥掉了、字符串不能剥）——
  //   这是上一条对照的"阳性配对"：只证明"不误报"是不够的。
  const stringOnly = { '人造-真引入.js': "import axios from 'axios'\n" }
  if (!checkPackages(stringOnly).hits.some((h) => h.pkg === 'axios')) {
    problems.push('阳性对照失效：真 import 的包名没被发现（字符串被一起剥掉了？）')
  }
  // 判据 B 的另一条反面对照：同前缀标识符不该被报
  const pkgGood = { '人造-无关.json': '{"dependencies":{"myAxiosWrapper":"1.0"}}' }
  if (checkPackages(pkgGood).hits.length) {
    problems.push('反面对照失效：无关标识符被当成 axios → ' + JSON.stringify(checkPackages(pkgGood).hits))
  }
  // ★ 判据 B 只该扫 `<script>`：模板里提到包名（比如组件名里带 `router`）不算依赖
  const pkgInTemplate = { '人造-模板提及.vue': '<template><view>axios</view></template>\n<script>const a = 1</script>' }
  if (checkPackages(pkgInTemplate).hits.length) {
    problems.push('反面对照失效：模板文本里的包名被当成依赖 → ' + JSON.stringify(checkPackages(pkgInTemplate).hits))
  }

  return problems
}

/* ==================== 主流程 ==================== */

const problems = selfTest()

const vueFiles = walk(SRC, (n) => n.endsWith('.vue'))
const srcFiles = walk(SRC, (n) => /\.(js|vue|json)$/.test(n))

const readMap = (paths) => {
  const map = {}
  for (const p of paths) map[rel(p)] = fs.readFileSync(p, 'utf8')
  return map
}

// 判据 A 跑在真实源码上
const tpl = checkTemplates(readMap(vueFiles))
// 判据 B 跑在「src 全部 js/vue/json」+ `package.json`（含依赖清单那一份）
const pkgMap = readMap(srcFiles)
pkgMap['mobile-web/package.json'] = fs.readFileSync(path.join(MOBILE, 'package.json'), 'utf8')
const pkg = checkPackages(pkgMap)

if (tpl.hits.length) problems.push('模板里出现禁用标签：' + JSON.stringify(tpl.hits))
if (pkg.hits.length) problems.push('出现禁用依赖：' + JSON.stringify(pkg.hits))

/* ---- 记账项（不算失败，但要看得见）：`#ifdef` 与裸的 window/document ---- */

const conditional = []
const domAccess = []
for (const [name, source] of Object.entries(pkgMap)) {
  source.split(/\r?\n/).forEach((line, i) => {
    if (/#ifdef|#ifndef|#endif/.test(line)) conditional.push(`${name}:${i + 1}  ${line.trim()}`)
    // ★ 这里**只列不判**：`window` 出现在 `typeof window !== 'undefined'` 或
    //   `#ifdef H5` 里都是对的，而"附近有没有守卫"用正则判不准。
    //   列出来给人看，比编一条会误报的规则诚实。
    if (/\b(window|document)\s*\./.test(line)) domAccess.push(`${name}:${i + 1}  ${line.trim()}`)
  })
}

const report = {
  判据A_禁用标签命中数: tpl.hits.length,
  判据B_禁用依赖命中数: pkg.hits.length,
  阳性对照与反面对照: problems.length ? '失败' : '通过',
  '失败项': problems,
}

if (process.argv.includes('--list')) {
  report['模板标签清单'] = Object.fromEntries([...tpl.inventory].sort((a, b) => b[1] - a[1]))
  report['条件编译记账'] = conditional
  report['window/document 出现处（只列不判）'] = domAccess
}

console.log(JSON.stringify(report, null, 2))
process.exit(problems.length ? 1 : 0)
