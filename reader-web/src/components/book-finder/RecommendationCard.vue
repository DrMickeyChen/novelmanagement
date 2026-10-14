<script setup>
defineProps({ recommendation: { type: Object, required: true }, index: Number })
</script>

<template>
  <article class="recommendation">
    <router-link :to="`/novel/${recommendation.book.id}`" class="book-head">
      <img v-if="recommendation.book.cover" :src="recommendation.book.cover" :alt="recommendation.book.title" loading="lazy" @error="$event.target.style.display = 'none'" />
      <div>
        <h3>{{ index + 1 }}. {{ recommendation.book.title }}</h3>
        <p>{{ recommendation.book.author || '作者资料暂缺' }}</p>
        <p>{{ recommendation.book.category }} · {{ recommendation.book.completed ? '已完结' : '连载中' }} · {{ recommendation.book.wordCount.toLocaleString() }} 字</p>
      </div>
    </router-link>
    <p class="intro">{{ recommendation.book.intro || '暂无简介' }}</p>
    <div class="matches"><el-tag v-for="match in recommendation.matches" :key="match" size="small" type="success">{{ match }}</el-tag></div>
    <p class="reason">{{ recommendation.reason }}</p>
    <div v-if="recommendation.assessments?.length" class="sources">
      <p v-for="item in recommendation.assessments" :key="item.criterion">
        {{ item.criterion }}{{ item.required ? '（必须满足）' : '' }}：
        <el-tag size="small" :type="item.verdict === 'SUPPORTED' ? 'success' : item.verdict === 'CONTRADICTED' ? 'danger' : 'info'">
          {{ item.verdict === 'SUPPORTED' ? '有支持线索' : item.verdict === 'CONTRADICTED' ? '有反面线索' : '资料不足' }}
        </el-tag><br />{{ item.scope }}
      </p>
    </div>
    <details v-if="recommendation.sources?.length" class="sources">
      <summary>正文证据来源（不展示剧情）</summary>
      <p v-for="source in recommendation.sources" :key="source.id">
        第 {{ source.chapterNumber }} 章 · 覆盖前 {{ source.coveredChapters }} / {{ source.totalChapters }} 个已发布章节{{ source.truncated ? '（部分正文截断）' : '' }}<br />
        {{ source.retrievalMode.includes('HYBRID') ? '关键词 + 向量融合' : '关键词检索' }}{{ source.retrievalMode.includes('RERANK') ? ' + 重排序' : '' }}。{{ source.notice }}
      </p>
    </details>
    <router-link :to="`/novel/${recommendation.book.id}`">查看作品 →</router-link>
  </article>
</template>

<style scoped>
.recommendation { padding: 18px; border: 1px solid #e4e7ed; border-radius: 12px; background: white; }
.book-head { display: flex; gap: 14px; color: inherit; text-decoration: none; }
.book-head img { width: 72px; height: 98px; object-fit: cover; border-radius: 5px; background: #f1f3f5; }
h3 { margin: 0 0 8px; font-size: 17px; }
p { margin: 6px 0; line-height: 1.7; }
.book-head p { color: #606266; font-size: 13px; }
.intro { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; font-size: 14px; }
.matches { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0; }
.reason { font-size: 13px; color: #606266; background: #f5f7fa; padding: 10px; border-radius: 6px; white-space: pre-wrap; overflow-wrap: anywhere; }
.sources { font-size: 12px; color: #606266; margin: 12px 0; } .sources summary { cursor: pointer; }
</style>
