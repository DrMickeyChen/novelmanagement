import request from '@/utils/request'
import { getToken } from '@/utils/auth'
import { WritingEventReader } from '@/utils/writing-events'

export const capabilities = () => request.get('/ai/writing/capabilities')
export const recentTasks = (chapterId, nodeId) => request.get('/ai/writing/tasks', { params: { chapterId, nodeId } })
export const getTask = id => request.get(`/ai/writing/tasks/${id}`)
export const getReport = id => request.get(`/ai/writing/tasks/${id}/report`)
export const newTask = (body, key = crypto.randomUUID()) => request.post('/ai/writing/tasks', body, { headers: { 'Idempotency-Key': key } })
export const command = (id, operation, body, key) => request.post(`/ai/writing/tasks/${id}/${operation}`, body, { headers: { 'Idempotency-Key': key } })

export async function subscribe(id, signal, onTask) {
  const response = await fetch(`/api/ai/writing/tasks/${id}/events`, {
    headers: { Accept: 'text/event-stream, application/json;q=0.9', Authorization: `Bearer ${getToken()}` }, signal,
  })
  if (!response.ok || !response.body) throw new Error('任务连接失败，正在重新读取已保存的结果')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const events = new WritingEventReader(id, onTask)
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      events.push(decoder.decode(value, { stream: true }))
    }
  } finally { await reader.cancel().catch(() => {}) }
}
