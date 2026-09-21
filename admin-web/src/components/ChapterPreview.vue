<script setup>
/**
 * 章节正文预览 —— **只读**。审核弹窗共用。
 *
 * ============================================================
 * ★★ 正文必须显示出来。这不是「锦上添花的预览」
 * ============================================================
 * 审核的全部意义就是**读过内容再决定**。一个只显示标题和字数的审核框，
 * 会把「审核」退化成「点通过」—— 审核员看不到任何能据以判断的东西，
 * 于是所有章节都会被通过，而违规内容一字不差地发布出去。
 *
 * 所以列表接口刻意不返回正文（一页 20 条就是几百 KB），
 * 但**点开审核时必须单独把它取回来**（`getChapterDetail`）。
 * 省掉这一步，「审核」这个功能在界面上就只是一个按钮。
 *
 * ============================================================
 * ★★ 高度封顶是必须的
 * ============================================================
 * 章节正文动辄几千字。不封顶的话弹窗会长到屏幕外面，
 * 底部的「通过 / 驳回」按钮就够不着了 ——
 * 而那两个按钮是这一页的**全部目的**。
 * 封顶的样式在 `AuditDialog.vue` 的 `.preview` 上（`max-height: 340px`），
 * 这里只负责内容。
 */
defineProps({
  /** `ChapterDetailVO`（含 `content` / `auditRemark` / `wordCount`） */
  chapter: { type: Object, default: null },
})

/** 字数格式化。★ 内联一个小的，避免为了一个数字引入整个 format.js 的函数 */
function withUnit(n) {
  const num = Number(n)
  if (!Number.isFinite(num) || num <= 0) return '—'
  return `${num.toLocaleString('zh-CN')} 字`
}
</script>

<template>
  <div v-if="chapter">
    <!-- ★ 上次驳回理由：作者改了什么、改到位没有，审核员据此判断 -->
    <div v-if="chapter.auditRemark" class="field remark-field">
      <span class="field-label">上次驳回理由</span>
      <span class="prev-remark">{{ chapter.auditRemark }}</span>
    </div>
    <div class="field">
      <span class="field-label">字数</span>
      <span>{{ withUnit(chapter.wordCount) }}</span>
    </div>
    <div class="content">{{ chapter.content || '（正文为空）' }}</div>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}

.field-label {
  color: #909399;
  flex-shrink: 0;
}

.remark-field .prev-remark {
  color: #e6a23c;
}

.content {
  color: #303133;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
