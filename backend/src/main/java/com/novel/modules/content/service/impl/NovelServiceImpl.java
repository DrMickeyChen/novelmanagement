package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.enums.NovelStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.dto.NovelCreateDTO;
import com.novel.modules.content.dto.NovelUpdateDTO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.entity.NovelCategory;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelCategoryMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.service.ContentUserLookup;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.ChapterVO;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 作品服务实现。
 *
 * <p><b>★★ 这个类里最重要的两个方法，改任何代码前先看它们：</b>
 * <ol>
 *   <li>{@link #transitStatus} —— 作品状态的<b>唯一</b>改动入口。它是 private 的，
 *       对外只暴露 {@code submit} / {@code audit} / {@code changeStatus} 这些
 *       <b>业务意图</b>方法。<b>Controller 拿不到「把状态设成 X」的方法</b>，
 *       所以「绕开状态机」在类型层面就做不到，而绕开状态机就会漏记审核日志。</li>
 *   <li>{@link #requireOwnedNovel} —— 归属校验。它和 URL 角色闸的分工是
 *       「角色闸管门、归属校验管物」：管理员能进作者端这个门，
 *       但改不了别人的书（会得到 {@code 2002}）。</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    /** 分类的「启用」状态值 */
    private static final int CATEGORY_ENABLED = 1;

    /** 分页兜底 */
    private static final long DEFAULT_PAGE_SIZE = 10;

    private final NovelMapper novelMapper;
    private final NovelCategoryMapper categoryMapper;
    private final ChapterMapper chapterMapper;
    private final ContentUserLookup userLookup;
    private final AuditLogService auditLogService;
    private final NovelAccessGuard guard;

    /* ================================================================
     *  读者端
     * ================================================================ */

    @Override
    public PageResult<NovelVO> readerPage(Long categoryId, String keyword, Integer isFinish,
                                          String sort, long page, long size) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                /*
                 * ★★ 这一行是 C-7 约束 1 的全部实现：未上架的作品读者端查不到。
                 *
                 * 它必须写在 WHERE 里，【不能】写成「查出来再 if 过滤」——
                 * 后者在列表接口上必然漏：混进一本未上架的书不会报错、不会崩溃，
                 * 只是列表里悄悄多了一行，而这一行可能就是作者还没提交审核的草稿。
                 */
                .eq(Novel::getStatus, NovelStatus.PUBLISHED)
                .eq(categoryId != null, Novel::getCategoryId, categoryId)
                .eq(isFinish != null, Novel::getIsFinish, isFinish)
                /*
                 * 书名模糊匹配。★ 这里是 LIKE '%kw%'，走不了 idx_title 索引
                 * （前导通配符让 B+ 树用不上），数据量大了必然全表扫描。
                 * P2 数据量小可以接受，P3 上 ES 正是为了解决它 ——
                 * 大纲把「搜索」单列一期，就是这个原因。
                 */
                .like(StringUtils.hasText(keyword), Novel::getTitle, keyword);

        applyReaderSort(wrapper, sort);

        return toVOPage(novelMapper.selectPage(buildPage(page, size), wrapper));
    }

    @Override
    public NovelDetailVO readerDetail(Long id) {
        /*
         * ★ 状态和 id 一起查，查不到就是「不存在」——
         * 刻意不区分「这本书不存在」和「这本书没上架」，两者都返回 2001。
         * 区分了的话，匿名调用者可以靠错误码的差别枚举出哪些 id 是真实存在的书
         * （这个接口在白名单里，谁都能扫）。
         */
        Novel novel = novelMapper.selectOne(new LambdaQueryWrapper<Novel>()
                .eq(Novel::getId, id)
                .eq(Novel::getStatus, NovelStatus.PUBLISHED));
        if (novel == null) {
            throw new BusinessException(ResultCode.NOVEL_NOT_FOUND);
        }

        NovelDetailVO vo = NovelDetailVO.of(novel,
                categoryNameOf(novel.getCategoryId()),
                userLookup.nicknameOf(novel.getAuthorId()));

        /*
         * ★ 显式清掉驳回理由。
         * 能查到这一步说明这本书是「已上架」，而已上架的作品 audit_remark 本来是空的
         * （提交审核时被清掉了），所以这行看起来多余 —— 但「数据恰好为空」不是防线。
         * 万一将来有人在别处给已上架的作品写了驳回理由（比如「先下架、写理由、再上架」
         * 的操作顺序），读者就会看到一句内部话术。
         * 一行赋值换掉一类泄露，不亏。
         */
        vo.setAuditRemark(null);

        /*
         * 目录：★ 只含【已发布】章节，这是 C-7 约束 2 的一部分。
         * 这里必须显式写 status = PUBLISHED —— 漏了的话草稿章、待审章、
         * 被驳回的章都会出现在读者端的目录里，等于「未审核内容对外可见」。
         */
        vo.setChapters(chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, id)
                        .eq(Chapter::getStatus, ChapterStatus.PUBLISHED)
                        .orderByAsc(Chapter::getChapterNo))
                .stream()
                .map(c -> ChapterVO.of(c, novel.getTitle()))
                .toList());

        return vo;
    }

    /* ================================================================
     *  作者端
     * ================================================================ */

    @Override
    public PageResult<NovelVO> myPage(NovelStatus status, long page, long size) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                /*
                 * ★ 只能看到自己的作品。这个条件【在查询里】，不是查完再筛 ——
                 * 查完再筛的话，只要有人忘了写那个 if，作者就能看到全站的书。
                 * 而这种「忘了写」不会报错，只是列表多几行。
                 */
                .eq(Novel::getAuthorId, UserContext.requireUserId())
                .eq(status != null, Novel::getStatus, status)
                // 作者侧按「最近改动」排 —— 他关心的是自己刚改过的那本
                .orderByDesc(Novel::getUpdateTime)
                .orderByDesc(Novel::getId);

        return toVOPage(novelMapper.selectPage(buildPage(page, size), wrapper));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(NovelCreateDTO dto) {
        requireCategoryUsable(dto.getCategoryId());

        Novel novel = new Novel();
        novel.setTitle(dto.getTitle().trim());
        novel.setCover(dto.getCover());
        novel.setCategoryId(dto.getCategoryId());
        novel.setIntro(dto.getIntro());
        novel.setTags(dto.getTags());
        novel.setIsFinish(dto.getIsFinish());
        /*
         * ★ 作者 id 取当前登录态，状态固定为草稿 —— 两者都不接受入参。
         * NovelCreateDTO 上压根没有这两个字段，所以作者没法在 body 里塞
         * {"status": 2} 让书直接上架（那就等于绕过整个审核流程）。
         */
        novel.setAuthorId(UserContext.requireUserId());
        novel.setStatus(NovelStatus.DRAFT);

        /*
         * ★ 两个冗余计数显式给 0，不依赖数据库的 DEFAULT 0。
         * 依赖 DEFAULT 的前提是「MP 生成的 INSERT 里没有这两列」——
         * 而这个前提会随着实体加字段、改 FieldStrategy 悄悄失效。
         * 一旦 MP 真的把 null 写进去，NOT NULL 列会直接报错；
         * 更坏的情况是列可空，于是库里躺着 null，页面上显示空白。
         * 显式给值，代价是两行赋值。
         */
        novel.setWordCount(0);
        novel.setChapterCount(0);

        novelMapper.insert(novel);

        log.info("新建作品 | id={} title={} authorId={}", novel.getId(), novel.getTitle(), novel.getAuthorId());
        return novel.getId();
    }

    @Override
    public NovelDetailVO authorDetail(Long id) {
        Novel novel = requireOwnedNovel(id);

        NovelDetailVO vo = NovelDetailVO.of(novel,
                categoryNameOf(novel.getCategoryId()),
                userLookup.nicknameOf(novel.getAuthorId()));

        /*
         * 作者端的目录【含全部状态】—— 作者要能看到自己的草稿章、待审章、被驳回的章。
         * 这和读者端是相反的口径，所以两个方法各自写一遍，不抽公共方法：
         * 抽出来的话，那个公共方法必然要收一个「要不要过滤状态」的参数，
         * 而传错的后果是内容泄露。见 NovelService 的类注释。
         */
        vo.setChapters(chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, id)
                        .orderByAsc(Chapter::getChapterNo))
                .stream()
                .map(c -> ChapterVO.of(c, novel.getTitle()))
                .toList());

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, NovelUpdateDTO dto) {
        Novel novel = requireOwnedNovel(id);

        /*
         * ★ 状态不允许编辑时必须拒绝（NovelStatus#isEditable）。
         * 最要紧的是【待审核期间不能改】：
         *   审核员打开的是 A 版的简介 → 作者在审核期间改成 B 版 →
         *   审核员点通过 → 上架的是 B 版。
         * 审核被绕过了，而日志上看起来是一次完全正常的审核 ——
         * 这类 bug 事后查不出来，因为它留下的痕迹和正常流程一模一样。
         */
        if (!novel.getStatus().isEditable()) {
            throw new BusinessException(ResultCode.NOVEL_STATUS_ILLEGAL,
                    "「" + novel.getStatus().getDesc() + "」状态下不能修改作品信息"
                            + "（要改请先等审核结果，或把它下架）");
        }

        /*
         * ★ 分类【没变】时不校验它是否可用。
         *
         * 不这么做的话会有一个把作者锁死的坑：
         *   书原来挂在「玄幻」上 → 管理员把「玄幻」停用了 →
         *   作者想改个简介错别字 → 校验失败「分类不存在或已停用」→
         *   作者必须换一个分类才能保存，哪怕他根本不想改分类。
         *
         * 校验的目的只是「不许往一个停用的分类里塞新书」，
         * 保持原样不属于这个范畴。
         */
        if (!Objects.equals(dto.getCategoryId(), novel.getCategoryId())) {
            requireCategoryUsable(dto.getCategoryId());
        }

        novel.setTitle(dto.getTitle().trim());
        novel.setCover(dto.getCover());
        novel.setCategoryId(dto.getCategoryId());
        novel.setIntro(dto.getIntro());
        novel.setTags(dto.getTags());
        novel.setIsFinish(dto.getIsFinish());

        novelMapper.updateById(novel);

        log.info("编辑作品 | id={} title={} status={}", id, novel.getTitle(), novel.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Novel novel = requireOwnedNovel(id);

        /*
         * ★ 下有章节时拒绝删除。
         *
         * 不拒绝的话，chapter 表里会留下一批 novel_id 指向「查不到的书」的章节，
         * 而它们自己还是「已发布」状态：
         *   读者端 —— 按 novel_id 查不到书，进不来（歪打正着地安全）
         *   管理端 —— 章节列表照常列出它们，审核员看到一行没有书名的章节，
         *             不知道该审什么，也找不到它的上下文
         * 所以这不是「数据完整性洁癖」，是管理端真的会坏掉。
         *
         * ★ 用 MP 的 selectCount，@TableLogic 会自动补 deleted = 0 ——
         * 手写 SQL 会把已逻辑删除的章节也算进去，症状是
         * 「这本书明明一章都没有，却删不掉」，而界面上根本看不到那些章节。
         */
        Long chapterCount = chapterMapper.selectCount(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getNovelId, id));
        if (chapterCount != null && chapterCount > 0) {
            throw new BusinessException(ResultCode.NOVEL_HAS_CHAPTERS,
                    "《" + novel.getTitle() + "》下还有 " + chapterCount + " 个章节，"
                            + "请先删除章节；若只是想暂停对外，请把它下架");
        }

        /*
         * ★ 这里可以直接 deleteById（逻辑删除），不需要像分类/章节那样
         * 「顺手改掉唯一键」。因为 novel 表上【没有】除主键外的唯一键
         * （schema.sql 里只有 PRIMARY KEY），逻辑删除的行不占任何业务唯一键。
         * 哪天给 novel 加了 uk_xxx，这里就得跟着改 —— 见 ChapterMapper 的注释。
         */
        novelMapper.deleteById(id);

        log.info("删除作品 | id={} title={}", id, novel.getTitle());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        Novel novel = requireOwnedNovel(id);
        // 状态合不合法由状态机说了算，这里不重复判断（重复判断迟早和状态机不一致）
        transitStatus(novel, NovelStatus.PENDING, null);
    }

    /* ================================================================
     *  管理端
     * ================================================================ */

    @Override
    public PageResult<NovelVO> adminPage(NovelStatus status, Long categoryId, String keyword,
                                         long page, long size) {
        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                /*
                 * ★ 这里【没有】status = PUBLISHED 这个条件，而且不能加。
                 * 管理端要能看到全部状态 —— 最常用的筛选恰恰是 status=1（待审核），
                 * 而那个条件在这里是入参。加了 PUBLISHED 就等于「审核员看不到待审核的书」，
                 * 整个审核功能直接失效。
                 *
                 * ⚠ 这也是本类里最容易出事的复制粘贴源：
                 *   把 readerPage 复制过来改，很容易把 PUBLISHED 那句一起带过来。
                 *   而症状不是报错，是「列表里少了一些书」—— 很难往这上面想。
                 */
                .eq(status != null, Novel::getStatus, status)
                .eq(categoryId != null, Novel::getCategoryId, categoryId)
                .like(StringUtils.hasText(keyword), Novel::getTitle, keyword)
                // 按最近改动倒序 —— 刚提交审核的书一定在最前面，这正是管理员要找的
                .orderByDesc(Novel::getUpdateTime)
                .orderByDesc(Novel::getId);

        return toVOPage(novelMapper.selectPage(buildPage(page, size), wrapper));
    }

    @Override
    public NovelDetailVO adminDetail(Long id) {
        /*
         * ★ 用 requireNovel（不带归属校验）而不是 requireOwnedNovel ——
         * 管理员审的就是别人的书。给他做归属校验等于审核功能不存在。
         * 这一处是「作者端和管理端共用同一个 Service」时最容易写错的地方，
         * 所以特意让两个方法名长得像但用途分明。
         */
        Novel novel = requireNovel(id);

        NovelDetailVO vo = NovelDetailVO.of(novel,
                categoryNameOf(novel.getCategoryId()),
                userLookup.nicknameOf(novel.getAuthorId()));

        /*
         * ★ 管理端目录【含全部状态】，和作者端一致、和读者端相反。
         * 审核员必须能看到草稿章和待审章 ——
         * 只给他看已发布的，他根本不知道该审什么。
         * ★ auditRemark 在这里【保留】：审核历史是审核员做判断的依据。
         */
        vo.setChapters(chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, id)
                        .orderByAsc(Chapter::getChapterNo))
                .stream()
                .map(c -> ChapterVO.of(c, novel.getTitle()))
                .toList());

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, boolean pass, String remark) {
        Novel novel = requireNovel(id);

        /*
         * ★★ 这一句是「状态机之外还要再判一次」的典型例子，务必看懂它为什么不是多余的。
         *
         * 状态机（canTransitTo）回答的是「这一步能不能走」，它的规则是：
         *     PENDING  → PUBLISHED   审核通过
         *     PENDING  → REJECTED    审核驳回
         *     OFFLINE  → PUBLISHED   重新上架     ← ★ 问题就在这一条
         * 于是「审核通过」和「重新上架」在状态机看来是【同一个流转】。
         * 不判 PENDING 的话，管理员对着一个【已下架】的作品点「审核通过」，
         * 会走 OFFLINE → PUBLISHED 成功上架，而且审核日志里记的是 REPUBLISH（重新上架）。
         * 结果不算错，但调用方以为自己审了一本书 —— 而这本书根本没在待审队列里。
         *
         * 「重新上架」有自己的接口（{@code changeStatus}），
         * 两件事必须分开：一个接口只做一件事，日志才读得懂。
         */
        if (novel.getStatus() != NovelStatus.PENDING) {
            throw new BusinessException(ResultCode.NOVEL_STATUS_ILLEGAL,
                    "「" + novel.getStatus().getDesc() + "」的作品不能审核，只有「待审核」的可以"
                            + "（要上架请用「上架/下架」接口）");
        }

        transitStatus(novel, pass ? NovelStatus.PUBLISHED : NovelStatus.REJECTED, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, NovelStatus target, String remark) {
        /*
         * ★ 这个接口只接受 2（上架）和 3（下架）两个目标。
         *
         * DTO 上 {@code status} 是 {@code NovelStatus} 类型，所以 {@code {"status": 0}}
         * 也能反序列化成功（0 是个合法的枚举值）—— 类型系统挡不住它。
         * 不判的话，管理员可以传 0 把一本书直接打回草稿，
         * 而「打回草稿」在本项目里是【没有独立接口】的动作（它属于 P4 的撤回提交），
         * 从上下架接口混进来会让日志里出现一条莫名其妙的 OFFLINE/REJECT 记录。
         *
         * 判在这里而不是在 DTO 上加 {@code @AssertTrue}：DTO 是给 Knife4j 看的文档，
         * 真正的规则属于业务，写在这里才能和状态机放在一起读。
         */
        if (target != NovelStatus.PUBLISHED && target != NovelStatus.OFFLINE) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "上下架接口只接受 2（上架）和 3（下架），收到：" + target.getCode());
        }

        Novel novel = requireNovel(id);
        transitStatus(novel, target, remark);
    }

    /* ================================================================
     *  冗余字段重算（供 ChapterService 调用）
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncNovelStat(Long novelId) {
        LambdaQueryWrapper<Chapter> publishedOnly = new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .eq(Chapter::getStatus, ChapterStatus.PUBLISHED);

        Long count = chapterMapper.selectCount(publishedOnly);
        Integer words = chapterMapper.sumPublishedWordCount(novelId);

        // 最新一章：按 chapter_no 倒序取一条。★ 用 chapter_no 而不是 create_time ——
        // 作者可能先建第 10 章再回头补第 9 章，那时「最新」指的应该是序号最大的
        Chapter last = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .eq(Chapter::getStatus, ChapterStatus.PUBLISHED)
                .orderByDesc(Chapter::getChapterNo)
                .last("LIMIT 1"));

        /*
         * ★★ 这里必须用 UpdateWrapper 的 set(...)，【不能】用
         * 「new Novel() + setXxx + updateById」。
         *
         * 原因是 MP 的 updateById 默认只更新【非 null】字段。
         * 当一本书的章节全部被退回草稿或删光时，last 会是 null，
         * 而 setLastChapterId(null) + updateById 会【跳过这一列】——
         * 于是 novel.last_chapter_id 永远停在上一章上，
         * 读者端点「继续阅读」会跳到一个已经不存在（或未发布）的章节。
         *
         * 这种 bug 只在「把最后一章退掉」时出现，非常难想到。
         * UpdateWrapper.set() 是显式写 null，不受非空策略影响。
         */
        LambdaUpdateWrapper<Novel> update = new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .set(Novel::getChapterCount, count == null ? 0 : count.intValue())
                .set(Novel::getWordCount, words == null ? 0 : words)
                .set(Novel::getLastChapterId, last == null ? null : last.getId())
                .set(Novel::getLastUpdateTime, last == null ? null : last.getPublishTime());

        novelMapper.update(null, update);

        log.debug("重算作品冗余字段 | novelId={} chapterCount={} wordCount={} lastChapterId={}",
                novelId, count, words, last == null ? null : last.getId());
    }

    /* ================================================================
     *  ★ 状态机 —— 作品状态的唯一改动入口
     * ================================================================ */

    /**
     * 把作品流转到目标状态。★ <b>整个项目里唯一改 {@code novel.status} 的地方。</b>
     *
     * <p><b>为什么是 private：</b>对外暴露的是 {@code submit} / {@code audit} /
     * {@code changeStatus} 这些业务意图方法。Controller 拿不到
     * 「把状态设成 X」的方法，于是「绕开状态机」在类型层面就做不到。
     * 绕开状态机的后果不是崩溃，而是<b>审核日志漏记</b> ——
     * 从此「这本书被谁改过、为什么」永远查不出来。
     *
     * <p><b>★ 三个设计点：</b>
     * <ol>
     *   <li><b>错误提示里带上「从什么状态到什么状态」</b>，不是干巴巴一句
     *       「当前作品状态不允许该操作」。状态机报错最常见的原因是前端拿了个
     *       过期页面，把 from/to 打出来能一眼定位。</li>
     *   <li><b>审核日志写在这里面</b>，不是各 Controller 自己记 ——
     *       这样「想绕开日志」就只剩「绕开状态机」一条路，而状态机是唯一入口。</li>
     *   <li><b>用 switch 表达式而不是 switch 语句</b>：Java 的 switch 语句
     *       对枚举<b>不做穷尽性检查</b>，将来给 {@code NovelStatus} 加一个状态，
     *       语句会静默漏掉它。表达式必须覆盖所有分支，否则<b>编译不过</b> ——
     *       这正是我们要的：让编译器替我们记住这件事。</li>
     * </ol>
     *
     * @param target      目标状态
     * @param auditRemark 备注，驳回时是驳回理由
     */
    private void transitStatus(Novel novel, NovelStatus target, String auditRemark) {
        NovelStatus from = novel.getStatus();

        if (!from.canTransitTo(target)) {
            throw new BusinessException(ResultCode.NOVEL_STATUS_ILLEGAL,
                    "「" + from.getDesc() + "」不能直接变成「" + target.getDesc() + "」");
        }

        novel.setStatus(target);

        AuditAction action = switch (target) {
            /*
             * ★ 没有、也不该有任何流转以 DRAFT 为目标（见 NovelStatus#canTransitTo）。
             * 真走到这里说明状态机被改坏了，抛异常比静默通过安全得多。
             * 「撤回提交」这条 PENDING → DRAFT 的流转属于 P4，那时把这一支改掉即可。
             */
            case DRAFT -> throw new IllegalStateException(
                    "没有任何流转以草稿为目标，状态机实现有误。novelId=" + novel.getId());

            // 提交审核：三个来源（草稿 / 已驳回 / 已下架）都算「提交」
            // ★ 重新提交要清掉上一次的驳回理由 —— 不清的话，作者会看到
            //   「已提交待审核」旁边挂着一句上次的驳回意见，以为又被打回了
            case PENDING -> AuditAction.SUBMIT;

            /*
             * 上架有两个来源，动作不同：
             *   待审核 → 已上架   审核通过
             *   已下架 → 已上架   重新上架
             * 流水里必须分开记 —— 统计「审核通过率」时只该数前一种。
             */
            case PUBLISHED -> from == NovelStatus.OFFLINE
                    ? AuditAction.REPUBLISH
                    : AuditAction.APPROVE;

            case OFFLINE -> AuditAction.OFFLINE;

            // 驳回理由必须留痕：它是作者唯一能看到的「为什么没过」
            case REJECTED -> AuditAction.REJECT;
        };

        /*
         * ★★ 状态机只改 status 和 audit_remark，其中 audit_remark 必须走 wrapper 的 set。
         *
         * updateById 会【跳过 null 字段】，所以「把驳回理由清空」这件事它做不到 ——
         * 提交审核那一支（PENDING）的 audit_remark 是 null，靠实体根本写不进库。
         * 症状：作者改了稿重新提交，状态变成「待审核」，
         * 但旁边还挂着上一次的驳回理由，他以为又被打回了。不报错，只是信息是错的。
         *
         * 只有值为 null 时才需要 wrapper —— 非 null（驳回）的情况实体自己会写，
         * 两边都写会让 SQL 里出现两个 `audit_remark = ?`。
         */
        LambdaUpdateWrapper<Novel> update = new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novel.getId());

        if (target == NovelStatus.REJECTED) {
            // 驳回：写入理由。非 null，实体自己会把它写进 SET
            novel.setAuditRemark(auditRemark);
        } else {
            // 其余情况一律清空。★ 清空【必须】靠 wrapper，见上面那段注释
            novel.setAuditRemark(null);
            update.set(Novel::getAuditRemark, null);
        }

        novelMapper.update(novel, update);

        /*
         * ★ 顺序很重要：先 updateById 再 syncNovelStat，不能反过来。
         *
         * syncNovelStat 会自己发一条 UPDATE 去写四个冗余字段。
         * 如果先调它、再 updateById(novel)，那么 novel 这个内存对象里
         * 还是【旧的】chapterCount / wordCount，updateById 会把这些旧值
         * 原样写回库里，把刚算好的结果覆盖掉。
         * 编辑器里看不出来，只有数据会慢慢变回错的。
         */
        if (target == NovelStatus.PUBLISHED) {
            syncNovelStat(novel.getId());
        }

        auditLogService.record(AuditTargetType.NOVEL, novel.getId(), action, auditRemark);
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 取作品，不存在就抛 {@code 2001}。★ 只用于管理端路径（管理员审的就是别人的书）。
     *
     * <p>作者侧请用 {@link #requireOwnedNovel} —— 两者的差别只在归属校验上，
     * 但把这个差别写成两个方法名，调用处一眼就能看出这条路径有没有做过归属校验。
     */
    private Novel requireNovel(Long id) {
        return guard.requireExisting(id);
    }

    /**
     * 取「我的」作品：不存在 → {@code 2001}，不是我的 → {@code 2002}。
     * <b>作者侧每个写接口第一行都调它。</b>
     *
     * <p>实现委托给 {@link NovelAccessGuard} —— 那里同时服务 {@code ChapterService}，
     * 而「越权必须打一条 WARN」这件事只能有一处实现（见该类的类注释）。
     * 这里保留一个同名的私有方法，是为了让调用处的可读性不变：
     * {@code requireOwnedNovel(id)} 比 {@code guard.requireOwned(id)} 更能说明在做什么。
     */
    private Novel requireOwnedNovel(Long novelId) {
        return guard.requireOwned(novelId);
    }

    /**
     * 校验分类<b>存在且已启用</b>，否则抛 {@code CATEGORY_NOT_FOUND(6001)}。
     *
     * <p><b>★ 为什么必须校验「启用」而不只是「存在」：</b>
     * 作者可以往一个刚被停用的分类里塞书，而停用的分类<b>不出现在读者端的分类列表里</b>。
     * 结果就是一本读者端能看到、但按分类筛不到的书 ——
     * 「分类筛选里没有它，可列表里明明有」，用户只会觉得系统坏了。
     *
     * <p><b>为什么复用 6001 而不是新造一个「分类已停用」：</b>
     * 对调用方（作者）来说，这两种情况的处理动作完全一样 ——
     * 「你选的分类不可用，重新选一个」。分成两个码只会让前端多写一个分支，
     * 而那个分支的文案和 6001 是一样的。差异体现在 message 里。
     */
    private void requireCategoryUsable(Long categoryId) {
        NovelCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !Objects.equals(category.getStatus(), CATEGORY_ENABLED)) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND,
                    "分类不存在或已停用，请重新选择");
        }
    }

    /** 取单个分类名。查不到返回 {@code null}（详情页的分类名会是空白） */
    private String categoryNameOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        NovelCategory category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    /**
     * 批量取分类名，返回 {@code categoryId → name}。
     *
     * <p><b>★ 列表接口必须用这个，不要循环调 {@link #categoryNameOf}：</b>
     * 一页 20 本就是 20 次查询，典型的 N+1。
     *
     * <p>和 {@code ContentUserLookup#nicknamesOf} 是同一个套路，但<b>没有抽成公共类</b> ——
     * 分类和作品同属 {@code content} 模块、同一张库，
     * 二期拆服务时它们一起去 {@code novel-content}，不存在「跨服务调用」的接缝要留。
     * 抽出来反而是为了对称而对称。
     *
     * <p>★ 查不到的分类返回的 name 是 {@code null}（分类被逻辑删除了）。
     * 正常情况下不会发生 —— {@code CATEGORY_HAS_NOVELS} 会挡住「删掉还有作品的分类」。
     */
    private Map<Long, String> categoryNamesOf(Collection<Long> categoryIds) {
        List<Long> ids = categoryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            // ★ 空集合不能拿去查库：会生成 WHERE id IN () 语法错误
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        /*
         * ★ 用 selectByIds 而不是 selectBatchIds。
         * MP 3.5.9 里 selectBatchIds 已经是个 @Deprecated 的 default 方法
         * （内部转调 selectByIds），留着它编译只报一句「使用了已过时的 API」，
         * 不报错 —— 但它是【要被删掉】的那个，升级 MP 时会直接编译不过。
         */
        for (NovelCategory category : categoryMapper.selectByIds(ids)) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    /**
     * 分页对象 + 页码兜底。
     *
     * <p><b>★ 每页条数的上限【不在这里】管</b>，它由
     * {@code MybatisPlusConfig} 里 {@code PaginationInnerInterceptor.setMaxLimit(100)} 负责。
     * 这里只管下限，因为下限那边有个真会出事的坑：
     * <b>MP 的分页插件在 {@code size < 0} 时【不做分页】，会把整张表返回</b>——
     * 前端传个 {@code size=-1} 就能一次拖走全部作品。
     * 这不是假设，MP 的这个行为是设计如此（负数表示「不分页」）。
     */
    private Page<Novel> buildPage(long page, long size) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = size < 1 ? DEFAULT_PAGE_SIZE : size;
        return new Page<>(safePage, safeSize);
    }

    /**
     * 读者端排序。<b>白名单映射，绝不把入参拼进 SQL。</b>
     *
     * <p>MP 的 {@code orderByDesc(String column)} 接受字符串列名 ——
     * 如果直接把 {@code sort} 参数传进去，就是一个 SQL 注入点
     * （{@code orderBy} 的值没法用参数占位符，它必须是标识符）。
     * 所以这里用 switch 把入参映射成<b>写死的</b>字段引用。
     *
     * <p><b>★ 不认识的值按默认排序处理，不报错。</b>
     * 理由：排序是「展示偏好」，传个 {@code sort=hot}（P3 才有）就 400
     * 会让前端在灰度期间各种炸；而静默降级到默认排序，用户只是看到
     * 「按最后更新排」，没有任何功能不可用。
     *
     * <p><b>为什么没有 {@code hot}（按阅读量）：</b>那是大纲 §610 的
     * {@code /api/novels/hot} 榜单，P3 要用 Redis 计数重做，P2 不抢它的活。
     */
    private void applyReaderSort(LambdaQueryWrapper<Novel> wrapper, String sort) {
        String key = StringUtils.hasText(sort) ? sort.trim().toLowerCase(Locale.ROOT) : "latest";

        switch (key) {
            case "new" -> wrapper.orderByDesc(Novel::getCreateTime);
            case "words" -> wrapper.orderByDesc(Novel::getWordCount);
            // latest 是默认值，也用 default 兜住所有不认识的值
            default -> wrapper.orderByDesc(Novel::getLastUpdateTime);
        }

        /*
         * ★ 最后按 id 兜底，不能只按上面那个字段排。
         *
         * 「按最后更新排」时，一堆从没发布过章节的书 last_update_time 全是 NULL，
         * MySQL 对排序值相同的行【返回顺序不保证稳定】——
         * 同一页数据翻两次可能顺序不同，现象是前端列表「自己会跳」。
         * 而 reset 之后每次执行的都是同一条 SQL，看起来完全正常。
         * id 是主键、全局唯一，加上它就是确定的了。
         *
         * ★ 这个排序正好命中 idx_status_update(status, last_update_time)：
         *   等值 status + 排序 last_update_time，是标准的复合索引最左前缀用法。
         */
        wrapper.orderByDesc(Novel::getId);
    }

    /**
     * 把分页结果里的实体批量转成 VO。
     *
     * <p><b>★ 不能直接用 {@code PageResult.of(page, this::toVO)}：</b>
     * 那个重载的转换函数是<b>逐条</b>调用的，在它里面查作者昵称和分类名
     * 就是 N+1（一页 20 条 → 40 次查询）。这里先收集所有 id、
     * 两次批量查完，再逐条组装。
     */
    private PageResult<NovelVO> toVOPage(IPage<Novel> page) {
        List<Novel> records = page.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(List.of(), page.getTotal(), page.getCurrent(),
                    page.getSize(), page.getPages());
        }

        Map<Long, String> authorNames = userLookup.nicknamesOf(
                records.stream().map(Novel::getAuthorId).toList());
        Map<Long, String> categoryNames = categoryNamesOf(
                records.stream().map(Novel::getCategoryId).toList());

        List<NovelVO> vos = records.stream()
                .map(n -> NovelVO.of(n, categoryNames.get(n.getCategoryId()), authorNames.get(n.getAuthorId())))
                .toList();

        return new PageResult<>(vos, page.getTotal(), page.getCurrent(),
                page.getSize(), page.getPages());
    }
}
