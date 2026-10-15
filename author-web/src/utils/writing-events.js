/** Writer streams contain absolute committed snapshots, never text to append across attempts. */
export class WritingEventReader {
  constructor(taskId, onTask) { this.taskId = taskId; this.onTask = onTask; this.buffer = ''; this.latest = -1 }
  push(text) {
    this.buffer = (this.buffer + text).replace(/\r\n/g, '\n')
    let end
    while ((end = this.buffer.indexOf('\n\n')) >= 0) {
      const frame = this.buffer.slice(0, end); this.buffer = this.buffer.slice(end + 2)
      const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
      if (!data) continue
      const event = JSON.parse(data), task = event.payload ?? event
      const seq = Number(task.throughSeq)
      if (task.id !== this.taskId || !Number.isSafeInteger(seq) || seq < 0) throw new Error('任务事件格式错误')
      if (seq <= this.latest) continue
      this.latest = seq; this.onTask(task)
    }
  }
}
