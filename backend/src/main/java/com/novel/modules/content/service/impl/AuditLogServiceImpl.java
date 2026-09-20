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
                 * ★ 评论和作者申请是 P5/P6 的，P2 一条都不会产生。
                 *   这里刻意返回 null 而不是查库 —— 那两张表对应的 Service
                 *   还不存在，现在写查询只会写出一段没被验证过的代码。
                 *   P5 落地时在这里补上分支即可（switch 表达式会强制你回来补）。
                 */
                case COMMENT, AUTHOR_APPLY -> null;
            };
            return AuditLogVO.of(l, title, operatorNames.get(l.getOperatorId()));
        }).toList();
    }

    /** 分页兜底。★ 下限必须自己兜：MP 对 {@code size < 1} 是【不分页】，会返回整张流水表 */
    private Page<AuditLog> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }
}
