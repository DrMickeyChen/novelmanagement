<script setup>
/**
 * 审核中心 —— **P5 的验收页**。
 *
 * 大纲 §1286 的验收原话是「管理员能在界面上审完一本书的全部章节」。
 * 这一页 + `views/chapter/audit.vue` 合起来就是那句话的实现：
 *   本页「待审作品」tab → 看到某本书 → 点「审章节」→ 进章节审核页 → 逐章审完
 *
 * ============================================================
 * ★★ 两个 tab，不是一个合并列表
 * ============================================================
 * 作品和章节**是两套状态机**（作品的 3 是「已下架」，章节的 3 不存在），
 * 审核接口也是两个（`PUT /admin/novels/{id}/audit` 和
 * `/admin/chapters/{id}/audit`）。塞进一个列表就得给每一行加
 * 「点审核时该调哪个接口」的判断，而那个判断没有任何收益 ——
 * 审核员本来就知道自己在审什么。
 *
 * ============================================================
 * ★★ 为什么要有「待审章节」这个跨作品的 tab
 * ============================================================
 * 「进某本书审完它的全部章节」是**主线**，但还有一条真实的需求：
 * 作者今天提交了三本书各一章，审核员想一次把它们清掉，
 * 而不是先进第一本、审完、退出来、再进第二本。
 * 所以两个入口都要有 —— 这正是 `GET /api/admin/chapters` 支持
 * 「传 novelId」和「不传 novelId」两种用法的原因。
 *
 * ============================================================
 * ★★ 审核按钮只对 status === 1 的行显示
 * ============================================================
 * 后端对非待审的作品返回 2003、对非待审的章节也会被状态机挡住。
 * 给一个「点了必然报错」的按钮，用户会觉得是系统坏了。
 * 所以两个 tab 都**只查 status = 1**，按钮恒可用。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNovelPage, getNovelDetail, getNovelCatalog, auditNovel } from '@/api/novel'
import { getChapterPage, getChapterDetail, auditChapter } from '@/api/chapter'
import { usePagedList } from '@/composables/usePagedList'
import { formatWordCount, formatDateTime } from '@/utils/format'
import { novelStatusType, chapterStatusType } from '@/utils/status'
import AuditDialog from '@/components/AuditDialog.vue'
import NovelPreview from '@/components/NovelPreview.vue'
import ChapterPreview from '@/components/ChapterPreview.vue'

const router = useRouter()

/** 当前 tab。'novel' = 待审作品，'chapter' = 待审章节 */
const tab = ref('novel')

/* ==================== 待审作品 ==================== */

const novelList = usePagedList(({ page, size }) => getNovelPage({ status: 1, page, size }), {
  pageSize: 10,
})

/* ==================== 待审章节 ==================== */

const chapterList = usePagedList(({ page, size }) => getChapterPage({ status: 1, page, size }), {
  pageSize: 10,
})

onMounted(() => {
  novelList.load()
  chapterList.load()
})

/* ==================== 审核弹窗（作品和章节共用） ==================== */

const auditVisible = ref(false)
const auditLoading = ref(false)
/**
 * 当前被审对象。
 * `{ kind: 'novel'|'chapter', id, name, detail }`
 * ★ 用 `kind` 而不是分别开两个弹窗：两个弹窗会有两份 loading、
 *   两份「提交后刷新哪个列表」的判断，而它们的逻辑只差一个接口调用。
 */
const auditTarget = ref(null)

/**
 * 打开作品审核。
 *
 * ★★ 点开时**才**调详情接口（列表里不调）。
 *   `NovelVO` 里没有 `intro`/`tags`/`auditRemark`，
 *   而这三样是审核员判断的全部依据 —— 但列表一页 10 行，
 *   为了它们预先调 10 次详情就是典型的 N+1。
 *   点开哪本调哪本。
 *
 * ★ 点开之后是**两个**请求（详情 + 目录），不是三个：目录 P6 起
 *   不再跟着详情回来，但它是「点开某一本」才需要的东西，
 *   所以仍然是「点开哪本调哪本」，N+1 没有回来。
 *   ★ 两个请求**并发**：串行会白白多一个 RTT。
 */
async function openNovelAudit(row) {
  try {
    const [detail, chapters] = await Promise.all([
      getNovelDetail(row.id),
      getNovelCatalog(row.id),
    ])
    auditTarget.value = { kind: 'novel', id: row.id, name: row.title, detail, chapters }
    auditVisible.value = true
  } catch {
    // 拦截器已提示（2001 不存在）
  }
}

/** 打开章节审核。★ 同样要先拿到正文 —— 不看正文点通过等于没审 */
async function openChapterAudit(row) {
  try {
    const detail = await getChapterDetail(row.id)
    auditTarget.value = {
      kind: 'chapter',
      id: row.id,
      name: `${row.novelTitle} · 第 ${row.chapterNo} 章 ${row.title}`,
      detail,
    }
    auditVisible.value = true
  } catch {
    // 拦截器已提示（3001 不存在）
  }
}

/**
 * 提交审核结果。
 *
 * ★ 弹窗只负责「通过还是驳回 + 理由」，**接口调用在这里** ——
 *   因为只有这一层知道该调作品接口还是章节接口。
 */
