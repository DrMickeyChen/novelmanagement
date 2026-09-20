package com.novel.modules.content.service;

import com.novel.modules.content.dto.OutlineNodeCreateDTO;
import com.novel.modules.content.dto.OutlineNodeMoveDTO;
import com.novel.modules.content.dto.OutlineNodeUpdateDTO;
import com.novel.modules.content.vo.OutlineChapterVO;
import com.novel.modules.content.vo.OutlineDeleteVO;
import com.novel.modules.content.vo.OutlineNodeVO;
import com.novel.modules.content.vo.OutlineTreeVO;

/**
 * 大纲树服务 —— P4 创作中心的核心。
 *
 * <p><b>★★ 这个模块里绝大部分代码是「防御」，不是「功能」，读之前先建立这个预期。</b>
 * 功能本身很简单：一棵树，增删改查加拖拽。难的是下面这四件事，
 * 它们各自都能让<b>整个创作中心打不开</b>（不只是某一本书）：
 * <ol>
 *   <li><b>环。</b>「把自己挂到自己的子孙下面」是一条语法完全合法的 UPDATE，
 *       数据库不会有任何抱怨。但树从此断成两截，而且组树时会递归到
 *       {@code StackOverflowError} —— <b>它是 {@code Error} 不是 {@code Exception}，
 *       掉不进 {@code GlobalExceptionHandler} 的兜底</b>，用户看到的是一个白页。
 *       → {@link #move} 的防环分支。</li>
 *   <li><b>null 作为 Map 的 key。</b>根节点的 {@code parentId} 就是 null，
 *       而 {@code Collectors.groupingBy} 遇到 null key 直接抛 NPE。
 *       「只要这本书有一个根节点，取大纲就 500」—— 100% 复现。
 *       → {@link #tree} 用 {@code computeIfAbsent} 分桶。</li>
 *   <li><b>归属校验漏了请求体里的 id。</b>建节点和拖拽都从<b>请求体</b>
 *       收一个节点 id，只校验 URL 上那个的话，就能把自己的节点挂到别人的节点下。
 *       症状是节点<b>在树上完全不显示且没有任何错误</b>。
 *       → {@link #create} 和 {@link #move} 里的 {@code requireNodeInNovel}。</li>
 *   <li><b>并发的「创建正文」。</b>连点两下会撞唯一键，
 *       用户看到的是一句和他的动作毫无关系的话。
 *       → {@link #createChapter} 的 {@code FOR UPDATE}。</li>
 * </ol>
 *
 * <p>详细的坑与取舍记录在 {@code docs/P4-创作中心.md}，
 * 代码里的注释只讲「为什么是这么写的」。
 *
 * @author MickeyChen
 */
public interface OutlineService {

    /* ==================== 读 ==================== */

    /**
     * 取整棵大纲树（<b>嵌套结构，一次全量</b>）。
     *
     * <p><b>★ 为什么是嵌套结构而不是扁平列表：</b>树节点上的状态图标长在
     * {@code chapter} 表上，扁平返回等于让前端做一次跨表 join。
     * 后端一次 {@code selectByIds} 就拼上了，没有 N+1。
     * <p>传输策略仍然是「一次全量、不分页、不懒加载」（大纲 §7.3⑦），
     * 两者说的不是同一件事。
     *
     * <p>★ 没有大纲时返回 {@code {roots: []}}，<b>不是 null</b>。
     *
     * <p>★ 数据有环时<b>不抛异常、不 StackOverflow</b>：坏掉的那个分支被剪断，
     * 打一条 {@code log.error}，其余部分正常返回。理由同一件事 ——
     * 一个坏节点不该让整个创作中心打不开。
     *
     * @param novelId 作品 id。<b>会先做归属校验</b>，不是自己的返回 {@code 2002}
     */
    OutlineTreeVO tree(Long novelId);

    /* ==================== 写 ==================== */

    /**
     * 新建节点。<b>新节点一律追加到同级末尾</b>，序号由后端分配。
     *
     * <p>★ {@code parentId} 为空 = 建根节点（全书大纲）。
     * 一本书只能有一个根，重复建返回 {@code 3008} ——
     * 这条规则<b>数据库拦不住</b>（唯一索引里 NULL 互不相等，
     * 没有「条件唯一索引」这种东西），只能在这里保证。
     *
     * <p>★ {@code parentId} 非空时<b>必须校验它属于同一本书</b>，
     * 理由见 {@code NovelAccessGuard#requireNodeInNovel}。
     *
     * <p><b>★★ 返回的是整个新节点，不是它的 id。</b>
     * 这不是「多给点信息反正没坏处」，而是前端<b>只能</b>二选一：
     * <ul>
     *   <li>返回 id → 前端拿到 id 却拼不出一个能渲染的节点
     *       （缺 {@code nodeTypeDesc}，而那个枚举的 desc 只有后端有）。
     *       于是它只能<b>重拉整棵树</b> —— 而 {@code el-tree} 换数据会
     *       <b>把所有节点的展开状态全部清空</b>（见 D-1 的实测记录），
     *       作者每建一个节点，整棵树就哗地收起一次。</li>
     *   <li>或者前端自己再写一份 {@code nodeType → 中文} 的映射表 ——
     *       第二份真值，加一个类型必然漏改一处。</li>
     * </ul>
     * 返回 VO 之后前端直接把它 push 进父节点的 {@code children} 就行，
     * 零重拉、零映射表。同样的理由见 {@code createChapter} 返回
     * {@code OutlineChapterVO} 而不是章节 id。
     *
     * @return 新建的节点（{@code children} 为空数组、{@code chapterId} 为 null）
     */
    OutlineNodeVO create(Long novelId, OutlineNodeCreateDTO dto);

