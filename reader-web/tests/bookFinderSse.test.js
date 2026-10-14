import test from 'node:test'
import assert from 'node:assert/strict'
import { readBookFinderStream } from '../src/utils/bookFinderSse.js'

function body(text, size = 1) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += size) controller.enqueue(bytes.slice(i, i + size))
    controller.close()
  } })
}
test('SSE preserves Chinese and CRLF split across byte chunks', async () => {
  const received = []
  await readBookFinderStream(body('event: progress\r\ndata: {"message":"正在找书"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n'), (event, data) => received.push([event, data]))
  assert.deepEqual(received, [['progress', { message: '正在找书' }], ['done', {}]])
})
test('Premature disconnect is an error', async () => {
  await assert.rejects(readBookFinderStream(body('event: progress\ndata: {}\n\n'), () => {}), /连接提前断开/)
})
test('Business error from SSE remains visible', async () => {
  await assert.rejects(readBookFinderStream(body('event: error\ndata: {"message":"额度不足"}\n\n'), (_, data) => { throw new Error(data.message) }), /额度不足/)
})
test('Malformed JSON is rejected', async () => {
  await assert.rejects(readBookFinderStream(body('event: done\ndata: nope\n\n'), () => {}), /响应格式/)
})
