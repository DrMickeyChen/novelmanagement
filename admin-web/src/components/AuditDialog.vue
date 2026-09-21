<script setup>
/**
 * 审核弹窗（作品 / 章节 / 评论 / 作者申请四处共用）。
 *
 * ============================================================
 * ★★ 为什么抽成一个组件：审核有四个调用点，但只有一套规矩
 * ============================================================
 * 四个地方的「审核」是同一件事：**看完内容 → 通过或驳回**。
 * 各写一份的话，四条规矩会在四个地方各自漂移：
 *   · 驳回理由必填 —— 漏一处，那个页面就会出现「驳回没说为什么」
 *   · 确认按钮的中文文案 —— 漏一处就是一处 OK/Cancel
 *   · 提交中禁用按钮 —— 漏一处就能连点两下、审两次
 *   · 上下文要说清「通过之后会发生什么」—— 漏一处审核员不知道后果
 *
 * ============================================================
 * ★★ 驳回理由必填 —— 前端拦，后端**刻意不拦**
 * ============================================================
 * 后端 `AuditDTO` 的类注释写明了这是刻意的取舍：
 * 「从产品角度『驳回必须写理由』更好（作者才知道要改什么），
 *   但这条规则属于**审核规范**，不是**数据完整性要求** ——
 *   数据库里 audit_remark 本来就可空」，
 * 并留了话：「如果哪天要强推这个规范，加一个 @AssertTrue 即可」。
 *
 * ★ 所以这个校验放在**前端**是对的，而且是有意为之：
 *   后端把它做成硬校验会**破坏 P2 已验收的接口契约**
 *   （现在 `{"pass": false}` 不传 remark 是合法的），
 *   而这条规范真正要约束的是**人**（审核员），不是**调用方**（curl/脚本）。
 *   前端拦住就够 —— 而且拦在这里的提示更及时（不用等一个往返）。
 *
 * ============================================================
 * ============================================================
 * ★★ 四处共用的前提：差异全都做成 prop，不在组件里 if 判断
 * ============================================================
 * 四个调用点的「通过 / 驳回」并不完全一样，尤其是**评论审核**：
 *   · 动作叫「隐藏」不叫「驳回」（`rejectText`）
 *   · 理由**不必填**（`remarkRequired=false`）—— 后端就是这么定的
 *   · 审核**可逆**，已显示的不能再通过（`passDisabled` / `rejectDisabled`）
 * 所以下面那一串 prop 不是「参数太多」，是四处差异的清单。
 * 组件里**没有一处 `if (props.kind === 'comment')`** ——
 * 那种写法会让第五个调用点必须回来改这个文件。
 *
 * ============================================================
 * ★★ 通过 / 驳回是两个按钮，不是一个下拉 + 提交
 * ============================================================
 * 审核是**高频重复**动作：一个管理员可能一口气审几十章。
 * 「选下拉 → 点提交」是两次点击，两个按钮是一次 ——
 * 几十次下来差几十次点击。而且两个按钮把「通过」和「驳回」
 * 的视觉权重分开了（通过是主按钮、驳回是危险色），
 * 下拉框做不到这一点。
 */
import { ref, watch, computed } from 'vue'

