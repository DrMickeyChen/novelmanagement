# mobile-web · 移动端读者端（uni-app）

## 这个目录是什么

第三期 E3 的产物：一个 **uni-app（Vue3 + Vite CLI）** 工程，只做**读者端**。
它与三端（`reader-web` / `author-web` / `admin-web`）平级，但**不是同一套工具链** —— 见下。

## ★★ 为什么这里的版本号全是精确版本、一个 `^` 都没有

`package.json` 里 `@dcloudio/*` / `vue` / `vite` / `sass` **全部写死**，这不是洁癖，是三个具体原因：

1. **`@dcloudio/*` 的 `latest` tag 指向 Vue2 线。**
   实测 `npm view @dcloudio/uni-app dist-tags`：
   `latest = 2.0.2-…`、`vue3 = 3.0.0-alpha-…`。
   所以 `npm i @dcloudio/uni-app`（不带版本）**装到的是 Vue2 版**，报错与「版本」二字毫无关系。
2. **整条 Vue3 线是 alpha 通道。** `3.0.0-alpha-*` 每天都在发。
   写 `^3.0.0-alpha-5020620260914001` 会让 `npm i` 随时把上游某个未审阅的 alpha 拉进来 ——
   本项目**只在 `npm install` 那一刻**决定装了哪个版本，之后必须是确定的。
   本工程取的是**非 alpha 线**的最新版 `3.0.0-5020620260917001`（2026-09-17），
   三个 `@dcloudio` 包在**同一条线**上，版本号逐字相同 —— 混线会直接崩。
3. **`vite` 是 `peerDependencies` 里精确写的 `5.2.8`**（不是 range），
   且 `@dcloudio/vite-plugin-uni` 的 `dependencies` 里 `@vue/compiler-*` 精确锁 `3.4.21`
   → 运行时 `vue` 也必须是 `3.4.21`，否则「模板编译用的编译器」与「跑起来的运行时」不是同一版。

## ★★ 与三端的版本差：这里比它们老一个版本，**别去统一**

| | 三端（reader/author/admin-web） | 本工程（mobile-web） |
|---|---|---|
| vue | `^3.5.42` | **`3.4.21`** |
| vite | `^8.3.0` | **`5.2.8`** |
| 路由 | `vue-router` | **`pages.json`**（uni-app 没有 vue-router） |
| HTTP | `axios` | **`uni.request`** |
| UI | `element-plus` | **没有 UI 库** —— uni 内置组件 + 手写 |

**这是被 uni-app 的 `peerDependencies` 钉死的，不是疏忽。**
两端能并存，靠的是「每个子项目各有自己的 `node_modules`」——
根 `package.json` **没有 `workspaces`**，用的是 `npm --prefix`。
所以：**改本工程的版本不会影响三端，改三端的版本也救不了本工程。**
★ 看到 `vue 3.4.21` 时的第一反应**不该**是「升上去对齐三端」——
升到 3.5 会让 `@vue/compiler-sfc@3.4.21` 编不出运行时的模板。

## ★ 为什么没有 UI 库（这一格原本写的是 `wot-design-uni`，是错的）

S1 的计划是「装 `wot-design-uni` 跑通一个 `wd-button`，装不上就退 `uni-ui`」。
实际结果是**两个都没装** —— 界面全部用 uni 的**内置组件**（`view` / `text` / `image` /
`scroll-view` / `input` / `textarea`）加手写样式搭出来，`pages.json` 里**没有 `easycom` 段**，
`src/` 里 `wd-*` 组件用量为 **0**。

原因不是装不上，而是走到 S3 发现**读者端需要的控件本来就少**（列表、卡片、按钮、弹层、
分页触底），而 uni 内置组件在这几个上够用；引入第三方 UI 库反而要处理
「它的样式体系与本工程的 rpx 布局打架」这件事。

★ 这一格**曾经是假的**（写着 `wot-design-uni`，而 `package.json` 里根本没有它）——
和本项目已记过的另一条同源：**写在文档里、读起来像已经确认过的事实，是一个从未被验证的断言。**
判据是结构式的：`grep -o 'wd-[a-z]' src/**` 命中 0 且 `package.json` 里没有该依赖。

## ★ 代价：Vue 3.5 的写法在这里不能用

`useTemplateRef`、`defineModel` 的 3.4 之后那部分、props 解构的响应式糖……都不能用。
`<script setup>` 按 **3.4 的语法**写。

## 跑起来

```bash
npm run dev            # = uni（H5 开发服务器，:5176）
npm run dev:mp-weixin  # 小程序构建产物（★ E3 收尾已在微信开发者工具里实跑通过，见 docs/E3-uni-app.md 诚实 3）
```

★ **访问入口是 `http://localhost:3000/mobile/`，不是 `:5176`。**
原因见 `dev-proxy/vite.config.js` 头部：localStorage 按 origin 隔离，
`/reader/` 登录的状态要在 `/mobile/` 读得到，就必须同源。

★★ E3 收尾给本工程的 `vite.config.js` 也加了 `/api` + `/uploads` 转发
（为 HBuilderX「运行到浏览器」—— 它直接起这个 dev server、不经过 `:3000`）。
**于是 `:5176` 直开时接口也是通的** —— 但这**恰恰让上面那条更容易踩**：
现在只坏「登录态共享」那一半，**且没有任何提示**（症状是"接口全通、人却是未登录"）。
**入口判据：演示前看一眼地址栏是不是 `:3000`。**
