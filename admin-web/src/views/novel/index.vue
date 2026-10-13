<script setup>
/**
 * 作品管理 —— 全部作品 + 筛选 + 上下架。
 *
 * ============================================================
 * ★★ 这一页**没有**「审核」按钮，那是审核中心的事
 * ============================================================
 * 两个接口都会把作品变成「已上架」，但后端刻意把它们分开了：
 *   · `PUT /admin/novels/{id}/audit`  → 审核通过，流水里记 APPROVE
 *   · `PUT /admin/novels/{id}/status` → 上下架，从「已下架」回来记 REPUBLISH
 * `NovelServiceImpl.transitStatus` 里那个 switch 就是干这个的：
 * `case PUBLISHED -> from == OFFLINE ? REPUBLISH : APPROVE`。
 *
 * 所以本页只调 `changeNovelStatus`，审核一律去审核中心。
 * 混用的话，日志里**再也说不清**「这本书是自己上架的还是被审出来的」——
 * 而「审核通过率」这类统计正是建立在这个区分上的。
 *
 * ============================================================
 * ★★ 上下架按钮只对 2 / 3 显示，其余状态一个都不给
 * ============================================================
 * 不是偷懒，是状态机就是这么定的（`NovelStatus.canTransitTo`）：
 *
 *   草稿(0)     → 上架：**不允许**。作者得先「提交审核」。
 *   待审核(1)   → 上架：状态机允许，但**不该从这里走** ——
 *                 那是审核通过，要去审核中心，日志才记得对。
 *   已驳回(4)   → 上架：不允许。作者得先改稿重新提交。
 *   已上架(2)   → 下架：允许。这是本页唯一的「撤下来」入口。
 *   已下架(3)   → 重新上架：允许。★ 这一条是后补进状态机的，
 *                 没有它「重新上架」这个功能根本做不出来（见枚举里的注释）。
 *
 * 给一个点了必然报 2003 的按钮，用户只会觉得系统坏了。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getNovelPage, getNovelDetail, getNovelCatalog, changeNovelStatus } from '@/api/novel'
import { getCategoryList } from '@/api/category'
import { usePagedList } from '@/composables/usePagedList'
import { formatWordCount, formatDateTime } from '@/utils/format'
import { novelStatusType } from '@/utils/status'
import { confirm } from '@/utils/confirm'
import NovelPreview from '@/components/NovelPreview.vue'
import NovelImportDialog from '@/components/NovelImportDialog.vue'
const importVisible = ref(false)

/* ==================== 筛选 ==================== */

const STATUS_OPTIONS = [
  { label: '全部状态', value: null },
  { label: '草稿', value: 0 },
  { label: '待审核', value: 1 },
  { label: '已上架', value: 2 },
  { label: '已下架', value: 3 },
  { label: '已驳回', value: 4 },
]

/**
 * 筛选条件。
 *
 * ★ `status` / `categoryId` 用 `null` 表示「不限」——
 *   axios 的默认参数序列化器会**跳过 null 和 undefined**，
 *   所以 `null` 就是「这个参数不传」，和后端「不传 = 全部」的语义正好对上。
 *   不要用空字符串：那会发成 `?status=`，后端解析空串成数字时会报错。
 */
const filter = ref({ status: null, categoryId: null, keyword: '' })

/** 分类下拉的数据源。★ 用管理端接口（含已停用的分类），见 api/category.js */
const categories = ref([])

async function loadCategories() {
  try {
    categories.value = (await getCategoryList()) || []
  } catch {
    // ★ 静默失败：分类下拉是**筛选的辅助**，拉不到就用不了「按分类筛」，
    //   但整页的作品列表照常能用。为它弹一个红字错误会把用户吓一跳。
    categories.value = []
  }
}

const paged = usePagedList(
  ({ page, size }) =>
    getNovelPage({
      status: filter.value.status,
      categoryId: filter.value.categoryId,
      // ★ trim 之后是空串就传 undefined（= 不传）。传空串虽然后端也认，
      //   但会让 URL 里挂一个没意义的 `keyword=`，看日志时容易误判
      keyword: filter.value.keyword.trim() || undefined,
      page,
      size,
    }),
  { pageSize: 10 },
)

onMounted(() => {
  loadCategories()
  paged.load()
})

/** 查询。★ 走 reload（回第 1 页）—— 在第 5 页换筛选条件会得到一片空白 */
function search() {
  paged.reload()
}

/** 重置。★ 注意 `keyword` 也要清 —— 只清下拉框的话，搜索框里还留着上次的词 */
function reset() {
  filter.value = { status: null, categoryId: null, keyword: '' }
  paged.reload()
}

/* ==================== 详情弹窗 ==================== */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
/** 目录。★ P6 起详情不再带它了，是**另一个请求**（见 api/novel.js） */
const detailChapters = ref([])

/**
 * ★ 先开弹窗、再拉数据（`v-loading` 顶住中间那段）。
 *
 * 反过来（先 await 再开）的话，网络慢的时候点「查看」会**什么都不发生** ——
 * 用户不知道是没点上还是在加载，于是再点一次。
 */
function openDetail(row) {
  detail.value = null
  detailChapters.value = []
  detailVisible.value = true
  loadDetail(row.id)
}

