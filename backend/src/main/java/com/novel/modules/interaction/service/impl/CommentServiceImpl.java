package com.novel.modules.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.enums.CommentStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.service.ContentUserLookup;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.interaction.dto.CommentAuditDTO;
import com.novel.modules.interaction.dto.CommentCreateDTO;
import com.novel.modules.interaction.entity.NovelComment;
import com.novel.modules.interaction.mapper.NovelCommentMapper;
import com.novel.modules.interaction.service.CommentService;
import com.novel.modules.interaction.vo.CommentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * {@code novel_comment.target_type} 的书评取值。
     *
     * <p>★ 只有一个常量、没有枚举，理由见 {@code NovelComment#targetType}：
     * 枚举会长得和 {@code common/enums/AuditTargetType} 几乎一样，
     * 而两个近义枚举同时存在是最难发现的一类混淆。
     * <p>★ P5 的全部接口都只写这一个值 —— 接口形状本身就锁死了它：
     * {@code /api/novels/{novelId}/comments} 的路径变量就是 novelId。
     * 章评（值 2）是 P6 的事。
     */
    private static final int TARGET_TYPE_NOVEL = 1;

    private final NovelCommentMapper commentMapper;

    /**
     * ★ 跨模块依赖有四个，每个都只说清一件事。
     *
     * <p><b>{@code NovelService} 与 {@code AuditLogService}（content 包）：</b>
     * 借一次校验和写一条流水，和 {@code HistoryServiceImpl} 的做法一致。
     * 二期 M3 拆 interaction 时，这两处变成 Feign 调用，<b>本类的代码不用改</b>。
     *
     * <p><b>{@code ContentUserLookup}（content 包）：</b>
     * 用它取昵称，而不是自己注入 {@code SysUserMapper} ——
     * 那个类是「读 system 模块」的<b>唯一接缝</b>，多一个同类接缝就是
     * M2/M3 时多一处要改成 Feign 的地方。
     * <p>★ 它自己的注释说「content 模块里唯一一处依赖 system 模块的地方」，
     * 这里从 interaction 调它，并<b>不</b>让那句话失真：依赖链条是
     * {@code interaction → content → system}，content 仍然只有一个出边。
     * <p>代价是 interaction 多依赖了 content 的一个类 —— 但这个方向本来就存在
     * （{@code NovelService} 已经在上面了），没有新增反向依赖。
     *
     * <p><b>{@code StringRedisTemplate}：</b>不是 {@code RedisTemplate<String,Object>}。
     * 后者的 JSON 序列化器会把 {@code SADD} 的成员写成带 {@code "@class"} 的 JSON，
     * 也会影响 {@code SET NX} 的返回值语义 ——
     * {@code NovelViewCounter} 的类注释里为同一件事写过一大段，这里不再重复。
     */
    private final NovelService novelService;
    private final AuditLogService auditLogService;
    private final ContentUserLookup userLookup;
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
                .eq(NovelComment::getTargetType, TARGET_TYPE_NOVEL)
                .eq(NovelComment::getTargetId, novelId)
                .eq(NovelComment::getStatus, CommentStatus.VISIBLE)
                /*
                 * ★ 按 id 倒序 = 最新的在最上面。
                 *   不用 create_time：同一秒发的两条用时间排不稳定，
                 *   翻页时会出现「某条重复出现、另一条永远看不到」。
                 *   id 是自增主键，严格全序。
                 */
                .orderByDesc(NovelComment::getId);

        Page<NovelComment> result = commentMapper.selectPage(buildPage(page, size), wrapper);

        /*
         * ★ 昵称是【查完之后】按 userId 一次 IN 查出来的，不是 SQL join。
         *   ① join 的话，sys_user 是 system 模块的表，二期拆服务时
         *      comment 的查询会变成一条跨库查询（大纲 §178：服务之间不共享表）；
         *   ② 循环调 nicknameOf 的话，一页 20 条就是 20 次查询（N+1）。
         *   一次 IN + 一次内存配对照顾了两头。
         */
        Map<Long, String> nicknames = nicknamesOf(result.getRecords());
        return PageResult.of(result, c -> CommentVO.forReader(c, nicknames.get(c.getUserId())));
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
         * ★ 复用 NovelService#requirePublished —— 它保证「不存在」和「未上架」
         *   返回同一个码（2001），所以这个接口不能被当成探针。
         *   这也是唯一能挡住「给一本没上架的书刷评论」的地方：
         *   评论落库之后读者虽然看不到（status=0），但它会出现在管理端的待审队列里，
         *   变成一次需要管理员人工清理的噪音。
         */
        novelService.requirePublished(novelId);

        NovelComment entity = new NovelComment();
        entity.setUserId(userId);
        entity.setTargetType(TARGET_TYPE_NOVEL);
        entity.setTargetId(novelId);
        /*
         * ★ 状态写死 PENDING，不从 dto 取 —— DTO 上根本没有这个字段。
         *   让调用方能指定状态，就是让读者自己把评论设成「已显示」，审核形同虚设。
         *
         * ★ 也【不接受】parentId：P5 没有回复功能（那是 P6）。
         *   表上有这一列，但让它成为可传参数，就得同时做「父评论必须存在、
         *   必须属于同一本书、不能回复一条已隐藏的评论」三件校验，
         *   而 P5 的读者端连评论列表界面都没有（决定 11）。留给 P6。
         */
        entity.setStatus(CommentStatus.PENDING);
        entity.setContent(dto.getContent().trim());
        entity.setScore(dto.getScore());
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
         *   ③ 没有「取消点赞」—— 方案里只定义了点赞（POST）。
         *      误点一下就是永久的，P6 做评论区 UI 时再补 DELETE。
         */
        String likeKey = RedisKeyConstant.LIKE_USER + commentId;
        Long added = stringRedisTemplate.opsForSet().add(likeKey, String.valueOf(userId));

        if (added != null && added == 1L) {
            /*
             * ★ 自增用 SQL 表达式，不用「查出来加一再写回去」：
             *   setSql("like_count = like_count + {0}", 1) 由数据库完成读改写，
             *   两个用户同时点赞不会互相覆盖。
             *   ★ {0} 是 MP 的预编译占位符，不是字符串拼接 —— 同 NovelViewCounter。
             *
             * ★ entity 传 null，所以 MybatisMetaObjectHandler 的 updateFill
             *   【不会执行】—— 这张表根本没有 update_time 列，
             *   传一个 entity 进去反而会踩「Unknown column」那个坑。
             *   （★ 这也是本类所有 UPDATE 都走 update(null, wrapper) 的原因，
             *   不只是风格问题。）
             */
            commentMapper.update(null, new LambdaUpdateWrapper<NovelComment>()
                    .eq(NovelComment::getId, commentId)
                    .setSql("like_count = like_count + {0}", 1));
        }

        /*
         * ★ 重新读一次，返回【库里真实的值】，而不是「刚才读到的 +1」。
         *   算出来的数字在并发下是错的：两个人同时点赞，两边都算出同一个数，
         *   前端会看到数字「卡住不动」，看起来像没点上。
         *
         * ★ 读不到（这一瞬间评论被删了）就退回上一次读到的值，不报错 ——
         *   点赞成功与否已经确定了，为了一个展示数字回滚是过度反应。
         */
        NovelComment fresh = commentMapper.selectById(commentId);
        return fresh == null ? comment.getLikeCount() : fresh.getLikeCount();
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
        auditLogService.record(AuditTargetType.COMMENT, commentId,
                target == CommentStatus.VISIBLE ? AuditAction.APPROVE : AuditAction.REJECT,
                dto.getRemark());

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
     * 按 userId 配对能让 {@code ContentUserLookup#nicknamesOf} 内部去重 ——
     * 20 条评论可能只对应 5 个人，那就是 5 个 id 的 IN。
     *
     * <p>★ <b>空列表必须短路</b>：拿空集合去查库会生成
     * {@code WHERE id IN ()}，那是语法错误（不是「返回空」，是直接报错）。
     */
    private Map<Long, String> nicknamesOf(List<NovelComment> comments) {
        if (comments.isEmpty()) {
            return Map.of();
        }
        return userLookup.nicknamesOf(comments.stream().map(NovelComment::getUserId).toList());
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
