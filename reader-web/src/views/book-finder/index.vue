<script setup>
import { onMounted, ref } from 'vue'
import { useBookFinder } from '@/composables/useBookFinder'
import RecommendationCard from '@/components/book-finder/RecommendationCard.vue'

const { turns, busy, restoring, examples, progress, error, quota, conditionLabels, restore, send, stop, reset, retry } = useBookFinder()
const input = ref('')
async function submit() {
  const message = input.value.trim()
  if (!message || busy.value) return
  input.value = ''
  await send(message)
}
onMounted(restore)
</script>

<template>
  <main class="finder">
    <header class="heading">
      <div><h1>找书助手</h1><p>说说你想看的故事，我帮你筛选站内作品。</p></div>
      <el-button :disabled="busy || restoring" @click="reset">新对话</el-button>
    </header>
    <div class="status-row">
      <span v-if="quota?.unlimited">管理员 · 不限每日次数</span>
      <span v-else-if="quota">今日剩余 {{ quota.remaining }} / {{ quota.limit }} 次</span>
      <el-tag v-if="quota?.mock" type="warning">本地演示模式</el-tag>
    </div>
    <div v-if="conditionLabels.length" class="conditions"><span>当前条件</span><el-tag v-for="label in conditionLabels" :key="label" effect="plain">{{ label }}</el-tag></div>
    <section v-if="!turns.length" class="welcome">
      <h2>从题材、完结状态或字数开始</h2>
      <p>可以继续说“换一批”“类似第二本”或“只要完结的”。风格偏好会根据已有资料判断。</p>
      <div class="examples"><el-button v-for="example in examples" :key="example" :disabled="busy || restoring" @click="send(example)">{{ example }}</el-button></div>
    </section>
    <section class="conversation" aria-live="polite" aria-label="找书对话">
      <article v-for="(turn, i) in turns" :key="i" class="turn">
        <div class="user-message">{{ turn.user }}</div>
        <div class="assistant-message">
          <p v-if="turn.text" class="answer">{{ turn.text }}</p>
          <p v-else-if="busy && i === turns.length - 1" class="working" role="status">{{ progress }}</p>
          <p v-else class="working">本次请求未完成。</p>
          <div v-if="turn.books.length" class="cards"><RecommendationCard v-for="(book, index) in turn.books" :key="book.book.id" :recommendation="book" :index="index" /></div>
          <small v-if="turn.usage">工具 {{ turn.usage.toolCalls }} 次 · {{ (turn.usage.durationMs / 1000).toFixed(1) }} 秒</small>
        </div>
      </article>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <div class="quick-actions">
      <el-button v-if="turns.some(t => t.books.length)" :disabled="busy" @click="send('换一批，保留当前筛选条件')">换一批</el-button>
      <el-button v-if="error && !busy" @click="retry">重新发送上个问题</el-button>
    </div>
    <form class="composer" @submit.prevent="submit">
      <el-input v-model="input" type="textarea" :rows="3" :maxlength="1200" show-word-limit placeholder="说说你想看的题材，也可以补充完结状态或字数" :disabled="busy || restoring" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
      <div class="composer-bottom"><span>推荐依据来自站内简介和标签；资料不足时不会保证风格符合。</span><el-button v-if="busy" @click="stop">停止</el-button><el-button v-else type="primary" :disabled="!input.trim()" native-type="submit">发送</el-button></div>
    </form>
  </main>
</template>

<style scoped>
.finder { max-width: 1000px; margin: 0 auto; padding: 24px 16px 48px; }
.heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h1 { margin: 0; font-size: 26px; } .heading p, .welcome p { color: #606266; line-height: 1.7; }
.status-row, .conditions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: #606266; font-size: 13px; margin: 14px 0; }
.welcome { padding: 28px; border: 1px solid #e4e7ed; border-radius: 14px; background: #fff; margin: 24px 0; }
.welcome h2 { font-size: 20px; } .examples { display: flex; gap: 10px; flex-wrap: wrap; } .examples .el-button { margin-left: 0; white-space: normal; height: auto; min-height: 36px; line-height: 1.6; }
.turn { margin: 24px 0; } .user-message { margin: 0 0 14px auto; width: fit-content; max-width: 85%; padding: 12px 16px; border-radius: 12px; background: #e8f1ff; white-space: pre-wrap; overflow-wrap: anywhere; }
.answer { white-space: pre-wrap; line-height: 1.8; } .cards { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.working { color: #409eff; } small { display: block; margin-top: 10px; color: #909399; }
.quick-actions { display: flex; gap: 10px; margin: 16px 0; }
.composer { padding: 16px; background: white; border: 1px solid #e4e7ed; border-radius: 12px; }
.composer-bottom { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-top: 12px; } .composer-bottom span { font-size: 12px; color: #909399; }
@media (max-width: 640px) { .cards { grid-template-columns: 1fr; } .finder { padding: 18px 8px; } .welcome { padding: 18px; } .composer-bottom span { max-width: 70%; } }
</style>
