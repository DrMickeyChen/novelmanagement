<script setup>
/**
 * 分类管理 —— 增删改 + 启停。
 *
 * ============================================================
 * ★★ 这一页【不用】usePagedList，是故意的，不是漏了
 * ============================================================
 * `GET /api/admin/categories` 一次返回**全部**分类，没有分页参数。
 * 硬套 usePagedList 的话：分页组件要按 `total` 渲染，而后端不返回 total
 * （它不是 PageResult，是个 List）—— 只能在前端写 `total = list.length`，
 * 那就是一个**永远只有一页、但长得像分页器**的东西。
 * 分类是个位数量级的字典表，它本来就不需要分页。
 *
 * 所以这里只有一个朴素的 `load()`。`loaded` 这个标志还是要留 ——
 * 理由和 usePagedList 里那条一样：区分「还没查过」和「查过但为空」，
 * 否则首次进入时加载动画和「暂无数据」会同时出现。
 *
 * ============================================================
 * ★★ 停用 ≠ 删除，这两个操作在界面上要分得清清楚楚
 * ============================================================
 *   停用（status = 0）：读者端的分类筛选栏里不再出现，**已有作品照常挂着**，
 *                      随时能改回来。这是「这个分类暂时不用了」的正确操作。
 *   删除：**名下还有作品时后端直接拒绝（6003）**，一本书都删不掉。
 *
 * 后端刻意不做级联删除 —— 级联意味着「删一个分类顺手删掉几十本书」，
 * 而管理员点删除时想的是「这个分类不用了」，两者差得很远。
 *
 * 所以确认框里必须把「先转移或删除这些作品」「只是想暂停使用请改成停用」
 * 这两句话说给管理员听，否则他只会反复点删除然后觉得系统坏了。
 *
 * ============================================================
 * ★★ 列表顺序由后端定，前端不要自己 sort
 * ============================================================
 * 后端已经按 `sort asc, id asc` 排好了（`CategoryServiceImpl.listByStatus`）。
 * `sort` 上没有唯一约束，两个分类可以都是 sort = 10，此时 MySQL 对相同
 * 排序值的返回顺序**不保证稳定** —— 后端补了 id 兜底才定下来。
 * 前端如果再 `.sort()` 一次，等于把这个保证又破坏掉（Vue 的 sort 在不同
 * 引擎上对相等元素的处理也不一致）。**原样渲染。**
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getCategoryList,
  createCategory,
  updateCategory,
  deleteCategory,
} from '@/api/category'
import { formatDate } from '@/utils/format'
import { confirm } from '@/utils/confirm'

/* ==================== 列表 ==================== */

const list = ref([])
const loading = ref(false)
/** ★ 区分「还没查过」和「查过但为空」，见文件头 */
const loaded = ref(false)
/** 行内操作进行中，用来只禁用那一行的按钮 */
const busyId = ref(null)

async function load() {
  loading.value = true
  try {
    list.value = (await getCategoryList()) || []
  } catch {
    /*
     * ★ 拦截器已经弹过提示了，这里只负责把页面推到「空态」。
     *   不清空 list 的话，失败时屏幕上还留着上一次的数据，
     *   看起来像「刷新成功了但没变化」—— 比直接空掉更容易误导。
     */
    list.value = []
  } finally {
    loading.value = false
    loaded.value = true
  }
}

onMounted(load)

/* ==================== 新增 / 编辑弹窗 ==================== */

const dialogVisible = ref(false)
/** 'create' | 'edit'，只影响标题和一两个文案 */
const mode = ref('create')
const saving = ref(false)
const formRef = ref(null)

/**
 * 表单。
 *
 * ★ 用 `reactive` 而不是 `ref({...})`：`el-form` 的 `:model` 需要一个对象，
 *   而 `ref` 每次都要 `.value`，模板里 `form.name` 会变成 `form.value.name` 才能取到
 *   —— 而 element-plus 内部是按 `model[prop]` 取的，传 `form.value` 进去
 *   它拿到的是**当次渲染时的那个对象**，重新赋值 `form.value = {...}` 之后
 *   就会失效。reactive 对象是稳定引用，没有这个问题。
 */
const form = reactive({ id: null, name: '', sort: 0, status: 1 })

const rules = {
  /*
   * ★ 校验规则和后端 CategorySaveDTO 上的注解**逐条对齐**：
   *   name @NotBlank + max 50，sort @Min 0 @Max 9999，status @Min 0 @Max 1。
   *   前端不写的话，用户要等一个来回才知道名字超长了；
   *   写得比后端**松**的话，用户会看到一个没预料到的后端报错；
   *   写得比后端**紧**的话，用户会遇到「明明能填却填不进去」。
   */
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 50, message: '分类名称不能超过 50 个字', trigger: 'blur' },
  ],
  sort: [
    {
      type: 'number',
      min: 0,
      max: 9999,
      message: '排序值在 0 ~ 9999 之间',
      trigger: 'blur',
    },
  ],
}

