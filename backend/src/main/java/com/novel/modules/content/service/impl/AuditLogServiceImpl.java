package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.result.PageResult;
import com.novel.common.security.UserContext;
import com.novel.modules.content.entity.AuditLog;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.AuditLogMapper;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.service.CommentTextLookup;
import com.novel.modules.content.service.ContentUserLookup;
import com.novel.modules.content.vo.AuditLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 实现在 interaction 侧（{@code CommentTextLookupImpl}），
     * 依赖方向是 interaction → content，单向。
     * <b>本类里找不到任何对 interaction 的 import</b>，这就是这个接口的全部意义。
     *
     * <p>★ 完整理由（含「为什么 P1 的 ContentUserLookup 是反过来的做法」）
     * 写在 {@link CommentTextLookup} 的类注释里。
     */
    private final CommentTextLookup commentTextLookup;

    @Override
    public void record(AuditTargetType targetType, Long targetId, AuditAction action, String remark) {
        AuditLog entity = new AuditLog();
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setAction(action);
        /*
         * ★ 操作人从当前登录态取，不接受调用方传入 —— 见 AuditLogService#record 的注释。
         *
         * 这里用 requireUserId() 而不是 getUserId()：流水表【必须】有个操作人，
         * 记成 null 的流水等于没记（查账时看到一条「某人做了某事」但不知道是谁）。
         * 如果真到了没有登录态的场景（定时任务），requireUserId 会明确报错，
         * 而不是悄悄写一条 operator_id 为 null 的记录。
         */
        entity.setOperatorId(UserContext.requireUserId());
        entity.setRemark(remark);

        auditLogMapper.insert(entity);

        /*
         * 日志级别用 info：审核是正常业务动作，不是异常。
         * 但一定要打 —— 这是「系统日志」和「业务流水」互相印证的另一半：
         * 库里的 audit_log 被谁删过（有权限直接连库的人），
         * 应用日志里那一条还在。
         */
        log.info("审核流水 | type={} targetId={} action={} operatorId={}",
                targetType, targetId, action, entity.getOperatorId());
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
         * ★ 这里【不判空集合】—— 判空在 commentTextLookup.textsOf 里面。
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

        Map<Long, String> novelTitles = new LinkedHashMap<>();
        if (!novelIds.isEmpty()) {
            for (Novel novel : novelMapper.selectByIds(novelIds)) {
                novelTitles.put(novel.getId(), novel.getTitle());
            }
        }

        Map<Long, String> chapterTitles = new LinkedHashMap<>();
        if (!chapterIds.isEmpty()) {
            for (Chapter chapter : chapterMapper.selectByIds(chapterIds)) {
                chapterTitles.put(chapter.getId(), chapter.getTitle());
            }
        }

        /*
         * ★ 评论没有「标题」这一列，正文开头十几个字就是它的名字 ——
         *   截断在实现里做（{@code MAX_TEXT_LENGTH = 30}），这里拿到的是成品。
         *
         * ★ 查不到的 id 不会出现在 Map 里，于是下面 get 出 null，
         *   VO 里 targetTitle 为空，前端显示成「评论 #5（已删除）」——
         *   和作品 / 章节完全一致，这是【有意接受】的结果：
         *   为了补出已删除对象的标题而绕过逻辑删除是错误的修法。
         */
        Map<Long, String> commentTexts = commentTextLookup.textsOf(commentIds);

        /*
         * ★ 作者申请的「标题」是「笔名（昵称）」，不是正文截断 ——
         *   申请的两个内容字段里，intro 可能很长（500 字）、也可能为空，
         *   而 pen_name 是必填的短字段，天生适合当标题。
         *   所以这里【没有】第二个 CommentTextLookup 那样的接口，
         *   直接走 ContentUserLookup —— 它本来就是这个模块望向 system 的窗口。
         */
        Map<Long, String> applyTitles = userLookup.authorApplyTitlesOf(applyIds);

        Map<Long, String> operatorNames = userLookup.nicknamesOf(
                logs.stream().map(AuditLog::getOperatorId).toList());

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
                case COMMENT -> commentTexts.get(l.getTargetId());
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

    /** 分页兜底。★ 下限必须自己兜：MP 对 {@code size < 1} 是【不分页】，会返回整张流水表 */
    private Page<AuditLog> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }
}
