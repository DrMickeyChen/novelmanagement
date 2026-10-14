import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { ref, reactive } from 'vue'
const source = fs.readFileSync(new URL('../src/components/RerankConfigPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const saved = { url: 'https://example.com/embeddings', model: 'm', batchSize: 8, apiKeyConfigured: true, apiKeyMasked: '****abcd' }
function panel(overrides = {}) {
  const context = vm.createContext({
    ref, reactive, onMounted() {}, defineEmits: () => () => {},
    ElMessage: { warning() {}, success() {} }, ElMessageBox: { confirm: async () => {} },
    getRerankConfig: async () => saved, saveRerankConfig: async () => saved, ...overrides,
  })
  vm.runInContext(script + '\n globalThis.panel = { load, save, apply, form, newKey, clearKey, current };', context)
  return context.panel
}
test('load shows mask separately and never puts it into key input', async () => {
  const p = panel(); await p.load()
  assert.equal(p.newKey.value, '')
  assert.equal(p.current.value.apiKeyMasked, '****abcd')
})
test('saving metadata omits blank key; a replacement is sent once then cleared', async () => {
  const payloads = []
  const p = panel({ saveRerankConfig: async payload => { payloads.push(payload); return saved } })
  await p.load(); await p.save()
  assert.equal(Object.hasOwn(payloads[0], 'apiKey'), false)
  p.newKey.value = 'isolated-fake-key'; await p.save()
  assert.equal(payloads[1].apiKey, 'isolated-fake-key')
  assert.equal(p.newKey.value, '')
  assert.equal(p.clearKey.value, false)
})
test('key replacement and clearing conflict without sending request', async () => {
  let writes = 0
  const p = panel({ saveRerankConfig: async () => { writes++; return saved } })
  await p.load(); p.newKey.value = 'fake'; p.clearKey.value = true
  await p.save(); assert.equal(writes, 0)
})
test('canceling clear confirmation does not send request', async () => {
  let writes = 0
  const p = panel({
    ElMessageBox: { confirm: async () => { throw new Error('cancel') } },
    saveRerankConfig: async () => { writes++; return saved },
  })
  await p.load(); p.clearKey.value = true
  await assert.rejects(p.save(), /cancel/); assert.equal(writes, 0)
})