const props = defineProps({
  /** v-model：弹窗是否可见 */
  modelValue: { type: Boolean, default: false },
  /** 弹窗标题，如「审核作品」「审核章节」 */
  title: { type: String, default: '审核' },
  /** 被审对象的名字，显示在标题下方（书名 / 章节标题 / 申请人） */
  targetName: { type: String, default: '' },
  /** 通过按钮的文案。★ 用动词，如「通过并上架」「通过并发布」 */
  passText: { type: String, default: '通过' },
  /** 通过之后会发生什么。★ 必须说清楚后果，审核员据此判断 */
  passHint: { type: String, default: '' },
  /** 正在提交（父组件控制） */
  loading: { type: Boolean, default: false },
  /** 预览区可折叠时，初始是否展开 */
  previewOpen: { type: Boolean, default: true },

  /* ============ 下面四个是给「评论审核」用的（P5 的 S10 补） ============ */

  /**
   * 驳回按钮的文案。默认「驳回」。
   *
   * ★ 评论审核传的是「隐藏」——
   *   它**不是**改个叫法：作品/章节驳回后作者要回去改，而评论隐藏是
   *   管理员对一条已发布内容的**处置**，没有「改完再交」这一步。
   *   文案不同不是风格问题，是这两件事在产品上就不是一回事。
   */
  rejectText: { type: String, default: '驳回' },

  /**
   * 驳回/隐藏时理由是否必填。默认 `true`。
   *
   * ★★ 评论审核传 `false`，而且这是**后端定的调子**：
   *   `CommentAuditDTO#remark` 上有一段长注释说明为什么它可选 ——
   *   章节驳回必须写理由是因为**作者会看到它**（不写就不知道改哪里），
   *   而评论人**看不到**（P5 没有「我的评论」页，没有站内信）。
   *   强制必填只会让管理员在每一行上填一个「违规」凑数，
   *   而凑出来的理由比不填更糟：它看起来像一条有效记录。
   *
   * ★ 所以这个 prop 不是「让前端松一点」，是让前端的规矩**对齐后端的规矩**。
   *   两边不一致的话，会出现「界面上拦住了、curl 却能提交」这种
   *   谁也说不清哪个才对的状态。
   */
  remarkRequired: { type: Boolean, default: true },

  /**
   * 理由输入框旁边那句提示。默认空 = 用内置那句（「驳回时必须填写……」）。
   *
   * ★ 内置那句是**跟着 `remarkRequired` 走的**，所以只在必填时才对。
   *   评论审核要自己给一句（说清「这条备注评论人看不到」），
   *   因为不说明的话，管理员会以为自己在给评论人留言。
   */
  remarkTip: { type: String, default: '' },

  /**
   * 通过按钮是否禁用。默认不禁用。
   *
   * ★★ 评论审核要用它，因为审核**不是单向的**：
   *   已显示的评论不能再「通过」（后端返回 4005），已隐藏的不能再「隐藏」。
   *   作品/章节的审核页用不到 —— 那两个页面只查 `status = 1` 的行，
   *   按钮恒可用（见 views/audit/index.vue 的文件头）。
   *   <p>★ 禁用而不是隐藏：审核员要能看到「通过」这个动作**存在**、
   *   只是当前不可用，从而明白这条评论现在是什么状态。
   *   按钮凭空消失会让人以为界面坏了。
   */
  passDisabled: { type: Boolean, default: false },

  /** 驳回按钮是否禁用。默认不禁用。理由同上 */
  rejectDisabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'submit'])

/** 备注 / 驳回理由 */
const remark = ref('')
/** 驳回理由为空时的行内错误 —— 比弹 ElMessage 更好：错误就贴在输入框上 */
const remarkError = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/**
 * ★ 每次打开都清空上一次的备注和错误。
 *
 * 不清的话：上一本审完留下的驳回理由会**带进下一本**，
 * 审核员点「驳回」时看起来已经填好了 —— 于是别人的书被填上了
 * 上一本书的理由，而且就这么提交了。
 * 这是审核流水里最难查的一类脏数据（理由存在、格式正确、但答非所问）。
 */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      remark.value = ''
      remarkError.value = ''
    }
  },
)

const previewVisible = ref(props.previewOpen)
watch(
  () => props.previewOpen,
  (v) => {
    previewVisible.value = v
  },
)

/** 通过。备注可选 —— 通过时本来也没什么要写给作者看的 */
function handlePass() {
  // ★ 两个 guard 都留着：按钮上虽然已经 :disabled，但禁用只是一层 UI，
  //   键盘回车 / 父组件误改 props 都还能走到这里
  if (props.loading || props.passDisabled) return
  emit('submit', { pass: true, remark: remark.value.trim() })
}

/**
 * 驳回 / 隐藏。★ 理由是否必填由 `remarkRequired` 决定（默认必填）。
 *
 * 空理由的提示语刻意写得**具体**（「请写明驳回原因，作者要据此修改」），
 * 而不是干巴巴的「不能为空」—— 后者会让人以为是个系统限制，
 * 前者才说清了「为什么非要你写」。
 *
 * ★ 不必填时（评论审核）传空串会让 `audit_remark` 为空 ——
 *   那是允许的，而且**不能**把它换成「无」之类的占位文本：
 *   那句占位会进 `audit_log.remark`，「管理员根本没写备注」和
 *   「管理员写了『无』」在流水里就再也分不出来了。
 */
