<script setup>
/**
 * 我的作品 —— 创作中心的落地页。
 *
 * ★ 它和左侧作品栏读的是**同一个接口**（GetMyNovels），但两者的用途不同：
 *   左栏是「快速切换当前在写的书」（一次拉 100 条、不筛状态、只要书名列个表），
 *   这一页是「管理」（要封面、状态、字数、操作按钮）。
 *   所以没有把它俩合并 —— 合并的结果是左栏被迫渲染一堆它不显示的数据。
 *
 * ★ 状态筛选在**前端**做不了，必须传给后端 —— 分页的 total 是按筛选后算的，
 *   前端筛只能筛当前这一页，页码就对不上了。
 */
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyNovels, deleteNovel, submitNovel, withdrawNovel } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import { formatWordCount, formatDate } from '@/utils/format'
import { novelStatusType } from '@/utils/status'

const router = useRouter()

/** 状态筛选。null = 全部。★ 用数字，和后端一致（见 AuthorNovelController#myList） */
const status = ref(null)

const STATUS_OPTIONS = [
  { label: '全部', value: null },
  { label: '草稿', value: 0 },
  { label: '待审核', value: 1 },
  { label: '已上架', value: 2 },
  { label: '已下架', value: 3 },
  { label: '已驳回', value: 4 },
]

const { list, total, page, size, loading, loaded, load, changePage, changeSize, reload } =
  usePagedList(({ page, size }) => getMyNovels({ status: status.value, page, size }), {
    pageSize: 12,
  })

onMounted(load)

/** ★ 换筛选条件必须走 reload（回第 1 页），不能 load —— 见 usePagedList 里的说明 */
watch(status, reload)

/** 操作进行中的作品 id，用来只禁用那一张卡片的按钮而不是全页面 */
const busyId = ref(null)

async function withBusy(novel, fn, successMsg) {
  busyId.value = novel.id
  try {
    await fn()
    ElMessage.success(successMsg)
    await load()
  } catch {
    // ★ 拦截器已经弹过具体原因了，这里不再重复提示
  } finally {
    busyId.value = null
  }
}

function handleSubmit(novel) {
  return withBusy(novel, () => submitNovel(novel.id), '已提交审核')
}

function handleWithdraw(novel) {
  return withBusy(novel, () => withdrawNovel(novel.id), '已撤回，可以继续修改了')
}

/**
 * 删除作品。
 *
 * ★★ 两个必须说清的点，都在确认框里：
 *   ① **有章节时后端会拒绝**（code 2005），所以提示里要写「请先删除章节」——
 *      不写的话作者点了删除、收到一句「请先删除章节」，不知道去哪删
 *   ② 「只是想暂停对外」应该用**下架**，不是删除 ——
 *      这两个动作在作者脑子里经常是混的，而删除是逻辑删除、有代价的
 */
