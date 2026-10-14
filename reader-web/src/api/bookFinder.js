import request from '@/utils/request'
import { getToken, clearAuth, redirectToReaderLogin } from '@/utils/auth'
import { readBookFinderStream } from '@/utils/bookFinderSse'

const path = '/ai/book-finder'
export const getBookFinderExamples = () => request.get(`${path}/examples`)
export const getBookFinderQuota = () => request.get(`${path}/quota`)
export const getBookFinderConversation = (id) => request.get(`${path}/conversations/${encodeURIComponent(id)}`)
export const deleteBookFinderConversation = (id) => request.delete(`${path}/conversations/${encodeURIComponent(id)}`)

export async function chatBookFinder(payload, signal, onEvent) {
  const token = getToken()
  const response = await fetch(`/api${path}/chat`, {
    method: 'POST', signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json;q=0.9',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
  })
  if (response.status === 401) {
    clearAuth()
    redirectToReaderLogin()
    throw new Error('登录已过期，请重新登录')
  }
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) {
    const data = await response.json().catch(() => ({}))
    throw new Error(data.message || (response.status === 504 ? '找书响应超时，请重试' : `找书请求失败（${response.status}），请重试`))
  }
  if (!response.body) throw new Error('浏览器不支持流式响应')
  return readBookFinderStream(response.body, onEvent)
}
