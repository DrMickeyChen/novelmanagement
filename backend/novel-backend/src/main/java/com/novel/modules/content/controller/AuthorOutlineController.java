package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.modules.content.dto.OutlineNodeCreateDTO;
import com.novel.modules.content.dto.OutlineNodeMoveDTO;
import com.novel.modules.content.dto.OutlineNodeUpdateDTO;
import com.novel.modules.content.service.OutlineService;
import com.novel.modules.content.vo.OutlineChapterVO;
import com.novel.modules.content.vo.OutlineDeleteVO;
import com.novel.modules.content.vo.OutlineNodeVO;
import com.novel.modules.content.vo.OutlineTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作者端 —— 大纲树（P4 创作中心的核心）。
 *
 * <p><b>★ 和 {@code AuthorChapterController} 一样，类上没有 {@code @RequestMapping}：</b>
 * URL 有两段前缀，硬凑一个公共前缀反而看不出它们的区别。
 * <pre>
 *   /api/author/novels/{novelId}/outline/nodes   建节点（大纲属于某本书，要带上书）
 *   /api/author/outline/nodes/{nodeId}/...       改 / 删 / 移动 / 建正文（已在操作具体节点）
 * </pre>
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/author/**} → AUTHOR 或 ADMIN），
 * 所以这个类里没有 {@code @PreAuthorize}；<b>真正的边界在 Service 里的
 * {@code requireOwnedOutlineNode} / {@code requireNodeInNovel}</b>。
 *
 * <p><b>★★ 这个类里有两个 id 来自【请求体】而不是 URL，这是本模块最容易漏的地方：</b>
 * <ul>
 *   <li>建节点时的 {@code parentId}</li>
 *   <li>拖拽时的 {@code targetParentId}</li>
 * </ul>
 * 不校验它们属于同一本书的话，作者能把自己的节点挂到<b>别人作品</b>的节点下面。
 * 那条 UPDATE 语法完全合法，症状是<b>节点在树上完全不显示、且没有任何错误</b> ——
 * 作者会以为保存失败，反复重试。
 * <p>校验在 Service 层（{@code guard.requireNodeInNovel}），
 * 写在这里只是为了让读到这个类的人知道有这回事。
 *
 * <p><b>★ 这个类里也没有任何地方接收 {@code sort} 或 {@code chapterId}：</b>
 * <ul>
 *   <li>排序由后端分配（新建时追加到末尾、拖拽时重排）—— 让前端传序号，
 *       两个标签页同时建节点会传出同一个值，而这张表上没有唯一键能拦住它</li>
 *   <li>{@code chapterId} 只能通过 {@code /chapter} 那条路径建立 ——
 *       它带着幂等和悬空自愈，开一个能直接写的口子就等于绕过那两件事</li>
 * </ul>
 *
 * @author MickeyChen
 */
@Tag(name = "16. 作者端 · 大纲树", description = "写作大纲的树形编辑。★ 需要 AUTHOR 或 ADMIN 角色")
@RestController
@RequiredArgsConstructor
public class AuthorOutlineController {

    private final OutlineService outlineService;

    /* ==================== /api/author/novels/{novelId}/outline ==================== */

    @Operation(
            summary = "取整棵大纲树",
            description = """
                    **一次返回全量嵌套结构**，不分页、不懒加载。

                    ★ 返回的是**嵌套**的树而不是扁平列表，是因为树上的状态图标长在
                    `chapter` 表上 —— 让前端组树等于让前端做一次跨表 join。
                    后端一次 `selectByIds` 就顺手拼上了，没有 N+1。

                    ★ **叶子节点的 `children` 是 `[]` 而不是 `null`**，可以直接
                    `node.children.length`。这不只是"友好"：全局配了
                    `default-property-inclusion: non_null`，为 null 的字段会
                    **整个从 JSON 里消失**，前端拿到 undefined 会 TypeError，
                    整棵树白屏 —— 而且只在"恰好没有子节点的节点"上出现。

                    ★ `roots` 是**数组**，"还没有大纲"返回 `{roots: []}`。
                    `parent_id IS NULL` 的行数据库拦不住多行（MySQL 没有条件唯一索引），
                    万一有两个根，返回数组最坏只是渲染出两棵树；
                    返回单个对象会反序列化失败 —— **整个创作中心打不开**。

                    ★ 数据有环时**不报错、不白页**：坏掉的分支被剪断 + 一条 error 日志。

                    作品不是自己的返回 `2002`。""")
    @GetMapping("/api/author/novels/{novelId}/outline")
    public Result<OutlineTreeVO> tree(@Parameter(description = "作品 id") @PathVariable Long novelId) {
        return Result.success(outlineService.tree(novelId));
    }