async function handleDelete(novel) {
  try {
    await ElMessageBox.confirm(
      `确定删除《${novel.title}》吗？名下有章节时需要先删除章节；` +
        `如果只是想暂停对外，请改用「下架」。`,
      '删除作品',
      // ★ 命令式 API 拿不到 el-config-provider 的语言包，必须显式传按钮文案
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  await withBusy(novel, () => deleteNovel(novel.id), '已删除')
}

/** 能编辑的状态：0 草稿 / 3 已下架 / 4 已驳回。其余状态后端会返回 2003 */
function canEdit(novel) {
  return [0, 3, 4].includes(novel.status)
}

function openWorkbench(novel) {
  router.push(`/novel/${novel.id}/work`)
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">我的作品</h2>
      <el-button type="primary" @click="router.push('/novel/create')">新建作品</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-radio-group v-model="status" size="small">
          <el-radio-button v-for="o in STATUS_OPTIONS" :key="String(o.value)" :value="o.value">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <!--
        ★★ v-loading 和 el-empty 的分工见 usePagedList 里那段：
           条件是 `loaded && !list.length`，不是 `!list.length` ——
           否则首次进入时「加载中」和「暂无数据」会同时出现再闪一下。
      -->
      <div v-loading="loading" class="list-wrap">
        <el-empty v-if="loaded && !list.length" description="这里还没有作品">
          <el-button type="primary" @click="router.push('/novel/create')">写第一本书</el-button>
        </el-empty>

        <el-row v-else :gutter="16">
          <el-col v-for="n in list" :key="n.id" :xs="24" :sm="12" :md="8" :lg="6">
            <el-card shadow="hover" class="novel-card">
              <div class="cover-row">
                <img v-if="n.cover" :src="n.cover" class="cover" alt="" />
                <div v-else class="cover cover-fallback">{{ (n.title || '?')[0] }}</div>

                <div class="meta">
                  <div class="title" :title="n.title">{{ n.title }}</div>
                  <el-tag :type="novelStatusType(n.status)" size="small">{{ n.statusDesc }}</el-tag>
                  <div class="sub">{{ n.categoryName || '未分类' }}</div>
                  <div class="sub">
                    {{ n.chapterCount || 0 }} 章 · {{ formatWordCount(n.wordCount) }}
                  </div>
                  <div class="sub muted">{{ formatDate(n.lastUpdateTime) || '尚未发布章节' }}</div>
                </div>
              </div>

              <div class="actions">
                <el-button link type="primary" size="small" @click="openWorkbench(n)">
                  工作台
                </el-button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click="router.push(`/novel/${n.id}/chapters`)"
                >
                  章节
                </el-button>
                <!--
                  ★ 不能编辑时按钮【禁用而不是隐藏】：
                    隐藏会让作者以为「这个功能不存在」，
                    禁用 + 一个说明性 title 才告诉他「现在不行，因为状态不对」。
                -->
                <el-tooltip
                  :disabled="canEdit(n)"
                  content="待审核期间不能修改内容，已上架请先下架"
                  placement="top"
                >
                  <span>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="!canEdit(n)"
                      @click="router.push(`/novel/${n.id}/edit`)"
                    >
                      编辑
                    </el-button>
                  </span>
                </el-tooltip>

                <el-button
                  v-if="n.status === 0 || n.status === 3 || n.status === 4"
                  link
                  type="success"
                  size="small"
                  :loading="busyId === n.id"
                  @click="handleSubmit(n)"
                >
                  提交审核
                </el-button>
                <!-- 撤回只在待审核出现；其余状态调后端会拿到 2003，没必要给按钮 -->
                <el-button
                  v-else-if="n.status === 1"
                  link
                  type="warning"
                  size="small"
                  :loading="busyId === n.id"
                  @click="handleWithdraw(n)"
                >
                  撤回提交
                </el-button>

                <el-button
                  link
                  type="danger"
                  size="small"
                  :loading="busyId === n.id"
                  @click="handleDelete(n)"
                >
                  删除
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <div v-if="total > 0" class="pager">
        <el-pagination
          :current-page="page"
          :page-size="size"
          :total="total"
          :page-sizes="[12, 24, 48]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.filters {
  margin-bottom: 16px;
}

/* ★ 最小高度：加载中和空态的高度不一样的话，切换筛选条件时页面会跳一下 */
.list-wrap {
  min-height: 200px;
}

.novel-card {
  margin-bottom: 16px;
}

.cover-row {
  display: flex;
  gap: 12px;
}

.cover {
  width: 72px;
  height: 96px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
  background: #f0f2f5;
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 600;
  color: #909399;
}

.meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  font-size: 12px;
  color: #909399;
}

.muted {
  color: #c0c4cc;
}

/* ★ 分隔线用 border-top 而不是 el-divider：
   卡片里已经有一个 el-card 的 body padding，再套一个 divider 会有两层间距 */
.actions {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #f0f2f5;
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}

.pager {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}
</style>
