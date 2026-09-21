<script setup>
/**
 * 菜单管理 —— 系统里存在哪些「权限点」的**只读清单**。
 *
 * ============================================================
 * ★★ 这一页没有、也不该有写入口
 * ============================================================
 * 菜单树是系统的**权限清单**：它由 `docs/sql/seed.sql` 定义，每加一个
 * 权限点，前端 `AdminLayout.vue` 里那份菜单表也要同步加一项 ——
 * **改它等于改代码**。做成界面可编辑只会让人以为改完就生效了，
 * 而新菜单指向的前端路由可能根本不存在，点进去是 404。
 *
 * 后端 `AdminMenuController` 也只有一个 GET，理由写在它的类注释里。
 * 管理员真正需要的能力是「**给角色勾选**已有的菜单」，那个在角色管理页里。
 *
 * ============================================================
 * ★★ 这一页为什么值得存在：它是 `path` 那一列的「核对入口」
 * ============================================================
 * 菜单的 `path` 长期没有消费者（左侧菜单走的是 AdminLayout 里那份**写死的表**，
 * 按叶子节点的 `perms` 过滤显示），于是它可以一直写错而没人发现 ——
 * 事实上原来 12 个叶子里有 11 个写的是 `/x/list`。
 * 这一页把 `path` 显示出来，就是让「它和真实路由是否一致」变成一件
 * **看得见、可以对**的事，而不是藏在 SQL 里等二期做动态菜单时才爆发。
 *
 * ★ 也正因如此，这一页显示的是**数据本身的样子**，不做任何美化：
 *   `icon` 列显示的是库里那个字符串（`Reading`、`Bell`……），
 *   而左侧菜单今天用的是 emoji（📖、📢）—— **两者不是一个东西**。
 *   把这里显示成 emoji 会让这一页开始骗人：它就不再是「库里有什么」了。
 *
 * ============================================================
 * ★ 没有显示 `visible` 列，是有意的
 * ============================================================
 * `sys_menu` 还有一列 `visible`（1显示 0隐藏），种子里没有它、全是默认值 1，
 * 而且**当前没有任何代码读它**。它是给二期的动态菜单预留的
 * （下掉一个菜单入口但不想删掉这行数据时用）。
 * 把一列「永远是 1、谁都不读」的东西摆到界面上，和把错的 `path` 摆上去
 * 是同一类问题 —— 看起来像有含义，实际没有。所以留到最后一行说明里讲。
 */
import { ref, onMounted } from 'vue'
import { getMenuTree } from '@/api/menu'
import { menuTypeTag, menuStatusType } from '@/utils/status'

/** 整棵菜单树（嵌套）。后端已按 `sort, id` 排好序，前端不重排 */
const tree = ref([])
const loading = ref(false)
/** ★ 区分「还没查过」和「查过但为空」，理由见 composables/usePagedList.js */
const loaded = ref(false)

async function load() {
  loading.value = true
  try {
    tree.value = await getMenuTree()
    loaded.value = true
  } catch {
    // 拦截器已经弹过错误提示。吞掉，页面至少显示空态而不是永远转圈
    loaded.value = false
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 数一下一共多少项 —— 15 个菜单分三棵树，摊开了看反而不知道总数 */
function countNodes(nodes) {
  return (nodes || []).reduce((sum, n) => sum + 1 + countNodes(n.children), 0)
}
</script>

<template>
  <div>
    <el-card shadow="never" class="head-card">
      <div class="head">
        <el-button @click="load">刷新</el-button>
        <span class="count">共 {{ countNodes(tree) }} 项</span>
      </div>

      <!--
        ★ 模板里的纯文本按原样渲染，不能写 Markdown 的 ** 加粗（会显示成星号）。
      -->
      <p class="hint">
        菜单树是系统的权限清单，由 seed.sql 定义 —— 每加一个菜单，前端的路由和菜单表也要同步加，
        所以这一页是只读的，没有增删改。给角色勾选权限请去「角色管理 → 菜单权限」。
      </p>
    </el-card>

    <el-card shadow="never">
      <!--
        ★ `tree-props` 指定子节点字段名；叶子节点**没有** children 这个键
          （响应体是 non_null 的），el-table 对缺失的 children 处理是对的 ——
          当成叶子，不显示展开箭头。
        ★ `row-key` 必须有，否则树的展开状态在数据刷新后会错位。
      -->
      <el-table
        v-loading="loading"
        :data="tree"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
      >
        <el-table-column prop="name" label="菜单名" min-width="180" />

        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <!--
              ★ 文案在前端写死：MenuVO 没有 typeDesc。这是有意的边界 ——
                有状态机的那几个 VO 才由后端给文案，见 utils/status.js 文件头。
              ★ 3（按钮）种子里没有，但 schema 把它定为合法取值，所以这里留着分支。
            -->
            <el-tag size="small" effect="plain" :type="menuTypeTag(row.type)">
              {{ row.type === 1 ? '目录' : row.type === 3 ? '按钮' : '菜单' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="路由路径" min-width="150">
          <template #default="{ row }">
            <!--
              ★★ 这一列必须和 admin-web/src/router/index.js 里的真实路由一致。
                目录行（/content、/system、/ops）只是分组标识，不对应任何路由。
            -->
            <el-tag v-if="row.type === 1" size="small" type="info" effect="plain">
              {{ row.path }}
            </el-tag>
            <span v-else class="mono">{{ row.path }}</span>
          </template>
        </el-table-column>

        <el-table-column label="权限标识" min-width="180">
          <template #default="{ row }">
            <!--
              ★ 目录行的 perms 是 null（目录本身不是权限点）。
                显示成「—」而不是留白 —— 空白看起来像「这个字段没做」，
                而「—」是「这里本来就没有」，两者含义不同。
            -->
            <span v-if="row.perms" class="mono">{{ row.perms }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="图标" width="140">
          <template #default="{ row }">
            <!--
              ★ 显示的是**库里的字符串**（Element Plus 的图标名），
                不是左侧菜单那个 emoji —— 侧栏今天用的是 AdminLayout 里
                自己那份写死的表（emoji 就写在那里），没有读这一列。
                这一列是给二期动态菜单预留的。
            -->
            <span v-if="row.icon" class="mono">{{ row.icon }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column prop="sort" label="排序" width="70" align="center" />

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="menuStatusType(row.status)">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <!--
          ★ 这里【没有】「创建时间」列：`MenuVO` 根本没有 `createTime` 字段
            （出参只有 id/parentId/name/type/path/perms/icon/sort/visible/status/children）。
            加一列会永远渲染成「—」，而那是**假的信息** ——
            它看起来像「数据缺失」，实际是「这个字段不存在」。
            菜单是种子数据，一次全建好，创建时间也没有参考价值，所以不补这个字段。
        -->

        <template #empty>
          <el-empty v-if="loaded" description="没有菜单" :image-size="80" />
        </template>
      </el-table>

      <p class="hint bottom">
        停用的菜单在这里**标灰但不隐藏**：藏起来的话，「某个角色为什么会有这个权限」
        在界面上就变成了一个无法回答的问题。
        另外表里还有一列 visible（1显示 0隐藏），种子里全是 1、当前没有任何代码读它，
        是给二期动态菜单预留的（下掉入口但不删数据），所以没有在这里显示。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.count {
  color: #909399;
  font-size: 13px;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.hint.bottom {
  margin-top: 14px;
}

/* 路由和权限点是「给机器看的字符串」，用等宽字体，和中文名一眼分开 */
.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  color: #606266;
}

.none {
  color: #c0c4cc;
}
</style>
