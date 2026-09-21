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
import com.novel.modules.content.entity.OutlineNode;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.mapper.NovelCategoryMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.mapper.OutlineNodeMapper;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.service.ContentUserLookup;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.ChapterBriefVO;
import com.novel.modules.content.vo.ChapterVO;
import com.novel.modules.content.vo.NovelBriefVO;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    /** 榜单默认返回条数 */
    private static final int RANK_DEFAULT_LIMIT = 10;

    /**
     * 榜单条数上限。
     *
     * <p>★ 比 {@code MybatisPlusConfig} 里 {@code setMaxLimit(100)} 那个硬上限更小，
     * 是<b>有意</b>的：那个 100 是「防止有人一次拖走全表」的兜底，
     * 这里的 50 是「这个接口打算提供多少」的声明。榜单是首屏组件，
     * 再多也没人往下看，而每次请求都是一次全表排序 + 分配内存。
     * 两个数字职责不同，不要合并成一个。
     */
    private static final int RANK_MAX_LIMIT = 50;

    /** 榜单类型：已完结。另外两个（hot / new）是默认分支，不做常量 */
    private static final String RANK_TYPE_FINISHED = "finished";

    /** 榜单类型：最新创建 */
    private static final String RANK_TYPE_NEW = "new";

    /** 完结标记：是 */
    private static final int FINISHED_YES = 1;

    private final NovelMapper novelMapper;
    private final NovelCategoryMapper categoryMapper;
    private final ChapterMapper chapterMapper;
    private final OutlineNodeMapper outlineNodeMapper;
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
    public List<NovelVO> rank(String type, Integer limit) {
        int safeLimit = clampRankLimit(limit);

        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                // ★ 三个榜都只含已上架的作品。这一条和 readerPage 里那条一样，
                //   必须在 WHERE 里，不能查出来再过滤 —— 理由见 readerPage 的注释。
                .eq(Novel::getStatus, NovelStatus.PUBLISHED);

        String key = StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : "";

        switch (key) {
            case RANK_TYPE_NEW -> wrapper.orderByDesc(Novel::getCreateTime);
            case RANK_TYPE_FINISHED -> wrapper
                    .eq(Novel::getIsFinish, FINISHED_YES)
                    .orderByDesc(Novel::getViewCount);
            // hot 是默认值，default 顺带兜住所有不认识的值（不报错）
            default -> wrapper.orderByDesc(Novel::getViewCount);
        }

        /*
         * ★ 末尾按 id 兜底，理由和 applyReaderSort 里那段完全一样：
         * 种子数据里 view_count 大量并列（1280/960/410…），
         * 「新书」榜的 create_time 更是可能撞在同一秒。
         * MySQL 对排序值相同的行【返回顺序不保证稳定】，
         * 同一页翻两次可能给出不同顺序，现象是前端榜单「自己会跳」。
         *
         * ★ 刻意复用 applyReaderSort 的结论但【不复用那个方法】：
         * 它收的是 sort 的取值（latest/new/words），和榜单调的是两套词汇。
         * 硬凑成一个方法就要在里面判「现在是谁在调我」——
         * 那是把两个独立的口径绑死在一起，以后改榜单会动到列表页。
         */

        /*
         * ★ 用 buildPage(1, safeLimit) + selectPage，【不是】.last("LIMIT " + limit)。
         * 后者是往 SQL 里拼字符串，limit 一旦来自请求参数就是一个注入口；
         * 而且它绕过了 PaginationInnerInterceptor，setMaxLimit 那道兜底也一起失效。
         * 这里的 safeLimit 已经夹过一遍，分页插件还会再兜一次（双保险）。
         */
        Page<Novel> page = novelMapper.selectPage(buildPage(1, safeLimit), wrapper);
        return toVOList(page.getRecords());
    }

    /**
     * 把榜单条数夹到 {@code [1, RANK_MAX_LIMIT]}，{@code null} 用默认值。
     *
     * <p>★ 三个分支都是「显式给一个安全值」，没有一个分支会走到「按调用方说的办」。
     * 这类方法最容易写出的毛病是 {@code limit == null} 时忘了兜底，
     * 结果 {@code buildPage(1, null)} 在拆箱时抛 NPE ——
     * 而 NPE 发生在 SQL 执行前，日志里只有一句 NullPointerException，
     * 看不出是哪个参数。所以这里先兜成默认值再比较。
     */
    private int clampRankLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return RANK_DEFAULT_LIMIT;
        }
        return Math.min(limit, RANK_MAX_LIMIT);
    }

    @Override
    public PageResult<NovelVO> search(String keyword, long page, long size) {
        /*
         * ★★ 空白关键词必须在【进 wrapper 之前】短路，不能靠 .like(condition, ...)
         * 那个重载的布尔开关：
         *
         * ① 开关只关掉 title 那一半，作者那一半还会照跑 —— 而空关键词会匹配
         *    【所有用户】，userIdsByNickname 一看没文本就返回空列表，
         *    于是下面那个 .in() 拿到的是空集合，生成 IN () —— 直接是 SQL 语法错误，
         *    报 500。这正是 ContentUserLookup.nicknamesOf 注释里记着的老坑。
         * ② 就算作者那半也关掉，剩下 WHERE status = 2 就是「列出全部已上架作品」，
         *    语义上等于把搜索页变成了第二个列表页 —— 搜索框里什么都没输，
         *    却返回了一整页书，用户会以为搜索坏了。
         *
         * ★ 返回 PageResult.empty() 而不是抛 400：调用方是首页顶部的搜索框，
         *   用户按了回车但没输内容是很正常的操作，弹一个错误提示很别扭。
         *   空结果页面上显示「没有找到相关作品」就够了。
         */
        if (!StringUtils.hasText(keyword)) {
            return PageResult.empty();
        }

        String kw = keyword.trim();
        /*
         * 先把作者名换成 id 列表 —— 这是「搜作者名」必须付的代价：
         * novel 表里只有 author_id，没有作者名。
         * ★ 拿不到（没人叫这个名字）时是空列表，下面 .or() 的布尔开关会
         *   把整个作者分支去掉，而不是生成 IN ()。
         */
        List<Long> authorIds = userLookup.userIdsByNickname(kw);

        LambdaQueryWrapper<Novel> wrapper = new LambdaQueryWrapper<Novel>()
                /*
                 * ★★ 这一行是 C-7 约束 1，和 readerPage / rank 里那条同源。
                 * 它【必须在括号外面】—— 详见下面 .and() 那段注释，
                 * 移进括号里就等于没写。
                 */
                .eq(Novel::getStatus, NovelStatus.PUBLISHED)
                /*
                 * ★★ 整个方法唯一的实现难点：这一对括号。
                 *
                 * 平铺写成 .like(Novel::getTitle, kw).or().in(Novel::getAuthorId, authorIds)
                 * 生成的是：
                 *     WHERE status = 2 AND title LIKE ? OR author_id IN (...)
                 * 而 SQL 里 AND 的优先级【高于】OR，它等价于：
                 *     WHERE (status = 2 AND title LIKE ?) OR (author_id IN (...))
                 *
                 * 后果：只要作者名匹配上，那本书是不是已上架【根本不参与判断】——
                 * 作者还没提交审核的草稿会被搜出来。这是真实的内容泄露，
                 * 而且不报错、不崩溃、不抛异常，只是结果里多了几行，
                 * 靠「跑一遍看看」是发现不了的（要正好搜到一本未上架的书）。
                 *
                 * .and(w -> ...) 的作用就是显式加这对括号。
                 *
                 * ★ .or(condition, consumer) 那个重载的布尔开关是为了作者列表为空时
                 *   去掉整个作者分支。不关的话生成的是 `title LIKE ? OR `（尾巴悬空），
                 *   同样（更明显地）是语法错误。
                 */
                .and(w -> w.like(Novel::getTitle, kw)
                        .or(!authorIds.isEmpty(), x -> x.in(Novel::getAuthorId, authorIds)));

        /*
         * ★ 排序用 view_count 降序 —— 这不是「相关性排序」，是【在没有相关性可言时
         * 挑一个用户能接受的顺序】。LIKE '%kw%' 只能回答「命中 / 不命中」，
         * 回答不了「谁更相关」（书名完全等于关键词的和只在简介里提了一句的，
         * 在这个查询里完全一样）。热度至少让「搜出来的第一本是本有名的书」。
         *
         * ★ 二期 M4 上 ES 之后，这里会换成真正的相关性打分（书名命中 > 作者名命中、
         *   完全匹配 > 前缀匹配），那时这两行注释就该删掉。
         * ★ 末尾 id 兜底，理由同 rank / applyReaderSort：view_count 并列时
         *   MySQL 不保证顺序稳定，翻页会看到重复或漏掉的书。
         */
        wrapper.orderByDesc(Novel::getViewCount).orderByDesc(Novel::getId);

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
        vo.setFirstChapterId(firstChapterIdOf(id));

        return vo;
    }

    @Override
    public PageResult<ChapterVO> chapterPage(Long novelId, long page, long size) {
        Novel novel = requirePublished(novelId);

        /*
         * ★ 只含【已发布】章节，这是 C-7 约束 2 的一部分。
         * 必须显式写 status = PUBLISHED —— 漏了的话草稿章、待审章、
         * 被驳回的章都会出现在读者端的目录里，等于「未审核内容对外可见」。
         *
         * ★ 不用再补 id 兜底排序：chapter 表上有 uk_novel_chapter_no
         * （novel_id, chapter_no），chapterNo 在书内唯一，这个排序已经是全序。
         */
        IPage<Chapter> result = chapterMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, novelId)
                        .eq(Chapter::getStatus, ChapterStatus.PUBLISHED)
                        .orderByAsc(Chapter::getChapterNo));

        return PageResult.of(result, c -> ChapterVO.of(c, novel.getTitle()));
    }

    @Override
    public List<ChapterBriefVO> catalog(Long novelId) {
        requirePublished(novelId);
        return chaptersOf(novelId).stream().map(ChapterBriefVO::of).toList();
    }

    @Override
    public List<ChapterVO> adminCatalog(Long novelId) {
        Novel novel = requireNovel(novelId);

        /*
         * ★ 管理端目录【含全部状态】，和作者端一致、和读者端相反 ——
         * 审核员必须能看到草稿章和待审章，只给他看已发布的，他根本不知道该审什么。
         *
         * ★ 用 requireNovel（不带归属校验）而不是 requireOwnedNovel：
         * 管理员审的就是别人的书，加归属校验等于审核功能不存在。
         *
         * ★ 不分页：这一屏回答的是「这本书整体审得怎么样了」——
         * 章节全是草稿的书，上架了也等于没上架，翻页看反而看不出全貌。
         * 行为与 P6 之前 NovelDetailVO.chapters 逐字一致（那份也含全部状态、不分页）。
         */
        return chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, novelId)
                        .orderByAsc(Chapter::getChapterNo))
                .stream()
                .map(c -> ChapterVO.of(c, novel.getTitle()))
                .toList();
    }

    /**
     * 这本书<b>已发布</b>章节的有序列表，按 {@code chapterNo} 正序。
     *
     * <p><b>★★ 「已发布 + 按 chapterNo 正序」这个谓词在本类里有三处</b>：
     * 这里、{@link #chapterPage}、{@link #firstChapterIdOf}。
     * 三处一旦分叉，症状是<b>「目录里少了几章」</b>或
     * <b>「点开始阅读跳到的不是第一章」</b>—— 都不会报错，而其中两个接口还是匿名的。
     *
     * <p>★ 后两处<b>不能</b>复用本方法：它们要的是「一条」和「一页」，
     * 而本方法会把整本目录读进内存 —— 那正是 P6 拆目录要避免的开销。
     * 所以重复的是谓词，不是查询。
     */
    private List<Chapter> chaptersOf(Long novelId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getNovelId, novelId)
                .eq(Chapter::getStatus, ChapterStatus.PUBLISHED)
                .orderByAsc(Chapter::getChapterNo));
    }

    /**
     * 第一章的 id（按 {@code chapterNo} 最小的那个已发布章节），没有则 {@code null}。
     *
     * <p><b>★ 干什么用：</b>详情页的「开始阅读」按钮。<b>P6 之前它取的是
     * 目录数组的第一个元素</b> —— 目录改成分页之后，第一页不一定拿到第一章
     * （书的章节顺序和分页没必然关系，改一下 size 就可能变），
     * 所以这个「第一章是谁」必须由后端直接回答。
     *
     * <p>★ <b>用 {@code buildPage(1, 1)} 而不是 {@code .last("LIMIT 1")}</b> ——
     * 同 {@link #rank} 里那段说明：{@code last()} 是拼接原生 SQL 的口子，
     * 开了这个头，下一个人就会往里拼变量。
     *
     * <p>★ 和 {@code novel.lastChapterId}（最新已发布章节）不是一回事，
     * 别拿那个来顶替：一个是最新、一个是最早，症状是「开始阅读」直接跳到结尾。
     */
    private Long firstChapterIdOf(Long novelId) {
        IPage<Chapter> first = chapterMapper.selectPage(buildPage(1, 1),
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getNovelId, novelId)
                        .eq(Chapter::getStatus, ChapterStatus.PUBLISHED)
                        .orderByAsc(Chapter::getChapterNo));
        List<Chapter> records = first.getRecords();
        return records.isEmpty() ? null : records.get(0).getId();
    }

    @Override
    public Novel requirePublished(Long id) {
        /*
         * ★★ 和上面 readerDetail 的第一段查询【完全一样】，包括那个
         * 「不区分不存在与未上架」的取舍。
         *
         * ★ 刻意重复、不抽成一个私有方法：抽出来就变成一个
         * 「返回 null 还是抛异常、抛哪个码」的共享方法，而两个调用方在这些
         * 细节上一致是【巧合】—— readerDetail 要接着拼 VO，这里只要一个校验。
         * 复制三行换来「改一个不会影响另一个」，在这个位置是划算的
         * （对照 NovelService 里 readerPage / adminPage 刻意都写全、
         * 不抽公共方法的理由：抽出来必然要收一个布尔参数，
         * 而那个参数传错的后果是内容泄露）。
         */
        Novel novel = novelMapper.selectOne(new LambdaQueryWrapper<Novel>()
                .eq(Novel::getId, id)
                .eq(Novel::getStatus, NovelStatus.PUBLISHED));
        if (novel == null) {
            throw new BusinessException(ResultCode.NOVEL_NOT_FOUND);
        }
        return novel;
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
         * ★ P6：目录不再跟着详情返回（NovelDetailVO 去掉了 chapters）。
         *   作者端的章节目录在 /api/author/novels/{id}/chapters 那个列表接口里，
         *   含全部状态 —— 作者要能看到自己的草稿章、待审章、被驳回的章。
         *   作者端的「开始阅读」也用不上 firstChapterId（那是读者端的按钮），
         *   所以这里不填。
         */
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
        /*
         * ★★ 顺手把这本书的大纲节点也逻辑删掉。
         *
         *   novel_outline 是 P4 加的表，而删除作品这条路径是 P2 写的 ——
         *   于是它一开始不知道有这张表，留下的节点【永远不会被清理】。
         *   负向用例跑完清理临时作品时实测到：删完书，库里还有 11 行
         *   novel_id 指向那本已删作品的大纲节点。
         *
         *   ★ 为什么这不会「看不见就算了」：
         *     孤儿节点本身确实查不出来（取大纲要过 requireOwned(novelId)），
         *     但它是【只增不减】的 —— 每删一本书留一批，谁也发现不了。
         *     数据洁癖在这里不是洁癖：它是一条不可能被观测到的泄漏，
         *     而修复成本是一行。
         *
         *   ★ 用 MP 的 delete(wrapper) 而不是手写 SQL：@TableLogic 会自动
         *     翻译成 UPDATE ... SET deleted = 1，和别处一致。
         *     （OutlineNodeMapper 是空接口，正是为了走这些生成的方法。）
         *
         *   ★ 顺序：先删大纲、后删作品。反过来的话，中途失败会留下
         *     「作品没了、大纲还在」的脏数据；而先删大纲再删作品，
         *     最坏是「大纲标了删除、作品还在」—— 那个状态用户看得见，能重试。
         *     ★ 整个方法在同一个事务里，其实两种顺序都会一起回滚；
         *       但保持一致的习惯比依赖回滚更可靠。
         */
        outlineNodeMapper.delete(new LambdaQueryWrapper<OutlineNode>()
                .eq(OutlineNode::getNovelId, id));

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        Novel novel = requireOwnedNovel(id);
        /*
         * ★ 和 submit 一样，这里【不】显式判断「当前必须是 PENDING」——
         * 状态机（NovelStatus#canTransitTo）已经限死了只有 PENDING → DRAFT 这一条，
         * 重复判断迟早会和状态机不一致，而且不一致时以哪边为准没有答案。
         * 非法状态会由 transitStatus 抛出 2003。
         *
         * ★ 撤回的意义：作者提交后发现简介有错别字，原本只能等管理员驳回，
         * 现在可以自己把书从审核队列里拿回来改。这不绕过审核 ——
         * 撤回后状态变回 DRAFT，改完还要重新提交、重新审。
         * 真正会绕过审核的是「让 PENDING 也能编辑」，那个仍然被
         * NovelStatus#isEditable 挡着。
         */
        transitStatus(novel, NovelStatus.DRAFT, null);
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
         * ★ auditRemark 在这里【保留】：审核历史是审核员做判断的依据。
         * ★ P6：目录不再跟着详情返回，和作者端一样。
         *   管理端的章节目录在 /api/admin/novels/{id}/chapters，含全部状态
         *   （审核员必须看到草稿章和待审章，只给已发布的他就不知道该审什么）。
         */
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
             * ★ P4 补的：作者撤回提交（PENDING → DRAFT）。
             *
             * 这里是状态机里【唯一】以 DRAFT 为目标的流转，来源也只有
             * PENDING 一个（见 NovelStatus#canTransitTo）——
             * 从 PUBLISHED 是退不回草稿的，那要走「下架」（OFFLINE）再改。
             *
             * ★ 复用 REVERT 而不是新加一个 AuditAction.WITHDRAW：
             * REVERT 的语义是「从审核流程里退回来」，章节那边
             * （已发布改正文自动退回草稿）用的也是它。
             * 两者在流水上要表达的是同一件事。真需要区分时
             * audit_remark 里能看出差别，而加一个枚举值会让
             * 「哪些动作算退回」这件事多出一份需要同步的名单。
             *
             * ★ 这里【不调】syncNovelStat：撤回前是 PENDING、撤回后是 DRAFT，
             * 两个状态都不计入 chapter_count（口径是"已发布章节数"），
             * 统计值压根没变。只有 PUBLISHED 进出才需要重算。
             */
            case DRAFT -> AuditAction.REVERT;

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
     *
     * <p><b>★ 为什么是泛型（本项目其它 Service 的 {@code buildPage} 都是具体类型）：</b>
     * 本类从 P6 起也分页查 {@code chapter}（目录接口 {@link #chapterPage}），
     * 而章节不是 {@code Novel} 实体 —— 写死 {@code Page<Novel>} 就编译不过。
     * <b>另开一个 {@code buildChapterPage} 是错的</b>：兜底规则和上面这段论证
     * 会在同一个类里出现两份，改一份漏一份的症状是「某个接口能拖走整张表」。
     * 泛型不改任何行为，{@code Page} 的两个参数本来就与实体无关。
     */
    private <T> Page<T> buildPage(long page, long size) {
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
        return new PageResult<>(toVOList(page.getRecords()), page.getTotal(),
                page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 实体列表 → VO 列表，<b>顺带批量补齐分类名和作者昵称</b>。
     *
     * <p>★ 两个 {@code xxxNamesOf} 都是<b>按 id 集合批量查</b>（一次 IN 查询），
     * 不是每条记录查一次 —— 后者就是经典的 N+1：一页 10 条会变成 20 次查询，
     * 而且因为它「结果完全正确」，在功能测试里永远发现不了。
     *
     * <p>★ 抽成独立方法是给 {@link #rank} 用的：榜单不分页、拿不到
     * {@code IPage}，但也需要同样的补名逻辑。抽的是「记录 → VO」这一段，
     * 分页包装留在 {@link #toVOPage} 里。
     */
    private List<NovelVO> toVOList(List<Novel> records) {
        if (records.isEmpty()) {
            return List.of();
        }

        Map<Long, String> authorNames = userLookup.nicknamesOf(
                records.stream().map(Novel::getAuthorId).toList());
        Map<Long, String> categoryNames = categoryNamesOf(
                records.stream().map(Novel::getCategoryId).toList());

        return records.stream()
                .map(n -> NovelVO.of(n, categoryNames.get(n.getCategoryId()), authorNames.get(n.getAuthorId())))
                .toList();
    }

    /* ==================== 供 interaction 模块调用 ==================== */

    /**
     * 批量取作品摘要。★ <b>只返回已上架的</b> —— 完整论证见
     * {@link NovelService#briefsOf}。
     */
    @Override
    public Map<Long, NovelBriefVO> briefsOf(Collection<Long> novelIds) {
        List<Long> ids = novelIds == null ? List.of()
                : novelIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            // ★ 空集合不能拿去查库：会生成 WHERE id IN () 语法错误
            return Map.of();
        }

        /*
         * ★ 这里用 selectList + LambdaQueryWrapper，不是 selectByIds。
         * selectByIds 只按主键取，加不上 status 条件 —— 那就得查回来再在内存里
         * 过滤掉未上架的，而「查出来再过滤」正是可见性规则出现第二处实现的开端。
         * 条件写进 WHERE 才是唯一一处。
         */
        List<Novel> novels = novelMapper.selectList(new LambdaQueryWrapper<Novel>()
                .in(Novel::getId, ids)
                .eq(Novel::getStatus, NovelStatus.PUBLISHED));
        if (novels.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> authorNames = userLookup.nicknamesOf(
                novels.stream().map(Novel::getAuthorId).toList());
        Map<Long, String> categoryNames = categoryNamesOf(
                novels.stream().map(Novel::getCategoryId).toList());

        Map<Long, NovelBriefVO> briefs = new LinkedHashMap<>();
        for (Novel n : novels) {
            briefs.put(n.getId(), NovelBriefVO.of(n, categoryNames.get(n.getCategoryId()),
                    authorNames.get(n.getAuthorId())));
        }
        return briefs;
    }

    /**
     * 写回收藏数。★ 绝对值覆盖 —— 完整论证见
     * {@link NovelService#refreshCollectCount}。
     */
    @Override
    public void refreshCollectCount(Long novelId, long count) {
        /*
         * ★ 用 LambdaUpdateWrapper.set() 而不是「把值塞进实体再 updateById」：
         * updateById 只更新非 null 字段，而这里要写的值恰好可能是 0
         * （所有读者都移出了书架）—— MP 对实体上的 0 是照写的，但
         * 一旦有人把 setter 改成 Integer 并传了 null，收藏数就永远停在旧值。
         * set() 是「明确写这一列」，没有「值看起来像空所以跳过」的歧义。
         * 同 syncNovelStat 里 setLastChapterId 那段。
         *
         * ★ 不填 update_time：collect_count 是冗余统计列，
         * 「有人收藏了」不是「作品更新了」。作品的更新时间由
         * chapter 侧走 syncNovelStat，别在这里插一脚。
         */
        novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                // collect_count 是 INT；夹一下而不是直接强转，避免溢出后变成负数
                .set(Novel::getCollectCount, (int) Math.min(count, Integer.MAX_VALUE)));
    }

    /**
     * 写回评分。★ 绝对值覆盖 —— 完整论证见
     * {@link NovelService#refreshScore}。
     */
    @Override
    public void refreshScore(Long novelId, BigDecimal score) {
        novelMapper.update(null, new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .set(Novel::getScore, score));
    }
}