async function submitAudit({ pass, remark }) {
  const target = auditTarget.value
  if (!target) return

  auditLoading.value = true
  try {
    if (target.kind === 'novel') {
      await auditNovel(target.id, { pass, remark })
    } else {
      await auditChapter(target.id, { pass, remark })
    }

    ElMessage.success(pass ? '已通过' : '已驳回')
    auditVisible.value = false

    /*
     * ★ 刷新【两个】列表，不只是当前 tab 那个。
     *
     * 审完一章会重算作品的章节数/字数（后端在同一个事务里做的），
     * 所以「待审作品」里的数字会变；审完作品会改它的状态。
     * 只刷当前 tab 的话，切过去会看到过期的数字 ——
     * 而审核员会以为「刚才那一章白审了」。
     * 两个请求都很轻（待审队列本来就不长）。
     */
    await Promise.all([novelList.reload(), chapterList.reload()])
  } catch {
    // 拦截器已提示（2003 状态不对 / 网络错误）。
    // ★ 弹窗**保持打开** —— 关掉的话审核员刚写的驳回理由就没了，
    //   而他多半会想「那我重试一次」。
  } finally {
    auditLoading.value = false
  }
}

/** 去某本书的章节审核页 —— 验收主线的入口 */
function gotoNovelChapters(row) {
  router.push(`/audit/novel/${row.id}`)
}
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- ==================== 待审作品 ==================== -->
        <el-tab-pane name="novel">
          <template #label>
            待审作品
            <el-badge
              v-if="novelList.total.value"
              :value="novelList.total.value"
              type="warning"
              class="tab-badge"
            />
          </template>

          <el-table
            v-loading="novelList.loading.value"
            :data="novelList.list.value"
            stripe
            row-key="id"
          >
            <el-table-column prop="title" label="书名" min-width="180" show-overflow-tooltip />
            <el-table-column prop="authorName" label="作者" width="120" />
            <el-table-column prop="categoryName" label="分类" width="100" />
            <el-table-column label="字数" width="110">
              <template #default="{ row }">{{ formatWordCount(row.wordCount) }}</template>
            </el-table-column>
            <el-table-column label="最后更新" width="150">
              <template #default="{ row }">{{ formatDateTime(row.lastUpdateTime) || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="novelStatusType(row.status)" size="small">
                  {{ row.statusDesc }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <!--
                  ★ 「审章节」放在「审核」左边，因为它是审核一本书的**主要工作**：
                    一本 40 章的书，审核员要做的是审 40 次章节、审 1 次作品。
                -->
                <el-button link type="primary" @click="gotoNovelChapters(row)">
                  审章节
                </el-button>
                <el-button link type="primary" @click="openNovelAudit(row)">
                  查看并审核
                </el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty
                v-if="novelList.loaded.value"
                description="没有待审的作品 —— 队列是空的"
                :image-size="80"
              />
            </template>
          </el-table>

          <el-pagination
            v-if="novelList.total.value > 0"
            class="pager"
            :current-page="novelList.page.value"
            :page-size="novelList.size.value"
            :total="novelList.total.value"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="novelList.changePage"
            @size-change="novelList.changeSize"
          />
        </el-tab-pane>

        <!-- ==================== 待审章节 ==================== -->
        <el-tab-pane name="chapter">
          <template #label>
            待审章节
            <el-badge
              v-if="chapterList.total.value"
              :value="chapterList.total.value"
              type="warning"
              class="tab-badge"
            />
          </template>

          <el-table
            v-loading="chapterList.loading.value"
            :data="chapterList.list.value"
            stripe
            row-key="id"
          >
            <el-table-column prop="novelTitle" label="书名" min-width="160" show-overflow-tooltip />
            <el-table-column prop="chapterNo" label="章号" width="80" />
            <el-table-column prop="title" label="章节标题" min-width="180" show-overflow-tooltip />
            <el-table-column label="字数" width="110">
              <template #default="{ row }">{{ formatWordCount(row.wordCount) }}</template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="chapterStatusType(row.status)" size="small">
                  {{ row.statusDesc }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openChapterAudit(row)">
                  审核
                </el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty
                v-if="chapterList.loaded.value"
                description="没有待审的章节 —— 队列是空的"
                :image-size="80"
              />
            </template>
          </el-table>

          <el-pagination
            v-if="chapterList.total.value > 0"
            class="pager"
            :current-page="chapterList.page.value"
            :page-size="chapterList.size.value"
            :total="chapterList.total.value"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="chapterList.changePage"
            @size-change="chapterList.changeSize"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ==================== 审核弹窗 ==================== -->
    <AuditDialog
      v-model="auditVisible"
      :title="auditTarget?.kind === 'novel' ? '审核作品' : '审核章节'"
      :target-name="auditTarget?.name || ''"
      :pass-text="auditTarget?.kind === 'novel' ? '通过并上架' : '通过并发布'"
      :pass-hint="
        auditTarget?.kind === 'novel'
          ? '通过后作品立刻上架，读者马上能搜到'
          : '通过后章节立刻发布，读者马上能在目录里读到'
      "
      :loading="auditLoading"
      @submit="submitAudit"
    >
      <!-- ============ 预览区（两个组件，见各自的文件头） ============ -->
      <NovelPreview
        v-if="auditTarget?.kind === 'novel'"
        :novel="auditTarget.detail"
        :chapters="auditTarget.chapters"
      />
      <ChapterPreview
        v-else-if="auditTarget?.kind === 'chapter'"
        :chapter="auditTarget.detail"
      />
    </AuditDialog>
  </div>
</template>

<style scoped>
.tab-badge {
  margin-left: 6px;
  margin-top: -2px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

/* ★ 预览区那几十行样式搬去了 NovelPreview.vue / ChapterPreview.vue —— 那里是三处共用的 */
</style>
