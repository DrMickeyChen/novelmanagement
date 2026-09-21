<script setup>
/**
 * 公告管理 —— 列表 + 增删改 + 发布 / 下线。
 *
 * ============================================================
 * ★★ 「发布 / 下线」为什么是一个按钮，而不是单独的接口
 * ============================================================
 * 公告**没有状态机**：草稿 ↔ 发布 ↔ 下线 ↔ 再发布，来回切都合法，
 * 重复点同一个动作也是幂等的。这和作品 / 章节 / 评论 / 作者申请
 * 那四个**刻意不同** —— 那些有终态、有 `canTransitTo`，所以各有
 * 一个带动作语义的 `/audit` 接口（要传通过/驳回 + 理由）。
 *
 * 所以这里的行内按钮走的就是普通 `PUT`（把 status 翻一下），
 * 和分类管理页的「启用 / 停用」完全同一套做法。
 *
 * ★★ 但有一件事比分类复杂：`publishTime` 的行为分四种情况
 * ============================================================
 * 它由**后端**根据 status 的「变化」算，前端只是把 status 翻一下。
 * 那张表在 `api/notice.js` 的文件头，这里只留一句最需要被记住的：
 * **下线再发布 = 公告重新置顶**（发布时间会刷新），
 * **已发布时改错别字 = 不会置顶**（发布时间不动）。
 * 这两条决定了确认框里该怎么说话。
 *
 * ============================================================
 * ★★ 「下线」和「删除」在界面上必须分得清清楚楚
 * ============================================================
 *   下线（status = 0）：读者端立刻看不到，**数据还在、随时能再发** ——
 *                      而且再发的时候会重新排到最前面。
 *   删除（逻辑删）：读者端看不到，管理端列表里也没了。
 *
 * 这是管理员最容易选错的一对操作（分类页也有一对同样的：停用 vs 删除）。
 * 所以两个确认框都把「其实你想用另一个」写进去。
 *
 * ============================================================
 * ★ 列表顺序由后端定：状态升序 + id 倒序
 * ============================================================
 * 未发布的排在最前面 —— 这是个**待办队列**（「那条还没发出去的公告」
 * 就是这一页唯一有下一步动作的东西），不是一个档案页。
 * 前端不要自己 sort，理由和分类页那条一样：排序值相同时
 * 前端 sort 的结果在不同引擎上不一致，会把后端补的 id 兜底破坏掉。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getNoticePage, createNotice, updateNotice, deleteNotice } from '@/api/notice'
import { usePagedList } from '@/composables/usePagedList'
import { formatDateTime } from '@/utils/format'
import { confirm } from '@/utils/confirm'

/* ==================== 列表 ==================== */

/**
 * 筛选条件。★ `null` = 不限（axios 会跳过 null/undefined 参数，
 * 正好对上后端「不传 = 全部」）。不要用空字符串。
 * ★ 用 `ref({...})` 整体替换，和评论管理页一致 ——
 *   `reset()` 里一次赋一个新对象，比逐字段清干净。
 */
const filter = ref({ status: null, keyword: '' })

const {
  list,
  total,
  page,
  size,
  loading,
  loaded,
  load,
  changePage,
  changeSize,
  reload,
} = usePagedList(
  ({ page, size }) =>
    getNoticePage({
      status: filter.value.status,
      // ★ 空串要转成 null：后端是 `StringUtils.hasText` 判断，
      //   空串不会出事，但 `?keyword=` 会挂在 URL 上 ——
      //   排查请求时看着像「发了筛选却没生效」。
      keyword: filter.value.keyword.trim() || null,
      page,
      size,
    }),
  { pageSize: 10 },
)

onMounted(load)

/** 行内操作进行中，用来只禁用那一行的按钮 */
const busyId = ref(null)

/* ==================== 新增 / 编辑弹窗 ==================== */

const dialogVisible = ref(false)
/** 'create' | 'edit'，只影响标题和一两处文案 */
const mode = ref('create')
const saving = ref(false)
const formRef = ref(null)

/**
 * 表单。
 *
 * ★ 用 `reactive` 而不是 `ref({...})`：理由和分类页逐字相同 ——
 *   `el-form` 内部按 `model[prop]` 取值，传一个 ref 进去拿到的是
 *   **当次渲染时的那个对象**，重新赋值 `form.value = {...}` 之后就失效了。
 *   reactive 对象是稳定引用，没有这个问题。
 */
const form = reactive({ id: null, title: '', content: '', type: 1, status: 1 })

/**
 * 校验规则和后端 `NoticeSaveDTO` 上的注解**逐条对齐**：
 * `title` @NotBlank + max 200，`content` @NotBlank + max 5000，
 * `type` @NotNull + 1~2，`status` @NotNull + 0~1。
 *
 * ★ 写得比后端松 → 用户会撞上一个没预料到的后端 400；
 *   写得比后端紧 → 用户遇到「明明能填却填不进去」。
 *   所以四个字段一个都不能少，尤其是两个数字的区间。
 */
