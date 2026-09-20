<script setup>
/**
 * 作品信息表单 —— 新建和编辑【共用这一个组件】。
 *
 * ============================================================
 * ★★ 为什么新建和编辑共用一个组件，而不是两个
 * ============================================================
 * 两边的字段、校验规则、布局**完全一样**（就是 `NovelCreateDTO` 和
 * `NovelUpdateDTO`，字段一一对应）。拆成两个文件的话，加一个字段要改两处，
 * 而漏改一处不会报错 —— 表现是「新建时能填，编辑时看不到」，
 * 这种 bug 要等用户投诉才会被发现。
 *
 * 真正的差异只有三处，都是**一处判断**就能收口的：
 *   ① 初始数据：新建是空表单，编辑要先拉详情回填
 *   ② 提交调用的接口：create 还是 update
 *   ③ 标题文案：「新建作品」还是「作品信息」
 *
 * ★ 路由上体现为两个 path 指向同一个组件（见 router/index.js）——
 *   这是 vue-router 的常规用法，不是取巧。
 *
 * ★★ 用 `route.params.novelId` 判断模式，**不要用 `route.name`**：
 *   name 是「哪个路由」，params 是「什么数据」。将来如果加一条
 *   `/novel/:novelId/edit/basic` 的子路由复用这个组件，name 判断立刻失效，
 *   而 params 判断依然成立。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNovelDetail, createNovel, updateNovel } from '@/api/novel'
import { getCategories } from '@/api/category'
import NovelCoverUpload from '@/components/NovelCoverUpload.vue'
import { novelStatusType } from '@/utils/status'

const route = useRoute()
const router = useRouter()

const novelId = computed(() => {
  const raw = route.params.novelId
  return raw === undefined || raw === '' ? null : Number(raw)
})
const isEdit = computed(() => novelId.value !== null)

/* ==================== 表单 ==================== */

const formRef = ref(null)
const pageLoading = ref(false)
const saving = ref(false)

/**
 * ★ 字段名和后端 DTO **逐字对应**，不要起「更好听」的名字
 *   （比如把 categoryId 写成 category）—— 提交前还得再映射一次，
 *   而映射层正是那种「加了字段忘了加映射」的漏点。
 */
const form = reactive({
  title: '',
  cover: '',
  categoryId: null,
  intro: '',
  tags: '',
  isFinish: 0,
})

/**
 * ★ 校验规则只留「后端也有、但提前告诉用户更好」的那几条。
 *
 *   `maxlength` 属性已经硬性挡住了超长输入，所以这里**不再写 max 规则** ——
 *   写了的后果是那条 message 永远不可能出现，属于会腐烂的死代码。
 *   真正要防的是「绕过前端直接调接口」，那是后端 @Size 的职责。
 */
