package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.enums.NovelStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.modules.content.dto.ChapterCreateDTO;
import com.novel.modules.content.dto.ChapterSaveDTO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.ChapterContent;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.ChapterContentMapper;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.ChapterBriefVO;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.ChapterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 章节服务实现。
 *
 * <p><b>★★ 三个最容易出错的地方，改这个类之前先看这三条：</b>
 * <ol>
 *   <li>{@link #transitStatus} 是章节状态的<b>唯一</b>改动入口（同作品）。</li>
 *   <li>{@link #delete} <b>不能</b>用 {@code deleteById} —— 唯一键
 *       {@code uk_novel_chapter_no} 不含 {@code deleted}，
 *       必须用 {@link ChapterMapper#logicDeleteAndFreeChapterNo} 顺手腾出序号。</li>
 *   <li>{@link #saveContent} 在「已发布」时会<b>自动退回草稿</b>并重算作品统计。</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterServiceImpl implements ChapterService {

    private static final long DEFAULT_PAGE_SIZE = 10;

    /**
     * 「空白字符」的定义，用于算字数。
     *
     * <p>预编译成常量，不在每次算字数时重新编译 —— 一章叫一次，
     * 一页 20 章就是 20 次编译；正则编译本身是这几行代码里最贵的一步。
     *
     * <p>★ <b>口径：去掉所有空白字符之后的字符数。</b>
     * {@code \s} 在 Java 里是 {@code [ \t\n\x0B\f\r]}，
     * 中文、英文、标点都算 1 个字。
     * <b>不数 emoji 的「一个字符」</b>（{@code String.length()} 数的是 UTF-16 码元，
     * emoji 算 2），也不按「词」数 —— 网文行业按字符数算，这个精度足够。
     * 为一个字数统计引分词库（IK / jieba）是明显的过度工程。
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private final ChapterMapper chapterMapper;
    private final ChapterContentMapper chapterContentMapper;
    private final NovelMapper novelMapper;
    private final NovelService novelService;
    private final NovelAccessGuard guard;
    private final AuditLogService auditLogService;

    /* ================================================================
     *  作者端
     * ================================================================ */

    @Override
    public List<ChapterVO> authorList(Long novelId, ChapterStatus status) {
        // ★ 先做归属校验：不是自己的作品直接 2002，一个章节都不会返回
        Novel novel = guard.requireOwned(novelId);

        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .eq(status != null, Chapter::getStatus, status)
                .orderByAsc(Chapter::getChapterNo));

        /*
         * ★ 书名是同一个，循环外取一次就行 —— 所以这里不需要
         * ContentUserLookup 那种批量查询。看起来是 N+1 的形状，
         * 实际上只查了一次库。
         */
        return chapters.stream()
                .map(c -> ChapterVO.of(c, novel.getTitle()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long novelId, ChapterCreateDTO dto) {
        guard.requireOwned(novelId);

        Chapter chapter = new Chapter();
        chapter.setNovelId(novelId);
        chapter.setChapterNo(nextChapterNo(novelId));
        chapter.setTitle(dto.getTitle().trim());
        // ★ 新建的章节永远是草稿，和作品一样不接受入参
        chapter.setStatus(ChapterStatus.DRAFT);
        chapter.setIsFree(dto.getIsFree());
        // ★ 显式给 0：没正文时 countWords 返回 0，不依赖数据库的 DEFAULT
        chapter.setWordCount(countWords(dto.getContent()));

        try {
            chapterMapper.insert(chapter);
        } catch (DuplicateKeyException e) {
            /*
             * ★★ 并发撞号。这里【刻意不做重试循环】。
             *
             * 直觉上「重试一次就好了」，但重试在 MySQL 默认的 REPEATABLE READ 下
             * 是【错的】：同一个事务里重新执行 SELECT MAX(chapter_no)，
             * 读到的还是事务开始时的快照 —— 也就是同一个号，
             * 重试三次全部失败，最后还是要抛错。要让重试真正生效，
             * 得把重试包在一个 REQUIRES_NEW 的事务里重新开快照。
             *
             * 为一个「作者同时开两个标签页建章」的低频场景引入两个事务传播级别，
             * 换来的只是省掉一次点击 —— 不划算。
             * CHAPTER_NUMBER_EXISTS 是早就定好的错误码，作者看到
             * 「该章节序号已存在」再点一次就好了。
             */
            log.warn("新建章节撞号 | novelId={} chapterNo={}", novelId, chapter.getChapterNo());
            throw new BusinessException(ResultCode.CHAPTER_NUMBER_EXISTS);
        }

        // 建章时带了正文才写正文表 —— 空正文不写，省一行孤儿数据
        if (StringUtils.hasText(dto.getContent())) {
            upsertContent(chapter.getId(), dto.getContent());
        }

        /*
         * ★ 这里【不调】syncNovelStat，这是「重算」方案的一个自然好处：
         * 新建的是草稿，草稿不计入 novel.chapter_count（口径是「已发布章节数」），
         * 所以统计值压根没变。只有发布状态发生变化时才需要重算。
         */
        log.info("新建章节 | id={} novelId={} chapterNo={}", chapter.getId(), novelId, chapter.getChapterNo());
        return chapter.getId();
    }

    @Override
    public ChapterDetailVO authorDetail(Long id) {
        Chapter chapter = guard.requireOwnedChapter(id);
        Novel novel = novelMapper.selectById(chapter.getNovelId());

        return ChapterDetailVO.of(chapter, readContent(id), novel == null ? null : novel.getTitle());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveContent(Long id, ChapterSaveDTO dto) {
        Chapter chapter = guard.requireOwnedChapter(id);
        ChapterStatus from = chapter.getStatus();
        boolean leftPublished = false;

        if (from == ChapterStatus.PUBLISHED) {
            /*
             * ★★ 已发布的章节要改正文 → 自动退回草稿。
             *
             * 为什么是「自动」而不是「让作者先点退回草稿」：
             * 大纲 §5.3 规定已发布的章节改了内容就要重新审核（PUBLISHED → DRAFT → PENDING → PUBLISHED），
             * 但大纲的接口表里【没有】「退回草稿」这个接口。
             * 让作者为改一个错别字去找一个不存在的按钮，等于「已发布的章节永远不能改」。
             *
             * ★ 退回必须留痕（AuditAction.REVERT）：
             * 不然日志上会出现「章节一直是已发布，但内容变了」的空档 ——
             * 说不清是谁、什么时候改的，也说不清改之前有没有重新审过。
             */
            transitStatus(chapter, ChapterStatus.DRAFT, null);
            leftPublished = true;
        } else if (!from.isEditable()) {
            /*
             * ★ 只剩 PENDING 会走到这里（DRAFT/REJECTED 在 isEditable 里返回 true，
             * PUBLISHED 被上面那个分支接走了）。
             *
             * 拒绝的理由：审核员弹窗预览的是 A 版正文，作者在审核期间换成 B 版，
             * 审核员点通过，读者读到的是 B —— 审核被绕过，
             * 而日志上是一次完全正常的审核。这类 bug 事后查不出来，
             * 因为它留下的痕迹和正常流程一模一样。
             */
            throw new BusinessException(ResultCode.CHAPTER_STATUS_ILLEGAL,
                    "「" + from.getDesc() + "」状态下不能修改正文"
                            + "（审核期间不能改，请等审核结果或让管理员驳回后再改）");
        }

        chapter.setTitle(dto.getTitle().trim());
        chapter.setWordCount(countWords(dto.getContent()));
        chapterMapper.updateById(chapter);

        /*
         * ★ 正文【无条件】写，包括传空串的时候。
         * 这里和 create 不一样：create 时空正文就不写正文表（省一行孤儿数据），
         * 而这里是「作者把正文全选删掉了」，那是【有意的清空】——
         * 跳过不写的话，数据库里还留着上一次的正文，作者一刷新又冒出来了。
         */
        upsertContent(id, dto.getContent() == null ? "" : dto.getContent());

        /*
         * ★ 只有「从已发布退下来」才需要重算 —— 草稿改来改去不影响统计值。
         * 放在最后调：此时章节已经是草稿，重算读到的就是正确的口径。
         */
        if (leftPublished) {
            novelService.syncNovelStat(chapter.getNovelId());
        }

        log.info("保存章节 | id={} from={} to={} wordCount={}",
                id, from, chapter.getStatus(), chapter.getWordCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        Chapter chapter = guard.requireOwnedChapter(id);

        /*
         * ★ 空正文不许提交审核。
         *
         * 这个校验只能在这里 —— 新建和保存都允许空正文（「先建章节占位」是
         * 大纲 §629 明确要支持的用法，编辑器全选删除也要能存）。
         * 但一个连正文都没有的章节送到审核员面前，他打开弹窗只看得到一个标题，
         * 除了点通过什么都做不了。挡在提交这一步，是唯一挡得住的位置。
         */
        String content = readContent(id);
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.CHAPTER_CONTENT_EMPTY);
        }

        transitStatus(chapter, ChapterStatus.PENDING, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        Chapter chapter = guard.requireOwnedChapter(id);
        /*
         * ★ 不显式判断「当前必须是 PENDING」—— 状态机已经限死了
         * 只有 PENDING → DRAFT 这一条（见 ChapterStatus#canTransitTo），
         * 重复判断迟早会和状态机不一致。非法状态由 transitStatus 抛 3003。
         *
         * ★ 不调 syncNovelStat：撤回前（PENDING）和撤回后（DRAFT）
         * 都不计入 chapter_count（口径是"已发布章节数"），统计值没变。
         * 对比 saveContent 里的"已发布自动退回草稿"—— 那条是从 PUBLISHED
         * 退下来的，统计值真的变了，所以那边必须调。
         */
        transitStatus(chapter, ChapterStatus.DRAFT, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Chapter chapter = guard.requireOwnedChapter(id);
        boolean wasPublished = chapter.getStatus() == ChapterStatus.PUBLISHED;

        /*
         * ★★ 必须用手写 SQL，不能用 deleteById。这是本项目最危险的一个坑。
         *
         * uk_novel_chapter_no(novel_id, chapter_no) 【不包含 deleted】。
         * deleteById 生成的只是「SET deleted = 1」，那一行还占着 (novel_id, chapter_no)
         * 这个键 —— 于是作者删掉草稿「第 5 章」之后，这本书【再也建不出第 5 章】，
         * 报 Duplicate entry。删一次就必然复现。
         *
         * 手写的那条 SQL 顺手把 chapter_no 改成 -id 来腾出序号。
         * 详见 ChapterMapper#logicDeleteAndFreeChapterNo。
         */
        int affected = chapterMapper.logicDeleteAndFreeChapterNo(id);

        /*
         * ★ affected == 0 不算错误，直接返回。
         * 它只有一个来源：并发下另一个请求已经把这一章删掉了。
         * 对调用方来说「它现在是删除状态」这个目标已经达成，
         * 再抛一个「章节不存在」只会让用户困惑（他明明刚点过删除）。
         */
        if (affected == 0) {
            log.warn("删除章节未影响任何行（可能已被并发删除）| chapterId={}", id);
            return;
        }

        /*
         * ★ 正文【不删】。chapter_content 没有 deleted 列，也没有外键级联。
         * 留着它是一行查不到的孤儿数据 —— 占空间，不影响正确性。
         * 真要清就等二期做数据归档时一起清。
         * 这里删它反而有风险：万一将来做「恢复已删除章节」，
         * 正文已经被删了，恢复出来的章节是空的。
         */

        // ★ 删掉已发布的章节才会影响统计值（草稿/待审/驳回都不计入）
        if (wasPublished) {
            novelService.syncNovelStat(chapter.getNovelId());
        }

        log.info("删除章节 | id={} novelId={} chapterNo={} wasPublished={}",
                id, chapter.getNovelId(), chapter.getChapterNo(), wasPublished);
    }

    /* ================================================================
     *  管理端
     * ================================================================ */

    @Override
    public PageResult<ChapterVO> adminPage(Long novelId, ChapterStatus status, long page, long size) {
        Page<Chapter> result = chapterMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<Chapter>()
                        .eq(novelId != null, Chapter::getNovelId, novelId)
                        .eq(status != null, Chapter::getStatus, status)
                        /*
                         * ★ 按更新时间倒序：审核员筛「待审核」时，
                         * 想先看到刚提交的那些，而不是从第 1 章开始翻。
                         * 同样的，这里也没有任何状态过滤 —— 管理端要看到全部。
                         */
                        .orderByDesc(Chapter::getUpdateTime)
                        .orderByDesc(Chapter::getId));

        /*
         * ★ selectPage 已经带了 total/current/pages，这里不能再自己 count 一次。
         * 用 PageResult.of(page, converter) 而不是手拼构造器 —— 手拼的话
         * current/size 用的是入参，而 MP 实际用的是兜底之后的值，
         * 传 ?page=0 时返回体里的 current 会和真实页码对不上。
         *
         * ★ 转换走 toVOList 而不是单条 lambda：管理端列表是【跨作品】的，
         * 20 行可能来自 20 本书，逐行查书名就是 N+1。见 toVOList 的注释。
         */
        return new PageResult<>(
                toVOList(result.getRecords()),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages());
    }

    @Override
    public ChapterDetailVO adminDetail(Long id) {
        Chapter chapter = chapterMapper.selectById(id);
        if (chapter == null) {
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }
        Novel novel = novelMapper.selectById(chapter.getNovelId());

        /*
         * ★ 这里【保留 auditRemark】——审核员要看历史驳回理由才能判断
         * 「上次为什么没过、这次改了没有」。这和读者端是相反的口径。
         */
        return ChapterDetailVO.of(chapter, readContent(id), novel == null ? null : novel.getTitle());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, boolean pass, String remark) {
        Chapter chapter = chapterMapper.selectById(id);
        if (chapter == null) {
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }

        /*
         * ★ 这里【不需要】像 NovelService.audit 那样前置判断「必须是待审核」。
         * 作品要判，是因为状态机允许 OFFLINE → PUBLISHED（重新上架），
         * 「审核通过」和「重新上架」在状态机上撞成了同一个流转。
         * 章节没有「已下架」状态：PUBLISHED 和 REJECTED 都只能由 PENDING 到达，
         * 状态机已经把它限死了。
         * 这个不对称是【状态机本身的不对称】造成的，不是这里漏写。
         */
        transitStatus(chapter, pass ? ChapterStatus.PUBLISHED : ChapterStatus.REJECTED, remark);
    }

    /* ================================================================
     *  读者端
     * ================================================================ */

    @Override
    public ChapterDetailVO readerDetail(Long novelId, Long chapterId) {
        /*
         * ★★ 第一步查【作品】，而且必须带 status = PUBLISHED。这是 C-7 约束 2。
         *
         * 漏掉这一步，只查章节的话：作品下架后，它的章节正文【照常匿名可读】——
         * 而下架往往正是因为这本书的内容有问题，这是真实的越权读。
         * 一般也想不到去测「下架之后正文还能不能读」。
         */
        Novel novel = novelMapper.selectOne(new LambdaQueryWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .eq(Novel::getStatus, NovelStatus.PUBLISHED));
        if (novel == null) {
            // ★ 和 readerDetail 一样，不区分「不存在」和「未上架」
            throw new BusinessException(ResultCode.NOVEL_NOT_FOUND);
        }

        /*
         * ★ 第二步查章节，用 requireChapterInNovel 而不是按 id 单独查：
         * URL 上有 novelId 和 chapterId 两个 id，两边都要对上。
         * 只按 chapterId 查的话，把 cid 换成别的书的章节 id，
         * 就能从一本已上架的书【读到另一本书（可能未上架）的章节】，
         * URL 上的 novelId 形同虚设。
         */
        Chapter chapter = guard.requireChapterInNovel(novelId, chapterId);
        if (!chapter.getStatus().isVisibleToReader()) {
            throw new BusinessException(ResultCode.CHAPTER_NOT_FOUND);
        }

        ChapterDetailVO vo = ChapterDetailVO.of(chapter, readContent(chapterId), novel.getTitle());
        /*
         * ★ 显式清空驳回理由。
         * 能走到这里说明 chapter 是「已发布」，而已发布章节的 audit_remark
         * 本来就是空的（通过审核时不会写理由），所以这行看起来多余 ——
         * 但「数据恰好为空」不是防线。一行赋值换掉一类泄露。
         */
        vo.setAuditRemark(null);
        return vo;
    }

    /* ================================================================
     *  ★ 状态机 —— 章节状态的唯一改动入口
     * ================================================================ */

    /**
     * 把章节流转到目标状态。<b>整个项目里唯一改 {@code chapter.status} 的地方。</b>
     *
     * <p>结构和 {@code NovelServiceImpl#transitStatus} 完全一致（private、switch 表达式、
     * 内部写审核日志），<b>刻意各写一份而不是抽公共方法</b>：
     * 两者的副作用不同（作品上架要刷冗余字段，章节发布也要，但退回时的处理不一样），
     * 抽出来会得到一个收 {@code Object} 的万能方法。
     * 这两段代码各自不到 40 行，重复是可接受的代价。
     *
     * <p><b>★ 用 switch 表达式而不是 switch 语句：</b>Java 的 switch 语句对枚举
     * <b>不做穷尽性检查</b>，将来给 {@code ChapterStatus} 加一个状态，语句会静默漏掉它。
     * 表达式必须覆盖所有分支，否则编译不过。
     */
    private void transitStatus(Chapter chapter, ChapterStatus target, String auditRemark) {
        ChapterStatus from = chapter.getStatus();

        if (!from.canTransitTo(target)) {
            throw new BusinessException(ResultCode.CHAPTER_STATUS_ILLEGAL,
                    "「" + from.getDesc() + "」不能直接变成「" + target.getDesc() + "」");
        }

        chapter.setStatus(target);

        AuditAction action = switch (target) {
            /*
             * ★ 章节目录里没有「已下架」这个状态，所以这一支永远不会命中。
             * 但 switch 表达式要求覆盖所有常量，而且真走到这里说明状态机被改坏了 ——
             * 抛异常比静默通过安全。
             */
            // ★ publishTime 用「通过审核的时间」，读者端目录按它排「更新时间」
            case PUBLISHED -> AuditAction.APPROVE;
            case PENDING -> AuditAction.SUBMIT;
            // 驳回理由必须留痕：它是作者唯一能看到的「为什么没过」
            case REJECTED -> AuditAction.REJECT;
            /*
             * ★ REVERT（退回草稿）只有一条来源：已发布的章节被改正文，
             * 由 saveContent 自动触发。见那个方法的注释。
             * 退回时要清掉 publishTime —— 它已经不是已发布状态了，
             * 留着一个「发布时间」会让作者端的目录显出错误的更新时间。
             */
            case DRAFT -> AuditAction.REVERT;
        };

        /*
         * 状态机只改这三列：status / publish_time / audit_remark。
         * 标题、正文字数不归它管（那是 saveContent 的事）。
         */
        LocalDateTime publishTime = target == ChapterStatus.PUBLISHED ? LocalDateTime.now() : null;
        String remarkToPersist = target == ChapterStatus.REJECTED ? auditRemark : null;

        // ★ 先写回内存对象：不写的话，saveContent 紧跟着的 updateById(chapter)
        //   会把对象里那个【旧的】publishTime 当成有效值又写回库里去。
        chapter.setPublishTime(publishTime);
        chapter.setAuditRemark(remarkToPersist);

        /*
         * ★★ 这里不能只用 updateById —— 它【跳过 null 字段】，所以
         * 「把 publish_time 清空」这件事它根本做不到（详见 saveContent 里的实测）。
         * 同一个坑在 NovelServiceImpl 里也有一份。
         *
         * 规避办法是 wrapper 的 set：只有值为 null 时才需要它，
         * 非 null 的情况实体自己就会把这一列写进去（写两遍是没必要的）。
         */
        LambdaUpdateWrapper<Chapter> update = new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, chapter.getId());
        if (publishTime == null) {
            update.set(Chapter::getPublishTime, null);
        }
        if (remarkToPersist == null) {
            update.set(Chapter::getAuditRemark, null);
        }
        chapterMapper.update(chapter, update);

        /*
         * ★ 顺序同作品：先 updateById 再 syncNovelStat。
         * syncNovelStat 会自己发一条 UPDATE 去写 novel 的四个冗余字段；
         * 反过来先调它的话，chapter 这个内存对象里还是旧状态，
         * 后面 updateById 会把旧值写回去。
         *
         * ★★ 判据是「【发布状态】是否发生了变化」，所以看两个方向：
         *      target == PUBLISHED  有章节被发布了
         *      from   == PUBLISHED  有已发布的章节被撤下来了
         *
         * ⚠ P4 之前这里写的是 `target == DRAFT`，那是**错的**（只是当时恰好没暴露）：
         *   它把「以草稿为目标」当成了「从已发布退下来」的同义词。
         *   当时 DRAFT 只有一个来源（saveContent 的自动退回，from 必是 PUBLISHED），
         *   两者确实等价。但 P4 加了「撤回提交」（PENDING → DRAFT）之后就不等价了 ——
         *   撤回来的章节从来没被发布过，统计值压根没变，
         *   用 target == DRAFT 判断会白算一次 4 个冗余列、
         *   并给这本书凭空加一次 UPDATE novel 的行锁。
         *   改成看 from 之后，两种情况各自正确，而且【读起来就是它的本意】。
         *   （这个 bug 不会报错、不会出错数据，只会让并发时多一次锁等待 ——
         *    所以它本来永远也不会被发现。）
         */
        boolean leftPublished = from == ChapterStatus.PUBLISHED;
        if (target == ChapterStatus.PUBLISHED || leftPublished) {
            novelService.syncNovelStat(chapter.getNovelId());
        }

        auditLogService.record(AuditTargetType.CHAPTER, chapter.getId(), action, auditRemark);
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 分配下一个章节序号：{@code MAX(chapter_no) + 1}。
     *
     * <p><b>★ 为什么是 MAX+1 而不是「最后一章 +1」：</b>
     * 删掉最后一章之后再建，MAX+1 会复用那个序号（好），
     * 而「最后一章 +1」会跳号 —— 作者在目录里看到「第 12 章」后面跟着「第 14 章」，
     * 会以为系统丢了一章。
     *
     * <p>★ 手写 SQL 里带了 {@code deleted = 0}：已删除章节的序号是 {@code -id}，
     * 不排除掉的话 MAX 会把它们算进来 —— 虽然负数不会赢过正数，
     * 但一本书【只】有已删除章节时（全部删光），MAX 会返回一个负数，
     * 于是新章节的序号变成负数，而负数段是删除标记专用的，会撞车。
     *
     * <p>★ 并发下两个请求可能拿到同一个号 —— 靠唯一键兜住，见 {@link #create}。
     */
    private int nextChapterNo(Long novelId) {
        Integer max = chapterMapper.selectMaxChapterNo(novelId);
        // ★ 判空：一本书的第一章没有「上一个序号」，直接拆箱会 NPE
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 算字数：去掉所有空白字符后的字符数。
     *
     * <p>★ {@code null} 和空串都返回 <b>0 而不是 null</b> ——
     * {@code chapter.word_count} 是 {@code NOT NULL} 列，
     * 返回 null 会在 insert 时直接报错。
     */
    private int countWords(String content) {
        if (content == null) {
            return 0;
        }
        return WHITESPACE.matcher(content).replaceAll("").length();
    }

    /**
     * 写正文（有则更新，无则插入）。
     *
     * <p>没有用 MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} ——
     * 那需要再写一条手写 SQL，而这里「先查再决定」只有两步、
     * 且在事务里，是可读性和代价都更好的写法。
     *
     * <p>★ {@code ChapterContent} 的主键是 {@code IdType.INPUT}：
     * {@code chapter_id} 由我们传进去（就是 chapter 的 id），
     * 数据库那边不是自增列。写成 AUTO 的话 MyBatis-Plus 会忽略我们传的值，
     * 正文就挂到别的章节上了。
     */
    private void upsertContent(Long chapterId, String content) {
        ChapterContent exist = chapterContentMapper.selectById(chapterId);
        if (exist == null) {
            ChapterContent entity = new ChapterContent();
            entity.setChapterId(chapterId);
            entity.setContent(content);
            chapterContentMapper.insert(entity);
        } else {
            exist.setContent(content);
            chapterContentMapper.updateById(exist);
        }
    }

    /**
     * 读正文。<b>查不到返回 {@code null}</b>，由调用方决定怎么处理 ——
     * 「作者还没写正文」和「正文是空串」在这里不做区分，
     * 因为下游（提交审核的校验、VO 的可空字段）对两者的处理完全一样。
     */
    private String readContent(Long chapterId) {
        ChapterContent content = chapterContentMapper.selectById(chapterId);
        return content == null ? null : content.getContent();
    }

    /**
     * 分页兜底。
     *
     * <p>★ 上限（{@code size} 过大）不用管 —— {@code PaginationInnerInterceptor}
     * 配了 {@code setMaxLimit(100)}，会被自动压到 100。
     * 但<b>下限必须自己兜</b>：MP 对 {@code size < 1} 的处理是
     * <b>完全不拼 LIMIT，返回整张表</b>，既不是报错也不是退回默认值。
     * 一个 {@code ?size=-1} 就能把全库章节拉出来。
     */
    private Page<Chapter> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }

    /**
     * 章节实体 → VO，<b>书名批量补齐</b>。
     *
     * <p>★ 为什么不逐条 {@code novelMapper.selectById(c.getNovelId())}：
     * 管理端的章节列表是跨作品的，一页 20 行可能来自 20 本书 —— 逐条查就是
     * 教科书式的 N+1（20 行 21 次查询）。这里先收集去重再一次查完，
     * 一页最多 2 次（1 次章节 + 1 次书名）。
     *
     * <p>★ {@code LinkedHashMap} 而不是 {@code HashMap}：这里只是查表，
     * 顺序无所谓，但用 LinkedHashMap 让「重复的 novelId 只留一个」这件事
     * 在调试时看得出来（后面的覆盖前面的，值相同）。
     */
    private List<ChapterVO> toVOList(List<Chapter> chapters) {
        if (chapters.isEmpty()) {
            return List.of();
        }

        List<Long> novelIds = chapters.stream().map(Chapter::getNovelId).distinct().toList();
        Map<Long, String> titles = new LinkedHashMap<>();
        for (Novel novel : novelMapper.selectByIds(novelIds)) {
            titles.put(novel.getId(), novel.getTitle());
        }

        return chapters.stream()
                .map(c -> ChapterVO.of(c, titles.get(c.getNovelId())))
                .toList();
    }

    /* ==================== 供 interaction 模块调用 ==================== */

    /**
     * 批量取章节摘要。★ <b>只返回已发布的</b> —— 完整论证见
     * {@link ChapterService#briefsOf}。
     */
    @Override
    public Map<Long, ChapterBriefVO> briefsOf(Collection<Long> chapterIds) {
        List<Long> ids = chapterIds == null ? List.of()
                : chapterIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            // ★ 空集合不能拿去查库：会生成 WHERE id IN () 语法错误
            return Map.of();
        }

        // 条件写进 WHERE，理由同 NovelServiceImpl#briefsOf（可见性规则只有一处实现）
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .in(Chapter::getId, ids)
                .eq(Chapter::getStatus, ChapterStatus.PUBLISHED));

        Map<Long, ChapterBriefVO> briefs = new LinkedHashMap<>();
        for (Chapter c : chapters) {
            briefs.put(c.getId(), ChapterBriefVO.of(c));
        }
        return briefs;
    }
}