function resetForm() {
  form.id = null
  form.name = ''
  form.sort = 0
  form.status = 1
  // ★ 清掉上一次留下的红字。不 clear 的话，打开新增弹窗时
  //   上次编辑时那条「名称不能为空」还挂在输入框下面
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
  form.name = row.name
  form.sort = row.sort ?? 0
  form.status = row.status ?? 1
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const payload = {
    // ★ 必须 trim：后端 @NotBlank 只挡全空白，不挡「前后带空格」——
    //   " 玄幻" 和 "玄幻" 会被当成两个不同的名字，而界面上看起来一模一样，
    //   于是「分类名称已存在」这条报错会变得完全无法理解。
    name: form.name.trim(),
    sort: Number(form.sort) || 0,
    status: form.status,
  }

  saving.value = true
  try {
    if (mode.value === 'create') {
      await createCategory(payload)
      ElMessage.success('新增成功')
    } else {
      await updateCategory(form.id, payload)
      ElMessage.success('修改成功')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 拦截器已提示（6002 重名 / 6001 不存在）。
    // ★ 弹窗**不关** —— 用户刚填的表单要留着，改个名字重试就行
  } finally {
    saving.value = false
  }
}

/* ==================== 启用 / 停用 ==================== */

/**
 * 翻转启用状态。
 *
 * ★ 给它一个独立的行内按钮，而不是让管理员「编辑 → 改单选 → 保存」三步走：
 *   停用是**高频且轻量**的操作（某个分类暂时不收了），
 *   而它正是后端在删除被拒时推荐的替代方案。
 *   藏进三层点击里的话，管理员遇到「删不掉」时不会想到还有这条路。
 */
async function toggleStatus(row) {
  const disable = row.status === 1

  const ok = await confirm(
    disable
      ? `「${row.name}」停用后，读者端的分类筛选项里就看不到它了；` +
        `已经挂在这个分类下的作品不受影响，照常显示、照常能读。随时可以再启用。`
      : `「${row.name}」启用后会重新出现在读者端的分类筛选项里。`,
    disable ? '停用分类' : '启用分类',
    { type: 'warning', confirmText: disable ? '确定停用' : '确定启用' },
  )
  if (!ok) return

  busyId.value = row.id
  try {
    /*
     * ★ 这是个**全量覆盖**的接口（PUT），不是 PATCH ——
     *   `name` 也是 @NotBlank 必填。所以必须把原有字段一起带上，
     *   只传 `{ status: 0 }` 会得到 400「分类名称不能为空」。
     *   这是本项目里最容易写错的一处接口调用。
     */
    await updateCategory(row.id, {
      name: row.name,
      sort: row.sort ?? 0,
      status: disable ? 0 : 1,
    })
    ElMessage.success(disable ? '已停用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示（6001 分类不存在 —— 比如别处刚把它删了）
  } finally {
    busyId.value = null
  }
}

/* ==================== 删除 ==================== */

async function doDelete(row) {
  const ok = await confirm(
    `「${row.name}」删除后界面上的分类名会变成空白。` +
      `如果它下面还挂着作品，系统会拒绝删除并告诉你还有几本 —— ` +
      `需要先把那些作品转移到别的分类（或删掉），才能删这个分类。` +
      `若只是不想再用了，请改用「停用」：读者端不再显示，但已有作品不受影响。`,
    '删除分类',
    { type: 'warning', confirmText: '确定删除' },
  )
  if (!ok) return

  busyId.value = row.id
  try {
    await deleteCategory(row.id)
    ElMessage.success('删除成功')
    await load()
  } catch {
    // 拦截器已提示（6003 名下还有作品 / 6001 不存在）
  } finally {
    busyId.value = null
  }
}

/* ==================== 展示 ==================== */

/** 启停按钮的文案。抽出来是因为类型和文案要成对出现，写在模板里容易改漏一边 */
function toggleText(row) {
  return row.status === 1 ? '停用' : '启用'
}
</script>

<template>
  <div>
    <!-- ==================== 顶部说明 + 新增 ==================== -->
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div class="head-left">
          <span class="head-title">分类列表</span>
          <el-tag v-if="loaded" size="small" type="info" effect="plain">
            共 {{ list.length }} 个
          </el-tag>
        </div>
        <el-button type="primary" @click="openCreate">新增分类</el-button>
      </div>
      <!--
        ★ 这句话必须写在界面上：管理员最常见的误解是
          「我不想用它了」→ 点删除 → 被拒 → 以为系统有 bug。
      -->
      <p class="hint">
        想让某个分类不再出现在读者端，请用「停用」而不是「删除」——
        停用只影响读者端的筛选栏，已有作品不受影响；删除则要求该分类下没有作品。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" row-key="id" stripe>
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />

        <el-table-column prop="sort" label="排序" width="90">
          <template #default="{ row }">
            <!-- ★ 说明它是「越小越靠前」，否则 0 和 9 哪个在前要靠猜 -->
            <span class="sort">{{ row.sort ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="130">
          <template #default="{ row }">{{ formatDate(row.createTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              :disabled="busyId === row.id"
              @click="toggleStatus(row)"
            >
              {{ toggleText(row) }}
            </el-button>
            <el-button
              link
              type="danger"
              :disabled="busyId === row.id"
              @click="doDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="loaded" description="还没有任何分类" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ==================== 新增 / 编辑弹窗 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="mode === 'create' ? '新增分类' : '编辑分类'"
      width="460px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="form.name"
            maxlength="50"
            show-word-limit
            placeholder="例如：玄幻"
            @keyup.enter="submitForm"
          />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
          <span class="field-hint">数字越小越靠前</span>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="mode === 'edit'" label=" ">
          <!--
            ★ 这个提示只在编辑时出现：改名会影响所有已挂在它下面的作品
              （读者端的分类名是实时 join 的，不是存快照），改一次全局生效。
              这是我们要的行为，但管理员改之前应该知道。
          -->
          <span class="field-hint">改名会立刻影响所有挂在这个分类下的作品</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <!--
          ★ 按钮文案手写。ElMessageBox 读不到语言包，但 el-dialog 的
            默认按钮（这里是自定义的）不受影响 —— 写出来只是为了让
            「保存中」能禁用掉取消键，避免请求还没回来用户就关了弹窗。
        -->
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head-title {
  font-weight: 600;
  font-size: 15px;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.sort {
  font-variant-numeric: tabular-nums;
  color: #606266;
}

.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
