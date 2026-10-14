import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { ref, reactive, computed, watch, nextTick } from 'vue'

// Exercise the component's actual setup logic with stubbed HTTP calls; no real builds/deletes.
const source = fs.readFileSync(new URL('../src/components/BookKnowledgePanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
function panel(overrides = {}) {
  const context = vm.createContext({
    ref, reactive, computed, watch, defineProps: () => ({ mode: 'knowledge', revision: 0 }), onMounted() {}, onUnmounted() {},
    setTimeout: callback => { callback(); return 1 }, clearInterval() {}, setInterval() {},
    ElMessage: { warning() {}, success() {} }, ElMessageBox: { confirm: async () => {} },
    getNovelPage: async () => ({ records: [], total: 0 }),
    getKnowledgeStatus: async () => ({}), getKnowledge: async () => [],
    getKnowledgeTasks: async () => [], getFinderSkills: async () => [],
    getKnowledgeNotes: async () => [], getFinderRuns: async () => [],
    deleteKnowledge: async () => {}, ...overrides,
  })
  vm.runInContext(script + '\n globalThis.panel = { books, knowledge, tasks, selected, selectedKnowledge, query, knowledgeQuery, pendingBooks, bookPage, filteredKnowledge, knowledgePage, total, setSelection, loadBooks, removeSelected, submit, form, stopBatch, refresh, poll, loading, failed, refreshError };', context)
  return context.panel
}
const books = count => Array.from({ length: count }, (_, i) => ({ id: String(i + 1), title: `Book ${i + 1}` }))
const documents = rows => rows.map(book => ({ novelId: book.id, book }))

test('overlapping refresh waits for all requests and does not start a second batch after an early failure', async () => {
  let release, calls = 0
  const slow = new Promise(resolve => { release = resolve })
  const p = panel({
    getKnowledge: () => { calls++; return slow },
    getKnowledgeStatus: async () => { throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }) },
  })
  const first = p.refresh()
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(p.refresh(), first)
  assert.equal(p.loading.value, true)
  release(documents(books(2)))
  await first
  assert.equal(calls, 1)
  assert.equal(p.knowledge.value.length, 2)
  assert.equal(p.failed.value, true)
  assert.equal(p.loading.value, false)
  assert.match(p.refreshError.value, /超时/)
})

test('background refresh is silent, preserves displayed data and avoids the loading overlay', async () => {
  let calls = 0
  const p = panel({
    getKnowledge: async config => { assert.equal(config.silent, true); return documents(books(2)) },
    getFinderSkills: async () => { calls++; return [] },
    getFinderRuns: async () => { calls++; return [] },
  })
  const pending = p.refresh(true)
  assert.equal(p.loading.value, false)
  await pending
  assert.equal(p.knowledge.value.length, 2)
  assert.equal(calls, 0)
})

test('hidden panels and browser tabs do not refresh or poll', async () => {
  let calls = 0
  const p = panel({
    defineProps: () => ({ mode: 'knowledge', active: false, revision: 0 }),
    getKnowledge: async () => { calls++; return [] },
  })
  await p.refresh()
  p.poll()
  const hidden = panel({
    document: { visibilityState: 'hidden' },
    getKnowledge: async () => { calls++; return [] },
  })
  hidden.tasks.value = [{ status: 'RUNNING' }]
  hidden.poll()
  await Promise.resolve()
  assert.equal(calls, 0)
})

test('exclude indexed and active books before pagination; failed books remain eligible', async () => {
  const p = panel()
  p.books.value = books(45)
  p.knowledge.value = documents(books(10))
  p.tasks.value = [{ novelId: '11', status: 'RUNNING' }, { novelId: '12', status: 'FAILED' }]
  await nextTick()
  assert.equal(p.total.value, 34)
  assert.equal(p.bookPage.value.length, 20)
  assert.equal(p.bookPage.value[0].id, '12')
  p.query.page = 2
  assert.equal(p.bookPage.value.length, 14)
})

test('current page and all-result selections persist across pages and prune newly built rows', async () => {
  const p = panel()
  p.books.value = books(45)
  await nextTick()
  p.setSelection('books', p.bookPage.value.map(b => b.id), true)
  p.query.page = 2
  p.setSelection('books', [p.bookPage.value[0].id], true)
  assert.equal(p.selected.value.length, 21)
  p.setSelection('books', p.pendingBooks.value.map(b => b.id), true)
  assert.equal(p.selected.value.length, 45)
  p.knowledge.value = documents(books(1))
  await nextTick()
  assert.equal(p.selected.value.length, 44)
  assert.equal(p.selected.value.includes('1'), false)
})

