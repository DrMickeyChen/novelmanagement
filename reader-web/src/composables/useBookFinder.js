import { computed, onBeforeUnmount, ref } from 'vue'
import { getUser } from '@/utils/auth'
import { chatBookFinder, deleteBookFinderConversation, getBookFinderConversation, getBookFinderQuota, getBookFinderExamples } from '@/api/bookFinder'

export function useBookFinder() {
  const user = getUser()
  const key = `novel_book_finder_${user?.id ?? user?.userId ?? 'current'}`
  const conversationId = ref(sessionStorage.getItem(key) || '')
  const turns = ref([])
  const busy = ref(false)
  const restoring = ref(true)
  const examples = ref([])
  const progress = ref('')
  const error = ref('')
  const quota = ref(null)
  const conditions = ref(null)
  let controller
  let lastMessage = ''
  const conditionLabels = computed(() => {
    const c = conditions.value
    if (!c) return []
    return [c.category, c.tag && `标签：${c.tag}`, c.completed != null && (c.completed ? '已完结' : '连载中'),
      c.minWords != null && `至少 ${c.minWords.toLocaleString()} 字`,
      c.maxWords != null && `最多 ${c.maxWords.toLocaleString()} 字`, c.keyword && `关键词：${c.keyword}`].filter(Boolean)
  })
  async function refreshQuota() {
    try { quota.value = await getBookFinderQuota() } catch { /* request interceptor displays failures */ }
  }
  async function restore() {
    try {
    await getBookFinderExamples().then(value => { examples.value = value || [] }).catch(() => {})
    await refreshQuota()
    if (!conversationId.value) return
    try {
      const state = await getBookFinderConversation(conversationId.value)
      turns.value = (state.history || []).map((t) => ({ user: t.user, text: t.assistant, books: [] }))
      const last = state.lastAnswer
      if (last && turns.value.length) {
        const turn = turns.value.at(-1)
        turn.books = last.recommendations || []
        turn.usage = last.usage
      }
      conditions.value = state.conditions
    } catch {
      conversationId.value = ''
      sessionStorage.removeItem(key)
      error.value = '原会话不可用，已开始新对话。'
    }
    } finally { restoring.value = false }
  }
  async function send(message) {
    message = message?.trim()
    if (!message || busy.value || restoring.value) return
    if (message.length > 1200) { error.value = '问题最多 1200 字'; return }
    busy.value = true
    error.value = ''
    progress.value = '正在连接找书助手'
    lastMessage = message
    controller = new AbortController()
    const turn = { user: message, text: '', books: [], status: 'running' }
    turns.value.push(turn)
    // Read the reactive proxy for async updates rather than mutating the original object.
    const active = turns.value.at(-1)
    try {
      await chatBookFinder({ message, requestId: crypto.randomUUID(), conversationId: conversationId.value || null }, controller.signal,
        (event, data) => {
          if (event === 'session') {
            conversationId.value = data.conversationId
            sessionStorage.setItem(key, data.conversationId)
          } else if (event === 'progress') progress.value = data.message
          else if (event === 'text') active.text += data.text
          else if (event === 'result') {
            active.text = data.answer
            active.books = data.recommendations || []
            active.usage = data.usage
            conditions.value = data.conditions
          } else if (event === 'error') throw new Error(data.message || '找书失败')
          else if (event === 'done') active.status = 'done'
        })
    } catch (e) {
      active.status = 'failed'
      error.value = e.name === 'AbortError' ? (quota.value?.unlimited ? '已停止本次请求。' : '已停止本次请求。已发生的调用仍计入额度。') : e.message
    } finally {
      busy.value = false
      progress.value = ''
      controller = null
      await refreshQuota()
    }
  }
  function stop() { controller?.abort() }
  async function reset() {
    if (busy.value || restoring.value) return
    if (conversationId.value) {
      try { await deleteBookFinderConversation(conversationId.value) }
      catch (e) { if (e.response?.status !== 404) { error.value = e.message; return } }
    }
    conversationId.value = ''
    sessionStorage.removeItem(key)
    turns.value = []
    conditions.value = null
    error.value = ''
  }
  onBeforeUnmount(stop)
  return { turns, busy, restoring, examples, progress, error, quota, conditionLabels, restore, send, stop, reset, retry: () => send(lastMessage) }
}
