package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.result.PageResult;
import com.novel.common.security.UserContext;
import com.novel.content.config.AuditAggExecutorConfig;
import com.novel.modules.content.entity.AuditLog;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.AuditLogMapper;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.AuditLogService;
import com.novel.common.internal.CommentBriefDTO;
import com.novel.modules.content.service.CommentTextLookup;
import com.novel.modules.content.service.ContentUserLookup;
import com.novel.modules.content.vo.AuditLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 审核流水实现。全项目最简单的一个 Service —— 只有一条 INSERT。
 *
 * <p><b>★ 简单是它的特性，不是它的缺陷。</b>流水表就该是「只追加」的：
 * 没有 update、没有 delete、没有状态位。任何给这个类加
 * {@code update} / {@code remove} 方法的需求，都应该先问一嘴
 * 「流水被改掉之后，还怎么用来追责」。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final long DEFAULT_PAGE_SIZE = 10;

    private final AuditLogMapper auditLogMapper;

    /*
     * ★ 这三个依赖【只服务于 page()】，record() 一个都不用。
     *
     * 看起来像是「一个类被拆成了两个职责」，但它们是同一件事的两面：
     * record 负责「记的时候不丢东西」，page 负责「读的时候补上人看得懂的信息」。
     * 拆成两个类反而会让「流水表只追加」这条规矩散在两个文件里。
     */
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final ContentUserLookup userLookup;

    /**
     * ★★ <b>注意它的类型是一个接口，而不是某个 Mapper。</b>
     *
     * <p>评论表在 {@code modules/interaction} 里，直接注入
     * {@code NovelCommentMapper} 会造出一条 {@code content → interaction} 的依赖；
     * 而 M3 要摘的正是 interaction，摘出去之后 content 反过来调它
     * —— 那就是 D-1 里警告过的双向依赖，拆不动。
     * <p>所以接口定义在 content 侧（本模块的 {@code service} 包），
     * 实现在 interaction 侧，依赖方向是 interaction → content，单向。
     * <b>本类里找不到任何对 interaction 的 import</b>，这就是这个接口的全部意义。
     *
     * <p><b>★★ M2 之后「互动服务在另一个进程里」，而这一行代码一个字都不用改。</b>
     * 这正是当初把它做成接口换来的东西：那个 {@code content → interaction}
     * 的依赖现在变成了一次<b>真实的 HTTP 往返</b>
     * （{@code CommentTextLookupImpl} → {@code CommentBriefClient}），
     * 而本类看到的仍然只是「调一个方法、拿一个 Map」。
     * <p>★ 代价要说清楚：<b>互动服务不可用时，管理端审核日志页会 500</b>
     * （而不是显示成「已删除」）。这是刻意的，理由写在
     * {@code CommentTextLookupImpl} 的类注释里（<b>宁可 500，
     * 也不要一个看起来正常但内容是错的页面</b>）。
     *
     * <p>★ 完整理由（含「为什么 P1 的 ContentUserLookup 是反过来的做法」）
     * 写在 {@link CommentTextLookup} 的类注释里。
     */
    private final CommentTextLookup commentTextLookup;

    /**
     * <b>★★ M5（S7）：渲染一页日志时，那三次跨服务取值在这个池上并行跑。</b>
     *
     * <p>为什么值得单独开一个池、池参数为什么是那几个数，
     * 全写在 {@code AuditAggExecutorConfig} 的类注释里 —— <b>改这里之前先读那一段</b>
     * （尤其是「为什么绝不能用 {@code ForkJoinPool.commonPool}」和
     * 「{@code CallerRunsPolicy} 与 {@link com.novel.common.concurrent.ContextPropagatingTaskDecorator}
     * 是一对」）。
     *
     * <p>★★ <b>为什么是 {@code @Qualifier} 而不是按类型注入</b>：本服务里
     * {@code ThreadPoolTaskExecutor} 有<b>两个</b>（另一个是 AI 的 {@code aiStreamExecutor}），
     * 按类型注入会直接报「找到多个候选」。★ 项目里的同类先例是
     * {@code AiController} 的构造参数 —— 那里写的是显式构造 + 参数上的 {@code @Qualifier}。
     * 这里用字段上的，靠 Lombok 把注解复制到生成的构造参数上（Lombok 的内置可复制注解名单里
     * 有 {@code @Qualifier}）。★ <b>万一哪天它不复制了，症状是启动直接失败</b>
     * （{@code NoUniqueBeanDefinitionException}）—— 是响的，不是静默的，所以这个依赖可以接受。
     */
    @Qualifier(AuditAggExecutorConfig.BEAN_NAME)
    private final ThreadPoolTaskExecutor auditAggExecutor;

    @Override
    public void record(AuditTargetType targetType, Long targetId, AuditAction action, String remark) {
        /*
         * ★ 操作人从当前登录态取，不接受调用方传入 —— 见 AuditLogService#record 的注释。
         *
         * 这里用 requireUserId() 而不是 getUserId()：流水表【必须】有个操作人，
         * 记成 null 的流水等于没记（查账时看到一条「某人做了某事」但不知道是谁）。
         * 如果真到了没有登录态的场景（定时任务），requireUserId 会明确报错，
         * 而不是悄悄写一条 operator_id 为 null 的记录。
         *
         * ★★ M3 起这个「没有登录态的场景」真的存在了（MQ 消费者），
         *   而处置办法【不是】在这里放宽 —— 是让那种场景走下面那个显式重载。
         *   本方法保持原样：它一旦拿不到登录态就该抛，这是它的价值所在。
         */
        Long operatorId = UserContext.requireUserId();

        /*
         * ★ createTime 传 null = 「交给 MybatisMetaObjectHandler 填当前时间」。
         *   HTTP 路径上「现在」就是事件发生的时刻，没有第二个时间可用。
         *   有积压的那条链路（MQ）才需要显式传事件时刻，见那个重载。
         */
        record(targetType, targetId, action, remark, operatorId, null);
    }

    /**
     * ★★ M3 新增：操作人与发生时刻都由调用方给出的版本，唯一调用方是
     * {@code AuditRecordConsumer}。适用场景与「为什么要有第二个重载」写在
     * {@link AuditLogService} 的接口注释里。
     *
     * <p><b>★ 这个方法是本类唯一的写实现</b> —— 上面那个老重载只是取到
     * {@code operatorId} 之后转调过来。<b>别把 INSERT 复制成两份</b>：
     * 两份 insert 意味着「日志怎么打」「用户 id 怎么校验」有两个可以各自漂移的版本，
     * 而这正是流水表最不能有的东西。
     */
    @Override
    public void record(AuditTargetType targetType, Long targetId, AuditAction action, String remark,
                       Long operatorId, LocalDateTime createTime) {
        AuditLog entity = new AuditLog();
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setAction(action);
        entity.setOperatorId(operatorId);
        entity.setRemark(remark);
        /*
         * ★ createTime 为 null 时【刻意不 set】—— MybatisMetaObjectHandler 用的是
         *   strictInsertFill（「字段已经有值就不覆盖」），所以：
         *     传了值 → 用传进来的事件时刻（消息积压时它是唯一正确的时间）
         *     传 null → 字段留空 → handler 填 now()
         *   ★ 写成 setCreateTime(createTime == null ? LocalDateTime.now() : createTime)
         *     也能跑，但那就把「什么时候由谁决定时间」这件事从 handler 挪到了这里，
         *     而且多一次无意义的 now()。让 null 一路传下去，语义更清楚。
         */
        entity.setCreateTime(createTime);

        auditLogMapper.insert(entity);

        /*
         * 日志级别用 info：审核是正常业务动作，不是异常。
         * 但一定要打 —— 这是「系统日志」和「业务流水」互相印证的另一半：
         * 库里的 audit_log 被谁删过（有权限直接连库的人），
         * 应用日志里那一条还在。
         *
         * ★ 注意这里打的是 entity.getCreateTime()（落库后的真实值），
         *   不是入参 createTime —— 后者在老重载那条路上是 null，
         *   直接打日志会打出一片 null，看着像「时间没记上」。
         */
        log.info("审核流水 | type={} targetId={} action={} operatorId={} at={}",
                targetType, targetId, action, entity.getOperatorId(), entity.getCreateTime());
    }

    @Override
    public PageResult<AuditLogVO> page(AuditTargetType targetType, AuditAction action,
                                       Long operatorId, long page, long size) {
        Page<AuditLog> result = auditLogMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<AuditLog>()
                        .eq(targetType != null, AuditLog::getTargetType, targetType)
                        .eq(action != null, AuditLog::getAction, action)
                        .eq(operatorId != null, AuditLog::getOperatorId, operatorId)
                        /*
                         * ★ 只按 id 倒序，不按 create_time 倒序。
                         *
                         * 直觉上应该按 create_time，但同一个事务里连着写两条流水时
                         * （比如批量的补录），create_time 可能精确到同一毫秒 ——
                         * 此时 MySQL 的排序是不稳定的，两次翻页可能看到重复或漏掉的行。
                         * id 是自增的，天然单调且唯一，和写入顺序完全一致。
                         */
                        .orderByDesc(AuditLog::getId));

        return new PageResult<>(
                toVOList(result.getRecords()),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages());
    }

    /**
     * 批量把 {@code target_id} 补成书名 / 章节名，把 {@code operator_id} 补成昵称。
     *
     * <p><b>★ 一次要补两类对象，所以要按类型分组查，不能混在一起查：</b>
     * 一页 20 条里可能既有作品流水又有章节流水，它们的 id 分别来自
     * 不同的表，{@code id = 5} 在两边的含义完全不同。
     * 混着查的话会给作品 5 补上章节 5 的标题 —— 而且<b>不报错</b>。
     *
     * <p>所以这里收集的是「作品 id 集合」和「章节 id 集合」两个列表，
     * 各自查一次；再加操作人一次，一页最多 3 次查询（不是 20 次）。
     */
    private List<AuditLogVO> toVOList(List<AuditLog> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }

        List<Long> novelIds = logs.stream()
                .filter(l -> l.getTargetType() == AuditTargetType.NOVEL)
                .map(AuditLog::getTargetId)
                .distinct()
                .toList();
        List<Long> chapterIds = logs.stream()
                .filter(l -> l.getTargetType() == AuditTargetType.CHAPTER)
                .map(AuditLog::getTargetId)
                .distinct()
                .toList();
        /*
         * ★ 第三个分组：评论（P5 的 S9 补的）。同样单独收集、单独查 ——
         *   理由和上面那两段一样：novel_comment 的 id 和 novel / chapter 的 id
         *   完全独立，混着查会给评论 5 补上作品 5 的标题。
         *
         * ★ 这里【不判空集合】—— 判空在 commentTextLookup.briefsOf 里面。
         *   两层都判不会出错，但会让「谁负责这件事」变得模糊；
         *   这个方法是「消费方」，约定是「接口自己处理空集合」（见接口注释）。
         *   （上面那两个 Map 的 if 判空是因为 selectByIds 是 MP 的方法、
         *   我们没有地方可以把判空塞进去 —— 两者情况不同，不是风格不一致。）
         */
        List<Long> commentIds = logs.stream()
                .filter(l -> l.getTargetType() == AuditTargetType.COMMENT)
                .map(AuditLog::getTargetId)
                .distinct()
                .toList();
        /*
         * ★ 第四个分组：作者申请（P5 的 S11 补的）。同样是单独收集、单独查。
         *
         * ★★ 注意它的 target_id 是【申请表的 id】，不是申请人的 user_id ——
         *   所以补标题走的是 ContentUserLookup#authorApplyTitlesOf
         *   （内部做 apply_id → user_id → nickname 两跳），
         *   而不是现成的 nicknameOf。混用不会报错，只会显示成别人的申请。
         */
        List<Long> applyIds = logs.stream()
                .filter(l -> l.getTargetType() == AuditTargetType.AUTHOR_APPLY)
                .map(AuditLog::getTargetId)
                .distinct()
                .toList();

        /*
         * ★ 评论没有「标题」这一列，正文开头十几个字就是它的名字 ——
         *   截断在 interaction 那边做（{@code InternalCommentQueryService}
         *   里的 {@code MAX_TEXT_LENGTH = 30}），这里拿到的是成品。
         *   ★ P6 起返回值还带「它挂在什么对象上」，但【位置串在本类拼】——
         *   详见 CommentBriefDTO 的注释：让 interaction 拼位置会绕出一个 Bean 环，
         *   而且位置本来就是这一页的版式，本类别的三种 targetType 也都在这儿拼。
         *   ★★ M2 之后这条论证升级了：那个「Bean 环」变成了一次<b>真实的往返
         *   网络调用</b>（让它拼位置，就是一次请求打回调用方），
         *   所以「事实归 interaction、版式归 content」这个分工比 P6 时更硬。
         *
         * ★ 查不到的 id 不会出现在 Map 里，于是下面 get 出 null，
         *   VO 里 targetTitle 为空，前端显示成「评论 #5（已删除）」——
         *   和作品 / 章节完全一致，这是【有意接受】的结果：
         *   为了补出已删除对象的标题而绕过逻辑删除是错误的修法。
         */
        /*
         * ★★★ M5（S7）：三次跨服务取值【一起发出去】，不再一次一次等。
         *
         *   改造前这三行是串行的，一页日志要等三个往返：
         *     commentTextLookup.briefsOf        → interaction   （1 跳）
         *     userLookup.authorApplyTitlesOf    → auth          （★ 内部自己就 2 跳，见那个方法）
         *     userLookup.nicknamesOf            → auth          （1 跳）
         *   ★ 而它们【互相之间没有任何依赖】—— 纯粹是白等。
         *
         *   ★★ 为什么不是「快一点而已」：这三个方法的失败语义在 S5 已经分过档
         *   （briefsOf / authorApplyTitlesOf 失败仍然抛，nicknamesOf 降级成空 Map），
         *   而【它们的耗时都等于下游的响应时间】。下游一慢，串行的代价是三倍，
         *   正好落在「用户以为页面卡死了」的那个区间。
         *
         *   ★ 池的参数与拒绝策略见 AuditAggExecutorConfig；池线程上
         *   traceId / 登录态怎么过去的，见 ContextPropagatingTaskDecorator。
         *   ★★ 那两个不是配套设施，是【前提】：少了装饰器，这里发出去的
         *   Feign 调用会变成匿名的（且不报错）。
         */
        CompletableFuture<Map<Long, CommentBriefDTO>> briefsFuture =
                CompletableFuture.supplyAsync(() -> commentTextLookup.briefsOf(commentIds),
                        auditAggExecutor);
        CompletableFuture<Map<Long, String>> applyTitlesFuture =
                CompletableFuture.supplyAsync(() -> userLookup.authorApplyTitlesOf(applyIds),
                        auditAggExecutor);
        CompletableFuture<Map<Long, String>> operatorNamesFuture =
                CompletableFuture.supplyAsync(
                        () -> userLookup.nicknamesOf(logs.stream().map(AuditLog::getOperatorId).toList()),
                        auditAggExecutor);

        /*
         * ★ 先等评论素材 —— 不是因为它最慢，是因为【下面两次本地查询依赖它】。
         *
         *   等它的这段时间里另外两个任务在池线程上继续跑，所以这段等待不是白等；
         *   反过来先等那两个的话，「等远程」和「查本地库」就排成了两段，
         *   三次远程调用实际上还是两批。★ 顺序在这里是有性能含义的，
         *   不是随手挑的。（★ R9：这只影响【什么时候拿到数据】，
         *   不影响最终 list 的顺序 —— 见方法末尾那个 stream。）
         */
        Map<Long, CommentBriefDTO> commentBriefs = joinOrRethrow(briefsFuture);

        /*
         * ★ 章评的 target_id 是 chapterId，得先有章节才知道它挂在哪本书上，
         *   所以章节必须【比作品先查】。两次查询的 id 集合都是合并出来的：
         *   章节 = 章节行的 ∪ 章评的；作品 = 作品行的 ∪ 书评自己的 ∪ 章评所属章节的。
         *   合并是为了「一页日志里每种对象只查一次」，不是为了少写几行 ——
         *   拆开查会让评论的位置多两次往返，而它们本来就在同一页里。
         */
        Set<Long> allChapterIds = new LinkedHashSet<>(chapterIds);
        for (CommentBriefDTO brief : commentBriefs.values()) {
            if (brief.chapterId() != null) {
                allChapterIds.add(brief.chapterId());
            }
        }

        Map<Long, Chapter> chapters = new LinkedHashMap<>();
        if (!allChapterIds.isEmpty()) {
            for (Chapter chapter : chapterMapper.selectByIds(allChapterIds)) {
                chapters.put(chapter.getId(), chapter);
            }
        }

        Set<Long> allNovelIds = new LinkedHashSet<>(novelIds);
        for (CommentBriefDTO brief : commentBriefs.values()) {
            if (brief.novelId() != null) {
                allNovelIds.add(brief.novelId());
            }
            Chapter chapter = brief.chapterId() == null ? null : chapters.get(brief.chapterId());
            if (chapter != null) {
                allNovelIds.add(chapter.getNovelId());
            }
        }

        Map<Long, Novel> novels = new LinkedHashMap<>();
        if (!allNovelIds.isEmpty()) {
            for (Novel novel : novelMapper.selectByIds(allNovelIds)) {
                novels.put(novel.getId(), novel);
            }
        }

        /*
         * ★ 这里留的是实体不是标题串：章评的位置要用 {@code chapterNo} 而不是章节标题，
         *   所以 {@code chapters} 得活着。两个 Map 只是给 switch 用的便捷视图。
         */
        Map<Long, String> novelTitles = new LinkedHashMap<>();
        novels.forEach((id, novel) -> novelTitles.put(id, novel.getTitle()));

        Map<Long, String> chapterTitles = new LinkedHashMap<>();
        chapters.forEach((id, chapter) -> chapterTitles.put(id, chapter.getTitle()));

        /*
         * ★ 作者申请的「标题」是「笔名（昵称）」，不是正文截断 ——
         *   申请的两个内容字段里，intro 可能很长（500 字）、也可能为空，
         *   而 pen_name 是必填的短字段，天生适合当标题。
         *   所以这里【没有】第二个 CommentTextLookup 那样的接口，
         *   直接走 ContentUserLookup —— 它本来就是这个模块望向 system 的窗口。
         */
        /*
         * ★ 到这里本地两次查询已经做完，才回来取这两个还没到的结果。
         *   多数情况下它们【早就回来了】（上面那两次本地查询正好盖住了这两段的网络时间），
         *   所以这两行通常是零等待 —— 这正是「先等谁」那个顺序换来的东西。
         *
         * ★★ 失败语义【和改造前一模一样】，一个都没有被这次并行化动过：
         *     applyTitles 失败 → 抛（S5 刻意不降级：它的空值在页面上会渲染成
         *     「作者申请 #5（已删除）」，而那条申请并没有被删）
         *     operatorNames 失败 → 空 Map → 操作人显示成「用户 #1」（S5 判定这是真话，可降级）
         *   ★ 而且归因顺序也没变：briefsFuture 先被 join，所以「多个下游同时挂掉」
         *   时冒出来的仍然是同一个异常 —— 异常类型和消息都照旧。
         */
        Map<Long, String> applyTitles = joinOrRethrow(applyTitlesFuture);

        Map<Long, String> operatorNames = joinOrRethrow(operatorNamesFuture);

        return logs.stream().map(l -> {
            /*
             * ★ 补不出标题是正常情况（对象被逻辑删除了），返回 null，
             *   前端显示成「作品 #5（已删除）」。见 AuditLogVO#targetTitle 的注释 ——
             *   为了补出已删除对象的标题而绕过 @TableLogic 是错误的修法。
             */
            String title = switch (l.getTargetType()) {
                case NOVEL -> novelTitles.get(l.getTargetId());
                case CHAPTER -> chapterTitles.get(l.getTargetId());
                /*
                 * ★ P5（S9）补上的分支 —— 这个 switch 表达式从 P2 起就
                 * 等着这一行：写成 switch 表达式（而不是语句 + default）
                 * 就是为了「新增一个枚举值必须回来补」，编译器会拦住你。
                 * 在补上之前，评论的审核流水在日志页显示的是「评论 #5」，
                 * 管理员看不出审的是哪一条。
                 */
                case COMMENT -> commentTitle(commentBriefs.get(l.getTargetId()), chapters, novels);
                /*
                 * ★★ P5（S11）补上的最后一条分支。至此 AuditTargetType 的
                 *   四个取值全都有了实现 —— 这个 switch 表达式一共拦了两次
                 *   （S9 的 COMMENT、这里的 AUTHOR_APPLY），它就是这么设计的。
                 *
                 * ★ 走到 null 只有一种情况：申请被逻辑删除。
                 *   （申请人注销不会 —— 那时 applyTitles 里仍然有纯笔名。）
                 */
                case AUTHOR_APPLY -> applyTitles.get(l.getTargetId());
            };
            return AuditLogVO.of(l, title, operatorNames.get(l.getOperatorId()));
        }).toList();
    }

    /**
     * ★★ <b>等一个异步结果，并且把异常【还原成它本来的样子】再抛。</b>
     *
     * <p>这是 S7 里唯一一处「写错了能编译、能跑、测试还是绿的、但行为已经变了」的地方：
     * {@code CompletableFuture.join()} 抛的<b>不是</b>任务里那个异常，
     * 而是 {@link CompletionException} <b>包着</b>它。就这么让它冒出去，症状是：
     *
     * <pre>
     *   改造前：briefsOf 失败 → BusinessException(2001/9999…)
     *                        → 全局异常处理器认得它        → 客户看到那句话
     *   改造后：同一个失败     → CompletionException 包着它
     *                        → 全局处理器【不认识这个类型】 → 落到兜底分支
     * </pre>
     *
     * <p>★ <b>两者都是 500</b>，所以「状态码对不对」这种验收完全看不出来 ——
     * 变的是响应体里那句话、以及日志里堆栈的第一行。
     * <b>「能跑通但说错了原因」正是本项目反复强调的那类错误</b>（同 S5 的
     * 「（已删除）」、判断 4 里那个把「content 挂了」说成「ES 挂了」的 catch），
     * 所以这里显式展开，不用「反正都是 500」放过它。
     *
     * <p>★ 只展开 {@code RuntimeException}：这三个任务体里跑的全是 Feign 调用，
     * 抛出来的一律是运行时异常。真冒出受检异常的话（本类的三个任务都不会），
     * 说明有人往池里塞了新东西 —— 那就不猜了，{@code CompletionException} 原样抛，
     * 至少让人看见「这里有个我不认识的失败」。
     *
     * <p>★ 顺带一条：这三个 {@code CompletableFuture} <b>不需要 join 完</b>。
     * 上面第一个 join 就抛出去时，另外两个任务仍在池线程上跑到底 ——
     * 它们是无副作用的读，跑完的结果没人取，也不会有线程泄漏。
     * <b>不需要（也不该）为它们加取消逻辑</b>：取消一个已经在飞的 HTTP 调用省不下什么，
     * 反而多一条要维护的路径。
     */
    private static <T> T joinOrRethrow(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * 拼评论在审核日志里的一行：{@code 《书名》第N章 · 正文}（书评没有「第N章」）。
     *
     * <p>★ <b>位置拼不出来就只给正文</b>，而不是给一个「《》第 章」这种半个位置 ——
     * 那比没有位置更难读。拼不出来只有一种情况：书 / 章节已被逻辑删除
     * （{@code selectByIds} 把逻辑删的行滤掉了），或者 id 根本对不上。
     *
     * <p>★ <b>和上面作品 / 章节那两行有一处刻意的不一样：这里不筛上架 / 发布状态。</b>
     * 审核日志是管理端的页面，管理员本来就看得见全部；而「这本书已经下架了」
     * 恰恰是查流水时最该看到的信息，把它藏起来只会让人以为数据丢了。
     * （P6 中途这里曾走过只返回已上架作品的 {@code NovelService#briefsOf}，
     * 结果是书一被下架、它名下所有评论的位置集体消失 —— 那样是错的。）
     */
    private String commentTitle(CommentBriefDTO brief, Map<Long, Chapter> chapters,
                                Map<Long, Novel> novels) {
        if (brief == null) {
            return null;
        }

        String location = null;
        if (brief.chapterId() != null) {
            Chapter chapter = chapters.get(brief.chapterId());
            Novel novel = chapter == null ? null : novels.get(chapter.getNovelId());
            if (novel != null) {
                location = "《" + novel.getTitle() + "》第" + chapter.getChapterNo() + "章";
            }
        } else if (brief.novelId() != null) {
            Novel novel = novels.get(brief.novelId());
            if (novel != null) {
                location = "《" + novel.getTitle() + "》";
            }
        }

        if (location == null) {
            return brief.text();
        }
        return brief.text() == null ? location : location + " · " + brief.text();
    }

    /** 分页兜底。★ 下限必须自己兜：MP 对 {@code size < 1} 是【不分页】，会返回整张流水表 */
    private Page<AuditLog> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }
}
