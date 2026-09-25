package com.novel.modules.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.CommentBriefDTO;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.entity.AiAuditResult;
import com.novel.modules.ai.mapper.AiAuditResultMapper;
import com.novel.modules.ai.vo.AiAuditRowVO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.CommentTextLookup;
import com.novel.modules.content.service.ContentUserLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 违禁审核<b>队列</b>的管理端读写（E1/S9）。
 *
 * <h3>★★★ 为什么它和 {@link AiAuditService} 是两个类</h3>
 *
 * <p>「判定」和「队列」是两件事，差别不是分层，而是<b>时间观</b>：
 * <pre>
 *   AiAuditService     在【消费端】，回答「这条内容危不危险」——
 *                      它跑在 MQ 线程上、要花钱、幂等键是内容指纹
 *   本类                在【管理端】，回答「哪些判定还没人处置」——
 *                      它跑在请求线程上、一分钱不花、只读写 ai_audit_result
 * </pre>
 * ★ 合在一起的话，本类会拿到 {@code SensitiveWordScanner} / {@code AiModelClient}
 * 这些只有消费端才需要的依赖，而<b>「管理端页面永远不该能触发一次付费调用」</b>
 * 这条不变量就只剩注释在守着了。
 *
 * <h3>★★ 本类【不碰】任何业务状态</h3>
 *
 * <p>它只写 {@code ai_audit_result} 一张表。评论 / 章节的通过、驳回<b>只发生在既有
 * 的处置页</b>（审核中心 / 评论管理）—— 那两处会经 {@code AuditLogServiceImpl#record}
 * 反过来把队列里这行标掉。★ 这条分工是判断 3.2 的落点，
 * 也是「队列页不碰 {@code comment.status} / {@code chapter.status}」那条决定
 * 在代码上的样子：本类里没有任何一行能改那两张表。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditQueueService {

    private static final long DEFAULT_PAGE_SIZE = 10;

    private final AiAuditResultMapper auditResultMapper;

    /*
     * ★ 三个只读依赖，和 AuditLogServiceImpl#page 是同一套（那三个也服务于同一件事：
     *   把裸 id 变成人看得懂的一行）。本类不引入任何新接缝 ——
     *   chapter / novel 在本库，评论正文走既有的 CommentTextLookup，昵称走既有的 ContentUserLookup。
     *   ★★ 出站边一条都没多：这两个接缝在 M2/M3 就存在（铁律 1 的「不新增边」）。
     */
    private final ChapterMapper chapterMapper;
    private final NovelMapper novelMapper;
    private final CommentTextLookup commentTextLookup;
    private final ContentUserLookup userLookup;

    /**
     * 队列分页。
     *
     * <p><b>★★ 三个筛选条件都可以为 {@code null} = 不筛，但页面默认传 {@code status=0}</b>
     * —— 这一页叫「违禁审核」，默认视图是<b>待办</b>；已处置的行要主动切过去看。
     * （不把 {@code status=0} 写死在这里：那样「查已排除的记录」就变成两个接口。
     * 权威在调用方，见 {@code AdminAiAuditController}。）
     *
     * <p>★ <b>排序是「风险等级倒序 → id 倒序」</b>，不是单纯的时间倒序：
     * 队列是给人办事的顺序，高风险该排在前面。{@code id} 是自增的，
     * 所以第二段排序键<b>同时</b>兑现了本项目那条「一律带 {@code id DESC} 兜底」的规矩
     * （同一秒 {@code DATETIME} 会打平，翻页顺序会跳）—— 一行两个作用，不是随手加的。
     *
     * @param targetType 1小说 2章节 3评论，{@code null} = 全部
     * @param riskLevel  0~3，{@code null} = 全部
     * @param status     0待确认 1已确认 2已排除，{@code null} = 全部
     */
    public PageResult<AiAuditRowVO> page(Integer targetType, Integer riskLevel, Integer status,
                                         long page, long size) {
        Page<AiAuditResult> result = auditResultMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<AiAuditResult>()
                        .eq(targetType != null, AiAuditResult::getTargetType, targetType)
                        .eq(riskLevel != null, AiAuditResult::getRiskLevel, riskLevel)
                        .eq(status != null, AiAuditResult::getStatus, status)
                        .orderByDesc(AiAuditResult::getRiskLevel)
                        .orderByDesc(AiAuditResult::getId));

        return new PageResult<>(toVOList(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 「排除」—— 管理员判定这是一次<b>AI 误杀</b>（或词库误杀），把它从待办里拿掉。
     *
     * <p><b>★★★ 它刻意【不写】{@code audit_log}，而「通过 / 驳回」那边会写。</b>
     * 判据是「有没有状态流转可记」（与「发表评论不记流水」同一条论证）：
     * <pre>
     *   通过 / 驳回 → 内容的状态真的变了 → 有流转 → 记流水（并由此联动本表，反过来标掉队列行）
     *   排除        → 什么状态都没变，只是「这条 AI 判定不用再看了」→ 没流转 → 不记流水
     * </pre>
     * ★★ 所以这一页有<b>两个入口、两种后果</b>，页面必须把它们说清楚：
     * 「排除」只是清掉提示，<b>内容还在待审队列里</b>，该驳回还得去处置页驳回。
     * 把两个按钮做成看起来一样，症状是管理员以为「点一下这事就完了」，
     * 而那条违规评论一直挂在待审队列里 —— 或者更糟，谁都没注意它其实是<b>已通过</b>的。
     *
     * <p>★ <b>它走的是和联动完全相同的那条 UPDATE</b>
     * （{@code AiAuditResultMapper#linkHumanDisposition}）：乐观条件 {@code status=0}、
     * 写 {@code audit_user_id} / {@code audit_time}、不碰判定内容。
     * 两处共用一条语句是有意的 —— 「什么算被人处置过」只能有一个定义。
     *
     * @param targetType 1小说 2章节 3评论
     * @param targetId   目标 id
     * @throws BusinessException {@code AI_AUDIT_ALREADY_DISPOSED(5010)}
     *                           —— 这行已经不是待办了（别人先处置了，或自己点了两次）。
     *                           ★ 见那个错误码的注释：这里<b>不能</b>装作成功，
     *                           否则页面会对一条「已确认违规」的行说「已排除」。
     */
    public void exclude(Integer targetType, Long targetId) {
        Long operatorId = UserContext.requireUserId();
        int rows = auditResultMapper.linkHumanDisposition(
                targetType, targetId, AiAuditResult.STATUS_EXCLUDED, operatorId, LocalDateTime.now());
        if (rows == 0) {
            /*
             * ★ 记一条 WARN：这不是错误，但它【值得被看见】——
             *   它要么说明页面上的数据旧了（别人刚处置过），
             *   要么说明有人在重复点击。两种都指向「这一页该刷新了」。
             */
            log.warn("排除失败：该审核不是待办状态 | targetType={} targetId={} operatorId={}",
                    targetType, targetId, operatorId);
            throw new BusinessException(ResultCode.AI_AUDIT_ALREADY_DISPOSED);
        }
        log.info("AI 审核已排除（未写 audit_log：没有任何状态流转）| targetType={} targetId={} operatorId={}",
                targetType, targetId, operatorId);
    }

    /**
     * 实体列表 → VO 列表。
     *
     * <p><b>★ 形状抄 {@code AuditLogServiceImpl#toVOList}</b>：先收集 id → 批量查 →
     * 再装配。循环里查库在 M2 之后是<b>一页 20 次跨进程 HTTP</b>，
     * 而代码上它只多了一个字母。
     *
     * <p>★★ <b>这里的合并比审核日志那页更绕一层，原因值得写下来：</b>
     * 本表的每一行只有一个 {@code (targetType, targetId)}，而<b>要显示它需要三种对象</b>：
     * <pre>
     *   章节行(2) → 章节 → 它的 novel_id → 作品
     *   评论行(3) → 评论 → 它挂在谁身上（书评给 novel_id、章评给 chapter_id）→ 章节 → 作品
     * </pre>
     * 所以「章节」这个集合是<b>章节行的 id ∪ 章评所挂的 chapterId</b>，
     * 「作品」是<b>章节的 novelId ∪ 书评的 novelId</b>。合并的意义是
     * 「一页里每种对象只查一次」—— 拆开查会让章评的位置多两次往返，
     * 而它们本来就在同一页里。
     *
     * <p>★ 顺序上<b>必须先取评论素材</b>：章评的 chapterId 只有拿到 brief 才知道，
     * 而章节集合里有它的一份。
     *
     * <p>★★ 三个「取不到」在这里被<b>如实</b>地留成 {@code null}，一个都不编：
     * 对象被逻辑删除（{@code selectByIds} 会滤掉）、评论被删除（不在 Map 里）、
     * auth 挂了（昵称降级成空 Map）。VO 那边逐条说明了页面该怎么渲染它们。
     */
    private List<AiAuditRowVO> toVOList(List<AiAuditResult> rows) {
        if (rows.isEmpty()) {
            /*
             * ★ 空页直接返回，不去问 auth / interaction 要空集合 ——
             *   翻到最后一页之后的每一次点击都会走到这里（同 AiUsageServiceImpl）。
             */
            return List.of();
        }

        List<Long> chapterIds = idsOfType(rows, AuditTargetType.CHAPTER);
        List<Long> commentIds = idsOfType(rows, AuditTargetType.COMMENT);

        /* 评论素材：一次跨服务调用（★ 不降级，取不到就 500 —— 见 CommentTextLookup 的类注释） */
        Map<Long, CommentBriefDTO> briefs = commentTextLookup.briefsOf(commentIds);

        /* 章节：章节行的 id ∪ 章评挂着的 chapterId */
        Set<Long> allChapterIds = new LinkedHashSet<>(chapterIds);
        for (CommentBriefDTO brief : briefs.values()) {
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

        /* 作品：本章节的 novelId ∪ 书评自己的 novelId */
        Set<Long> allNovelIds = new LinkedHashSet<>();
        for (Chapter chapter : chapters.values()) {
            if (chapter.getNovelId() != null) {
                allNovelIds.add(chapter.getNovelId());
            }
        }
        for (CommentBriefDTO brief : briefs.values()) {
            if (brief.novelId() != null) {
                allNovelIds.add(brief.novelId());
            }
        }
        Map<Long, Novel> novels = new LinkedHashMap<>();
        if (!allNovelIds.isEmpty()) {
            for (Novel novel : novelMapper.selectByIds(allNovelIds)) {
                novels.put(novel.getId(), novel);
            }
        }

        /*
         * ★ 昵称：{@code audit_user_id} 在待办行上是 null，而 nicknamesOf 自己会
         *   滤掉 null 与空集合（ContentUserLookup#nicknamesOf 的头两行），所以这里直接传。
         *   ★ 它是唯一一个【可以降级】的取值，见 AuthUserClientFallbackFactory。
         */
        Map<Long, String> names =
                userLookup.nicknamesOf(rows.stream().map(AiAuditResult::getAuditUserId).toList());

        return rows.stream()
                .map(r -> AiAuditRowVO.of(r, factsOf(r, briefs, chapters, novels),
                        names.get(r.getAuditUserId())))
                .toList();
    }

    /** 取本页里某一类目标 id（去重）。★ 类型码统一从 {@code AuditTargetType} 取，本类里不出现裸数字 */
    private static List<Long> idsOfType(List<AiAuditResult> rows, AuditTargetType type) {
        return rows.stream()
                .filter(r -> type.getCode().equals(r.getTargetType()))
                .map(AiAuditResult::getTargetId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 一行的展示素材。★ 两种类型走两条完全不同的取法，所以这个 switch 是有信息量的：
     * <pre>
     *   章节 → 标题取 chapter.title；「最后更新时间」取 chapter.update_time（作者能改正文）
     *   评论 → 标题取【正文截断】；★ 没有更新时间可言（novel_comment 无 update_time 列，
     *          且正文端到端不可修改）→ contentImmutable=true
     * </pre>
     * <p>★ <b>不认识的类型（比如有人往表里插了一行 {@code target_type=1}）不给素材</b>：
     * 标题、书名全是 {@code null}，页面显示「小说 #8（已删除）」。
     * 那<b>不准确</b>（书可能好好地在库里），但它诚实地表达了「这一行我认不出来」——
     * 比给作品行补上一个标题、让它看起来和我们支持的类型一样要安全：
     * 后者会让人以为<b>作品也在 AI 审核范围内</b>，而它不在（作品简介审核是明确不做的）。
     * <p>★★ 本方法【不查库】：素材全靠调用方传进来的三个 Map，见 {@code toVOList}。
     */
    private static AiAuditRowVO.TargetFacts factsOf(AiAuditResult row,
                                                    Map<Long, CommentBriefDTO> briefs,
                                                    Map<Long, Chapter> chapters,
                                                    Map<Long, Novel> novels) {
        if (AuditTargetType.CHAPTER.getCode().equals(row.getTargetType())) {
            Chapter chapter = chapters.get(row.getTargetId());
            if (chapter == null) {
                // 章节被逻辑删除了 —— 标题与时间都补不出来，页面显示「章节 #id（已删除）」
                return new AiAuditRowVO.TargetFacts(null, null, null, null, false);
            }
            Novel novel = novels.get(chapter.getNovelId());
            return new AiAuditRowVO.TargetFacts(
                    chapter.getTitle(),
                    chapter.getNovelId(),
                    novel == null ? null : novel.getTitle(),
                    chapter.getUpdateTime(),
                    false);
        }

        if (AuditTargetType.COMMENT.getCode().equals(row.getTargetType())) {
            CommentBriefDTO brief = briefs.get(row.getTargetId());
            /*
             * ★ 书评直接有 novelId；章评要先经 chapters 反查它挂的那一章。
             *   两步都补不出来时（书/章被删）novelId 与 novelTitle 一起留空，
             *   页面据此【禁用】跳转按钮 —— 而不是跳到一个 404 的 URL 上。
             */
            Long novelId = null;
            if (brief != null) {
                if (brief.chapterId() != null) {
                    Chapter chapter = chapters.get(brief.chapterId());
                    novelId = chapter == null ? null : chapter.getNovelId();
                } else {
                    novelId = brief.novelId();
                }
            }
            Novel novel = novelId == null ? null : novels.get(novelId);
            /*
             * ★★★ contentImmutable 恒 true，【不依赖 brief 是否存在】——
             *   它说的是 novel_comment 那张表的结构（没有 update_time 列、正文不可改），
             *   与「这条评论还在不在」无关。写成 brief != null && ... 的话，
             *   「评论被删了」与「评论能改」会表现出同一个样子。
             */
            return new AiAuditRowVO.TargetFacts(
                    brief == null ? null : brief.text(),
                    novelId,
                    novel == null ? null : novel.getTitle(),
                    null,
                    true);
        }

        // ★ 认不出来的类型（或 targetType 为 null）：什么都不给，也不编
        return new AiAuditRowVO.TargetFacts(null, null, null, null, false);
    }

    /**
     * 分页兜底。★ <b>下限必须自己兜</b>：MP 对 {@code size < 1} 的处理是
     * <b>完全不拼 LIMIT，返回整张表</b> —— 一个 {@code ?size=-1} 就能把全部判定拉出来。
     * <p>★ 上限不在这里兜：{@code PaginationInnerInterceptor.setMaxLimit(100)} 是权威
     * （写第二份只会让人以为两者效力相同）。同 {@code AiUsageServiceImpl#buildPage}。
     */
    private Page<AiAuditResult> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }
}