    @Operation(
            summary = "新建大纲节点",
            description = """
                    `parentId` 不传 = 建**根节点**（全书大纲）。一本书只能有一个根，
                    重复建返回 `3008`。

                    **节点类型** `nodeType`：1全书 2卷 3章 4细纲。不传时按父节点推导
                    （全书 → 卷 → 章 → 细纲）。★ 传了就用传的，**不做等级校验** ——
                    作者想在「卷」下面直接写一条设定而不建章节，那是他的大纲。

                    ★ 新节点一律**追加到同级末尾**，序号由后端分配，请求体里传不了。

                    ★ `parentId` 属于**别的作品**时返回 `3007`
                    （这个 id 在请求体里，不是 URL，见类注释）。

                    ★★ **返回的是整个新节点，不是 id。** 前端直接把它 push 进父节点的
                    `children` 即可 —— 若只给 id，前端拼不出能渲染的节点
                    （缺 `nodeTypeDesc`），就只能重拉整棵树，而重拉会
                    **清空所有展开状态**（`el-tree` 换数据引用时的行为），
                    作者每建一个节点整棵树就哗地收起一次。""")
    @PostMapping("/api/author/novels/{novelId}/outline/nodes")
    public Result<OutlineNodeVO> create(@PathVariable Long novelId,
                               @Valid @RequestBody OutlineNodeCreateDTO dto) {
        return Result.success("新建成功", outlineService.create(novelId, dto));
    }

    /* ==================== /api/author/outline/nodes/{nodeId} ==================== */

    @Operation(
            summary = "修改大纲节点",
            description = """
                    标题和内容**全量覆盖**。

                    ★★ **`content` 传 `null` 表示「清空」，不是「不改」。**
                    这一点在实现上有代价：MyBatis-Plus 的 `updateById` 会跳过 null 字段，
                    用它的话「清空内容」保存后内容还在，**而且不报任何错**。
                    所以后端用的是 `update(null, wrapper.set(...))`。

                    ★ **改节点标题不会同步到已关联的章节标题**，这是刻意的：
                    章节标题的写入口带着「待审核期间不能改」的守卫，
                    同步过去等于开一条绕过那个守卫的写路径，而且没有任何症状 ——
                    审核照样通过，只是审的内容悄悄变了。

                    ★ 不含位置和类型：位置走 `/move`，类型不允许改
                    （把一个「卷」改成「细纲」，它下面已建好的正文怎么办？没有答案）。""")
    @PutMapping("/api/author/outline/nodes/{nodeId}")
    public Result<Void> update(@PathVariable Long nodeId,
                               @Valid @RequestBody OutlineNodeUpdateDTO dto) {
        outlineService.update(nodeId, dto);
        return Result.success("保存成功", null);
    }

    @Operation(
            summary = "删除大纲节点（含子树）",
            description = """
                    删除该节点**及其整棵子树**。

                    ★★ **绝不删除任何正文章节。** 删写作计划不该毁掉已经写好的正文 ——
                    重建大纲是几分钟的事，正文是几小时。

                    返回值里 `linkedChapterCount` 是"其中已有正文的节点数"，
                    前端要用它提示「其中 N 章正文**未被删除**，可在章节列表中找到」——
                    这句话是必要的：用户的直觉一定是"删了大纲我的正文没了"。

                    ★ 前端弹窗要显示"将删除 N 个节点"的话，**不用再调一个计数接口**：
                    整棵树已经在浏览器里了，递归求和一行就够
                    （`1 + children.reduce(...)`）。

                    节点不存在返回 `3006`，不是自己的返回 `3007`。""")
    @DeleteMapping("/api/author/outline/nodes/{nodeId}")
    public Result<OutlineDeleteVO> delete(@PathVariable Long nodeId) {
        return Result.success("删除成功", outlineService.delete(nodeId));
    }

