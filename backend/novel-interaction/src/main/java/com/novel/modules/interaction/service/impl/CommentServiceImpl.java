package com.novel.modules.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.enums.CommentStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.InternalResults;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.interaction.client.AuthNicknameClient;
import com.novel.interaction.client.ContentGateway;
import com.novel.modules.interaction.dto.CommentAuditDTO;
import com.novel.modules.interaction.dto.CommentCreateDTO;
import com.novel.modules.interaction.dto.CommentReplyDTO;
import com.novel.modules.interaction.entity.NovelComment;
import com.novel.modules.interaction.mapper.NovelCommentMapper;
import com.novel.modules.interaction.service.CommentService;
import com.novel.modules.interaction.vo.CommentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评论服务实现。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /**
     * 一级评论最多<b>内联</b>返回的二级回复条数。
     *
     * <p><b>★★ 截断是必须的，而且必须配一个 {@code replyCount}：</b>
     * 一条被热评的评论底下可能有几百条回复，全部内联会让一页 10 条的列表
     * 变成一次几百行的响应；而只截断不给总数，前端就渲染不出
     * 「还有 5 条回复」—— 它会拿 {@code replies.length} 当总数，
     * 于是那个按钮<b>永远不出现</b>，因为长度永远不超过这个值。
     * <p>★ 3 这个数字的来历：一屏能舒服地显示 3 行回复摘要，
     * 再多就该让用户主动展开了。看全部走
     * {@link #replyPage}（分页），不是在列表上假装全都有。
     */
    private static final int INLINE_REPLY_LIMIT = 3;

    /**
     * 「按作品筛」时章节 id 列表的告警阈值。<b>超过只打 WARN，不截断。</b>
     *
     * <p>取值没什么讲究（一本正常连载远到不了 2000 章），它是一条
     * 「该去看看了」的线，不是一条业务规则。
     * <p>★ 为什么不截断：截断会静默返回一份不完整的列表，而
     * 「列表少了一半」正是那条 SQL 最容易犯、且不报错的错
     * （见 {@code NovelCommentMapper#selectAdminPage}）。
     */
    private static final int ADMIN_CHAPTER_FILTER_WARN_THRESHOLD = 2000;

    private final NovelCommentMapper commentMapper;

    /**
     * ★ 跨模块依赖有四个，每个都只说清一件事。
     * <b>★★ M2 把它们从同进程 DI 换成了跨进程调用，本类的业务逻辑一行没动。</b>
     *
     * <p><b>{@code ContentGateway}（原 {@code NovelService} + {@code AuditLogService}
     * + {@code NovelAccessGuard} 三个接口）：</b>
     * 借一次校验、写一条流水、问一次章节归属 —— 和 {@code HistoryServiceImpl}
     * 共用同一个窗口，方向 {@code interaction → content}。
     * <p>★ 为什么是网关而不是直接注入 {@code ContentClient}：Feign 方法返回
     * {@code Result} 信封，而「HTTP 200 不等于成功」（铁律 3）——
     * 每个调用点都得拆一次信封，<b>漏掉的那个会静默失效</b>。
     * 完整论证写在 {@code ContentGateway} 的类注释里。
     *
     * <p><b>{@code AuthNicknameClient}（novel-auth）：</b>
     * 取评论人昵称。<b>★ M2 起它换了「上游」</b> —— 改造前走的是
     * content 的 {@code ContentUserLookup}（{@code interaction → content → system}
     * 两跳），现在 {@code sys_user} 在 auth 那边，<b>就近直连少一跳</b>。
     * <p>★ 这是 M2 里 interaction 新开的一条边，但它不改依赖方向
     * （{@code interaction → system} 与 {@code content → system} 同类，
     * 都是「业务模块读基础数据」），只是换了个更近的提供方。
     *
     * <p><b>{@code StringRedisTemplate}：</b>不是 {@code RedisTemplate<String,Object>}。
     * 后者的 JSON 序列化器会把 {@code SADD} 的成员写成带 {@code "@class"} 的 JSON，
     * 也会影响 {@code SET NX} 的返回值语义 ——
     * {@code NovelViewCounter} 的类注释里为同一件事写过一大段，这里不再重复。
     */
    private final ContentGateway contentGateway;
    private final AuthNicknameClient authNicknameClient;
    private final StringRedisTemplate stringRedisTemplate;

    /* ================================================================
     *  读者侧
     * ================================================================ */

    /**
     * 某本书的评论列表。
     *
     * <p><b>★★ 刻意【不校验】作品是否存在 / 是否上架。</b>
     * 直觉上应该校验（读者端接口，别的都要校验），但校验在这里是<b>有害的</b>：
     * <ul>
     *   <li>它不会挡住任何东西。可见性条件 {@code status = 1} 已经写在 WHERE 里了
     *       —— 一条评论能被查出来，就说明管理员已经审过它、点评的是一本
     *       当时已上架的书。作品后来下架了，这些评论也就没有页面会去请求了。</li>
     *   <li>它<b>会增加</b>泄露。不校验的响应只有一种形态（一个空列表）；
     *       校验之后，「2001 作品不存在」和「空列表」的差别就是一份
     *       「某个 novelId 存不存在、上架了没有」的探针 ——
     *       而这个接口是<b>匿名</b>的，正是最不该提供探针的地方。</li>
     * </ul>
     * <p>对照 {@code HistoryServiceImpl}：那里<b>必须</b>校验，因为它要往库里
     * <b>写</b>一行带 novelId 的数据，不校验就能给任意 id 造数据。
     * 这里是只读，判据不同，结论也就不同。
     */
    @Override
    public PageResult<CommentVO> readerPage(Long novelId, long page, long size) {
        return readerPageOf(NovelComment.TARGET_TYPE_NOVEL, novelId, page, size);
    }

    @Override
    public PageResult<CommentVO> chapterReaderPage(Long chapterId, long page, long size) {
        return readerPageOf(NovelComment.TARGET_TYPE_CHAPTER, chapterId, page, size);
    }

    /**
     * 书评 / 章评的列表：<b>只差一个 {@code targetType}，所以共用一段实现。</b>
     *
     * <p>★ 但<b>公开接口</b>是两个方法、不是一个带 targetType 参数的方法 ——
     * 理由见 {@code CommentService} 的类注释：那个参数写错位置编译器看不出来。
     * 这里收成一个 <b>private</b> 方法则是安全的，因为它只有上面两个调用方，
     * 而两个调用方各自把一个<b>常量</b>传进来。
     */
    private PageResult<CommentVO> readerPageOf(int targetType, Long targetId, long page, long size) {
        /*
         * ★ 用 wrapper 而不是手写 SQL：这张表有 deleted 列，
         *   wrapper 能让 @TableLogic 自动补上 deleted = 0。
         *   手写就得自己记得写 —— 忘了的话，读者会看到已经被删掉的评论。
         *
         * ★ 可见性条件在【查询里】，不是查出来再 filter：
         *   后者只要有人在某次重构里删掉那个 if，待审和已隐藏的评论就直接对外了，
         *   而且不报错、列表只是「多几行」。
         */
        LambdaQueryWrapper<NovelComment> wrapper = new LambdaQueryWrapper<NovelComment>()
                .eq(NovelComment::getTargetType, targetType)
                .eq(NovelComment::getTargetId, targetId)
                .eq(NovelComment::getStatus, CommentStatus.VISIBLE)
                /*
                 * ★★ 只要一级评论（parent_id IS NULL）。
                 *   少了这一句，二级回复会以「一级评论」的身份混进列表 ——
                 *   它同样满足 target/status 两个条件，而且看起来一切正常，
                 *   只是列表里多出一堆没头没尾的短句，
                 *   而真正的一级评论被挤到了第二页。
                 */
                .isNull(NovelComment::getParentId)
                /*
                 * ★ 按 id 倒序 = 最新的在最上面。
                 *   不用 create_time：同一秒发的两条用时间排不稳定，
                 *   翻页时会出现「某条重复出现、另一条永远看不到」。
                 *   id 是自增主键，严格全序。
                 */
                .orderByDesc(NovelComment::getId);

        Page<NovelComment> result = commentMapper.selectPage(buildPage(page, size), wrapper);

        return withReplies(result);
    }

    /**
     * 把一页一级评论的二级回复挂上去，并补齐昵称。
     *
     * <p><b>★★ 一共只有三条 SQL（含调用方的分页查询是四条），和页大小无关：</b>
     * <pre>
     *   ① 一级评论分页（调用方已经查完）
     *   ② 一个 IN 查这一页全部父评论的子回复
     *   ③ 一个 IN 查昵称（★ 一级和二级的 userId 【合并成一个集合】只查一次）
     * </pre>
     * <p>在循环里查子回复是 10 次往返、循环里查昵称又是 10 次 ——
     * <b>功能完全正常，只是慢</b>，开发机上根本察觉不到。
     *
     * <p><b>★ 子回复按 id 正序</b>（从早到晚）：一级评论是「最新的最值得看」，
     * 而一组回复是一段对话，正着读才对。
     * <p><b>★ 子回复的 WHERE 里带着自己的 {@code status = 1}</b>，
     * 不是「父可见所以子一定可见」—— 父通过了、子被管理员驳回是完全可能的，
     * 少了这个条件，那条被驳回的回复会从父评论底下漏出来。
     */
    private PageResult<CommentVO> withReplies(Page<NovelComment> result) {
        List<NovelComment> tops = result.getRecords();
        /*
         * ★ 看这一页的人（可能为 null = 匿名），一次取出来给下面三个地方用。
         *   取三次虽然结果一样，但会让「这里到底以谁为准」变得含糊。
         */
        Long viewerId = UserContext.getUserId();
        if (tops.isEmpty()) {
            /*
             * ★ 仍然走 of(result, ...) 而不是 PageResult.empty()：
             *   后者把 total / size / pages 写死成 0 / 10 / 0，
             *   于是「翻过头了」（有 30 条、翻到第 9 页）会显示成「共 0 条」，
             *   用户以为评论被清空了。转换函数对空列表一次都不会被调用，
             *   传什么都不影响结果。
             */
            return PageResult.of(result, c -> CommentVO.forReader(c, null, viewerId));
        }

        List<Long> parentIds = tops.stream().map(NovelComment::getId).toList();
        List<NovelComment> children = commentMapper.selectList(
                new LambdaQueryWrapper<NovelComment>()
                        .in(NovelComment::getParentId, parentIds)
                        .eq(NovelComment::getStatus, CommentStatus.VISIBLE)
                        .orderByAsc(NovelComment::getId));
        Map<Long, List<NovelComment>> grouped = children.stream()
                .collect(Collectors.groupingBy(NovelComment::getParentId));

        /*
         * ★ 昵称一次 IN 查出来，不是 SQL join。
         *   ① join 的话，sys_user 是 system 模块的表，二期拆服务时
         *      comment 的查询会变成一条跨库查询（大纲 §178：服务之间不共享表）；
         *   ② 循环调 nicknameOf 的话，一页 10 条就是 10 次查询（N+1）。
         *   一次 IN + 一次内存配对照顾了两头。
         *
         * ★★ 两级合并成一个集合再查，只发一次 —— 分开查两次是
         *   「一级的人」和「二级的人」各一次，而两边很可能大部分重合
         *   （同一批人既评论也回复）。
         */
        List<NovelComment> all = new ArrayList<>(tops);
        all.addAll(children);
        Map<Long, String> nicknames = nicknamesOf(all);

        PageResult<CommentVO> page = PageResult.of(result, c -> {
            CommentVO vo = CommentVO.forReader(c, nicknames.get(c.getUserId()), viewerId);
            List<NovelComment> kids = grouped.getOrDefault(c.getId(), List.of());
            /*
             * ★ replyCount 是【总数】，replies 是【截断后的前 N 条】。
             *   两个都给，前端才能渲染出「还有 X 条回复」。
             *   （只给 replies 的话长度永远 ≤ 3，按钮永远不出现。）
             */
            vo.setReplyCount(kids.size());
            vo.setReplies(kids.stream()
                    .limit(INLINE_REPLY_LIMIT)
                    .map(k -> CommentVO.forReader(k, nicknames.get(k.getUserId()), viewerId))
                    .toList());
            return vo;
        });

        /*
         * ★ 已赞态放在【拼完之后】统一填，不在上面的转换函数里逐条查 ——
         *   在转换函数里查就是一行一次 Redis 往返（一页 10 条一级 + 最多 30 条回复
         *   = 40 次），而这个方法的全部意义就是「和页大小无关的常数次查询」。
         *   转换函数是被 PageResult 逐行调用的，它天然做不了批量。
         */
        fillLiked(page.getRecords());
        return page;
    }

    /**
     * 发表评论。
     *
     * <p><b>★★ 执行的顺序是刻意的：限流 → 校验 → 落库。</b>
     * 限流放在最前面（先占坑再干活），理由：
     * <ol>
     *   <li>限流器的职责是<b>限制一个用户能造成多少工作量</b>。
     *       先校验再限流的话，一个用非法 novelId 刷接口的人<b>每次都绕过限流</b>
     *       （校验失败 → 从没占过坑），而后台的 SELECT 一次都不会少 ——
     *       等于给攻击者开了一条不消耗额度的通道。</li>
     *   <li>代价只是「一条写错的评论也要等 10 秒才能重发」。
     *       这比「刷子能打满数据库」轻得多。</li>
     * </ol>
     *
     * <p><b>★ 频控按 {@code userId} 不按 IP：</b>
     * 一套 NAT 后面整个公司共用一个出口 IP，或者一个学校共用一个 ——
     * 按 IP 限流会让这些正常用户互相误伤，而误伤的表现是
     * 「我什么也没干，就是发不出去评论」，用户和客服都无从解释。
     * 未登录用户根本进不来（这条路径需要登录），所以 userId 一定有值。
     */
    @Override
    public Long create(Long novelId, CommentCreateDTO dto) {
        Long userId = UserContext.requireUserId();

        // 第 1 步：占坑。★ 单条 SET NX EX，不是「先 GET 再 SET」—— 见 requireNotTooFast
        requireNotTooFast(userId);

        /*
         * 第 2 步：作品必须存在且已上架。
         * ★ 复用 ContentGateway#requirePublished —— 它保证「不存在」和「未上架」
         *   返回同一个码（2001），所以这个接口不能被当成探针。
         *   这也是唯一能挡住「给一本没上架的书刷评论」的地方：
         *   评论落库之后读者虽然看不到（status=0），但它会出现在管理端的待审队列里，
         *   变成一次需要管理员人工清理的噪音。
         */
        contentGateway.requirePublished(novelId);

        return insertComment(userId, NovelComment.TARGET_TYPE_NOVEL, novelId, null,
                dto.getContent(), dto.getScore());
    }

    /**
     * 发表章评。<b>多出的三道校验和它们的顺序，逐字照抄 {@code HistoryServiceImpl#report}</b>
     * —— 那是本项目里另一处「路径上同时有 novelId 和 chapterId」的地方，
     * 同一个形状的请求不该有两套校验。
     *
     * <p><b>★ 频控和书评共用</b>：两个接口是同一个「发评论」动作，
     * 各限各的等于额度直接翻倍，而限流器要限制的正是「一个用户能造成多少工作量」。
     */
    @Override
    public Long createChapterComment(Long novelId, Long chapterId, CommentCreateDTO dto) {
        Long userId = UserContext.requireUserId();

        requireNotTooFast(userId);

        // 第 1 步：作品必须存在且已上架
        contentGateway.requirePublished(novelId);

        /*
         * 第 2 步：章节必须存在，且确实属于上面那本作品。
         * ★ 两个 id 都来自 URL，所以必须两边对上 —— 只按 chapterId 查的话，
         *   把 chapterId 换成别的书的章节就能给一本完全无关的书刷评论。
         */
        ChapterStatus chapterStatus = contentGateway.requireChapterStatus(novelId, chapterId);

        /*
         * 第 3 步：章节必须是已发布的。
         * ★ 这一步单独存在、不能并入第 2 步：requireChapterStatus 只回答
         *   「这一章在不在这本书里」，不回答「读者能不能看到它」。
         *   少了它，作者还没提交审核的草稿章、被驳回的章，都能被评论 ——
         *   而「评论成功 / 失败」的差别就是一把探针，
         *   能反推出一本未上架的书里有哪些章节 id。
         */
        if (!chapterStatus.isVisibleToReader()) {
            log.debug("发表章评：章节未发布，拒绝 | userId={} novelId={} chapterId={} status={}",
                    userId, novelId, chapterId, chapterStatus);
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }

        /*
         * ★ score 传 null，和 reply 一样 —— 章评不打分。
         *   判据就在 CommentCreateDTO#score 上：「评分……仅书评有」。
         *   之前这里传的是 dto.getScore()，等于把那条声明放空了：
         *   交上来的分会被存进库、显示在章评行上，却进不了 novel.score
         *   （算子里有 target_type = 1），而读者看到自己给过的星星
         *   会以为它算进了这本书的评分 —— 一个不报错的静默落差。
         */
        return insertComment(userId, NovelComment.TARGET_TYPE_CHAPTER, chapterId, null,
                dto.getContent(), null);
    }

    @Override
    public Long reply(Long parentId, CommentReplyDTO dto) {
        Long userId = UserContext.requireUserId();

        requireNotTooFast(userId);

        /*
         * ★ requireVisibleComment 一次挡住四种情况：不存在 / 已删除 /
         *   待审核 / 已隐藏，统一返回 4001。所以「回复一条还没过审的评论」
         *   和「回复一条不存在的评论」在调用方看来完全一样。
         */
        NovelComment parent = requireVisibleComment(parentId);

        /*
         * ★★ 只允许两级：父评论自己已经有 parentId，说明它是二级回复，
         *   那么这次回复就是三级 —— 直接拒绝，不静默挂到祖父下面。
         *   静默展平比明确拒绝更难解释：用户明明回复的是 B，
         *   界面上却挂在 A 下面，而且看起来「就是这样」。
         */
        if (parent.getParentId() != null) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_NOT_ALLOWED);
        }

        /*
         * ★★ targetType / targetId 从父评论【继承】。
         *   让调用方传的话，就会出现「父是书评、子填了章评」这种行 ——
         *   它在列表里表现为「这条回复干脆不出现」（查询按 target 过滤），
         *   而库里那条数据看起来完全正常。
         *
         * ★ score 传 null：回复不打分。CommentReplyDTO 上根本没有这个字段，
         *   这里传 null 是「类型层面已经封死」之后的第二道 ——
         *   万一以后有人给 DTO 加了 score，这一行的 null 会让它不生效，
         *   而 novel.score 的算子里有 score IS NOT NULL，脏分进不去。
         */
        return insertComment(userId, parent.getTargetType(), parent.getTargetId(), parentId,
                dto.getContent(), null);
    }

    /**
     * 三个写入路径（发书评 / 发章评 / 回复）共用的落库那一段。
     *
     * <p><b>★★ 为什么是「共用一个 private 方法」而不是把三处各写一遍：</b>
     * 这里定的是评论<b>出生时的形态</b> —— 状态恒为待审核、点赞数恒为 0、
     * 正文去掉首尾空白。三处各写一遍的话，改一处忘一处不会有任何症状，
     * 而漏掉的那一处会让读者能自己把评论设成「已显示」（审核形同虚设）。
     *
     * @param parentId 一级评论传 {@code null}
     * @param score    回复传 {@code null}（回复不打分）
     */
    private Long insertComment(Long userId, Integer targetType, Long targetId, Long parentId,
                               String content, Integer score) {
        NovelComment entity = new NovelComment();
        entity.setUserId(userId);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setParentId(parentId);
        /*
         * ★ 状态写死 PENDING，不从 dto 取 —— DTO 上根本没有这个字段。
         *   让调用方能指定状态，就是让读者自己把评论设成「已显示」，审核形同虚设。
         */
        entity.setStatus(CommentStatus.PENDING);
        entity.setContent(content.trim());
        entity.setScore(score);
        /*
         * ★ likeCount 显式置 0，不靠列默认值。
         *   列上是 NOT NULL DEFAULT 0，但 MP 的 INSERT 策略是「null 的字段不写」，
         *   于是这个字段会被整个从 INSERT 里省掉、由数据库兜底 —— 也是一样的结果。
         *   显式写出来是为了让读代码的人一眼看到「新评论的点赞数是 0」，
         *   而不用去翻建表语句。（createTime / deleted 同理，但这两个
         *   一个由 MybatisMetaObjectHandler 填、一个由列默认值兜，都不该在这里 set。）
         */
        entity.setLikeCount(0);

        commentMapper.insert(entity);
        /*
         * ★ insert 之后 entity.id 被回填（@TableId(type = IdType.AUTO)
         *   + useGeneratedKeys），可以直接返回。
         *
         * ★ 这里【不记 audit_log】—— 见 audit() 里的注释：
         *   audit_log 记的是「状态流转」，而评论一出生就是待审核，
         *   没有「提交」这一次流转。
         */
        return entity.getId();
    }

    /**
     * 展开一条一级评论的全部二级回复（分页）。
     *
     * <p>★ 走 wrapper 而不是手写 SQL：这张表有 {@code deleted} 列，
     * wrapper 让 {@code @TableLogic} 自动补上 {@code deleted = 0}。
     *
     * <p>★ <b>这里不调用 {@code requireVisibleComment}</b>：传一个不存在的
     * 评论 id 会得到空列表而不是 4001。理由是接口的形状 ——
     * 它没有「操作」任何东西，只是「读这条评论下面有什么」，
     * 而一个没有回复的评论和一个不存在的评论在调用方看来是同一件事。
     * （对照 {@code reply}：那里必须挡，因为它在<b>写</b>一行指向这个 id 的数据。）
     */
    @Override
    public PageResult<CommentVO> replyPage(Long commentId, long page, long size) {
        Page<NovelComment> result = commentMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<NovelComment>()
                        .eq(NovelComment::getParentId, commentId)
                        .eq(NovelComment::getStatus, CommentStatus.VISIBLE)
                        /*
                         * ★ 正序：一级评论是「最新的最值得看」，回复是一段对话，
                         *   从早到晚读才对。
                         */
                        .orderByAsc(NovelComment::getId));

        Map<Long, String> nicknames = nicknamesOf(result.getRecords());
        Long viewerId = UserContext.getUserId();
        PageResult<CommentVO> voPage = PageResult.of(result,
                c -> CommentVO.forReader(c, nicknames.get(c.getUserId()), viewerId));

        /* ★ 展开出来的回复也有点赞按钮，所以同样要填已赞态。同 withReplies 的理由 */
        fillLiked(voPage.getRecords());
        return voPage;
    }

    @Override
    public int like(Long commentId) {
        Long userId = UserContext.requireUserId();
        NovelComment comment = requireVisibleComment(commentId);

        /*
         * ★★ 防重复点赞靠 Redis 的 Set，因为库里【没有】comment_like 表
         *    （22 张表已逐张核实）。SADD 返回 1 表示「这个用户是第一次点」，
         *    0 表示已经点过 —— 单条命令，Redis 单线程保证原子性。
         *
         * ★★ 已知的三个限制，都写在这里而不是分散在各处：
         *   ① Redis 被清空 / 实例被换掉之后，Set 没了 → 同一个用户可以再点一次，
         *      计数会虚高。**点赞数是展示型数据，虚高 1 可以接受，
         *      但「谁点过」这件事本身就不精确了** —— 涉及钱的场景绝不能照抄这套做法。
         *   ② 这两个键【没有过期时间】。上界是「有点赞的评论数」（不是点赞人次），
         *      量级可控。刻意不设 TTL：设了之后「同一个人可以再点一次」会变成
         *      一个按时间随机发生的现象，比键一直留着更难解释。
         *      二期 interaction 独立部署时随它的 Redis 策略一起定。
         *   ③ P5 时这里写着「方案里只定义了点赞，取消点赞 P6 再补 DELETE」。
         *      P6 补上了，就是这个类下面的 {@link #unlike} —— 两个方法共用
         *      {@link #refreshLikeCount}，上面 ①② 两条限制对它们是同一件事。
         */
        String likeKey = RedisKeyConstant.LIKE_USER + commentId;
        Long added = stringRedisTemplate.opsForSet().add(likeKey, String.valueOf(userId));

        if (added != null && added == 1L) {
            refreshLikeCount(commentId);
        }

        /*
         * ★ 重新读一次，返回【库里真实的值】，而不是「算出来的那个数」。
         *   算出来的数字在并发下是错的：两个人同时点赞，两边都算出同一个数，
         *   前端会看到数字「卡住不动」，看起来像没点上。
         *
         * ★ 读不到（这一瞬间评论被删了）就退回上一次读到的值，不报错 ——
         *   点赞成功与否已经确定了，为了一个展示数字回滚是过度反应。
         */
        NovelComment fresh = commentMapper.selectById(commentId);
        return fresh == null ? comment.getLikeCount() : fresh.getLikeCount();
    }

    @Override
    public int unlike(Long commentId) {
        Long userId = UserContext.requireUserId();
        NovelComment comment = requireVisibleComment(commentId);

        /*
         * ★★ 取消点赞【不需要判「这条评论是不是我的」】——
         *   Set 的成员是 userId，这里删的只能是自己的那个成员。
         *   （对照 delete()：那个必须判归属，因为它删的是整行。）
         *
         * ★ 幂等：SREM 返回 0（本来就没赞过）是【成功】，不报错 ——
         *   用户的意图是「让它变成未赞」，而它已经是了。
         */
        String likeKey = RedisKeyConstant.LIKE_USER + commentId;
        Long removed = stringRedisTemplate.opsForSet().remove(likeKey, String.valueOf(userId));

        if (removed != null && removed == 1L) {
            refreshLikeCount(commentId);
        }

        /* ★ 同 like()：返回库里真实的值，不返回「刚读到的 -1」 */
        NovelComment fresh = commentMapper.selectById(commentId);
        return fresh == null ? comment.getLikeCount() : fresh.getLikeCount();
    }

    /**
     * 用 Redis Set 的基数重算 {@code like_count} 并写回，返回新值。
     *
     * <p><b>★★ 重算，不是 +1 / -1。</b> 同 S2 的 {@code ShelfServiceImpl#refreshCollectCount}
     * 和 S6 的 {@code novel.score}：<b>冗余列一律由权威数据重算</b>。
     * 这里 Set 是权威（「谁赞过」只有它知道），{@code like_count} 是它的投影。
     *
     * <p><b>★ 什么时候两者会分叉：</b>Redis 被清空或换了实例之后 Set 没了，
     * 而 {@code like_count} 还留着旧值。±1 会把这个差<b>永远保留下去</b>
     * （点一次 +1、取消一次 -1，差值原封不动）；重算则在下一次点赞或取消时
     * 把它修回来 —— 表现是<b>数字突然变小</b>，那是修复而不是错误。
     * <p>★ {@code SCARD} 永远 ≥ 0，所以重算本身不会写出负数；
     * {@code Math.max(0, ...)} 留着只是为了「万一这段被改坏了」也不会把负数写进库。
     */
    private int refreshLikeCount(Long commentId) {
        Long count = stringRedisTemplate.opsForSet()
                .size(RedisKeyConstant.LIKE_USER + commentId);
        int value = count == null ? 0 : Math.max(0, count.intValue());

        /*
         * ★ entity 传 null，所以 MybatisMetaObjectHandler 的 updateFill
         *   【不会执行】—— 这张表根本没有 update_time 列，
         *   传一个 entity 进去反而会踩「Unknown column」那个坑。
         *   （★ 这也是本类所有 UPDATE 都走 update(null, wrapper) 的原因，
         *   不只是风格问题。）
         */
        commentMapper.update(null, new LambdaUpdateWrapper<NovelComment>()
                .eq(NovelComment::getId, commentId)
                .set(NovelComment::getLikeCount, value));
        return value;
    }

    /**
     * 评论状态变了之后，重算它所属作品的 {@code novel.score}。
     *
     * <p><b>★★ 只在书评上做。</b> 评分的算子里有 {@code target_type = 1}，
     * 章评的 {@code target_id} 是 chapterId —— 传进来只会算出一个和这本书
     * 无关的数字（撞号时是「另一本书的平均分」），而它看起来完全合理。
     * 判据放在这里，算子的那条注释写在 {@code NovelCommentMapper#avgScoreOfNovel}。
     *
     * <p><b>★ 三个触发点：审核（通过 / 隐藏）、删除。</b>
     * 「通过」是加分、「隐藏」和「删除」是减分，但三处都是<b>同一次重算</b> ——
     * 增减会写错方向，而重算没有方向可写错。
     *
     * <p>★ 传 {@code novelId} 而不是 {@code commentId}：跨模块那一步
     * （{@code ContentGateway#refreshScore}）是 content 的表，
     * 这边算完把<b>绝对值</b>交出去，写回由 content 自己做
     * —— 同 {@code ShelfServiceImpl#refreshCollectCount}。
     */
    private void refreshNovelScore(Integer targetType, Long targetId) {
        if (targetType == null || !targetType.equals(NovelComment.TARGET_TYPE_NOVEL)) {
            return;
        }
        contentGateway.refreshScore(targetId, commentMapper.avgScoreOfNovel(targetId));
    }

    /**
     * 批量填 {@code liked}（当前登录用户赞没赞过每一条）。
     *
     * <p><b>★★ 用 pipeline，不是循环 {@code SISMEMBER}。</b>
     * 一页 10 条一级评论 + 最多各 3 条回复 = 最多 40 次查询，
     * 循环写就是 40 次网络往返 —— 而这里要的只是 40 个 bit。
     * pipeline 把它们并成<b>一次</b>往返。
     * <p>★ 为什么不用「一次 {@code SMEMBERS} 拿到自己赞过的全部评论、再在内存里
     * {@code contains}」：一个活跃读者赞过的评论可以上万，那个 Set 会整个
     * 搬进应用内存，而且每翻一页搬一次。pipeline 只回 40 个布尔。
     *
     * <p><b>★★ 匿名时【什么都不做】</b>：{@code liked} 保持 {@code null}，
     * 全局的 {@code default-property-inclusion: non_null} 会让这个 key 整个消失，
     * 前端写 {@code row.liked ?? false}。★ 不能填 {@code false} ——
     * 那是在说「这个人没赞过」，而我们根本不知道他是谁
     * （同 {@code CommentVO#deleted} 用 {@code Boolean} 的理由）。
     *
     * <p>★ 用原始 {@code byte[]} + {@code setCommands()}，不转成
     * {@code StringRedisConnection}：本项目所有的 Redis 直连都是这个形状
     * （见 {@code TokenStore}），少一层「这个 connection 到底被谁包过」的依赖。
     */
    private void fillLiked(List<CommentVO> comments) {
        Long userId = UserContext.getUserId();
        if (userId == null || comments == null || comments.isEmpty()) {
            return;
        }

        /* ★ 一级评论和它内联的回复都要填 —— 回复下面同样有点赞按钮 */
        List<CommentVO> targets = new ArrayList<>();
        for (CommentVO vo : comments) {
            if (vo == null) {
                continue;
            }
            targets.add(vo);
            if (vo.getReplies() != null) {
                targets.addAll(vo.getReplies());
            }
        }

        byte[] member = String.valueOf(userId).getBytes(StandardCharsets.UTF_8);
        List<Object> flags = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (CommentVO vo : targets) {
                byte[] key = (RedisKeyConstant.LIKE_USER + vo.getId())
                        .getBytes(StandardCharsets.UTF_8);
                connection.setCommands().sIsMember(key, member);
            }
            /*
             * ★ 必须返回 null：executePipelined 把非 null 的返回值当成
             *   「这个回调不该有返回值」而抛 InvalidDataAccessApiUsageException。
             *   结果从 pipeline 的回复列表里取，不是从返回值取。
             */
            return null;
        });

        /*
         * ★ 只认 Boolean.TRUE。Lettuce 的 SISMEMBER 回的就是 Boolean。
         *   若以后换了客户端（比如回 0/1 的 Long），这里会【全部变成 false】——
         *   那是一个看得见的错误（所有按钮都显示未赞），不是静默的数据损坏。
         *
         * ★ 回复数少于请求数（理论上不会）时【不填】而不是填 false：
         *   「不知道」要保持 null，这条规则在整个方法里是同一个。
         */
        for (int i = 0; i < targets.size() && i < flags.size(); i++) {
            targets.get(i).setLiked(Boolean.TRUE.equals(flags.get(i)));
        }
    }

    /**
     * 删除自己的评论（逻辑删）。
     *
     * <p><b>★ 三种失败各有各的码，因为它们的「下一步」不一样：</b>
     * <pre>
     *   4001 评论不存在     → 可能链接是旧的 / 已被彻底清掉，刷新页面
     *   4002 只能删自己的   → 点错了，或者有人在扫别人的评论（★ 要留 WARN）
     *   4003 已经被删过了   → 上一次点击其实成功了，不需要再做什么
     * </pre>
     * <p>合成一个「操作失败」，用户就不知道自己该刷新还是该放弃。
     * <p><b>★ 4003 需要看得到已删除的行</b>，所以走
     * {@code selectByIdIncludingDeleted}（表上有 {@code deleted}，
     * {@code selectById} 带 {@code @TableLogic} 会把它当成「不存在」）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long commentId) {
        Long userId = UserContext.requireUserId();

        NovelComment comment = commentMapper.selectByIdIncludingDeleted(commentId);
        if (comment == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        /*
         * ★★ 归属校验在「是否已删除」之前。顺序反了的话，
         *   对一条【别人的、已删除的】评论会返回 4003，
         *   而对一条【别人的、正常的】评论返回 4002 ——
         *   这个差别就是一份「这个 id 存不存在、是不是别人的」的探针。
         *   现在无论哪种情况都是 4002，看不出任何多余的信息。
         */
        if (!Objects.equals(comment.getUserId(), userId)) {
            log.warn("[越权尝试] userId={} 试图删除 commentId={}（属于 userId={}）",
                    userId, commentId, comment.getUserId());
            throw new BusinessException(ResultCode.COMMENT_NOT_YOURS);
        }
        if (comment.getDeleted() != null && comment.getDeleted() == 1) {
            throw new BusinessException(ResultCode.COMMENT_ALREADY_DELETED);
        }

        /*
         * ★ 逻辑删：把 deleted 置 1，行还在。
         *
         * ★ 条件是 deleted = 0 —— 和上面那次读取之间可能有人抢先删了
         *   （同一账号两个标签页）。命中 0 行说明是那种情况，
         *   对用户来说结果是一样的（评论没了），所以不报错、也不重复处理。
         *
         * ★ 不真删。理由有三条，每一条单独都够：
         *   ① 管理端要能查证「消失的评论」说了什么（有人投诉时）；
         *   ② audit_log 里可能有指向这条评论的流水，物理删会留下悬空引用；
         *   ③ 二期 M3 做「评论数」的统计口径时，需要知道「曾经有多少条」。
         */
        commentMapper.update(null, new LambdaUpdateWrapper<NovelComment>()
                .eq(NovelComment::getId, commentId)
                .eq(NovelComment::getDeleted, CommonConstant.NOT_DELETED)
                .set(NovelComment::getDeleted, CommonConstant.DELETED));

        /*
         * ★★ 级联删掉它的二级回复（一条 UPDATE，不需要循环）。
         *
         *   不级联的后果不是「回复还能看见」—— 读者端查子回复的条件里
         *   有 status = 1，父评论被删之后整条线索都不再渲染，
         *   所以页面上看不出任何异常。问题在<b>库里</b>：
         *   这些行永远不显示、却一直占着位置，
         *   而 replyCount 统计的是「看得见的子回复」，两边从此对不上。
         *
         *   ★ 只有一级评论会有子回复（两级封顶），所以一条 WHERE parent_id = ?
         *     就够了，不需要递归。
         *   ★ 条件里同样带 deleted = 0：这次删除<b>之后</b>再有回复落库
         *     是不可能的（父已不可见，reply 会返回 4001），
         *     但如果将来有人加「恢复评论」，这一条能保证不会把
         *     上一次删掉的回复一起复活。
         *
         * ★ 状态【不】联动：父被驳回时子回复保持原状。
         *   管理员的驳回针对的是那条内容，不是整条线索 —— 而读者端
         *   因为父不可见而整条不显示，效果上已经够了。
         */
        commentMapper.update(null, new LambdaUpdateWrapper<NovelComment>()
                .eq(NovelComment::getParentId, commentId)
                .eq(NovelComment::getDeleted, CommonConstant.NOT_DELETED)
                .set(NovelComment::getDeleted, CommonConstant.DELETED));

        refreshNovelScore(comment.getTargetType(), comment.getTargetId());
    }

    /* ================================================================
     *  管理侧
     * ================================================================ */

    @Override
    public PageResult<CommentVO> adminPage(CommentStatus status, Long novelId, String keyword,
                                           long page, long size) {
        /*
         * ★★ 走手写 SQL，因为这一条【故意不滤 deleted】——
         *   MP 的 wrapper 会无条件加上 deleted = 0，没有开关能关掉它。
         *   详细理由见 NovelCommentMapper#selectAdminPage。
         *
         * ★ keyword 先 trim，并且把「全是空格」当成不筛：
         *   不处理的话，管理员在搜索框里敲了个空格回车，
         *   会得到一份「正文里含空格」的结果 —— 看起来像搜索坏了。
         */
        String safeKeyword = keyword == null ? null : keyword.trim();
        if (safeKeyword != null && safeKeyword.isEmpty()) {
            safeKeyword = null;
        }

        /*
         * ★★ M2：按作品筛时，先跨服务把这本书的章节 id 拿回来。
         *
         * 这一条 SQL 原来内联了 `target_id IN (SELECT id FROM chapter WHERE novel_id = ?)`，
         * 而 chapter 是 content 的表 —— 拆服务之后读不到了。<b>这是 M2 唯一一处
         * 必须修的跨表读</b>（决定 1：一个 schema + 表归属独占）。
         *
         * ★★ <b>空列表不能当成「返回空页」。</b>
         * 一本已上架、一章都还没发的新书完全可以有<b>书评</b>
         * （书评走 target_type = 1，不经过章节）。直接返回空页会把那些书评吞掉。
         * 传一个空集合下去，让 SQL 把章评那一整段 OR 消失 —— 和原来空子查询的
         * 语义逐字相同。理由完整写在 {@code NovelCommentMapper#selectAdminPage}。
         *
         * ★ <b>chapterIds 用 List.of() 而不是 null</b>：null 在那边也会退化成
         * 「只查书评」，但那是<b>调用方漏传时的一个静默兜底</b>，
         * 不是一种正常取值 —— 用空集合把「这本书没有章节」和「我忘了传」区分开。
         */
        List<Long> chapterIds = List.of();
        if (novelId != null) {
            chapterIds = contentGateway.chapterIdsOfNovel(novelId);
            /*
             * ★ 章节 id 条数理论上无上限（一本连载几千章）。IN 列表过大会撑爆
             *   SQL（max_allowed_packet）并让这条查询变慢。
             *   ★★ M2 【只警告、不截断】：截断 = 静默返回一份不完整的列表，
             *   而「管理端评论列表少了一半」正是这个查询最容易犯、且最难发现的错
             *   （见 NovelCommentMapper 里那条 ★★）。宁可慢，也不要静默出错。
             *   ★ 这条债在 M4 上 ES 时会随检索层一起重做。
             */
            if (chapterIds.size() > ADMIN_CHAPTER_FILTER_WARN_THRESHOLD) {
                log.warn("按作品筛评论：章节 id 列表过长，SQL 会很大 | novelId={} count={}",
                        novelId, chapterIds.size());
            }
        }

        /*
         * ★ 变量类型是 IPage 而不是 Page —— 手写 SQL 的 mapper 方法返回
         *   MP 的 IPage（分页拦截器把 Page 传进去、返回同一个对象，
         *   但编译期看到的类型是接口）。写成 Page 会编译不过：
         *   IPage 是接口，Page 是它的实现，收窄必须显式强转，
         *   而这里没有强转的理由 —— 后面只用 getRecords() / PageResult.of()，
         *   两个都接受 IPage。
         */
        IPage<NovelComment> result = commentMapper.selectAdminPage(
                buildPage(page, size),
                status == null ? null : status.getCode(),
                novelId,
                chapterIds,
                safeKeyword);

        /*
         * ★ 补昵称的方式和读者端完全一样（一次 IN 而不是 N+1）。
         *
         * ★ 用 forAdmin 而不是 forReader —— 这一句决定了 userId / status /
         *   auditRemark / deleted 会不会出现在响应里。写错方法名不会报错，
         *   界面上只是少几列（或者读者的接口多几个字段），见 CommentVO 的类注释。
         */
        Map<Long, String> nicknames = nicknamesOf(result.getRecords());
        return PageResult.of(result, c -> CommentVO.forAdmin(c, nicknames.get(c.getUserId())));
    }

    /**
     * 审核：通过（→ 已显示）或隐藏（→ 已隐藏）。
     *
     * <p><b>★ 事务：状态更新和审核流水必须一起成功或一起失败。</b>
     * {@code AuditLogService#record} 自己没有 {@code @Transactional}，
     * 所以它加入本方法的事务 —— 这正是那个接口注释里写明的设计
     * （「一条不真实的流水比没有流水更糟」）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long commentId, CommentAuditDTO dto) {
        Long operatorId = UserContext.requireUserId();

        /*
         * ★ 用 selectById（带 @TableLogic，deleted = 0）。
         *   于是「用户自己已经删掉的评论」在这里等同于「不存在」，
         *   管理员审不了它 —— 这是对的：那条评论读者已经看不到了，
         *   再给它盖一个「已隐藏」的章只会污染统计（看起来像一次有效的处置）。
         *   ★ 但它在【列表】里是看得见的（那条查询刻意不滤 deleted），
         *   所以界面上要显示「已删除」标记，否则管理员会对它反复点隐藏、
         *   每次都得到 4001，却看不出为什么。
         */
        NovelComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }

        CommentStatus target = CommentStatus.fromCode(dto.getStatus());
        /*
         * ★ 单独拦「改回待审」，而不是让它掉进下面那条 canTransitTo。
         *   因为 canTransitTo 会说「当前状态不允许该操作」，而管理员真正需要知道的是
         *   「审核结果只能是已显示或已隐藏」—— 他并没有在改「当前状态」，
         *   他在选一个非法的目标状态。文案指错方向，人会去反复点别的按钮。
         */
        if (target == CommentStatus.PENDING) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "审核结果只能是「已显示」或「已隐藏」，不能改回待审核");
        }
        /*
         * ★★ 状态机。所有改状态的路径都必须过这一关。
         *   漏了的后果：已显示的评论被改成待审 —— 它同时从读者眼前消失、
         *   又在待审队列里多出一条，排查时看到的是「凭空多出来的待审评论」。
         *
         * ★ 重复审核（对「已显示」的再点一次通过）会在这里被拦下，
         *   返回 4005 而不是静默成功。和 NovelService#transitStatus 一致：
         *   本项目在「幂等」和「明确报错」之间选后者 ——
         *   静默成功会让管理员以为刚才那一下没点上，然后再点。
         */
        if (!comment.getStatus().canTransitTo(target)) {
            throw new BusinessException(ResultCode.COMMENT_STATUS_ILLEGAL);
        }

        LocalDateTime now = LocalDateTime.now();
        /*
         * ★★ UPDATE 上挂 eq(status, 读到的状态) —— 一次乐观检查。
         *
         *   管理员连点两下、或者两个管理员同时审同一条，都会出现
         *   「两边都读到 PENDING、都过了 canTransitTo」的情况。
         *   带上这个条件之后，后到的那条会命中 0 行，于是走到下面的
         *   「被别人抢先了」分支，把 4005 返回去 ——
         *   而不是默默地再写一遍、再记一条 audit_log。
         *
         * ★ 和 Novel 不同的是这张表【没有 version 列】
         *   （MybatisPlusConfig 里的乐观锁插件靠 @Version 字段工作），
         *   所以用「前置状态」当版本号。Novel / Chapter 用的也是这个办法。
         */
        int rows = commentMapper.update(null, new LambdaUpdateWrapper<NovelComment>()
                .eq(NovelComment::getId, commentId)
                .eq(NovelComment::getStatus, comment.getStatus())
                .set(NovelComment::getStatus, target)
                .set(NovelComment::getAuditUserId, operatorId)
                .set(NovelComment::getAuditTime, now)
                .set(NovelComment::getAuditRemark, dto.getRemark()));

        if (rows == 0) {
            log.warn("评论审核：状态已被别人改过，本次放弃 | commentId={} 期望前置状态={} operatorId={}",
                    commentId, comment.getStatus(), operatorId);
            throw new BusinessException(ResultCode.COMMENT_STATUS_ILLEGAL);
        }

        /*
         * ★★ 记一条审核流水。这是 audit_log 里 COMMENT targetType 的第一批真实数据 ——
         *   在此之前 AuditLogServiceImpl 的那个 switch 表达式里
         *   `case COMMENT -> null`，注释写着「P5 落地时补上」。
         *
         * ★ 动作只可能是 APPROVE / REJECT 之一：
         *   → VISIBLE（通过）记 APPROVE
         *   → HIDDEN（隐藏）记 REJECT
         *   ★ 「把一条已经显示的正常评论事后隐藏」也记 REJECT ——
         *     从审核流水看这是一次「否定的处置」，用 SUBMIT/OFFLINE 那些都不对，
         *     而多造一个「隐藏」动作会让 AuditAction 的语义split成两套。
         *     备注里能看懂具体发生了什么。
         *
         * ★★★ 为什么【发表评论】不记流水（读者发完评论这里没有 record）：
         *   audit_log 记的是【状态流转】，而评论一出生就是「待审核」——
         *   没有「提交」这一次流转可记。对照作品：先建为草稿（0），
         *   再提交审核（0 → 1），那才是一次真实的流转，所以作品有 SUBMIT。
         *   两条额外的理由：① 评论的写入量比作品高几个数量级，
         *   给每条都记一笔会让这张「追加、永不删除」的表迅速变大，
         *   而它的价值在审核动作上；② 评论的提交时间已经在 create_time 里了。
         */
        contentGateway.recordCommentAudit(commentId,
                target == CommentStatus.VISIBLE ? AuditAction.APPROVE : AuditAction.REJECT,
                dto.getRemark());

        refreshNovelScore(comment.getTargetType(), comment.getTargetId());

        log.info("评论审核完成 | commentId={} {} → {} operatorId={}",
                commentId, comment.getStatus(), target, operatorId);
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 10 秒频控。<b>只能这么写，没有别的写法。</b>
     *
     * <pre>
     *   SET novel:comment:limit:{userId} &lt;timestamp&gt; NX EX 10
     * </pre>
     *
     * <p><b>★★ 返回 false 就是「10 秒内已经发过」，抛 4004。</b>
     *
     * <p><b>★ 为什么必须 {@code NX} + 过期时间在同一条命令里：</b>
     * 「先 {@code GET} 判断、再 {@code SET}」是两步非原子操作 ——
     * 连点两下时，两个请求可能都在对方写入之前读到「没有」，于是双双通过。
     * 这正是这个接口最容易被刷的方式（用户不需要恶意，连点就够）。
     * <p>「先 {@code SETNX} 再 {@code EXPIRE}」也不行，症状更隐蔽：
     * 两条命令之间如果进程被杀 / 连接断了，<b>那个 key 就永远不会过期</b>，
     * 这个用户从此再也发不出评论 —— 而且没有任何日志。
     * <p>{@code setIfAbsent(key, value, Duration)} 对应的是 Redis 的
     * {@code SET key value NX PX &lt;ms&gt;}，一条命令，原子。
     *
     * <p><b>★ value 存时间戳而不是 "1"：</b>
     * 出问题时 {@code GET novel:comment:limit:{userId}} 能直接看到
     * 「他上一次是什么时候发的」；存 "1" 的话只知道「被限过」，
     * 而那个信息 {@code TTL} 已经给了。
     */
    private void requireNotTooFast(Long userId) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                RedisKeyConstant.COMMENT_LIMIT + userId,
                String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(CommonConstant.COMMENT_INTERVAL_SECONDS));

        /*
         * ★ 只有明确返回 false 才算被限流。
         *   Boolean 是包装类型，理论上可能为 null（连接异常时），
         *   用 Boolean.FALSE.equals(...) 而不是 !acquired ，
         *   免得 NPE 把一个限流判断变成 500 ——
         *   而 Redis 抽风时让评论【发出去】是更安全的方向：
         *   这是防刷，不是安全边界（真正的边界是登录）。
         */
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("评论频控拦截 | userId={}", userId);
            throw new BusinessException(ResultCode.COMMENT_TOO_FAST);
        }
    }

    /**
     * 取一条【已显示】的评论。
     *
     * <p><b>★ 不存在、已删除、待审核、已隐藏 —— 四种情况返回同一个
     * {@code COMMENT_NOT_FOUND(4001)}。</b>
     * 因为对调用方它们是同一件事：「这条评论你现在动不了」。
     * 区分开就等于确认了「这个 id 是真的，只是还没过审」，
     * 而那是一份「管理员审到哪了」的探针。
     * <p>（{@code HistoryServiceImpl} 里对章节未发布返回 3001、
     * {@code NovelService#requirePublished} 对未上架返回 2001，同一个取舍。）
     */
    private NovelComment requireVisibleComment(Long commentId) {
        NovelComment comment = commentMapper.selectById(commentId);
        if (comment == null || !comment.getStatus().isVisibleToReader()) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }

    /**
     * 把一页评论的评论人昵称一次查出来，返回 {@code userId → nickname}。
     *
     * <p>★ 编号是 {@code userId} 而不是评论 id：同一页里一个人发好几条很常见，
     * 按 userId 配对能让对端内部去重 ——
     * 20 条评论可能只对应 5 个人，那就是 5 个 id 的 IN。
     *
     * <p>★ <b>空列表必须短路</b>：拿空集合去查库会生成
     * {@code WHERE id IN ()}，那是语法错误（不是「返回空」，是直接报错）。
     * 这里短路省的是<b>这次跨服务往返本身</b>（对端也判了空）。
     *
     * <p><b>★★ M2 之后这里是一次 HTTP 调用</b>（改造前 {@code ContentUserLookup}
     * 是同一个进程里的方法调用，现在换成了 auth 的
     * {@code POST /internal/users/nicknames}）。于是多出两件本地调用没有的事：
     * <ul>
     *   <li><b>信封要拆</b>：Feign 不会因为业务码抛异常，必须显式
     *       {@link InternalResults#unwrap} —— 漏掉的话失败会变成一个
     *       空 Map，评论列表里的昵称集体消失（<b>看起来像数据坏了</b>）。</li>
     *   <li><b>返回可能是 null</b>：HTTP 这层多了一个「什么都没返回」的状态，
     *       兜成空 Map，让调用方只需要处理「这个 id 不在 Map 里」一种情况。</li>
     * </ul>
     * <p>★ 用 {@code unwrap} 而不是 {@code unwrapBusiness}：这是
     * 「对端替我查一次数据」，不是「对端替我执行一次校验」——
     * 对端的业务码在这里没有意义，失败只可能是「auth 不可用」，翻 500 是对的。
     */
    private Map<Long, String> nicknamesOf(List<NovelComment> comments) {
        if (comments.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = InternalResults.unwrap(
                authNicknameClient.nicknamesOf(
                        comments.stream().map(NovelComment::getUserId).toList()),
                "批量取用户昵称 count=" + comments.size());
        // ★ 查不到的 userId 不会出现在 Map 里 → 调用方按 null 处理（显示「已注销用户」）
        return result == null ? Map.of() : result;
    }

    /**
     * 分页参数兜底。
     *
     * <p><b>★ {@code size} 的下界必须自己兜，不能只靠拦截器。</b>
     * {@code PaginationInnerInterceptor} 只管<b>上界</b>（{@code setMaxLimit(100)}），
     * 而 {@code size < 0} 在 MP 里是「<b>关闭分页</b>」的意思 ——
     * {@code ?size=-1} 会把整张表一次性查出来。
     * 这个坑在 {@code NovelServiceImpl} / {@code ChapterServiceImpl} /
     * {@code AdminUserServiceImpl} 等每个 Service 里都各兜了一次，
     * 本项目就是这么一个约定。
     */
    private Page<NovelComment> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page,
                size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size);
    }
}
