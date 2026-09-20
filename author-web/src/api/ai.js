/**
 * AI 写作助手 —— 对应后端 AiController（/api/ai/**）。
 *
 * ------------------------------------------------------------
 * ★★ 这个文件里【只有额度那一个接口】
 * ------------------------------------------------------------
 * 四个生成接口（outline / write / inspire / polish）**不在这里**，
 * 它们不能用 axios，原因有三条，每一条都是硬性的：
 *
 *   ① axios 会把整个响应体**读完再交给你** —— 而 SSE 的价值就在「边收边显示」，
 *      走 axios 的话使用者要盯着转圈等十几秒，然后一次性看到全文，
 *      流式效果完全没了
 *   ② axios 读不到 `response.body`（ReadableStream），拿不到增量
 *   ③ 更实际的一条：本项目要求「能中断」，而 axios 的取消是**取消请求**，
 *      它没有一个「读到一半停下、但已收到的内容保留」的位置
 *
 * 所以那四个走 `utils/sse.js`（fetch + ReadableStream + AbortController）。
 * ★ 那里也【没有】用 EventSource —— 它不支持自定义请求头，
 *   token 只能塞进 URL，而 §7.5 明确拒绝了「把 token 放 URL」。
 */
import request from '@/utils/request'

/**
 * 今日剩余额度。
 *
 * @returns {Promise<{remaining: number, total: number, provider: string}>}
 *
 * ★★ `provider` 决定前端要不要显示「**演示模式**」标签：
 *   `mock` = 内容由本地生成，不是真实模型。
 *
 * ★ 这个字段**不由前端写死**是刻意的 —— E1 换成真实模型后，
 *   后端配置一改，标签自动消失，不需要有人记得去改前端。
 *   反过来说：**前端绝对不要在这里写 `provider === 'mock' ? '演示模式' : ''`
 *   之外的判断**，也别自己维护一份「哪个 provider 是假的」的清单。
 */
export function getQuota() {
  return request.get('/ai/quota')
}
