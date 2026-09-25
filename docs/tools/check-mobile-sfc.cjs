/*
 * 批量 SFC 编译校验 —— 移动端（mobile-web）的 `.vue` 全部解析一遍。
 *
 * ★★★ 为什么必须有这一步（E1 判据 7 的由来）：E1 的事故是 `/ai` 页面
 *   **从未编译通过**，而**任何日志里都没有** ——
 *   因为 `{{ '{{x}}' }}` 让模板编译器在找插值结束符时提前收尾
 *   （模板编译器**不认识字符串字面量**），整个 `.vue` 编译失败，
 *   而**全走 HTTP 的验收可以完全绕开页面本体**：接口全通、页面不在。
 *
 * ★★ 所以判据必须钉在**编译产物**上，不能钉在 HTTP 上。
 *   这里直接调 vue 自己的编译器（`@vue/compiler-sfc`），把每个 `.vue` 过一遍。
 *
 * ★★★ 阳性对照（铁律 8）：脚本内置一个【已知会失败】的样本 ——
 *   就是 E1 那个真实形状。它**必须被报出来**，否则这个脚本
 *   只是在打印「一切正常」，而不是在检查什么。
 *   → 没有这条对照时，`失败: []` 有两种含义：「都编得过」和「根本没检查」。
 *
 * 用法（在仓库根目录）：
 *   node docs/tools/check-mobile-sfc.cjs
 * 退出码 0 = 全部通过且阳性对照被检出；非 0 = 有文件编不过 或 对照没被检出。
 */
const fs = require('fs')
const path = require('path')

// 仓库根 = 本文件的上两级（docs/tools/ → 仓库根）
const ROOT = path.resolve(__dirname, '..', '..')
const SRC = path.join(ROOT, 'mobile-web', 'src')

// ★ 用 mobile-web 自己的编译器（版本被 uni 钉在 3.4.21）——
//   拿三端的 compiler-sfc 来验会验错对象：模板编译用的编译器
//   与运行时的版本不是同一版时，结论没有意义。
const SFC = require(path.join(ROOT, 'mobile-web', 'node_modules', '@vue', 'compiler-sfc'))
const { parse, compileTemplate } = SFC

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
  return out
}

/** 解析一个 .vue 源码，返回错误数组（空 = 通过） */
function check(source, filename) {
  const { descriptor, errors } = parse(source, { filename })
  const errs = errors.map((e) => `[SFC] ${e.message}`)

  if (descriptor.template) {
    const r = compileTemplate({
      source: descriptor.template.content,
      filename,
      id: filename,
    })
    for (const e of r.errors || []) errs.push(`[template] ${e.message || e}`)
  }
  return errs
}

/* ---------- 阳性对照：E1 那个真实形状，必须被报出来 ---------- */
const 阳性对照源码 = `<template>\n  <view>提示：{{ '{{x}}' }} 是占位符</view>\n</template>\n`
const 阳性对照错误 = check(阳性对照源码, 'positive-control.vue')

/* ---------- 真文件 ---------- */
const files = walk(SRC)
const 失败 = []
for (const f of files) {
  const errs = check(fs.readFileSync(f, 'utf8'), f)
  if (errs.length) 失败.push({ 文件: path.relative(SRC, f), 错误: errs })
}

console.log(
  JSON.stringify(
    {
      vue文件数: files.length,
      通过: files.length - 失败.length,
      失败,
      阳性对照被检出: 阳性对照错误.length > 0,
      阳性对照错误原文: 阳性对照错误.slice(0, 1),
    },
    null,
    2,
  ),
)

// ★ 阳性对照没被检出 = 这个脚本什么都没验 → 必须以失败退出
process.exit(阳性对照错误.length > 0 && 失败.length === 0 ? 0 : 1)