test('metadata loading fetches subsequent server pages instead of limiting select-all to twenty', async () => {
  const calls = []
  const all = books(215)
  const p = panel({ getNovelPage: async q => {
    calls.push(q)
    return { records: all.slice((q.page - 1) * q.size, q.page * q.size), total: all.length }
  } })
  await p.loadBooks()
  assert.equal(p.total.value, 215)
  assert.deepEqual(calls.map(q => q.page), [1, 2, 3])
  assert.ok(calls.every(q => q.status === 2 && q.size === 100))
})

test('built list searches and paginates; selection survives changing pages', async () => {
  const p = panel()
  p.knowledge.value = documents(books(45))
  await nextTick()
  p.setSelection('knowledge', p.knowledgePage.value.map(k => k.novelId), true)
  p.knowledgeQuery.page = 3
  assert.equal(p.knowledgePage.value.length, 5)
  assert.equal(p.selectedKnowledge.value.length, 20)
  p.knowledgeQuery.keyword = 'Book 45'
  await nextTick()
  assert.equal(p.knowledgeQuery.page, 1)
  assert.equal(p.filteredKnowledge.value.length, 1)
})

test('batch removal removes successful rows, keeps failed selections and returns books to pending', async () => {
  let remaining = documents(books(3))
  const deleted = []
  const p = panel({
    deleteKnowledge: async id => {
      if (id === '2') throw new Error('busy')
      deleted.push(id)
      remaining = remaining.filter(k => k.novelId !== id)
    },
    getKnowledge: async () => remaining,
  })
  p.books.value = books(3)
  p.knowledge.value = remaining
  await nextTick()
  p.setSelection('knowledge', ['1', '2', '3'], true)
  await p.removeSelected()
  await nextTick()
  assert.deepEqual(deleted, ['1', '3'])
  assert.equal(p.selectedKnowledge.value.join(','), '2')
  assert.equal(p.pendingBooks.value.map(b => b.id).join(','), '1,3')
})

test('select-all construction splits 120 books into batches of at most fifty and waits between batches', async () => {
  const batches = [], events = []
  let tasks = []
  const p = panel({
    buildKnowledge: async payload => {
      batches.push(payload.ids)
      events.push('build')
      tasks = payload.ids.map(id => ({ id: `task-${id}`, novelId: id, status: 'DONE' }))
      return tasks
    },
    getKnowledgeTasks: async () => { events.push('poll'); return tasks },
  })
  p.books.value = books(120)
  await nextTick()
  p.setSelection('books', p.pendingBooks.value.map(b => b.id), true)
  await p.submit()
  assert.deepEqual(batches.map(b => b.length), [50, 50, 20])
  assert.equal(new Set(batches.flat()).size, 120)
  assert.equal(p.selected.value.length, 0)
  assert.ok(events.slice(1, events.indexOf('build', 1)).includes('poll'))
})

test('canceling batch removal confirmation never deletes knowledge', async () => {
  let deletes = 0
  const p = panel({
    ElMessageBox: { confirm: async () => { throw new Error('cancel') } },
    deleteKnowledge: async () => { deletes++ },
  })
  p.knowledge.value = documents(books(3))
  await nextTick()
  p.setSelection('knowledge', ['1', '2', '3'], true)
  await assert.rejects(p.removeSelected(), /cancel/)
  assert.equal(deletes, 0)
  assert.equal(p.selectedKnowledge.value.length, 3)
})

test('stopping subsequent batches keeps unsubmitted books selected without canceling accepted tasks', async () => {
  const batches = []
  let p
  p = panel({ buildKnowledge: async payload => {
    batches.push(payload.ids)
    p.stopBatch.value = true
    return payload.ids.map(id => ({ id: `task-${id}`, novelId: id, status: 'QUEUED' }))
  } })
  p.books.value = books(120)
  await nextTick()
  p.setSelection('books', p.pendingBooks.value.map(b => b.id), true)
  await p.submit()
  assert.equal(batches.length, 1)
  assert.equal(batches[0].length, 50)
  assert.equal(p.selected.value.length, 70)
})