    @Operation(
            summary = "拖拽移动 / 排序",
            description = """
                    把节点移到 `targetParentId` 下面、排在 `beforeNodeId` 前面。

                    **入参形状是 `{targetParentId, beforeNodeId}`**，其中
                    `beforeNodeId` 传 `null` = **追加到末尾**：

                    | el-tree 的 dropType | 入参 |
                    |---|---|
                    | `inner`（拖进节点里） | `{targetParentId: 目标id, beforeNodeId: null}` |
                    | `after` | `{targetParentId: 父id, beforeNodeId: 下一个兄弟id 或 null}` |
                    | `before` | `{targetParentId: 父id, beforeNodeId: 该节点id}` |

                    ★ 为什么不用 `position` 索引：**没有任何 position 值能表达"追加到末尾"**
                    （末尾索引取决于当前有几个兄弟，算出来的一瞬间就可能过期），
                    而"拖进节点里 = 追加为最后一个子节点"恰恰是最高频的操作。

                    ★★ **前端注意**：`el-tree` 根层级的 `data.parent.data` 是一个
                    **包裹用的虚拟根，它的 id 是 `undefined`** —— 必须转成 `null` 再发，
                    否则"移到根下"会被理解成别的东西。

                    **错误码：**
                    - `3007` 目标父节点不属于这本作品
                    - `3009` 目标父节点是它**自己或自己的子孙**（会造成环）
                    - `3010` 库里本来就有环 —— ★ 和 3009 **分开**：
                      提示的是"数据坏了，请刷新"，不是"你操作错了"
                    - `3011` 移动后深度超过 20 层

                    ★ 防环是**写库之前**拦的，不是拖完才报错 ——
                    环会让组树时递归到 `StackOverflowError`，而它是 `Error`
                    不是 `Exception`，**掉不进全局异常处理器**，
                    症状是**整个创作中心白页**（不只这一本书）。""")
    @PutMapping("/api/author/outline/nodes/{nodeId}/move")
    public Result<Void> move(@PathVariable Long nodeId,
                             @Valid @RequestBody OutlineNodeMoveDTO dto) {
        outlineService.move(nodeId, dto);
        return Result.success("移动成功", null);
    }

    @Operation(
            summary = "由大纲节点创建正文",
            description = """
                    在这个大纲节点下建一个正文章节，并把 `chapter_id` 回填到节点上。

                    **★★ 这个接口是幂等的**：节点已经有正文时**不报错**，
                    返回 `created: false` + 已有章节信息。

                    **前端必须按 `created` 区分提示语：**
                    - `created: true` → 「已创建章节，正在打开」
                    - `created: false` → 「这一章已有正文，正在打开」

                    不区分的话，用户会以为多建了一章。而报错更糟 ——
                    他点第二下时意图和第一下**完全相同**，
                    报错等于说"你想做的事已经做完了，这是个错误"。

                    **三条不做的事：**
                    - ★ **不把节点内容拷成正文**：`node.content` 是剧情笔记，
                      拷了会让"空正文"永远不成立，提交审核时那道
                      `3005` 校验从此形同虚设
                    - ★ **序号用 `MAX+1`，不按树的位置算**：按位置算的话，
                      把节点从第 3 位拖到第 5 位就要重排两章正文的序号，
                      而那两章可能**已经发布**，读者正在看的目录会突然变化
                    - ★ **不重算作品字数/章节数**：新建的必是草稿，而那两个字段
                      的口径是"已发布"

                    ★ **悬空自愈**：如果 `chapter_id` 指向的章节已经被作者在
                    **章节列表**里删掉了（那条路径不经过大纲树），这里不会报错，
                    而是清空链接后重新建 —— 否则作者会被彻底卡死：
                    页面显示"已有正文"、点进去 `3001`，而界面上没有任何按钮能修。

                    ★ 连点两下只会建出**一章**（后端用 `SELECT ... FOR UPDATE` 串行化），
                    不会看到 `3004`。""")
    @PostMapping("/api/author/outline/nodes/{nodeId}/chapter")
    public Result<OutlineChapterVO> createChapter(@PathVariable Long nodeId) {
        return Result.success(outlineService.createChapter(nodeId));
    }
}
