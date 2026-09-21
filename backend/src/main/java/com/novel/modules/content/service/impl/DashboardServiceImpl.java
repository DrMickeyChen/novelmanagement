package com.novel.modules.content.service.impl;

import com.novel.common.enums.AuditStatus;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.enums.CommentStatus;
import com.novel.common.enums.NovelStatus;
import com.novel.modules.content.mapper.DashboardMapper;
import com.novel.modules.content.service.DashboardService;
import com.novel.modules.content.vo.DashboardStatsVO;
import com.novel.modules.content.vo.StatusDistVO;
import com.novel.modules.content.vo.TrendVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 数据大盘的实现。
 *
 * <p><b>这个类只做两件事：算日期 + 补零。</b>所有 SQL 都在
 * {@link DashboardMapper} 里，一行都不在这里 —— 这样「跨模块 join 了哪些表」
 * 这个问题只需要看一个文件就能回答（拆服务时要知道的就是这个）。
 *
 * <p><b>★★ 手写 SQL + {@code @TableLogic} = 必须自己写 {@code deleted = 0}。</b>
 * 这里再强调一次是因为它属于「不写也能跑、数字看起来也对」的那类错误：
 * 只有真的删过作品/用户/评论，统计才会多算 —— 而多算的那个数字
 * 长得和一个正确的数字一模一样。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;

    /** ★ 和 SQL 里 {@code DATE_FORMAT(create_time, '%Y-%m-%d')} 必须是同一个格式 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 趋势最少 1 天（0 天没有意义，负数会造出「未来」的日期） */
    private static final int MIN_DAYS = 1;

    /**
     * 趋势最多 90 天。
     *
     * <p><b>★ 上限不是「产品上不需要更多」，是防止一个请求打满 CPU：</b>
     * {@code ?days=100000} 会让下面那个 {@code IntStream.range(...)}
     * 在内存里造十万个日期字符串、再跑十万次 map —— 一次请求就能把
     * 应用卡住几十秒。<b>这和每个 Service 里 {@code buildPage} 兜
     * {@code size} 是同一类问题</b>（那边的坑是 {@code size < 0} 会让 MP
     * 关掉分页，见 {@code AdminUserServiceImpl#buildPage}）。
     */
    private static final int MAX_DAYS = 90;

    /* ==================== 顶部卡片 ==================== */

    @Override
    public DashboardStatsVO stats() {
        /*
         * ★★ 「今天」的起点由【应用时区】算出，不用 SQL 的 CURDATE()。
         *
         * CURDATE() 用的是【数据库时区】。两边不一致时（应用容器是 UTC、
         * MySQL 是 Asia/Shanghai —— 这是容器化部署最常见的组合），
         * 每天 08:00 之前「今日新增」会显示成昨天的数字，
         * 而没有任何报错、也没法从界面上看出来。
         *
         * 本项目实测：MySQL 的 @@global.time_zone = +08:00，
         * JDBC URL 里 serverTimezone=Asia/Shanghai，两边一致 ——
         * 所以现在两种写法结果相同。用 Java 算是因为一旦哪天上线的
         * 容器时区变了，这里的行为<b>不会跟着变</b>，
         * 而不是因为现在有 bug。
         */
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        /*
         * ★ 状态码从这里传进去，而不是写死在 SQL 里。
         *   写死的话，枚举改了编号 → 统计静默算错，而数字看起来永远正常。
         */
        return dashboardMapper.selectStats(
                todayStart,
                NovelStatus.PENDING.getCode(),
                ChapterStatus.PENDING.getCode(),
                CommentStatus.PENDING.getCode(),
                /*
                 * ★ author_apply.status 用 AuditStatus，不是 AuditAction。
                 *   两个枚举名字像、编码完全一样（0/1/2 和 1/2/3），
                 *   用错的话会往查询里塞一个不在值域里的数 ——
                 *   结果是「待处理申请永远是 0」，不报错。
                 *   见 AuditStatus 类注释里那段双向警示。
                 */
                AuditStatus.PENDING.getCode());
    }

    /* ==================== 趋势 ==================== */

    @Override
    public TrendVO trend(int days) {
        int safeDays = clampDays(days);

        /*
         * ★★ 只取一次 now()。
         *
         * 下面的日期轴和查询区间必须来自【同一个今天】。
         * 分成两次 LocalDate.now() 的话，请求正好跨过午夜时，
         * 轴的最后一天是「新的一天」而查询区间还停在「昨天」——
         * 于是那一天的计数落在区间外被丢弃，图上最后一天永远是 0。
         * 这种 bug 一天只出现一瞬，本地几乎不可能复现。
         */
        LocalDate today = LocalDate.now();

        List<String> axis = buildDayAxis(today, safeDays);

        /*
         * ★ 半开区间 [start, end)，不是 BETWEEN。
         *   end 取「明天 0 点」—— 干净的边界值，且不会漏掉
         *   今天 23:59:59.500 这种带小数秒的行。
         *   理由详见 DashboardMapper 里那段注释。
         */
        LocalDateTime start = today.minusDays(safeDays - 1L).atStartOfDay();
        LocalDateTime end = today.plusDays(1L).atStartOfDay();

        TrendVO vo = new TrendVO();
        vo.setDays(axis);
        vo.setNovels(fill(axis, dashboardMapper.countNovelByDay(start, end), "作品"));
        vo.setUsers(fill(axis, dashboardMapper.countUserByDay(start, end), "用户"));
        vo.setComments(fill(axis, dashboardMapper.countCommentByDay(start, end), "评论"));
        return vo;
    }

    /**
     * 生成连续无缺口的日期轴，从早到晚，最后一个是 {@code today}。
     *
     * <p><b>★★ 这个方法是「图不说谎」的全部保障。</b>
     * {@code GROUP BY DATE(create_time)} 只会返回<b>有数据的那几天</b>。
     * 把稀疏的结果直接丢给 ECharts 折线图，它会按 x 轴顺序把这些点
     * <b>连起来</b> —— 于是「9/1 有 5 本、9/20 有 3 本」被画成一条
     * 穿过 9/2~9/19 的平滑斜线，看上去是「每天都在稳定新增 0.3 本」。
     * 中间那 18 天的 0 <b>在图上完全看不出来</b>。
     * 这不是「显示得不好看」，是<b>图表在陈述一个不存在的事实</b>，
     * 而看的人没有任何办法察觉。
     *
     * <p>所以：把 N 天全部先造出来，再把查到的值填回去，缺的补 0。
     */
    private List<String> buildDayAxis(LocalDate today, int days) {
        return IntStream.range(0, days)
                // ★ 减的是 days - 1 - i：i = 0 时是「days-1 天前」，
                //   最后一个 i = days-1 时正好是 today
                .mapToObj(i -> today.minusDays(days - 1L - i).format(DATE_FMT))
                .toList();
    }

    /**
     * 把稀疏的「某天 → 条数」填进完整的日期轴。
     *
     * <p><b>★ 返回值长度一定等于 {@code axis.size()}</b>，
     * 这是 {@link TrendVO} 三个平行数组的唯一契约（见那个类的注释）。
     * 用 {@code stream().map()} 而不是 for 循环累加，是为了让这件事
     * 由构造方式本身保证 —— 循环里一旦有人写了 {@code continue}，
     * 长度就对不上了，而长度对不上时三个数组会整体错位，
     * <b>图还是能画出来</b>，只是每个点都对应错了日期。
     *
     * @param what 序列的中文名，只用在日志里
     */
    private List<Long> fill(List<String> axis, List<DashboardMapper.DayCount> rows, String what) {
        Map<String, Long> byDay = new HashMap<>();
        for (DashboardMapper.DayCount row : rows) {
            byDay.put(row.getStatDay(), row.getStatCount() == null ? 0L : row.getStatCount());
        }

        /*
         * ★★ 区间外的行会被下面那行 getOrDefault 静默丢弃 —— 那一天会显示成 0。
         *   正常情况下不可能发生（SQL 的 where 和 axis 是同一段区间），
         *   唯一会发生的场景就是【数据库时区和应用时区不一致】：
         *   DATE_FORMAT 按数据库时区切天，于是某条记录被归到了
         *   axis 里没有的日期上，计数凭空消失、那天显示 0。
         *
         *   这正是最难查的一类问题：没有异常、没有报错，
         *   只是某一天的数字偏低。所以这里留一条 warn，
         *   把「静默算错」变成「日志里有话」。
         */
        Set<String> axisSet = new HashSet<>(axis);
        List<String> outside = byDay.keySet().stream().filter(d -> !axisSet.contains(d)).toList();
        if (!outside.isEmpty()) {
            log.warn("大盘趋势有 {} 天的数据落在请求区间外，这些计数会被丢弃（显示为 0）。"
                            + "通常是数据库时区和应用时区不一致 | 序列={} 区间=[{} ~ {}] 越界日期={}",
                    outside.size(), what, axis.get(0), axis.get(axis.size() - 1), outside);
        }

        return axis.stream().map(d -> byDay.getOrDefault(d, 0L)).toList();
    }

    /**
     * 夹住天数。
     *
     * <p>★ 越界时打一条 warn 但仍然返回结果，**不抛 400** ——
     * 理由和「按不存在的角色筛选用户返回空页」一致：
     * 这是个展示用的参数，把它变成红字报错对谁都没好处。
     * 但也不能一声不吭，否则前端传错了永远发现不了。
     */
    private int clampDays(int days) {
        if (days < MIN_DAYS) {
            log.warn("大盘趋势 days 过小，已按 {} 处理 | 传入={}", MIN_DAYS, days);
            return MIN_DAYS;
        }
        if (days > MAX_DAYS) {
            log.warn("大盘趋势 days 超上限，已按 {} 处理 | 传入={}", MAX_DAYS, days);
            return MAX_DAYS;
        }
        return days;
    }

    /* ==================== 状态分布 ==================== */

    @Override
    public StatusDistVO statusDist() {
        /*
         * ★ 两个饼图各自算一份「合法状态码集合」，不共用 ——
         *   作品是 0/1/2/3/4，章节是 0/1/2/4（★ 章节没有 3「已下架」）。
         *   共用一份的话，章节那个图会凭空多出一个永远是 0 的「已下架」扇区，
         *   而「章节不能单独下架」是这个项目里明确的设计（见 ChapterStatus）。
         */
        Map<Integer, Long> novelCounts = toCountMap(
                dashboardMapper.countNovelByStatus(), novelCodes(), "作品");
        Map<Integer, Long> chapterCounts = toCountMap(
                dashboardMapper.countChapterByStatus(), chapterCodes(), "章节");

        StatusDistVO vo = new StatusDistVO();

        /*
         * ★ 遍历的是【枚举】而不是查询结果，所以某种状态一条都没有时
         *   它仍然会以 count = 0 出现。理由见 StatusDistVO 的类注释：
         *   饼图里「0 个」和「这个状态根本不存在」长得一模一样。
         */
        vo.setNovels(Arrays.stream(NovelStatus.values())
                .map(s -> item(s.getCode(), s.getDesc(), novelCounts))
                .toList());
        vo.setChapters(Arrays.stream(ChapterStatus.values())
                .map(s -> item(s.getCode(), s.getDesc(), chapterCounts))
                .toList());
        return vo;
    }

    private Set<Integer> novelCodes() {
        return Arrays.stream(NovelStatus.values()).map(NovelStatus::getCode)
                .collect(Collectors.toSet());
    }

    private Set<Integer> chapterCodes() {
        return Arrays.stream(ChapterStatus.values()).map(ChapterStatus::getCode)
                .collect(Collectors.toSet());
    }

    /**
     * 查到的行 → {@code 状态码 → 条数}。
     *
     * <p><b>★ 数据库里出现了枚举里没有的状态码时打一条 warn。</b>
     * 那意味着有人绕过状态机直接改了库（或者枚举删过值）。
     * 这些行<b>不会出现在饼图上</b>（饼图的分类来自枚举），
     * 于是各扇区加起来对不上「作品总数」，而看图上完全看不出原因。
     *
     * @param known 枚举认识的状态码。★ 由调用方传进来而不是在这里判断，
     *              因为两个枚举不一样 —— 用「传个中文名进来再 if 一下」
     *              那种写法，中文名改了判断就静默失效了
     */
    private Map<Integer, Long> toCountMap(List<DashboardMapper.StatusCount> rows,
                                          Set<Integer> known, String what) {
        Map<Integer, Long> counts = new HashMap<>();
        for (DashboardMapper.StatusCount row : rows) {
            if (row.getStatus() == null) {
                // status 是 NOT NULL 列，走到这里说明查询写错了，不是数据问题
                continue;
            }
            counts.put(row.getStatus(), row.getStatCount() == null ? 0L : row.getStatCount());
            if (!known.contains(row.getStatus())) {
                log.warn("{}表里有枚举不认识的状态码，该行不会出现在大盘饼图上 | status={} count={}",
                        what, row.getStatus(), row.getStatCount());
            }
        }
        return counts;
    }

    /**
     * 拼一项分布。
     *
     * <p><b>★ 这个方法是「零填充」的落点：{@code counts} 里没有这个状态码时
     * 返回 {@code 0} 而不是跳过。</b>
     * 跳过的后果见 {@link StatusDistVO} 的类注释 ——
     * 饼图里「0 个」和「这个状态不存在」会长得一模一样。
     */
    private StatusDistVO.Item item(Integer status, String label, Map<Integer, Long> counts) {
        StatusDistVO.Item item = new StatusDistVO.Item();
        item.setStatus(status);
        item.setLabel(label);
        item.setCount(counts.getOrDefault(status, 0L));
        return item;
    }
}
