package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.content.dto.ChapterCreateDTO;
import com.novel.modules.content.dto.OutlineNodeCreateDTO;
import com.novel.modules.content.dto.OutlineNodeMoveDTO;
import com.novel.modules.content.dto.OutlineNodeUpdateDTO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.OutlineNode;
import com.novel.modules.content.enums.OutlineNodeType;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.OutlineNodeMapper;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.OutlineService;
import com.novel.modules.content.vo.OutlineChapterVO;
import com.novel.modules.content.vo.OutlineDeleteVO;
import com.novel.modules.content.vo.OutlineNodeVO;
import com.novel.modules.content.vo.OutlineTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 大纲树服务实现。
 *
 * <p><b>★★ 改这个类之前先读这四条，它们是本类存在的全部难度所在：</b>
 * <ol>
 *   <li>{@link #tree} —— 用 {@code computeIfAbsent} 分桶，<b>绝不能用
 *       {@code Collectors.groupingBy}</b>（null key 直接 NPE）。</li>
 *   <li>{@link #move} —— 写库<b>之前</b>必须拦下「挂到自己的子孙下面」，
 *       否则会递归到 {@code StackOverflowError}，而那是 {@code Error}
 *       不是 {@code Exception}，<b>掉不进全局异常处理器</b>。</li>
 *   <li>{@link #update} 和 {@link #move} —— 需要把字段写成 {@code null} 的地方
 *       <b>只能</b>用 {@code update(null, wrapper.set(...))}，
 *       {@code updateById} 会跳过 null 字段。</li>
 *   <li>{@link #createChapter} —— 整个方法靠 {@code SELECT ... FOR UPDATE}
 *       保证幂等，而那个锁<b>只在事务里有效</b>。</li>
 * </ol>
 *
 * <p>本类刻意用「把整本书的节点全查出来，在内存里算」的写法
 * （{@link #listAll}），而不是为每种操作写一条精确的 SQL。
 * 理由：一本作品的大纲撑死几百行、几十 KB，一次查询换来的
 * 是<b>所有父子关系判断都在同一份快照上做</b> ——
 * 而「跨父移动」「防环」「级联删除」这三件事最怕的恰恰是
 * 分几次查、每次查到的东西不一致。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutlineServiceImpl implements OutlineService {

    /**
     * 大纲树的最大层数。
     *
     * <p><b>★ 这个限制防的不是「用户建得太深」，是「数据坏掉之后还能不能打开」。</b>
     * 防环逻辑（{@link #requireNoCycle}）能挡住「手滑拖出环」，
     * 但挡不住一条手工改库造出来的 5000 层深的链 ——
     * 而组树是<b>递归</b>的，5000 层足够把栈打爆，
     * 症状和环一样：{@code StackOverflowError}、整个创作中心白页。
     *
     * <p>20 层对真实网文来说绰绰有余（全书 → 卷 → 章 → 细纲 → 场景，
     * 五层就到头了），所以这个上限不会误伤任何人。
     */
    private static final int MAX_DEPTH = 20;

    private final OutlineNodeMapper outlineNodeMapper;
    private final ChapterMapper chapterMapper;
    private final NovelAccessGuard guard;
    private final ChapterService chapterService;

    /* ================================================================
     *  读：整棵树
     * ================================================================ */

    @Override
    public OutlineTreeVO tree(Long novelId) {
        guard.requireOwned(novelId);

        List<OutlineNode> all = listAll(novelId);
        Map<Long, List<OutlineNode>> byParent = groupByParent(all);
        Map<Long, Chapter> chapters = loadChapters(all);

        List<OutlineNodeVO> roots = new ArrayList<>();
        for (OutlineNode root : byParent.getOrDefault(null, List.of())) {
            OutlineNodeVO vo = buildNode(root, byParent, chapters, new LinkedHashSet<>(), 1);
            /*
             * ★ buildNode 只在「数据有环」或「超过深度上限」时返回 null。
             * 两种情况都已经打了 log.error，这里跳过即可 ——
             * 不抛异常是刻意的：一个坏节点不该让整个创作中心打不开，
             * 作者还能看到并删掉其余部分。
             */
            if (vo != null) {
                roots.add(vo);
            }
        }

        OutlineTreeVO result = new OutlineTreeVO();
        result.setRoots(roots);
        result.setTotalNodes(countNodes(roots));
        warnUnreachable(novelId, all, roots);
        return result;
    }

    /**
     * 把「库里有、但树上没有」的节点记一条 error 日志。
     *
     * <p><b>★★ 为什么需要这个：一个环在父指针结构里【永远不会被 buildNode 走到】。</b>
     * 每个节点只有一个 {@code parent_id}，所以环所在的连通分量
     * <b>没有任何一个节点以分量外的节点为父</b> —— 从根出发根本进不去。
     * 也就是说 {@link #buildNode} 里那段环检测（{@code path.add} 返回 false）
     * 是一道<b>兜底</b>，正常路径触发不到它。
     *
     * <p>那环的实际症状是什么？<b>那些节点在树上完全不出现，而且没有任何迹象。</b>
     * 作者看到的是「我明明建过的几个节点没了」，而日志上一片安静 ——
     * 这和本项目其它地方的约定不一致（越权要留痕、3010 要 log.error）。
     * <b>一次静默的数据丢失，比一条错误日志难查得多。</b>
     *
     * <p>★ 除了环，这里还捞得到另一种坏数据：{@code parent_id} 指向一个
     * 已删除（或别的作品的）节点。那种节点同样进不了 {@code byParent} 的任何一个桶。
     *
     * <p>★ 只记日志、<b>不改变返回结果</b>：作者仍然能打开创作中心、
     * 看到其余部分、把坏掉的节点删掉。抛异常的话就变成「整本书打不开了」。
     */
    private void warnUnreachable(Long novelId, List<OutlineNode> all, List<OutlineNodeVO> roots) {
        Set<Long> rendered = new HashSet<>();
        collectIds(roots, rendered);
        if (rendered.size() == all.size()) {
            return;
        }
        List<Long> lost = new ArrayList<>();
        for (OutlineNode node : all) {
            if (!rendered.contains(node.getId())) {
                lost.add(node.getId());
            }
        }
        log.error("[大纲数据异常] {} 个节点不可达（父链成环，或父节点已不存在），它们在树上不会出现"
                        + " | novelId={} nodeIds={}",
                lost.size(), novelId, lost.stream().limit(10).toList());
    }

    /** 收集树里实际渲染出来的节点 id */
    private void collectIds(Collection<OutlineNodeVO> nodes, Set<Long> out) {
        for (OutlineNodeVO node : nodes) {
            out.add(node.getId());
            collectIds(node.getChildren(), out);
        }
    }

    /**
     * 递归组树。
     *
     * @param path 当前<b>路径</b>上的节点 id，不是「访问过的全部」——
     *             这个区别是本方法最容易写错的地方，见方法末尾的 {@code finally}
     * @return 组好的节点；<b>数据有环或超过深度上限时返回 {@code null}</b>
     */
    private OutlineNodeVO buildNode(OutlineNode node,
                                    Map<Long, List<OutlineNode>> byParent,
                                    Map<Long, Chapter> chapters,
                                    Set<Long> path,
                                    int depth) {
        Long id = node.getId();

        /*
         * ★★ 环检测。
         *
         * path.add(id) 返回 false 说明这个 id 已经在【当前这条路径】上了 ——
         * 也就是说从它出发绕了一圈又回到它自己。真出现这种情况时，
         * 继续递归下去就是无限递归 → StackOverflowError，
         * 而它是 Error 不是 Exception，**掉不进 GlobalExceptionHandler 的兜底**，
         * 用户看到的是一个没有任何错误信息的白页、日志里也只有一行栈溢出。
         *
         * 这里剪断分支、留一条 error 日志，让作者至少能打开创作中心、
         * 把坏掉的节点删掉。
         */
        if (!path.add(id)) {
            log.error("[大纲数据异常] 检测到环，已剪断该分支 | novelId={} nodeId={} title={}",
                    node.getNovelId(), id, node.getTitle());
            return null;
        }

        try {
            OutlineNodeVO vo = OutlineNodeVO.of(node, chapters.get(node.getChapterId()));

            /*
             * ★ 深度上限：到顶了就把节点本身返回、不再往下展开。
             *
             * 注意这里是【返回 vo 而不是 null】——这个节点本身是好的、
             * 应该显示出来，只是它的子树太深了。返回 null 会让父节点
             * 整个消失，比"少显示几层"严重得多。
             */
            if (depth >= MAX_DEPTH) {
                log.error("[大纲数据异常] 层级超过 {} 层，已停止展开 | novelId={} nodeId={}",
                        MAX_DEPTH, node.getNovelId(), id);
                return vo;
            }

            for (OutlineNode child : byParent.getOrDefault(id, List.of())) {
                OutlineNodeVO childVO = buildNode(child, byParent, chapters, path, depth + 1);
                if (childVO != null) {
                    vo.getChildren().add(childVO);
                }
            }
            return vo;
        } finally {
            /*
             * ★★ 回溯时必须把自己从 path 里移掉 —— 这是本方法最隐蔽的一处。
             *
             * path 的语义是「【当前这条路径】上的节点」，用来检测环。
             * 如果只 add 不 remove，它的语义就变成了「访问过的全部节点」——
             * 于是同一个节点在【两个不同的分支】下出现时
             * （数据坏掉时会有），第二个分支会被误判成环而被剪掉，
             * 而且日志上会说「检测到环」，把人往完全错误的方向引。
             *
             * 放在 finally 里而不是每个 return 前各写一遍：
             * 上面有两条 return 路径 + 正常路径，写三遍迟早漏一处。
             */
            path.remove(id);
        }
    }

    /* ================================================================
     *  写：新建
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutlineNodeVO create(Long novelId, OutlineNodeCreateDTO dto) {
        guard.requireOwned(novelId);

        Long parentId = dto.getParentId();
        OutlineNodeType type;

        if (parentId == null) {
            /*
             * ★ 建根节点：一本书只能有一个。
             *
             * 这条规则数据库拦不住 —— parent_id IS NULL 的行，
             * MySQL 没有「条件唯一索引」这种东西（唯一索引里 NULL 互不相等，
             * UNIQUE(novel_id, parent_id) 对多行 NULL 完全无效）。
             * 所以只能在这里保证。
             *
             * ★ 类型强制为「全书」、忽略入参：根节点的语义就是全书大纲，
             * 允许它建成「细纲」只会让前端不知道该怎么渲染它。
             */
            if (hasRoot(novelId)) {
                throw new BusinessException(ResultCode.OUTLINE_ROOT_EXISTS);
            }
            type = OutlineNodeType.BOOK;
        } else {
            /*
             * ★★ 这一行是 P4 归属校验里唯一的洞，别删。
             *
             * parentId 来自【请求体】，不是 URL。不校验的话，
             * 作者可以把自己的节点挂到【别人作品】的节点下面 ——
             * 那条 UPDATE 语法完全合法，数据库不会有任何抱怨。
             * 而症状极其隐蔽：取大纲走的是 requireOwned(自己的 novelId)，
             * 别人的节点根本查不出来，于是自己那个节点变成孤儿，
             * 【在树上完全不显示，且没有任何错误】。
             * 作者会以为"保存失败"，再拖一次，循环。
             */
            OutlineNode parent = guard.requireNodeInNovel(novelId, parentId);

            /*
             * 类型：传了就用传的，没传就按父节点推导。
             *
             * ★ 这里【不做等级校验】（比如「卷下面只能建章」）——
             * 见 OutlineNodeType 的类注释：这类规则能靠前端隐藏按钮规避，
             * 做成后端错误码之后，作者想在「卷」下面直接写一条设定
             * 就会被拦住。那是他的大纲，不是我们的。
             */
            type = dto.getNodeType() != null
                    ? OutlineNodeType.fromCode(dto.getNodeType())
                    : (parent.getNodeType() == null
                            ? OutlineNodeType.DETAIL
                            : parent.getNodeType().defaultChildOf());

            // 深度：父节点的层数 + 1，超过上限就拒绝（否则组树时会打爆栈）
            Map<Long, OutlineNode> byId = indexById(listAll(novelId));
            if (depthFromRoot(byId, parentId) + 1 > MAX_DEPTH) {
                throw new BusinessException(ResultCode.OUTLINE_TOO_DEEP);
            }
        }

        OutlineNode node = new OutlineNode();
        node.setNovelId(novelId);
        node.setParentId(parentId);
        node.setNodeType(type);
        node.setTitle(dto.getTitle().trim());
        node.setContent(dto.getContent());
        // ★ 追加到同级末尾 —— 前端传不了 sort，理由见 OutlineNodeCreateDTO 的注释
        node.setSort(nextSort(novelId, parentId));
        /*
         * ★ status 不写：它是死列（见 OutlineNode 的类注释）。
         * 数据库给它的 DEFAULT 0，我们既不读也不维护它。
         */

        outlineNodeMapper.insert(node);

        log.info("新建大纲节点 | id={} novelId={} parentId={} type={}",
                node.getId(), novelId, parentId, type);

        /*
         * ★ 直接返回刚插入的实体转出来的 VO，【不重新查库、不重拉整棵树】。
         *
         * content 传 null 是正常的：新节点的 chapterId 一定是 null
         * （建节点不建正文，「创建正文」是另一个接口）。
         * of() 里对 null chapter 的处理就是「不填章节那四个字段」。
         *
         * ★ children 不用手工设：OutlineNodeVO 的字段初值就是 new ArrayList<>()，
         * 这正是「叶子节点的 children 绝不能是 null」那条坑的收口方式。
         */
        return OutlineNodeVO.of(node, null);
    }

    /* ================================================================
     *  写：修改
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long nodeId, OutlineNodeUpdateDTO dto) {
        guard.requireOwnedOutlineNode(nodeId);

        /*
         * ★★ 必须用 update(null, wrapper)，不能用 updateById —— 后者【跳过 null 字段】，
         * 所以「把 content 清空」这件事它根本做不到：
         * 保存返回 200、重新打开内容还在，而且【不报任何错】。
         * （同一个坑 NovelServiceImpl / ChapterServiceImpl 里各有一份实测记录。）
         *
         * ★ updateTime 要自己 set：用 update(null, wrapper) 这种「只 set 指定列」
         * 的写法时，MyBatis-Plus 的 @TableField(fill = INSERT_UPDATE) 自动填充
         * 【不会触发】。不写这一行的话，更新时间和创建时间永远相同。
         *
         * ★ 标题改了【不】同步到已关联的章节标题，这是刻意的：
         * chapter.title 的写入口只有 saveContent，它带着「PENDING 期间不能改」的
         * 守卫（审核员看着 A 版、作者换成 B 版，审核就被绕过了）。
         * 做「改节点标题同步章节标题」等于多开一条绕过那个守卫的写路径，
         * 而且【没有任何症状】—— 审核照样通过，只是审的内容变了。
         * 所以节点标题只在「创建正文」那一刻拷一次。
         */
        outlineNodeMapper.update(null, new LambdaUpdateWrapper<OutlineNode>()
                .eq(OutlineNode::getId, nodeId)
                .set(OutlineNode::getTitle, dto.getTitle().trim())
                .set(OutlineNode::getContent, dto.getContent())
                .set(OutlineNode::getUpdateTime, LocalDateTime.now()));
    }

    /* ================================================================
     *  写：删除子树
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutlineDeleteVO delete(Long nodeId) {
        OutlineNode node = guard.requireOwnedOutlineNode(nodeId);
        List<OutlineNode> all = listAll(node.getNovelId());

        Set<Long> subtree = collectSubtreeIds(all, nodeId);

        /*
         * ★ 统计「其中已有正文的节点数」。这个数字只用来给用户一句提示，
         * 正文【一个都不删】（见下面那段）。
         */
        int linkedChapterCount = 0;
        for (OutlineNode n : all) {
            if (subtree.contains(n.getId()) && n.getChapterId() != null) {
                linkedChapterCount++;
            }
        }

        /*
         * ★★ 只删节点，【绝不删除正文章节】。
         *
         * 大纲 §7.3 ② 原文说的是「级联删除【子树】」，指的是节点。
         * 删写作计划不该毁掉已经写好的正文 —— 两件事的代价完全不对等：
         * 重建大纲是几分钟的事，正文是几小时。
         *
         * ★ deleteByIds 是 @TableLogic 感知的（生成 UPDATE ... SET deleted = 1），
         * 【不需要】手写 deleted = 0。这和 ChapterMapper#logicDeleteAndFreeChapterNo
         * 完全不同 —— 那条是手写 SQL，必须自己写。这里走的是 MP 生成的方法。
         * （这也是 OutlineNodeMapper 能基本保持空接口的原因。）
         */
        outlineNodeMapper.deleteByIds(subtree);

        /*
         * ★ 这里【不】重排父节点的子序列。
         *
         * 删掉中间一个节点后，兄弟的 sort 会变成 0、2、3 这样有空洞 ——
         * 但排序仍然正确（我们比的是大小，不是连续性）。
         * 重排要发 N 条 UPDATE，换来的只是「好看」，
         * 而空洞本身不影响任何行为：新增用 MAX+1、移动用重排，
         * 都不会被空洞影响。
         */
        log.info("删除大纲子树 | nodeId={} novelId={} deletedNodes={} linkedChapterCount={}",
                nodeId, node.getNovelId(), subtree.size(), linkedChapterCount);

        OutlineDeleteVO vo = new OutlineDeleteVO();
        vo.setDeletedNodes(subtree.size());
        vo.setLinkedChapterCount(linkedChapterCount);
        return vo;
    }

    /* ================================================================
     *  写：拖拽移动
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void move(Long nodeId, OutlineNodeMoveDTO dto) {
        OutlineNode node = guard.requireOwnedOutlineNode(nodeId);
        Long novelId = node.getNovelId();
        Long targetParentId = dto.getTargetParentId();

        List<OutlineNode> all = listAll(novelId);
        Map<Long, OutlineNode> byId = indexById(all);
        Map<Long, List<OutlineNode>> byParent = groupByParent(all);

        if (targetParentId != null) {
            /*
             * ★★ 和 create 一样：targetParentId 来自【请求体】，必须校验归属。
             * 不校验就能把节点挂到别人的节点下，而它【在树上完全不显示、
             * 也没有任何错误】。详见 NovelAccessGuard#requireNodeInNovel。
             */
            guard.requireNodeInNovel(novelId, targetParentId);
        }

        /*
         * ★★ 「拖到根层级」必须拦下 —— 否则 create 的 3008 不变式会被拖拽绕过。
         *
         *   create 用 3008 保证「一本书只有一个根」，而这条规则【数据库拦不住】：
         *   parent_id IS NULL 的行，MySQL 没有「条件唯一索引」这种东西
         *   （唯一索引里 NULL 互不相等，UNIQUE(novel_id, parent_id) 对多行 NULL 完全无效）。
         *   所以它只能靠代码守 —— 而这里原来少守了一处：
         *   targetParentId 为 null 时直接把 parent_id 写成 null，
         *   于是【拖一下就能造出第二个根】。
         *
         *   ★ 这个 bug 是负向用例 17 发现的，不是看出来的。触发路径真实存在：
         *     在 el-tree 里把节点拖到根节点的【上边或下边】（dropType = prev / next），
         *     前端算出来的 targetParentId 就是 null（见 OutlineTree.vue 的 handleDrop）。
         *     症状：树上凭空多出一棵顶层树，而「全书大纲」不再包含全部内容 ——
         *     这恰恰是 OutlineTreeVO 注释里说的「roots 长度大于 1 表示数据有异常」。
         *     ★ 而它比「数据异常」更糟：这是一条【用户点得到】的路径。
         *
         *   ★ 只拦「本来不是根、却要变成根」这一种：
         *     · 节点本来就是根（parentId 为 null）→ 根之间的排序，放行（结果等价于没动）
         *     · 数据本来就有两个根 → 不在这里报错。那是 requireNoCycle 走父链时
         *       该撞上的 3010 的地盘；这里只保证【不新增】一个根。
         *
         *   ★ 前端也加了一道 allowDrop（禁止光标），和深度上限一样是
         *     「UI 挡 + 后端兜底」两层 —— 因为数据可能是别处改坏的。
         */
        if (targetParentId == null && node.getParentId() != null
                && all.stream().anyMatch(n -> n.getParentId() == null)) {
            throw new BusinessException(ResultCode.OUTLINE_ROOT_EXISTS);
        }

        /*
         * ★★ 防环 —— 本模块最危险的一处，必须在写库之前拦下。
         *
         * 「把自己挂到自己的子孙下面」是一条【语法完全合法】的 UPDATE，
         * 数据库不会有任何抱怨。但树从此断成两截，
         * 而且组树时会递归到 StackOverflowError。
         * 更糟的是它是 Error 不是 Exception，掉不进 GlobalExceptionHandler，
         * 于是【整个创作中心打不开】，不只是这一本书。
         */
        int parentDepth = requireNoCycle(byId, nodeId, targetParentId);

        // 移动后整棵子树的深度 = 新父节点的层数 + 子树自己的高度
        if (targetParentId != null) {
            int height = subtreeHeight(byParent, nodeId, new HashSet<>());
            if (parentDepth + height > MAX_DEPTH) {
                throw new BusinessException(ResultCode.OUTLINE_TOO_DEEP);
            }
        }

        // 目标父节点下现有的兄弟，按 sort 排好，★ 排除被移动的节点自己
        List<Long> targetOrder = orderedChildIds(all, targetParentId, nodeId);

        /*
         * 决定插到哪一位。
         *
         * ★ beforeNodeId == null 就是「追加到末尾」—— 这是 beforeNodeId 方案
         * 相对「传 position 索引」最关键的优势：末尾索引取决于当前有几个兄弟，
         * 而算出来的那一瞬间就可能过期；null 则永远稳定。
         */
        int insertIndex;
        if (dto.getBeforeNodeId() == null) {
            insertIndex = targetOrder.size();
        } else {
            insertIndex = targetOrder.indexOf(dto.getBeforeNodeId());
            if (insertIndex < 0) {
                /*
                 * ★★ 抛 500 而不是业务错误码，这是刻意的。
                 *
                 * 「beforeNodeId 不是 targetParentId 的子节点」这种组合
                 * 是【前端算错了】，不是用户能做出来的操作 ——
                 * 用户只会把节点拖到某个位置，三种 dropType 到入参的映射
                 * 是零算术的，正常前端不可能发出这种请求。
                 * 给业务码会把它伪装成一个"用户可以理解并纠正"的错误，
                 * 而它其实是前后端约定被打破了，必须暴露成故障。
                 */
                throw new IllegalStateException(String.format(
                        "拖拽入参不一致：beforeNodeId=%s 不是 targetParentId=%s 的子节点（nodeId=%s）",
                        dto.getBeforeNodeId(), targetParentId, nodeId));
            }
        }
        targetOrder.add(insertIndex, nodeId);

        /*
         * ★★ 跨父移动时，必须把【原父节点】剩下的孩子也重排一遍。
         *
         * 漏掉这一步的症状很典型：把第 2 个节点拖走后，原来的第 3、4 个
         * sort 还是 2、3 —— 表面看顺序没变，但从此这个列表缺了 0。
         * 真正暴露是在【几次拖拽之后】：把节点拖回来时顺序和预期不一致，
         * 而此时已经没人记得是哪一次操作造成的。不报错，无法归因。
         *
         * ★ 原地重排（父节点没变）时不需要 —— targetOrder 已经包含了
         * 全部兄弟（除了自己又插了回去），重排它就等于重排原列表。
         */
        if (!Objects.equals(node.getParentId(), targetParentId)) {
            renumber(orderedChildIds(all, node.getParentId(), nodeId));
        }

        /*
         * ★★ 改 parent_id 必须用 wrapper.set —— updateById【跳过 null 字段】，
         * 所以「移到根层级」（targetParentId 为 null）用它根本做不到：
         * 松手后节点弹回原位，而且不报任何错。
         *
         * ★ 加锁顺序提醒（同 OutlineNodeMapper#selectByIdForUpdate 的注释）：
         * 本项目涉及 novel_outline 和 chapter 两张表的写操作，
         * 一律【先锁/先写 novel_outline、后写 chapter】。
         * 反过来就是经典的 AB-BA 死锁。
         */
        outlineNodeMapper.update(null, new LambdaUpdateWrapper<OutlineNode>()
                .eq(OutlineNode::getId, nodeId)
                .set(OutlineNode::getParentId, targetParentId)
                .set(OutlineNode::getUpdateTime, LocalDateTime.now()));

        renumber(targetOrder);

        log.info("移动大纲节点 | nodeId={} from parentId={} to parentId={} beforeNodeId={}",
                nodeId, node.getParentId(), targetParentId, dto.getBeforeNodeId());
    }

    /* ================================================================
     *  写：由节点创建正文
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutlineChapterVO createChapter(Long nodeId) {
        /*
         * ★★ 必须是 ForUpdate 版本，而且必须在事务里。
         *
         * 不加锁时，「连点两下」的两个并发请求【都读到 chapter_id 为空】，
         * 都去调 chapterService.create()，第二次撞 uk_novel_chapter_no
         * （MAX+1 在 REPEATABLE READ 下读的是同一份快照，两个事务算出同一个序号）
         * → 用户看到 3004「该章节序号已存在」，
         * 一句和他的动作【毫无关系】的话，然后他多半再点一次。
         *
         * 加锁后，后到的请求会阻塞，等第一个事务提交后读到新的 chapter_id，
         * 直接走下面的幂等分支。
         *
         * ★ 而锁只在事务里有效 —— 没有 @Transactional 的话 InnoDB 在语句结束
         * 就释放锁，锁形同虚设且【不会有任何报错】。这两个 annotation
         * 是一套的，删任何一个都会静默失效。
         */
        OutlineNode node = guard.requireOwnedOutlineNodeForUpdate(nodeId);

        if (node.getChapterId() != null) {
            Chapter existing = chapterMapper.selectById(node.getChapterId());

            if (existing != null) {
                /*
                 * ★ 幂等分支：已经有正文了，不报错，返回 created: false。
                 *
                 * 用户点第二下时意图和第一下【完全相同】（"我要编辑这一章的正文"），
                 * 报错等于说"你想做的事已经做完了，这是个错误"。
                 * 前端按 created 区分提示语，让他知道没有多出一章。
                 */
                OutlineChapterVO vo = toChapterVO(existing);
                vo.setCreated(false);
                return vo;
            }

            /*
             * ★★ 悬空自愈：chapter_id 指向的章节【已经被删掉了】。
             *
             * 这会发生，因为作者可以在【章节列表】里单独删一章，
             * 那条路径不经过大纲树（OutlineServiceImpl 完全不知道）。
             *
             * 为什么这里【不报错】而是默默重建：这是唯一能把作者彻底卡死的情形 ——
             * 页面上显示"已有正文"，点进去报 3001「章节不存在」，
             * 而【界面上没有任何按钮能修】。他只能手工改数据库。
             * 与其把他堵死，不如自愈 —— 代价只是正文没了，
             * 而正文本来就已经被他删掉了。
             */
            log.warn("[悬空章节链接] outlineNodeId={} 指向的 chapterId={} 已不存在，清空链接后重建",
                    nodeId, node.getChapterId());

            outlineNodeMapper.update(null, new LambdaUpdateWrapper<OutlineNode>()
                    .eq(OutlineNode::getId, nodeId)
                    .set(OutlineNode::getChapterId, null)
                    .set(OutlineNode::getUpdateTime, LocalDateTime.now()));

            // ★ 同步内存对象，否则下面的分支判断用的还是旧值
            node.setChapterId(null);
        }

        ChapterCreateDTO dto = new ChapterCreateDTO();
        /*
         * ★ 标题：节点标题，【只在这一刻拷一次】。
         * 之后改节点标题不会同步到章节 —— 理由见 update() 里那段注释
         * （chapter.title 的写入口带着「PENDING 期间不能改」的审核守卫）。
         */
        dto.setTitle(node.getTitle());
        /*
         * ★★ 正文【故意不拷】node.content。
         *
         * node.content 是"这一段要写什么"的剧情笔记，不是正文。
         * 塞进去有两个后果：
         *   ① 作者打开新章节，看到一堆自己的笔记（很困惑）；
         *   ② 更隐蔽 —— 它让"空正文"永远不成立，
         *      ChapterService#submit 里那道 CHAPTER_CONTENT_EMPTY 从此形同虚设。
         *      而那道校验是审核质量的最后一道防线。
         */
        dto.setIsFree(1);

        /*
         * ★ 序号由 ChapterService#create 内部的 MAX+1 分配，
         * 这里【绝不能】按树的位置算 —— 按位置算意味着把节点从第 3 位
         * 拖到第 5 位就要重排两章正文的序号，而那两章可能【已经发布】，
         * 读者正在看的目录会突然变化。拖大纲不该改动已发布作品的目录。
         * 序号的定义就是"第几个建出来的"。
         *
         * ★ 这里调的是 chapterService（而不是自己写 insert）：
         * 序号分配、撞号翻译成 3004、新建的必是草稿，这三条规则的
         * 唯一实现处就在那里，复制一份出来迟早会分家。
         *
         * ★ 不调 syncNovelStat：新建的必是草稿，而 chapter_count 的口径是
         * "已发布章节数"，草稿不计入。顺手调一下"反正没坏处"是错的 ——
         * 它会重算 4 个冗余列并 UPDATE novel，凭空给这本书加一次行锁，
         * 而这个作者可能正同时开着保存章节的请求。
         */
        Long chapterId = chapterService.create(node.getNovelId(), dto);

        outlineNodeMapper.update(null, new LambdaUpdateWrapper<OutlineNode>()
                .eq(OutlineNode::getId, nodeId)
                .set(OutlineNode::getChapterId, chapterId)
                .set(OutlineNode::getUpdateTime, LocalDateTime.now()));

        Chapter created = chapterMapper.selectById(chapterId);

        log.info("由大纲节点创建正文 | outlineNodeId={} chapterId={} novelId={}",
                nodeId, chapterId, node.getNovelId());

        OutlineChapterVO vo = toChapterVO(created);
        vo.setCreated(true);
        return vo;
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 把一本书的大纲节点全查出来，<b>按 sort, id 排好序</b>。
     *
     * <p><b>★ 排序必须带上 {@code id} 兜底列。</b>{@code novel_outline.sort}
     * 有大量并列（DDL 的默认值是 0，历史数据全是 0），
     * 只按 sort 排的话顺序由 MySQL 的返回顺序决定 ——
     * <b>同一个请求刷新两次可能看到两种顺序</b>。
     * （{@code NovelServiceImpl#applyReaderSort} 里已经踩过一次同样的坑。）
     *
     * <p>而且这里排好序有个额外好处：所有「取某个父节点下的孩子」
     * 都能靠一次线性扫描完成（见 {@link #groupByParent} /
     * {@link #orderedChildIds}），不用再排一次。
     */
    private List<OutlineNode> listAll(Long novelId) {
        return outlineNodeMapper.selectList(new LambdaQueryWrapper<OutlineNode>()
                .eq(OutlineNode::getNovelId, novelId)
                .orderByAsc(OutlineNode::getSort)
                .orderByAsc(OutlineNode::getId));
    }

    /**
     * 按父节点分桶，<b>每个桶内保持 {@link #listAll} 的顺序</b>。
     *
     * <p><b>★★ 绝对不能用 {@code Collectors.groupingBy} 写这个方法。</b>
     * 它在 <b>key 为 {@code null} 时直接抛 {@code NullPointerException}</b>，
     * 而「根节点的 {@code parentId} 是 {@code null}」正是最正常的情况 ——
     * 也就是说，<b>只要这本书有一个根节点，取大纲就 500</b>。
     * 100% 复现，但只在真的有数据之后才出现，本地空库怎么测都是通的。
     *
     * <p>这行代码看上去毫无问题，是 P4 最阴的一个坑。
     */
    private Map<Long, List<OutlineNode>> groupByParent(List<OutlineNode> all) {
        Map<Long, List<OutlineNode>> byParent = new LinkedHashMap<>();
        for (OutlineNode node : all) {
            byParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
        }
        return byParent;
    }

    /** id → 节点。用于防环时沿父链往上走 */
    private Map<Long, OutlineNode> indexById(List<OutlineNode> all) {
        Map<Long, OutlineNode> byId = new HashMap<>();
        for (OutlineNode node : all) {
            byId.put(node.getId(), node);
        }
        return byId;
    }

    /**
     * 批量取节点关联的章节。
     *
     * <p><b>★ 空集合必须提前返回，不能直接丢给 {@code selectByIds}。</b>
     * 传空集合进去，MyBatis-Plus 会拼出 {@code WHERE id IN ()} ——
     * SQL 语法错误 → 500。
     * <p>而「一个节点都还没建正文」是新手作者的常态，
     * <b>也就是说这条路在首次使用时必踩</b>。
     *
     * <p><b>★★ 返回的 map 必须能接受 {@code null} 键，所以提前返回时
     * 不能写 {@code Map.of()}。</b>
     *
     * <p>这里的调用方式是把「节点上可能为空的 chapterId」直接喂进去查
     * （{@code chapters.get(node.getChapterId())}），而节点没有关联章节时
     * 那个值就是 {@code null}。{@code Map.of()} / {@code Map.copyOf()} /
     * {@code Collectors.toUnmodifiableMap} 返回的都是 JDK 的不可变 Map，
     * 它们的 {@code get(null)} <b>会抛 NullPointerException 而不是返回 null</b>
     * （{@code ImmutableCollections.MapN#get} 第一行就是
     * {@code Objects.requireNonNull(key)}）—— 而 {@code HashMap} 允许 null 键，
     * 查不到就老实返回 null。
     *
     * <p>症状很有迷惑性：<b>一本书只要「所有节点都还没建正文」，
     * {@code GET /outline} 就 500</b>；而一旦给任意一个节点建了正文，
     * {@code chapterIds} 非空、走的是下面那个 {@code HashMap}，
     * 同一个接口又完全正常。所以它会在"刚建完大纲、还没写正文"这个
     * <b>最正常的首次使用路径上必现</b>，却在之后的每一次测试里都不复现。
     */
    private Map<Long, Chapter> loadChapters(List<OutlineNode> nodes) {
        Set<Long> chapterIds = new LinkedHashSet<>();
        for (OutlineNode node : nodes) {
            if (node.getChapterId() != null) {
                chapterIds.add(node.getChapterId());
            }
        }
        if (chapterIds.isEmpty()) {
            return new HashMap<>();
        }

        Map<Long, Chapter> chapters = new HashMap<>();
        for (Chapter chapter : chapterMapper.selectByIds(chapterIds)) {
            chapters.put(chapter.getId(), chapter);
        }
        /*
         * ★ selectByIds 返回的行数可能【少于】传进去的 id 数 ——
         * 有些章节已经被删了（悬空链接）。这不是异常，
         * 那些 id 在 map 里查不到就是 null，VO 里 chapterId 仍有值、
         * 章节信息为空。前端会显示"已有正文"，
         * 用户点进去时由 createChapter 的悬空自愈分支处理。
         * 这里【不】把悬空的 chapterId 抹掉：那是掩盖问题，
         * 而且会让作者以为自己的正文凭空消失了。
         */
        return chapters;
    }

    /**
     * 取某个父节点下的孩子 id，<b>按已有顺序</b>，可排除一个。
     *
     * <p><b>★ 这里手工 new ArrayList 而不是 {@code stream().toList()} ——
     * 后者返回的是【不可变 List】</b>（{@code Collectors.toList()} 才可变，
     * 两个方法名几乎一样，<b>编译器不会有任何提示</b>）。
     * 调用方要往结果里 {@code add} 被移动的节点，用 toList() 的话
     * 会在拖拽时抛 {@code UnsupportedOperationException} → 500。
     *
     * @param excludeId 要排除的节点 id，可为 {@code null}
     */
    private List<Long> orderedChildIds(List<OutlineNode> all, Long parentId, Long excludeId) {
        List<Long> ids = new ArrayList<>();
        for (OutlineNode node : all) {
            if (!Objects.equals(node.getParentId(), parentId)) {
                continue;
            }
            if (excludeId != null && excludeId.equals(node.getId())) {
                continue;
            }
            ids.add(node.getId());
        }
        return ids;
    }

    /**
     * 把一批节点重排成 {@code 0, 1, 2, ...}。
     *
     * <p><b>★ sort 可以直接写、不用先挪到临时值</b> ——
     * {@code novel_outline} 上<b>没有唯一键</b>（只有两个普通索引），
     * 这点和 {@code chapter.chapter_no} 完全不同：
     * 那边有 {@code uk_novel_chapter_no}，腾序号得先把旧值改成 {@code -id}。
     * <p><b>反过来说：千万别给 {@code sort} 加唯一键</b>，
     * 加了之后这个方法立刻开始撞（重排过程中必然出现两个节点同值的一瞬间）。
     *
     * <p>★ 代价是有几条节点就发几条 UPDATE。拖拽是用户动作、不是热循环，
     * 同层节点数也有限，这个代价可以接受。
     * 真到了「一卷三百章、每次拖拽发三百条 UPDATE」的时候，
     * 优化方向是「只给被移动的节点算一个落在邻居之间的值」，
     * 而不是加大批量接口 —— 记在 docs/P4-创作中心.md 的待办里。
     */
    private void renumber(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            outlineNodeMapper.update(null, new LambdaUpdateWrapper<OutlineNode>()
                    .eq(OutlineNode::getId, orderedIds.get(i))
                    .set(OutlineNode::getSort, i));
        }
    }

    /**
     * 新节点追加到同级末尾时用的序号：<b>同级 {@code MAX(sort) + 1}</b>。
     *
     * <p><b>★ 为什么是 MAX+1 而不是「同级节点个数」：</b>
     * 删除节点时我们<b>不重排</b>兄弟（见 {@link #delete} 里那段），
     * 所以 sort 会有空洞（0、2、3）。用「个数」当新序号的话，
     * 空洞存在时新节点会插到<b>中间</b>去 ——
     * 作者点「新建」，节点却出现在列表中部，而且不报任何错。
     *
     * @return 没有兄弟时返回 0
     */
    private int nextSort(Long novelId, Long parentId) {
        int max = -1;
        for (OutlineNode node : listAll(novelId)) {
            if (Objects.equals(node.getParentId(), parentId)) {
                max = Math.max(max, node.getSort() == null ? 0 : node.getSort());
            }
        }
        return max + 1;
    }

    /** 这本书有没有根节点 */
    private boolean hasRoot(Long novelId) {
        return outlineNodeMapper.selectCount(new LambdaQueryWrapper<OutlineNode>()
                .eq(OutlineNode::getNovelId, novelId)
                .isNull(OutlineNode::getParentId)) > 0;
    }

    /**
     * 从 {@code nodeId} 沿父链走到根，返回它的<b>层数</b>（根 = 1）。
     *
     * <p><b>★★ 那个 {@code visited} 集合不是防御性编程，是必须的。</b>
     * 库里可能<b>本来就有</b>环（历史脏数据、手工改库、
     * 或者这个 bug 曾经的版本写进去的）。有环的话
     * {@code while (cur != null)} <b>永远走不到 null</b> ——
     * 一个 HTTP 请求把 Tomcat 的工作线程占死，
     * 比抛一个异常严重得多（异常至少会返回、会释放线程）。
     *
     * <p>★ 所以用 {@code visited.add()} 的<b>返回值</b>判断，
     * 而不是先 contains 再 add —— 后者在两行之间没有任何区别，
     * 但多一次哈希查找，而且容易在重构时被"简化"掉。
     *
     * @throws BusinessException 父链上有环 → {@code 3010}
     */
    private int depthFromRoot(Map<Long, OutlineNode> byId, Long nodeId) {
        Set<Long> visited = new HashSet<>();
        Long cur = nodeId;
        int depth = 0;

        while (cur != null) {
            if (!visited.add(cur)) {
                log.error("[大纲数据异常] 父链上检测到环 | 起点 nodeId={}", nodeId);
                throw new BusinessException(ResultCode.OUTLINE_TREE_BROKEN);
            }
            depth++;
            if (depth > MAX_DEPTH) {
                throw new BusinessException(ResultCode.OUTLINE_TOO_DEEP);
            }
            OutlineNode node = byId.get(cur);
            if (node == null) {
                break;
            }
            cur = node.getParentId();
        }
        return depth;
    }

    /**
     * 防环：确认把 {@code nodeId} 挂到 {@code targetParentId} 下面不会造出环。
     *
     * <p><b>判据方向：判「targetParentId 是不是 nodeId 自己或它的子孙」，
     * 所以从 targetParentId <u>往上走</u>父链</b> ——
     * 目标父节点通常在浅层，往上走的路更短；
     * 而「从 nodeId 往下走找子孙」要遍历整棵子树。
     *
     * <p><b>★ 两种情况给【不同的】码，这个区分很重要：</b>
     * <ul>
     *   <li>走到了 {@code nodeId} → 用户做了非法操作 → {@code 3009}
     *       「不能把节点移动到它自己的子节点下」，他能自己纠正。</li>
     *   <li>父链上有环 → <b>数据本来就是坏的</b> → {@code 3010}
     *       「大纲结构异常，请刷新后重试」。
     *       合成一个码的话，数据坏掉时会提示「不能移动到自己的子节点下」，
     *       而用户根本没做这件事 —— 他会反复重试，而日志上一条异常都没有。</li>
     * </ul>
     *
     * @return {@code targetParentId} 的层数；{@code targetParentId} 为 null 时返回 0
     */
    private int requireNoCycle(Map<Long, OutlineNode> byId, Long nodeId, Long targetParentId) {
        if (targetParentId == null) {
            // 移到根层级 —— 根没有父节点，不可能成环
            return 0;
        }

        Set<Long> visited = new HashSet<>();
        Long cur = targetParentId;
        int depth = 0;

        while (cur != null) {
            if (cur.equals(nodeId)) {
                throw new BusinessException(ResultCode.OUTLINE_PARENT_ILLEGAL);
            }
            if (!visited.add(cur)) {
                log.error("[大纲数据异常] 父链上检测到环 | nodeId={} targetParentId={}",
                        nodeId, targetParentId);
                throw new BusinessException(ResultCode.OUTLINE_TREE_BROKEN);
            }
            depth++;
            if (depth > MAX_DEPTH) {
                throw new BusinessException(ResultCode.OUTLINE_TOO_DEEP);
            }
            OutlineNode node = byId.get(cur);
            if (node == null) {
                break;
            }
            cur = node.getParentId();
        }
        return depth;
    }

    /**
     * 以 {@code rootId} 为根的子树高度（只有自己时为 1）。
     *
     * <p>★ 环保护同样是 {@code path.add()} 的返回值 ——
     * 数据坏掉时这里也会无限递归。返回 0 表示"这一支不算高度"，
     * 剪断而不是抛异常：这只是个用于深度校验的估算，
     * 不值得为它中断用户的整个拖拽操作。
     */
    private int subtreeHeight(Map<Long, List<OutlineNode>> byParent, Long rootId, Set<Long> path) {
        if (!path.add(rootId)) {
            log.error("[大纲数据异常] 计算子树高度时检测到环，已剪断 | nodeId={}", rootId);
            return 0;
        }
        try {
            int max = 0;
            for (OutlineNode child : byParent.getOrDefault(rootId, List.of())) {
                max = Math.max(max, subtreeHeight(byParent, child.getId(), path));
            }
            return max + 1;
        } finally {
            // ★ 回溯时移除，理由同 buildNode：path 是「当前路径」不是「访问过的全部」
            path.remove(rootId);
        }
    }

    /**
     * 收集以 {@code rootId} 为根的整棵子树的 id（含自己）。
     *
     * <p><b>★ 用内存 BFS 而不是 MySQL 的 {@code WITH RECURSIVE}：</b>
     * 递归 CTE 遇到环会<b>一直循环到 {@code cte_max_recursion_depth}
     * （默认 1000）才报错</b>；而我们已经知道数据<b>可能</b>有环
     * （这正是 {@link #requireNoCycle} 存在的理由）。
     * BFS 版本用 {@code result.add()} 的返回值去重，坏数据只影响那一次请求。
     *
     * <p>★ 顺带的好处：它同时被「删除」和前端弹窗的计数用着，
     * 而递归 CTE 只能躺在 SQL 里。
     */
    private Set<Long> collectSubtreeIds(List<OutlineNode> all, Long rootId) {
        Map<Long, List<OutlineNode>> byParent = groupByParent(all);

        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        result.add(rootId);
        queue.add(rootId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (OutlineNode child : byParent.getOrDefault(current, List.of())) {
                /*
                 * ★ 用 add() 的返回值判重 —— 它是环的唯一出口。
                 * 换成「先 contains 再 add」在功能上等价，但多一次查找；
                 * 换成无条件 add + 入队，遇到环就是死循环。
                 */
                if (result.add(child.getId())) {
                    queue.add(child.getId());
                }
            }
        }
        return result;
    }

    /** 章节实体 → 创建正文的返回值 */
    private OutlineChapterVO toChapterVO(Chapter chapter) {
        OutlineChapterVO vo = new OutlineChapterVO();
        if (chapter == null) {
            /*
             * ★ 理论上不会走到这里（刚 insert 完就查）。
             * 但真走到了，返回一个只有 created 的 VO 也比 NPE 好 ——
             * 而且 created 会是 false（默认值），前端会提示"已有正文"，
             * 不会误导用户以为建了一章。
             */
            log.error("[意料之外] 刚创建的章节查不到");
            return vo;
        }
        vo.setChapterId(chapter.getId());
        vo.setChapterNo(chapter.getChapterNo());
        vo.setTitle(chapter.getTitle());
        vo.setStatus(chapter.getStatus());
        vo.setStatusDesc(chapter.getStatus() == null ? null : chapter.getStatus().getDesc());
        vo.setWordCount(chapter.getWordCount());
        return vo;
    }

    /**
     * 统计树里的节点数（用于「共 N 个节点」）。
     *
     * <p>★ 注意它统计的是<b>实际渲染出来的</b>节点数 ——
     * 数据有环或超深时被剪掉的分支不计入。
     * 这个口径才是对的：作者看到的是树，数字要和树对得上。
     */
    private int countNodes(Collection<OutlineNodeVO> nodes) {
        int count = 0;
        for (OutlineNodeVO node : nodes) {
            count += 1 + countNodes(node.getChildren());
        }
        return count;
    }
}