function handleReject() {
  if (props.loading || props.rejectDisabled) return

  const text = remark.value.trim()
  if (!text && props.remarkRequired) {
    remarkError.value = '请写明驳回原因，作者要据此修改后重新提交'
    return
  }

  remarkError.value = ''
  emit('submit', { pass: false, remark: text })
}

/** 用户开始输入就把行内错误清掉 */
function onRemarkInput() {
  if (remarkError.value) remarkError.value = ''
}

function handleClose() {
  if (props.loading) return
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="760px"
    top="6vh"
    :close-on-click-modal="false"
    :close-on-press-escape="!loading"
    :show-close="!loading"
  >
    <div v-if="targetName" class="target">
      <span class="target-label">对象</span>
      <span class="target-name">{{ targetName }}</span>
    </div>

    <!-- ============ 预览区（作品简介 / 章节正文 / 评论内容） ============ -->
    <div v-if="$slots.default" class="preview-wrap">
      <div class="preview-head" @click="previewVisible = !previewVisible">
        <span>内容预览</span>
        <el-button link type="primary" size="small">
          {{ previewVisible ? '收起' : '展开' }}
        </el-button>
      </div>
      <div v-show="previewVisible" class="preview">
        <slot />
      </div>
    </div>

    <!-- ============ 备注 / 驳回理由 ============ -->
    <div class="remark-wrap">
      <!--
        ★ 标题写成「备注 / 理由」而不是「备注 / 驳回理由」：
          四处的用法里，通过时也能填备注，那时候它不是「驳回理由」。
      -->
      <div class="remark-head">
        <span>备注 / 理由</span>
        <!--
          ★ 不写 v-html：默认那句里有一个红色的 <b>，用 v-html 渲染
            就等于把一个（今天来自代码、明天可能来自配置的）字符串当 HTML 执行。
             为此保留一个 <template v-else> 分支比 v-html 便宜得多。
        -->
        <span class="remark-tip">
          <template v-if="remarkTip">{{ remarkTip }}</template>
          <template v-else>
            ★ <b>驳回时必须填写</b> —— 作者/申请人要据此知道改什么
          </template>
        </span>
      </div>
      <el-input
        v-model="remark"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        :placeholder="'通过时可不填；驳回时请写明原因'"
        @input="onRemarkInput"
      />
      <div v-if="remarkError" class="remark-error">{{ remarkError }}</div>
    </div>

    <template #footer>
      <div class="footer">
        <!-- ★ 提示语放在左边，按钮在右边：审核员一眼能看到「通过之后会怎样」 -->
        <div v-if="passHint" class="hint">{{ passHint }}</div>
        <div class="buttons">
          <el-button :disabled="loading" @click="handleClose">取消</el-button>
          <el-button
            type="danger"
            :loading="loading"
            :disabled="rejectDisabled"
            @click="handleReject"
          >
            {{ rejectText }}
          </el-button>
          <el-button
            type="primary"
            :loading="loading"
            :disabled="passDisabled"
            @click="handlePass"
          >
            {{ passText }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.target {
  margin-bottom: 14px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.target-label {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.target-name {
  font-weight: 600;
  font-size: 15px;
  word-break: break-all;
}

.preview-wrap {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  margin-bottom: 16px;
  overflow: hidden;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

/*
 * ★ 预览区必须有 max-height + overflow。
 *   章节正文动辄几千字，不封顶的话弹窗会长到屏幕外面，
 *   底部的「通过/驳回」按钮就够不着了 —— 而那两个按钮是这一页的全部目的。
 */
.preview {
  max-height: 340px;
  overflow-y: auto;
  padding: 14px 16px;
  font-size: 14px;
  line-height: 1.9;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.remark-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
}

.remark-head > span:first-child {
  font-weight: 600;
}

.remark-tip {
  color: #909399;
  font-size: 12px;
}

.remark-tip b {
  color: #f56c6c;
}

.remark-error {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.hint {
  font-size: 12px;
  color: #909399;
  text-align: left;
  line-height: 1.6;
}

.buttons {
  display: flex;
  flex-shrink: 0;
}
</style>
