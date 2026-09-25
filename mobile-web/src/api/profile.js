/**
 * 个人资料接口 —— 对应后端的 ReaderProfileController（`/api/reader/profile`）。
 *
 * ★ 路径与 reader-web/src/api/profile.js 逐字相同。
 *
 * ------------------------------------------------------------
 * ★★ 头像上传这一条【换了实现】，是本模块与桌面端唯一不同的地方
 * ------------------------------------------------------------
 * 桌面端是 `FormData` + `File`（浏览器给的对象），走 axios。
 * 移动端没有 `File`，也没有 `<input type="file">` —— 拿到本地图片的唯一方式是
 * `uni.chooseImage`（相册/拍照），上传的唯一方式是 `uni.uploadFile`。
 *
 * 所以 `uploadAvatar` 在移动端**不在这个文件里**，它落在
 * `pages/profile/profile.vue` 里直接调 `uni.uploadFile` ——
 * 因为它需要 `request.js` 之外的一条通道（见那个页面里的完整说明）。
 *
 * ★★ 但有一条约定两端必须一致：**上传即生效，没有第二步**。
 *    桌面端那份注释里讲了为什么不做 `updateAvatar(url)`：
 *    「先上传拿到 URL、再调一次接口把 URL 存起来」会让一个
 *    **调用方给的 URL 字符串**进数据库，而服务端对它再也做不了校验。
 *    `uploadAvatar` 一步做完「校验 → 落盘 → 写库」，进库的 URL
 *    永远是后端自己生成的那个。移动端照这个约定做，
 *    **不要**为了"复用 request.js"而拆成两步。
 */
import request from '@/utils/request'

/**
 * 查我的资料。
 *
 * @returns {Promise<{id, username, nickname, avatar, email}>}
 *   ★ `avatar` 没传过时**这个 key 直接不存在**（后端 `non_null` 序列化），
 *   取值写 `res.avatar || ''`，**不要写 `res.avatar === null`**。
 */
export function getProfile() {
  return request.get('/reader/profile')
}

/**
 * 改昵称。
 *
 * ★ **它就是评论区的显示名**：改完之后，书评 / 章评列表里所有属于我的评论
 *   下一次请求就会显示新昵称（昵称是查询时按 userId 现查的，没有快照）。
 *
 * @param {string} nickname 1~20 字；前后空格后端会去掉
 * @returns {Promise<Object>} 改完之后的完整资料
 */
export function updateNickname(nickname) {
  return request.put('/reader/profile', { nickname })
}
