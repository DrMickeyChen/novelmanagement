/**
 * 个人资料接口 —— 对应后端的 ReaderProfileController（`/api/reader/profile`）。
 *
 * ★★ 三个接口都【需要登录】。和 api/reader.js / api/shelf.js 同一个前缀族
 *   （`/api/reader/**` 是「会落用户数据的接口」的约定前缀），
 *   单独一个文件只是因为「个人资料」自成一块。
 *
 * ------------------------------------------------------------
 * ★★ 这里【没有】`updateAvatar(url)` 这样的函数，而且不打算加
 * ------------------------------------------------------------
 * 「先上传拿到 URL、再调一次接口把 URL 存起来」看起来更对称，
 * 但那样第二步就得把一个**调用方给的 URL 字符串**写进数据库 ——
 * 而服务端对那个字符串再也做不了任何校验（魔数校验发生在第一步）。
 * `uploadAvatar(file)` 一步做完「校验 → 落盘 → 写库」，
 * 进库的 URL 永远是后端自己生成的那个。
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

/**
 * 上传头像 —— 上传即生效，返回值里已经带着新的 `avatar`。
 *
 * ★ 用 FormData 时**不要**手动设 Content-Type：浏览器要自己往里面加
 *   `boundary=...`，手写成 'multipart/form-data' 会让 boundary 丢掉，
 *   后端解析不出任何字段（症状是「文件是 null」，不是一条明确的报错）。
 *   （同 author-web/src/api/upload.js 里那条。）
 *
 * ★ 校验全在后端（扩展名白名单 / Content-Type / **文件头魔数**），
 *   前端只做「大小和格式的即时提示」，真正的拒绝永远在服务端。
 *   头像比封面多一条 **2MB** 的上限（`ImageKind.AVATAR`）。
 *
 * @param {File} file
 * @returns {Promise<Object>} 换完头像之后的完整资料
 */
export function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/reader/profile/avatar', form)
}