    /**
     * 修改节点标题和内容（<b>全量覆盖</b>）。
     *
     * <p><b>★★ {@code content} 传 {@code null} 表示「清空」，不是「不改」。</b>
     * 实现上必须用 {@code update(null, wrapper.set(...))} ——
     * {@code updateById} 会跳过 null 字段，用它的话「清空内容」保存后内容还在，
     * <b>而且不报任何错</b>。
     *
     * <p>★ 不含位置和类型：前者走 {@link #move}，后者不允许改（见
     * {@code OutlineNodeUpdateDTO} 的类注释）。
     */
    void update(Long nodeId, OutlineNodeUpdateDTO dto);

    /**
     * 删除节点<b>及其整棵子树</b>。<b>★★ 绝不删除任何正文章节。</b>
     *
     * <p>大纲 §7.3 ② 原文说的是「级联删除<b>子树</b>」，指的是节点。
     * 删写作计划不该毁掉已经写好的正文 —— 两件事的代价完全不对等：
     * 重建大纲是几分钟的事，正文是几小时。
     * <p>所以返回值里带一个 {@code linkedChapterCount}，
     * 让前端能告诉作者「有 N 章正文还留着，在章节列表里」。
     *
     * @return 删除的节点数和其中已建正文的个数
     */
    OutlineDeleteVO delete(Long nodeId);

    /**
     * 拖拽移动 / 排序。
     *
     * <p>入参形状 {@code {targetParentId, beforeNodeId}} 的理由见
     * {@code OutlineNodeMoveDTO} 的类注释（一句话：{@code beforeNodeId = null}
     * 是「追加到末尾」唯一且稳定的表达方式）。
     *
     * <p><b>★★ 这里是全模块最危险的一处：</b>「把自己挂到自己的子孙下面」
     * 是一条语法合法的 UPDATE，但会让树断成两截，
     * 并在组树时递归到 {@code StackOverflowError}（{@code Error}，
     * 掉不进全局异常处理）→ <b>整个创作中心打不开</b>。
     * 所以写库<b>之前</b>必须拦下：
     * <ul>
     *   <li>目标父节点不属于同一本书 → {@code 3007}（同 {@link #create}）</li>
     *   <li>目标父节点是自己或自己的子孙 → {@code 3009}</li>
     *   <li>库里本来就有环 → {@code 3010}（<b>和 3009 分开</b>：
     *       前者提示的是「数据坏了」，不是「你操作错了」）</li>
     *   <li>移动后深度超过 20 层 → {@code 3011}</li>
     * </ul>
     */
    void move(Long nodeId, OutlineNodeMoveDTO dto);

    /**
     * 由大纲节点创建正文章节，并回填 {@code chapter_id}。
     *
     * <p><b>★★ 这个接口是幂等的，而且必须幂等。</b>
     * 节点已经有正文时<b>不报错</b>，返回 {@code created: false} + 已有章节信息。
     * 理由是用户点第二下时意图和第一下<b>完全相同</b>（「我要编辑这一章的正文」），
     * 报错等于说「你想做的事已经做完了，这是个错误」。
     * <p>前端要按 {@code created} 区分提示语 ——
     * 不区分的话，用户会以为多建了一章。
     *
     * <p><b>★ 并发安全靠 {@code SELECT ... FOR UPDATE}</b>（见
     * {@code OutlineNodeMapper#selectByIdForUpdate}）：不加锁时两个并发请求
     * 都读到 {@code chapter_id} 为空、都去建章，第二个撞
     * {@code uk_novel_chapter_no} → 用户看到 {@code 3004「该章节序号已存在」}，
     * 一句和他的动作毫无关系的话。加锁后后到的请求走幂等分支。
     *
     * <p><b>★ 悬空自愈：</b>{@code chapter_id} 指向的章节可能已经被作者
     * 在<b>章节列表</b>里删掉了（那条路径不经过大纲树）。这里不报错，
     * 打一条 warn、清空链接、重新建 —— 因为这是唯一能把作者彻底卡死的情形：
     * 页面显示「已有正文」，点进去 3001，而<b>界面上没有任何按钮能修</b>。
     *
     * <p><b>★ 三条不做的事，每条都有理由：</b>
     * <ul>
     *   <li><b>不拷 {@code node.content} 当正文。</b>它是剧情笔记不是正文。
     *       拷了会让「空正文」永远不成立，{@code submit} 里那道
     *       {@code CHAPTER_CONTENT_EMPTY} 从此形同虚设。</li>
     *   <li><b>不按树的位置算 {@code chapter_no}</b>，用
     *       {@code MAX+1}（{@code ChapterService#create} 内部）。
     *       按位置算的话，把节点从第 3 位拖到第 5 位就要重排两章正文的序号，
     *       而那两章可能<b>已经发布</b>，读者正在看的目录会突然变化。
     *       <b>拖大纲不该改动已发布作品的目录。</b></li>
     *   <li><b>不调 {@code syncNovelStat}。</b>新建的必是草稿，
     *       而 {@code chapter_count} 的口径是「已发布章节数」，草稿不计入。
     *       「顺手调一下反正没坏处」是错的：它会重算 4 个冗余列并
     *       {@code UPDATE novel}，凭空给这本书加一次行锁，
     *       而这个作者可能正同时开着保存章节的请求。</li>
     * </ul>
     */
    OutlineChapterVO createChapter(Long nodeId);
}
