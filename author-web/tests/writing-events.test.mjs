import { test } from 'node:test'
import assert from 'node:assert/strict'
import { WritingEventReader } from '../src/utils/writing-events.js'
const frame = task => `event: state\r\ndata: ${JSON.stringify(task)}\r\n\r\n`
test('fragmented CRLF frames restore a complete snapshot', () => {
  const seen = [], reader = new WritingEventReader('t', t => seen.push(t))
  const wire = frame({ id: 't', throughSeq: 1, partialDraft: '你好😀' })
  for (const character of wire) reader.push(character)
  assert.equal(seen.length, 1);assert.equal(seen[0].partialDraft, '你好😀')
})
test('duplicates and late events do not roll back the latest task', () => {
  const seen = [], reader = new WritingEventReader('t', t => seen.push(t))
  reader.push(frame({ id: 't', throughSeq: 3, draft: '新' }) + frame({ id: 't', throughSeq: 3, draft: '重复' }) + frame({ id: 't', throughSeq: 2, draft: '旧' }))
  assert.deepEqual(seen.map(t => t.draft), ['新'])
})
test('retry output replaces the old attempt rather than appending', () => {
  let current;const reader = new WritingEventReader('t', t => { current = t })
  reader.push(frame({ id:'t', throughSeq:1, activeAttemptId:'a', partialDraft:'旧部分' }))
  reader.push(frame({ id:'t', throughSeq:2, activeAttemptId:'b', partialDraft:'新部分' }))
  assert.equal(current.partialDraft, '新部分');assert.equal(current.activeAttemptId, 'b')
})
test('another task and malformed sequence are rejected', () => {
  const reader = new WritingEventReader('t', () => {})
  assert.throws(() => reader.push(frame({ id:'other', throughSeq:1 })))
  assert.throws(() => reader.push(frame({ id:'t', throughSeq:'wrong' })))
})