const rules = {
  title: [{ required: true, message: '请输入书名', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

/** 当前作品的状态（编辑模式才有），用来判断能不能改 */
const novelStatus = ref(null)

/**
 * 详情没拉到（不存在 / 不是自己的书）。
 *
 * ★ 这个标记的作用是「在跳转真正发生之前，表单绝不能是可提交的」——
 *   `router.replace` 是异步的，这一帧里表单已经渲染出来了。
 *   少了它，那一瞬间提交出去的请求带着 `categoryId: null`，
 *   后端会返回一个和真实问题（书不是你的）毫无关系的校验错误。
 */
const loadFailed = ref(false)

/**
 * `NovelStatus.isEditable()` 的前端对应物：0 草稿 / 3 已下架 / 4 已驳回。
 *
 * ★ 前端这份判断**不是**为了安全，纯粹是为了不让作者填完一整个表单
 *   再被后端一句 2003 打回来。真正拦住的还是后端（`NovelServiceImpl.update`）。
 * ★ 和后端保持一致的地方在 `utils/status.js`，那边集中管状态相关的展示与判断。
 */
const canEdit = computed(() => !isEdit.value || [0, 3, 4].includes(novelStatus.value))

/**
 * 表单只读。
 *
 * ★ 两个来源：① 作品状态不允许改；② 详情压根没拉到。
 *   写成 `a || b` 而不是两个分开的 disabled ——
 *   分开写就一定会有一个地方漏（比如「创建」按钮记得了、「封面」忘了）。
 */
const readOnly = computed(() => loadFailed.value || (isEdit.value && !canEdit.value))

/* ==================== 分类 ==================== */

const categories = ref([])
const categoryFailed = ref(false)

async function loadCategories() {
  categoryFailed.value = false
  try {
    categories.value = (await getCategories()) || []
  } catch {
    // ★ 这里**不能静默**：分类拉不到，这个表单就提交不了
    //   （categoryId 是必填）。作者必须知道「不是我没选，是它没加载出来」。
    categoryFailed.value = true
    categories.value = []
  }
}

/* ==================== 加载（编辑模式） ==================== */

async function loadNovel() {
  pageLoading.value = true
  try {
    const detail = await getNovelDetail(novelId.value)
    form.title = detail.title ?? ''
    form.cover = detail.cover ?? ''
    form.categoryId = detail.categoryId ?? null
    form.intro = detail.intro ?? ''
    form.tags = detail.tags ?? ''
    form.isFinish = detail.isFinish ?? 0
    novelStatus.value = detail.status ?? null
  } catch {
    // ★ 拦截器已经提示了原因（2001 不存在 / 2002 不是自己的书）。
    //   这里把 `loadFailed` 立起来让表单整体不可用，然后退回列表 ——
    //   而不是留一个【空的、可编辑的】表单：那看起来就是个新建页，
    //   作者会重新填一遍，提交时撞上后端的 2002，白填一次。
    //
    // ★ 用 replace 不是 push：这次跳转是「修正一个不该到达的地址」，
    //   不是一次导航。用 push 的话历史里会多一条，按返回键又会回来一次。
    loadFailed.value = true
    router.replace('/works')
  } finally {
    pageLoading.value = false
  }
}

onMounted(async () => {
  // ★ 两个请求并发，不要 await 完分类再 await 详情 ——
  //   它们是独立的，串起来只是白白多等一个往返。
  await Promise.all([loadCategories(), isEdit.value ? loadNovel() : Promise.resolve()])
})

/* ==================== 提交 ==================== */

/**
 * 规范化标签。
 *
 * ★★ 只做**归一化**，不做**校验** —— 这里删掉的东西（空项、重复、多余空格）
 *    都是「作者肯定不想要的」，删掉不会有歧义。
 *    但**绝不能顺手把非法字符也过滤掉**：那会让作者以为保存成功了，
 *    而实际存进去的内容和他输入的不一样。要拒绝就明确报错。
 *
 * ★ 中英文逗号都要认：中文输入法下打出来的是 `，`，作者不会去切换。
 *   不认的话「玄幻，热血」会变成一个标签，而它看上去完全正常。
 */
function normalizeTags(raw) {
  const parts = String(raw || '')
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
  // ★ 用 Set 去重而不是 filter+indexOf：后者是 O(n²)，
  //   虽然标签最多几十个无所谓，但这是个没有理由不写对的写法
  return [...new Set(parts)].join(',')
}

/**
 * 空字符串要转成 `null` 再发。
 *
 * ★★ 因为后端用的是 `updateById`，它**会跳过 null 字段** ——
 *    这正是我们要的：传 null 表示「这一项不动」。
 *    而传空字符串 `''` 会真的把数据库里的值改成空串，
 *    于是「清空简介」和「没填简介」在数据里变成了两种状态：
 *    前者是 `''`，后者是 `NULL`。前端到处都要写 `intro || ''` 来兜，
 *    任何一处写成 `intro ?? '默认'` 就会露出一个空串而不是默认值。
 *    统一成 null 之后，「空」只有一种表示。
 */
function nullIfBlank(v) {
  const s = String(v ?? '').trim()
  return s === '' ? null : s
}

async function handleSave() {
  try {
    await formRef.value.validate()
  } catch {
    return // el-form 已经在字段下面标红了
  }

  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      cover: nullIfBlank(form.cover),
      categoryId: form.categoryId,
      intro: nullIfBlank(form.intro),
      tags: nullIfBlank(normalizeTags(form.tags)),
      isFinish: form.isFinish,
    }

    if (isEdit.value) {
      await updateNovel(novelId.value, payload)
      ElMessage.success('已保存')
      // ★ 编辑完回列表：那边会重新拉一次，卡片上的书名 / 封面立刻是新值。
      //   留在本页的话作者看不到任何变化（表单本来就是这些值），
      //   会怀疑「到底存上没有」。
      router.push('/works')
    } else {
      await createNovel(payload)
      // ★ 新建的返回值是新作品的 id，但**故意不用它跳工作台**：
      //   刚建好的书还没有大纲，工作台里是空的（见 D-5：
      //   NovelServiceImpl.create 不建根节点），作者进去只会看到「还没有大纲」。
      //   先回列表让他确认书建出来了，再自己决定是先进工作台还是先传封面。
      ElMessage.success('已保存为草稿')
      router.push('/works')
    }
  } catch {
    // ★ 常见失败：2003（待审核 / 已上架不能改）、2004（书名重复）
    //   拦截器已经弹过具体原因了，这里不重复提示。
  } finally {
    saving.value = false
  }
}

function handleCancel() {
  router.push('/works')
}
</script>

<template>
  <div v-loading="pageLoading" class="novel-edit">
    <div class="page-head">
      <h2 class="page-title">{{ isEdit ? '作品信息' : '新建作品' }}</h2>
      <el-button link @click="handleCancel">返回列表</el-button>
    </div>

    <!--
      ★ 只读态要给【明确的原因】，不能只是把表单变灰。
        「按钮点不动」和「按钮点了报错」一样让人困惑，
        而这里的规则（待审核 / 已上架不能改）本身就不直观。
    -->
    <el-alert
      v-if="readOnly"
      type="warning"
      show-icon
      :closable="false"
      class="readonly-alert"
    >
      <template #title>
        这本书当前是「{{ novelStatus === 1 ? '待审核' : '已上架' }}」，不能修改内容
      </template>
      <template v-if="novelStatus === 1">
        审核员正在看这一版。如果要改，先去
        <el-link type="primary" @click="router.push('/works')">作品列表</el-link>
        点「撤回提交」。
      </template>
      <template v-else>
        已上架的作品要先
        <el-link type="primary" @click="router.push('/works')">下架</el-link>
        才能修改。
      </template>
    </el-alert>

    <el-card shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="readOnly"
        label-width="84px"
        class="form"
      >
        <el-form-item label="书名" prop="title">
          <!-- ★ maxlength 是硬截断，作者打到第 100 个字就打不进去了。
               配合后端 @Size(max = 100)，两边都不会出现「填了却存不下」 -->
          <el-input v-model="form.title" maxlength="100" placeholder="请输入书名" />
        </el-form-item>

        <el-form-item label="封面">
          <NovelCoverUpload v-model="form.cover" :disabled="readOnly" />
        </el-form-item>

        <el-form-item label="分类" prop="categoryId">
          <el-select
            v-model="form.categoryId"
            placeholder="请选择分类"
            class="w-240"
            :loading="!categories.length && !categoryFailed"
          >
            <el-option
              v-for="c in categories"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
            <!--
              ★ 分类拉不到时给一个【可点重试】的空态，而不是一个点开什么都没有的下拉框。
                下拉框是空的这件事本身不携带任何信息 ——
                作者会以为「后台还没建分类」。
            -->
            <template v-if="categoryFailed" #empty>
              <div class="select-empty">
                分类加载失败
                <el-link type="primary" @click="loadCategories">重试</el-link>
              </div>
            </template>
          </el-select>
        </el-form-item>

        <el-form-item label="简介">
          <el-input
            v-model="form.intro"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="一两句话说清这本书讲什么，读者在详情页看到的就是这段"
          />
        </el-form-item>

        <el-form-item label="标签">
          <el-input
            v-model="form.tags"
            maxlength="200"
            placeholder="用逗号分隔，例如：热血,升级流,东方玄幻"
          />
          <!-- ★ 说清楚「逗号会被统一」：不写的话作者输入「，」之后再打开
               发现变成了「,」，会以为自己的输入被改坏了 -->
          <div class="hint">中英文逗号都可以，保存时会统一成英文逗号并去重</div>
        </el-form-item>

        <el-form-item label="连载状态">
          <el-radio-group v-model="form.isFinish">
            <el-radio :value="0">连载中</el-radio>
            <el-radio :value="1">已完结</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="readOnly"
            @click="handleSave"
          >
            {{ isEdit ? '保存' : '创建' }}
          </el-button>
          <el-button @click="handleCancel">取消</el-button>

          <!--
            ★ 编辑模式下把当前状态摆出来。
              作者在这里只能看到表单，看不到列表上那个状态标签；
              没有这一行，他会困惑「为什么保存不了」而不知道去哪看状态。
          -->
          <el-tag v-if="isEdit && novelStatus !== null" :type="novelStatusType(novelStatus)" class="status-tag">
            当前：{{ { 0: '草稿', 1: '待审核', 2: '已上架', 3: '已下架', 4: '已驳回' }[novelStatus] ?? '未知' }}
          </el-tag>
        </el-form-item>
      </el-form>
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

.readonly-alert {
  margin-bottom: 16px;
}

.form {
  max-width: 720px;
}

.w-240 {
  width: 240px;
}

.hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.select-empty {
  padding: 10px 12px;
  font-size: 13px;
  color: #909399;
}

.status-tag {
  margin-left: 12px;
}
</style>
