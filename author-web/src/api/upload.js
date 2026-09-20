/**
 * 上传接口 —— 对应后端 AuthorUploadController。
 *
 * ★ 目前只有封面一个。头像（P6）、章节导入（E1）将来也走这个文件。
 */
import request from '@/utils/request'

/**
 * 上传封面。
 *
 * @param {File} file
 * @returns {Promise<string>} 可直接放进 `<img :src>` 的 URL，
 *   形如 `/uploads/cover/2026/09/20/3f2a....png`
 *
 * ★★ 三个由后端负责、前端**不要重复做**的判断（前端做了也不作数）：
 *   ① 扩展名白名单 —— 扩展名是客户端给的字符串，伪造零成本
 *   ② `file.type`（Content-Type）—— 同样是客户端给的
 *   ③ **文件头魔数** —— 这才是唯一的真防线（后端读前 12 字节验 JPEG/PNG/WEBP）
 *   所以前端只做一件事：**大小和扩展名的即时提示**，
 *   让用户不用等一次往返才知道选错了。真正的拒绝永远在服务端。
 *
 * ★ 用 FormData 时**不要**手动设 Content-Type —— 浏览器要自己
 *   往里面加 `boundary=...`，手动设成 'multipart/form-data' 会让 boundary 丢掉，
 *   后端解析不出任何字段（症状是「文件是 null」，而不是一个明确的报错）。
 *
 * ★ 超限时后端返回的是 **HTTP 400**（不是 500），提示语里带上真实的 MB 上限。
 */
export function uploadCover(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/author/upload/cover', form)
}
