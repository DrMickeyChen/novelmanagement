package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.modules.system.dto.NoticeSaveDTO;
import com.novel.modules.system.entity.SysNotice;
import com.novel.modules.system.mapper.SysNoticeMapper;
import com.novel.modules.system.service.NoticeService;
import com.novel.modules.system.vo.NoticeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告服务实现。
 *
 * <p><b>★ 这个类是「数据库列之间的一致性靠代码维持」的一个例子。</b>
 * {@code sys_notice} 没有约束能保证「{@code status = 1} 时 {@code publish_time}
 * 一定非空」—— MySQL 的 {@code CHECK} 在 8.0 之前是静默忽略的，
 * 建表语句里也没有触发器。所以这条不变式<b>只存在于
 * {@link #resolvePublishTime} 这一个方法里</b>。
 * <p>推论：<b>任何绕过本类直接写 {@code sys_notice} 的代码都会破坏它</b>，
 * 而破坏的症状（读者端某条已发布的公告沉在最后）离肇事现场非常远。
 * 所以本表的所有写路径都必须从这里过，Mapper 上一条手写 SQL 都没有
 * （见 {@code SysNoticeMapper}）。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {

    /** 已发布。★ 写成常量而不是散落的 1 —— 这个判断在读者端查询、发布、下线三处都要用 */
    private static final int STATUS_PUBLISHED = 1;

    /**
     * 读者端一次最多返回几条。
     *
     * <p>★ 和 {@code DashboardServiceImpl} 给 {@code days} 夹上限是同一类问题：
     * 接口暴露了一个数量参数，就得自己兜住上界，否则
     * {@code ?limit=100000} 会让 MySQL 把整张表扫出来、再在内存里组装十万个 VO。
     * 一条请求就能把 CPU 占满，而它看起来只是一次「参数填错了」。
     * <p>20 条对一条首页横幅已经远远够了（读者往下滚过 20 条公告的概率是零）。
     */
    private static final int READER_MAX_LIMIT = 20;

    /** 读者端默认返回几条。★ 首页横幅的位置只放得下两三条，5 是留了余量的 */
    private static final int READER_DEFAULT_LIMIT = 5;

    private final SysNoticeMapper noticeMapper;

    /* ================================================================
     *  管理端
     * ================================================================ */

    @Override
    public PageResult<NoticeVO> adminPage(Integer status, String keyword, long page, long size) {
        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<SysNotice>()
                .eq(status != null, SysNotice::getStatus, status);

        if (StringUtils.hasText(keyword)) {
            /*
             * ★ 只搜标题，不搜正文。
             *
             * 正文是 TEXT 列，`LIKE '%kw%'` 对它是全表扫描并且要逐行读大字段
             * （InnoDB 的 TEXT 可能溢出到外部页，每行一次额外 IO）。
             * 而公告的数量级是个位数到几十条 —— 管理员找一条公告从来是记标题的
             * （「维护通知」），没人会记得正文里写过哪句话。
             * ★ 这和 AuthorApplyServiceImpl 搜笔名/昵称不同：那里搜的是
             *   「人」，而人必须能被多种线索定位（用户名、昵称、笔名），
             *   漏掉一种就找不到。公告只有标题这一个名字。
             */
            wrapper.like(SysNotice::getTitle, keyword.trim());
        }

        /*
         * ★★ 先按状态升序（0 下线/草稿 → 1 已发布），再按 id 倒序。
         *
         * 这是一个【待办队列】，不是档案页 —— 和作者申请列表同一条理由
         * （见 AuthorApplyServiceImpl#adminPage 那段注释）：
         * 管理员打开这一页 99% 是为了「那条还没发出去的公告」，
         * 而它们就是 status = 0 的那些。按 id 倒序排的话，
         * 草稿会被夹在一堆已发布的中间，每来一条新的就要往下翻一次。
         *
         * ★ 同状态内按 id 倒序 = 最新建的在最前（id 自增）。
         *   不用 create_time 排：同一秒建的两条顺序不稳定，翻页会「自己跳」。
         * ★ 按状态筛选时这一句是无害的（该列恒等），不用为它加 if。
         */
        wrapper.orderByAsc(SysNotice::getStatus)
                .orderByDesc(SysNotice::getId);

        Page<SysNotice> result = noticeMapper.selectPage(buildPage(page, size), wrapper);

        /*
         * ★ 用 PageResult.of(page, 转换函数)，不手写 new PageResult<>(...)。
         *   后者要按 (records, total, current, size, pages) 的【位置】传五个参数，
         *   其中 total / current / size / pages 全是 Long —— 传错顺序编译器
         *   一句话都不会说，只会在前端分页器上表现为「总共 10 条、共 1 页」。
         *   项目里两种写法都有，这里跟 CommentServiceImpl 用 of()。
         *
         * ★ 这里没有 toVOList —— 公告的每一列都在自己这一行里，
         *   没有像作者申请那样要批量补齐的关联字段，所以不存在 N+1。
         *   （对照 AuthorApplyServiceImpl#toVOList：那里一次 IN 查完申请人，
         *    逐条查就是经典的 N+1，而且结果完全正确、功能测试永远发现不了。）
         */
        return PageResult.of(result, NoticeVO::forAdmin);
    }

    @Override
    public Long create(NoticeSaveDTO dto) {
        SysNotice entity = new SysNotice();
        entity.setTitle(dto.getTitle().trim());
        entity.setContent(dto.getContent().trim());
        entity.setType(dto.getType());
        entity.setStatus(dto.getStatus());

        /*
         * ★ 新建时的 publish_time 只看这一次的 status：
         *   直接发布 → 盖时间；存草稿 → 保持 null（MP 的 insert 会跳过 null 字段，
         *   那一列在 SQL 里根本不出现，于是落库就是建表时的 DEFAULT NULL）。
         *
         * ★ 这里【不】复用 resolvePublishTime —— 那个方法的入参是
         *   「改之前的状态」，而新建时没有「之前」，硬套会需要一个
         *   「假装之前是下线」的哨兵值，那是把简单的事绕复杂。
         *
         * ★★ 「DTO 里 status 是 null」这条路已经被 @NotNull 堵死了
         *   （见 NoticeSaveDTO 的类注释）：不传时是字段默认值 1，
         *   显式传 null 直接 400。所以这里 isPublished(dto.getStatus())
         *   的 false 分支【只可能】是「真的传了 0」——
         *   不会出现「status 由 DB 默认值变成 1、而时间戳没盖」那个坏状态。
         */
        if (isPublished(dto.getStatus())) {
            entity.setPublishTime(LocalDateTime.now());
        }

        noticeMapper.insert(entity);
        log.info("新增公告 | id={} title={} type={} status={} publishTime={}",
                entity.getId(), entity.getTitle(), entity.getType(),
                entity.getStatus(), entity.getPublishTime());
        return entity.getId();
    }

    @Override
    public void update(Long id, NoticeSaveDTO dto) {
        SysNotice exist = requireNotice(id);

        LocalDateTime publishTime = resolvePublishTime(exist, dto.getStatus());

        /*
         * ★ 用一个【只带要改的字段】的新对象 updateById，而不是
         *   「查出来的 exist 改几个字段再 updateById(exist)」——
         *   理由和 CategoryServiceImpl#update 一模一样：
         *   exist 带着全表所有字段，将来谁在上面多 set 一个字段，
         *   这个 UPDATE 就顺手把它写回库了，而改动的地方在几行之外。
         *
         * ★ publishTime 为 null 时【不 set】，于是 MP 的 updateById
         *   会跳过这一列 —— 这正是「不需要改动发布时间」时的正确行为
         *   （见 resolvePublishTime 的 else 分支）。
         *   若这里无条件 setPublishTime(exist.getPublishTime())，
         *   草稿（两列都是 null）也能工作，但下行那句「null = 跳过」
         *   就变成了一个必须靠 MP 的内部行为才能成立的东西。
         */
        SysNotice update = new SysNotice();
        update.setId(id);
        update.setTitle(dto.getTitle().trim());
        update.setContent(dto.getContent().trim());
        update.setType(dto.getType());
        update.setStatus(dto.getStatus());
        update.setPublishTime(publishTime);

        noticeMapper.updateById(update);

        log.info("编辑公告 | id={} title={} status={} → {} publishTime={} (原 {})",
                id, update.getTitle(), exist.getStatus(), dto.getStatus(),
                publishTime == null ? "不变" : publishTime, exist.getPublishTime());
    }

    @Override
    public void delete(Long id) {
        SysNotice exist = requireNotice(id);

        /*
         * ★ deleteById 在挂了 @TableLogic 的实体上执行的是
         *   UPDATE sys_notice SET deleted = 1 WHERE id = ? AND deleted = 0，
         *   不是物理 DELETE。公告【有】deleted 列，所以这条路走得通 ——
         *   对照 sys_role_menu（没有这一列，赋权只能物理删，见 D-4）。
         *   两张表的规则相反，看的是 schema 里有没有那一列。
         */
        int rows = noticeMapper.deleteById(id);
        if (rows == 0) {
            /*
             * ★★ 这个分支【只在真正的并发窗口里】才会走到，别把它当成
             *    「重复删除」的处理 —— 上面那行 requireNotice 已经把
             *    「公告不存在 / 已被删过」的情况拦成 7001 了。
             *
             *   能走到这里只有一种时序：requireNotice 读到了这一行，
             *   在它和 deleteById 之间（几毫秒）另一个请求把它删掉了。
             *   此时响应「删除成功」是对的 —— 用户想要的结果达成了，
             *   而弹 7001 会让他以为是自己点错了。
             *
             * ★ 这里【不抛异常】是刻意的，但「重复点删除」拿到的仍然是
             *   7001「公告不存在」：那是一条【如实】的提示 ——
             *   你看到的列表是旧的，这条公告已经不在了。
             *   两种处理不矛盾，它们回答的是两个不同的问题：
             *     rows == 0    → 「我这次删，删到了吗」
             *     requireNotice → 「你要删的那个东西还在吗」
             *
             * ★ 对照 CategoryServiceImpl#delete：那边结构完全一样
             *   （先 requireCategory 再判断 affected == 0），但它的注释
             *   读起来像是「重复删除也会返回成功」—— 实际上做不到，
             *   requireCategory 会先抛 6001。两处的真实行为是一致的，
             *   只是那边的话说得比代码满。
             *
             * ★ 留一条 warn：这是并发事件，出现了就该被人看到。
             */
            log.warn("删除公告时已被他人删除（并发） | id={} title={}", id, exist.getTitle());
            return;
        }

        log.info("删除公告 | id={} title={} status={}", id, exist.getTitle(), exist.getStatus());
    }

    /* ================================================================
     *  读者端
     * ================================================================ */

    @Override
    public List<NoticeVO> listPublished(int limit) {
        int safeLimit = limit < 1
                ? READER_DEFAULT_LIMIT
                : Math.min(limit, READER_MAX_LIMIT);

        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<SysNotice>()
                /*
                 * ★★ status = 1 写死在查询里，不是查出来再 filter。
                 *   查出来再滤的话，未发布的公告已经在内存里了 ——
                 *   任何一次「顺手把它加进返回列表」的改动都是泄露，
                 *   而 WHERE 里的条件没有这个口子。
                 *   （同 CategoryService#listEnabled 的注释：过滤在 WHERE 里，不是查出来再 if。）
                 *
                 * ★ deleted = 0 由 @TableLogic 自动补 —— 这就是本 Mapper
                 *   坚持不写手写 SQL 的收益。漏了它的症状是
                 *   「删掉的公告还挂在读者端首页上」。
                 */
                .eq(SysNotice::getStatus, STATUS_PUBLISHED)
                /*
                 * ★★ orderByDesc(publishTime) 之后【必须】再按 id 兜底。
                 *
                 * publish_time 上没有唯一约束（连索引都不是唯一的，
                 * idx_status_time 是普通索引）。运营在几秒内连发两条公告时，
                 * 两条的时间戳可能落在同一秒（DATETIME 不带小数秒）——
                 * MySQL 对排序值相同的行返回顺序【不保证稳定】，
                 * 同一份数据刷新两次可能顺序不同，横幅上的两条公告会自己换位置。
                 * 加 id 兜底之后顺序就是确定的（id 是主键）。
                 * 完全同款的理由见 CategoryServiceImpl#listByStatus 里那段注释。
                 */
                .orderByDesc(SysNotice::getPublishTime)
                .orderByDesc(SysNotice::getId)
                /*
                 * ★ .last() 里的 safeLimit 是一个【已经算好的 int】，
                 *   不是来自请求的字符串 —— 上面的夹取保证了它落在 1~20。
                 *   不存在拼接注入（同 ContentUserLookup#userIdsByNickname 的注释）。
                 */
                .last("LIMIT " + safeLimit);

        List<SysNotice> records = noticeMapper.selectList(wrapper);

        log.debug("读者端取公告 | limit={} → 实际 {} 条", limit, records.size());

        /*
         * ★ 走 forReader，不是 forAdmin —— 后者会带出 status / createTime / updateTime。
         *   这里 status 恒为 1（WHERE 里写死了），返回它只会让前端写出永远为真的分支。
         */
        return records.stream().map(NoticeVO::forReader).toList();
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 决定这次写操作之后 {@code publish_time} 该是什么值。
     *
     * <p><b>★★ 这个方法是「{@code status = 1} 当且仅当 {@code publishTime} 非空」
     * 这条不变式的唯一实现</b>（见类注释）。五条规则，每条都对应一个具体的坏结果：
     * <pre>
     *   草稿 → 发布     盖【当前】时间。
     *                   不盖的话 publish_time 是 NULL，而读者端按它倒序 ——
     *                   MySQL 里 NULL 在 DESC 时排在【最后】，这条刚发的公告
     *                   会沉到列表底部，前端翻遍接口也找不出原因。
     *
     *   发布 → 下线     返回 null（= 不修改那一列），【保留】原时间。
     *                   清空它看着更「干净」，代价是重新发布时无法区分
     *                   「第一次发」和「又发了一次」—— 而且清空之后
     *                   这条记录就永远丢失了「它曾经在什么时候上过线」。
     *
     *   下线 → 再发布   盖【新的】当前时间，公告重新置顶。
     *                   这是「重新发布」和「编辑一下」的全部区别所在：
     *                   运营下线再上线，意图就是「让读者再看到一次」，
     *                   时间不刷新的话它还是躺在三周前的位置，等于白发。
     *
     *   发布中 → 编辑   返回 null（不动）。★ 改个错别字不该把一条
     *                   三周前的公告顶到首页最上面 —— 那是运营最不想要的效果，
     *                   而且他完全预料不到（他只是改了个标点）。
     *
     *   草稿 → 草稿     返回 null。本来就没有时间，没什么可动的。
     * </pre>
     *
     * @param exist     库里当前这一行（提供「改之前」的状态和发布时间）
     * @param newStatus 本次要写入的状态
     * @return 要写进 {@code publish_time} 的值；<b>{@code null} 表示「不修改这一列」</b>
     *         （MP 的 updateById 会跳过 null 字段）
     */
    private LocalDateTime resolvePublishTime(SysNotice exist, Integer newStatus) {
        boolean wasPublished = isPublished(exist.getStatus());
        boolean willPublish = isPublished(newStatus);

        // 「不是已发布 → 已发布」这一次跨越，才是唯一需要盖时间戳的时刻
        if (willPublish && !wasPublished) {
            return LocalDateTime.now();
        }

        /*
         * ★ 其余全部返回 null = 不动那一列。
         *   注意这里【不】返回 exist.getPublishTime()：
         *   对一个草稿来说它是 null，返回 null 一样是「不动」；
         *   而显式返回原值只会让「null 有特殊含义」这件事被藏起来 ——
         *   读代码的人会以为「返回的就是最终值」，从而漏掉 MP 跳过 null 这一层。
         */
        return null;
    }

    /**
     * 判断是不是「已发布」。
     *
     * <p>★ 抽成一个方法而不是到处写 {@code status == 1}：
     * 这一列是 {@code Integer}（可空），直接 {@code == 1} 会<b>自动拆箱</b>，
     * 遇到 null 抛 {@code NullPointerException} → 500。
     * 建表时它是 {@code NOT NULL}，所以今天不可能为 null ——
     * 但这和 {@code CommentVO#forAdmin} 里那句「先判 null 再取 code」是同一个防御，
     * 代价是一个方法调用，收益是把「拆箱就崩」这条路彻底堵死。
     */
    private boolean isPublished(Integer status) {
        return status != null && status == STATUS_PUBLISHED;
    }

    /**
     * 取公告，不存在就抛 {@code NOTICE_NOT_FOUND(7001)}。
     *
     * <p>★ {@code selectById} 会自动带 {@code deleted = 0}（{@code @TableLogic}），
     * 所以「已删除的公告」在这里等同于「不存在」—— 这正是我们要的：
     * 已删除的公告不该还能被编辑，而 {@link #delete} 的幂等
     * 靠的是 {@code deleteById} 返回 0 行，不是靠这里放行。
     */
    private SysNotice requireNotice(Long id) {
        SysNotice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new BusinessException(ResultCode.NOTICE_NOT_FOUND);
        }
        return notice;
    }

    /**
     * 分页兜底。
     *
     * <p><b>★ {@code size} 的下界必须自己兜，不能只靠拦截器。</b>
     * {@code PaginationInnerInterceptor} 只管<b>上界</b>（{@code setMaxLimit(100)}），
     * 而 {@code size < 0} 在 MP 里是「<b>关闭分页</b>」的意思 ——
     * {@code ?size=-1} 会把整张表一次性查出来。
     * 本项目每个 Service 都各自兜了一次，这是约定。
     */
    private Page<SysNotice> buildPage(long page, long size) {
        long safePage = page < 1 ? CommonConstant.DEFAULT_PAGE_CURRENT : page;
        long safeSize = size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size;
        return new Page<>(safePage, safeSize);
    }
}