async function loadDetail(id) {
  detailLoading.value = true
  try {
    // ★ 详情和目录并发；任一失败都算失败（两个接口的错误码本来就一样）
    const [d, chapters] = await Promise.all([getNovelDetail(id), getNovelCatalog(id)])
    detail.value = d
    detailChapters.value = chapters ?? []
  } catch {
    // 拦截器已提示（2001 不存在）。★ 关掉弹窗 —— 里面没有任何东西可看
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/* ==================== 上下架 ==================== */

/** 行内操作进行中，用来只禁用那一行的按钮 */
const busyId = ref(null)

const canOffline = (row) => row.status === 2
const canRepublish = (row) => row.status === 3

async function doChangeStatus(row, target) {
  const offline = target === 3
  /*
   * ★ 文案是**单行纯文本**，不换行、不带 Markdown 记号 ——
   *   ElMessageBox 默认按纯文本渲染（`dangerouslyUseHTMLString` 是关的），
   *   写 `\n` 不会换行、写 `**` 会原样显示成两个星号。
   *   想换行只能开 HTML 字符串，而那是个 XSS 面 —— 为了一句换行不值得。
   */
  const ok = await confirm(
    offline
      ? `「${row.title}」下架后全部章节正文读者都读不到了，整本书从读者端消失。` +
        `这和「驳回某一章」不一样 —— 章节不能单独下架，只能跟着作品走。`
      : `「${row.title}」会立刻重新上架，读者马上能搜到，已发布的章节也立刻能读。`,
    offline ? '下架作品' : '重新上架',
    { type: 'warning', confirmText: offline ? '确定下架' : '确定上架' },
  )
  if (!ok) return

  busyId.value = row.id
  try {
    await changeNovelStatus(row.id, { status: target })
    ElMessage.success(offline ? '已下架' : '已重新上架')
    await paged.load()
  } catch {
    // 拦截器已提示（2003 状态不对 —— 比如别处刚把它改过了）
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.status" style="width: 130px" @change="search">
          <el-option
            v-for="o in STATUS_OPTIONS"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-select
          v-model="filter.categoryId"
          placeholder="全部分类"
          clearable
          style="width: 150px"
          @change="search"
        >
          <el-option
            v-for="c in categories"
            :key="c.id"
            :label="c.status === 1 ? c.name : `${c.name}（已停用）`"
            :value="c.id"
          />
        </el-select>

        <el-input
          v-model="filter.keyword"
          placeholder="书名关键词"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />

        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button type="success" @click="importVisible = true">导入 TXT</el-button>
      </div>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column prop="title" label="书名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="authorName" label="作者" width="120" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="100" />
        <el-table-column label="来源" width="110">
          <template #default="{ row }"><el-tag :type="row.sourceType === 'EXTERNAL' ? 'warning' : 'info'" size="small">{{ row.sourceType === 'EXTERNAL' ? '外部导入' : '平台原创' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="novelStatusType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chapterCount" label="章节" width="80" />
        <el-table-column label="字数" width="110">
          <template #default="{ row }">{{ formatWordCount(row.wordCount) }}</template>
        </el-table-column>
        <el-table-column label="阅读量" width="100">
          <template #default="{ row }">
            {{ Number(row.viewCount || 0).toLocaleString('zh-CN') }}
          </template>
        </el-table-column>
        <el-table-column label="最后更新" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastUpdateTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>

            <!-- 见文件头：只有 2 / 3 两个状态才有按钮，其余状态状态机不允许 -->
            <el-button
              v-if="canOffline(row)"
              link
              type="danger"
              :disabled="busyId === row.id"
              @click="doChangeStatus(row, 3)"
            >
              下架
            </el-button>
            <el-button
              v-else-if="canRepublish(row)"
              link
              type="success"
              :disabled="busyId === row.id"
              @click="doChangeStatus(row, 2)"
            >
              重新上架
            </el-button>
            <!--
              ★ 「待审核」的那本要说一句为什么没按钮 —— 否则管理员会以为
                是权限不够或者页面坏了，而实际上它只是该去审核中心。
            -->
            <span v-else-if="row.status === 1" class="hint">待审核 → 审核中心</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            v-if="paged.loaded.value"
            description="没有符合条件的作品"
            :image-size="80"
          />
        </template>
      </el-table>

      <el-pagination
        v-if="paged.total.value > 0"
        class="pager"
        :current-page="paged.page.value"
        :page-size="paged.size.value"
        :total="paged.total.value"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="paged.changePage"
        @size-change="paged.changeSize"
      />
    </el-card>

    <NovelImportDialog v-model="importVisible" :categories="categories" @imported="paged.reload()" />

    <!-- ==================== 详情弹窗（只读，和审核弹窗不是一回事） ==================== -->
    <el-dialog v-model="detailVisible" title="作品详情" width="760px" top="6vh">
      <div v-loading="detailLoading" class="detail-body">
        <NovelPreview :novel="detail" :chapters="detailChapters" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.hint {
  font-size: 12px;
  color: #909399;
}

/* ★ 详情弹窗要有最小高度，否则 v-loading 的转圈在内容为空时没地方显示 */
.detail-body {
  min-height: 120px;
}
</style>
