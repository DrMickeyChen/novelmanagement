<script setup>
/**
 * 作品详情预览 —— **只读**。审核弹窗和作品详情弹窗共用。
 *
 * ============================================================
 * ★★ 为什么要抽出来：这套字段出现在三个地方
 * ============================================================
 * · `views/audit/index.vue`   待审作品 → 查看并审核
 * · `views/chapter/audit.vue` 章节审核页 → 审核作品
 * · `views/novel/index.vue`   作品管理 → 查看
 *
 * 抄三遍的代价不是多写几个 div，而是**这三处会各自漂移**：
 * 哪天要加一个「完本/连载」的展示，改了审核页忘了作品管理页 ——
 * 而两边都是「作品详情」，没人会觉得它们应该不一样。
 *
 * ============================================================
 * ★★ 目录 + 每一章的状态是这个组件的重点，不是附赠
 * ============================================================
 * 「这本书能不能过」很大程度取决于**它的章节审得怎么样了**：
 * 一本章节全是「草稿」的书，作品审核通过后读者进来只有一个空目录 ——
 * 上架了等于没上架。所以章节列表不能省，且**必须带状态标签**。
 *
 * ★★ **P6 起目录是【单独一个 prop】**，不再从 `novel.chapters` 里读 ——
 * 详情接口不再返回目录了（见 `api/novel.js` 的 `getNovelCatalog`）。
 * 所以三个调用方都要**并行发两个请求**，然后 `:novel="detail" :chapters="chapters"`。
 * ★ 这是有意的：让「目录为空的空态」只在一种情况下出现（真的没章节），
 * 而不是「调用方忘了传」也长得一模一样。
 *
 * ============================================================
 * ★★ 「上次驳回理由」必须显示
 * ============================================================
 * 审核员据此判断「上次为什么没过、这次改了没有」。不显示的话，
 * 同一本书被反复驳回时，审核员每次都在从零开始判断，
 * 而且很可能给出和上次**互相矛盾**的理由 —— 作者就彻底不知道该听谁的了。
 */
import { formatWordCount } from '@/utils/format'
import { chapterStatusType, novelStatusType } from '@/utils/status'

defineProps({
  /**
   * `NovelDetailVO`（**不是** `NovelVO`）。
   * ★ 必须是详情：`NovelVO` 里没有 `intro` / `tags` / `auditRemark`，
   *   而这几样恰恰是这个组件要显示的全部内容。
   */
  novel: { type: Object, default: null },
  /**
   * 目录，来自 `getNovelCatalog`（**含全部状态、不分页**）。
   * ★ 和 `novel` 是两个请求的结果，不是从 `novel` 里取出来的 —— 见文件头。
   */
  chapters: { type: Array, default: () => [] },
})
</script>

<template>
  <div v-if="novel">
    <div class="field">
      <span class="field-label">作者</span>
      <span>{{ novel.authorName || '—' }}</span>
    </div>
    <div class="field">
      <span class="field-label">分类</span>
      <span>{{ novel.categoryName || '—' }}</span>
    </div>
    <div class="field">
      <span class="field-label">标签</span>
      <span>{{ novel.tags || '（无）' }}</span>
    </div>
    <div class="field">
      <span class="field-label">状态</span>
      <el-tag :type="novelStatusType(novel.status)" size="small">
        {{ novel.statusDesc }}
      </el-tag>
      <span class="field-label">
        共 {{ chapters.length }} 章 · {{ formatWordCount(novel.wordCount) }}
      </span>
    </div>

    <div v-if="novel.auditRemark" class="field remark-field">
      <span class="field-label">上次驳回理由</span>
      <span class="prev-remark">{{ novel.auditRemark }}</span>
    </div>

    <div class="field section-label">
      <span class="field-label">简介</span>
    </div>
    <div class="intro">{{ novel.intro || '（作者没有填写简介）' }}</div>

    <div class="field section-label">
      <span class="field-label">目录</span>
    </div>
    <div class="chapter-list">
      <div v-for="c in chapters" :key="c.id" class="chapter-row">
        <span class="chapter-no">第 {{ c.chapterNo }} 章</span>
        <span class="chapter-title">{{ c.title }}</span>
        <el-tag :type="chapterStatusType(c.status)" size="small">
          {{ c.statusDesc }}
        </el-tag>
      </div>
      <div v-if="!chapters.length" class="chapter-empty">
        这本书还没有章节 —— 通过审核后读者进来会是一个空目录
      </div>
    </div>
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

.section-label {
  margin-top: 12px;
  margin-bottom: 4px;
}

.intro {
  color: #303133;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 140px;
  overflow-y: auto;
  padding: 8px 10px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 13px;
}

.chapter-list {
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}

.chapter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  font-size: 13px;
  border-bottom: 1px solid #f2f6fc;
}

.chapter-row:last-child {
  border-bottom: none;
}

.chapter-no {
  color: #909399;
  flex-shrink: 0;
  width: 62px;
}

.chapter-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-empty {
  padding: 14px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}
</style>
