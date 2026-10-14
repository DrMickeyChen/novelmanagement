/** Incremental SSE parser, including split UTF-8 characters and CRLF boundaries. */
export async function readBookFinderStream(body, onEvent) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let ended = false
  function consume() {
    buffer = buffer.replace(/\r\n/g, '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      let event = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
      }
      if (data.length) {
        let value
        try { value = JSON.parse(data.join('\n')) } catch { throw new Error('找书响应格式不正确') }
        onEvent(event, value)
        if (event === 'done' || event === 'error') ended = true
      }
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) { buffer += decoder.decode(); consume(); break }
      buffer += decoder.decode(value, { stream: true })
      if (buffer.length > 1_000_000) throw new Error('找书响应过大')
      consume()
    }
    if (!ended) throw new Error('连接提前断开，请重新发送问题')
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