const rules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 200, message: '公告标题不能超过 200 个字', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入公告正文', trigger: 'blur' },
    { max: 5000, message: '公告正文不能超过 5000 个字', trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

function resetForm() {
  form.id = null
  form.title = ''
  form.content = ''
  form.type = 1
  form.status = 1
  // ★ 清掉上一次留下的红字，否则打开新增弹窗时上次那条
  //   「请输入公告标题」还挂在输入框下面
  formRef.value?.clearValidate()
}

function openCreate() {
  mode.value = 'create'
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  mode.value = 'edit'
  resetForm()
  form.id = row.id
  form.title = row.title
  form.content = row.content ?? ''
  form.type = row.type ?? 1
  form.status = row.status ?? 1
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const payload = {
    // ★ 必须 trim：后端 @NotBlank 只挡全空白，不挡「前后带空格」。
    //   " 维护通知" 和 "维护通知" 在界面上看起来一模一样，
    //   却是两条不同的标题 —— 搜「维护」时只能搜到其中一条。
    title: form.title.trim(),
    content: form.content.trim(),
    type: form.type,
    // ★ 这两个字段**必须显式传**：JSON 里传 null 会覆盖掉后端的字段默认值，
    //   而 NoticeSaveDTO 上挂着 @NotNull → 400。理由见 api/notice.js 的文件头。
    status: form.status,
  }

  saving.value = true
  try {
    if (mode.value === 'create') {
      await createNotice(payload)
      /*
       * ★ 提示语跟着 status 走：这两件事的后果完全不同，
       *   而「存成草稿」如果不说明，管理员会以为没保存成功
       *   （列表里能看到它，但读者端什么都没有）。
       */
      ElMessage.success(payload.status === 1 ? '已发布，读者端首页立刻可见' : '已存为未发布')
    } else {
      await updateNotice(form.id, payload)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    await reload()
  } catch {
    // 拦截器已提示（7001 不存在 / 400 参数）。
    // ★ 弹窗**不关** —— 用户刚填的内容要留着，改一下重试就行
  } finally {
    saving.value = false
  }
}

/* ==================== 发布 / 下线 ==================== */

/**
 * 翻转发布状态。
 *
 * ★★ 两个字段原样带上，否则后端会返回 400（全量覆盖接口）。
 *   这是本页最容易写错的一处调用：URL 里只有 id，
 *   看着很像 `PATCH { status: 0 }`，但它是 `PUT` 全量。
 */
async function toggleStatus(row) {
  const offline = row.status === 1

  const ok = await confirm(
    offline
      ? `「${row.title}」下线后读者端首页立刻看不到它。数据还在，随时可以再发布 —— ` +
        `而且**再发布时会重新排到最前面**（发布时间会刷新）。` +
        `如果只是想让某条内容不再置顶，用「编辑」改字不会刷新时间。`
      : `「${row.title}」发布后读者端首页**立刻**能看到它。`,
    offline ? '下线公告' : '发布公告',
    { type: 'warning', confirmText: offline ? '确定下线' : '确定发布' },
  )
  if (!ok) return

  busyId.value = row.id
  try {
    await updateNotice(row.id, {
      title: row.title,
      content: row.content ?? '',
      type: row.type ?? 1,
      status: offline ? 0 : 1,
    })
    ElMessage.success(offline ? '已下线，读者端看不到了' : '已发布，读者端立刻可见')
    // ★ 用 reload 而不是 load：状态翻转之后这一行在「状态升序」的排序里
    //   会换位置（发布 → 下线 会跳到最前面那一队），
    //   停在当前页看到的是一个被抽走一行的列表。
    await reload()
  } catch {
    // 拦截器已提示（7001 不存在 —— 比如别处刚把它删了）
  } finally {
    busyId.value = null
  }
}

/* ==================== 删除 ==================== */

async function doDelete(row) {
  const ok = await confirm(
    `「${row.title}」删除后，读者端和管理端列表里都没有了。` +
      `如果只是想让它暂时不出现，请改用「下线」—— 下线保留数据、` +
      `随时能再发布，而删除之后要重新写一遍。`,
    '删除公告',
    { type: 'warning', confirmText: '确定删除' },
  )
  if (!ok) return

  busyId.value = row.id
  try {
    await deleteNotice(row.id)
    ElMessage.success('删除成功')
    await reload()
  } catch {
    /*
     * ★ 拦截器已提示（7001 公告不存在）。
     *   这个错误在这个场景里是有价值的：它意味着「列表不是最新的，
     *   别人已经删过这一条了」，所以这里必须**重新拉一次列表**，
     *   否则那一行会一直挂在屏幕上、再点还是同样的报错。
     */
    await reload()
  } finally {
    busyId.value = null
  }
}

/* ==================== 筛选 ==================== */

/** 状态筛选变化 → 回第 1 页重查 */
function search() {
  reload()
}

function reset() {
  filter.value = { status: null, keyword: '' }
  reload()
}

/* ==================== 展示 ==================== */

/**
 * 「发布时间」那一列。
 *
 * ★ 未发布时后端**不返回** `publishTime` 这个 key（全局 non_null 约定），
 *   而它在管理端列表里是有意义的：一条从没发过的草稿，
 *   和一条发过又被下线的公告，这一列都应该让人看明白，
 *   所以这里不用「—」，用文字说明。
 */
function publishLabel(row) {
  if (row.status === 1) {
    return formatDateTime(row.publishTime) || '—'
  }
  // 下线但曾经发布过 → 保留着上次的发布时间，值得显示出来
  return row.publishTime ? `曾于 ${formatDateTime(row.publishTime)} 发布` : '从未发布'
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.status" style="width: 140px" @change="search">
          <!-- ★ value 用 null 表示「全部」：后端 eq 的条件参数为 false 时整条跳过 -->
          <el-option label="全部状态" :value="null" />
          <el-option label="已发布" :value="1" />
          <el-option label="未发布" :value="0" />
        </el-select>

        <el-input
          v-model="filter.keyword"
          placeholder="搜索标题"
          style="width: 220px"
          clearable
          @keyup.enter="search"
          @clear="search"
        />

        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>

        <div class="spacer" />

        <el-button type="primary" @click="openCreate">新增公告</el-button>
      </div>

      <!--
        ★ 这两句话必须写在界面上：
          「下线 ≠ 删除」是管理员最容易选错的一对操作；
          「未发布的排在最前面」解释了这张表看起来为什么不按时间排。
      -->
      <p class="hint">
        想让一条公告不再出现在读者端，请用「下线」而不是「删除」——
        下线保留数据、随时能再发布（并会重新排到最前面）。
        列表按「未发布在前」排序，因为这个页面主要是来发那几条还没发出去的公告的。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" row-key="id" stripe>
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />

        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <!--
              ★ 文案在前端写死：NoticeVO 没有 typeDesc。这是有意的边界 ——
                文案由后端给的只有那五个有状态机的 VO，
                而 type 只是个 1/2 的开关，见 utils/status.js 的文件头。
            -->
            <el-tag size="small" effect="plain" :type="row.type === 2 ? 'warning' : 'primary'">
              {{ row.type === 2 ? '通知' : '公告' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="正文" min-width="220">
          <template #default="{ row }">
            <span class="content-preview">{{ row.content }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '已发布' : '未发布' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">
            <span :class="{ muted: row.status !== 1 }">{{ publishLabel(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最后修改" width="150">
          <template #default="{ row }">{{ formatDateTime(row.updateTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              :disabled="busyId === row.id"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '下线' : '发布' }}
            </el-button>
            <el-button link type="danger" :disabled="busyId === row.id" @click="doDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="loaded" description="还没有任何公告" :image-size="80" />
        </template>
      </el-table>

      <!--
        ★ 总数不超过一页时不显示分页控件 —— 只有 2 条数据却挂着一条分页栏，
          看起来像页面没做完。
        ★ v-if 用 total > size 而不是 total > 0：后者在「一共 2 条、每页 10 条」
          时也会显示一个只有 1 页的分页控件。
      -->
      <div v-if="total > size" class="pager">
        <el-pagination
          layout="total, sizes, prev, pager, next"
          background
          :current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </el-card>

    <!-- ==================== 新增 / 编辑弹窗 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="mode === 'create' ? '新增公告' : '编辑公告'"
      width="600px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            maxlength="200"
            show-word-limit
            placeholder="例如：系统维护通知"
          />
        </el-form-item>

        <el-form-item label="正文" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            maxlength="5000"
            show-word-limit
            placeholder="正文会显示在读者端首页的横幅上（公告没有详情页）"
          />
        </el-form-item>

        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio :value="1">公告</el-radio>
            <el-radio :value="2">通知</el-radio>
          </el-radio-group>
          <!-- ★ 说清楚它只是个显示标记，否则管理员会以为两者行为不同 -->
          <span class="field-hint">只影响读者端横幅上的样式</span>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">立即发布</el-radio>
            <el-radio :value="0">存为未发布</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label=" ">
          <!--
            ★ 发布时间**不做成输入框**，只在这里说明它由系统管理。
              理由写在 api/notice.js 的文件头：让前端传它，
              就能造出「已发布但没有发布时间」和「发布时间在未来」
              这两种坏状态，而前者在读者端表现为「这条公告永远沉底」。
          -->
          <span class="field-hint">
            发布时间由系统自动填写：从「未发布」变成「已发布」时盖上当前时间。
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
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

/* 把「新增」推到最右边，和左边的筛选条件分开 */
.spacer {
  flex: 1;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

/* 正文预览：截断成一行，完整内容在编辑弹窗里看 */
.content-preview {
  color: #606266;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.muted {
  color: #909399;
}

.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}
</style>
