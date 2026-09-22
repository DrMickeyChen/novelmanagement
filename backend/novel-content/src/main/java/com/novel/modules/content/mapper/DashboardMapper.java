package com.novel.modules.content.mapper;

import com.novel.modules.content.vo.DashboardStatsVO;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据大盘的统计查询。
 *
 * <p><b>★★ 这个 Mapper 里【全是跨模块的表】，这不是疏忽，是有意的 —— 但必须知道代价。</b>
 *
 * <p>它同时读 {@code novel} / {@code chapter}（content 自己的表）、
 * {@code novel_comment}（interaction 的表）、{@code sys_user} 和
 * {@code author_apply}（system 的表）。也就是说这个类<b>横跨了三个服务
 * （M2 之后是三个进程：novel-content / novel-interaction / novel-auth）</b>。
 *
 * <p>为什么可以这么做：
 * <ol>
 *   <li>★ <b>P5 写这句时还是单体（「一个库，join 是免费的」）；M2 之后
 *       服务分了、进程分了，但库没分</b> —— 所以这几条 SQL
 *       仍然能跑，这是决定 1「一个 novel schema + 表归属独占」换来的
 *       （「表归属独占」是纪律，「一个 schema」是 M2 选的落地方式）；</li>
 *   <li>它<b>只读不写</b>，不参与任何业务事务，脏读最坏的结果是某个数字差一；</li>
 *   <li>它是<b>运维视图</b>，不是业务链路 —— 大盘挂了不影响任何人办事。</li>
 * </ol>
 *
 * <p><b>★★★ M2 已经过去了，这里【没有被修】—— 这是一个刻意的例外，不是漏改。</b>
 *
 * <p>原计划里 M2 会「回来处理这里」。实际实施时它被<b>明确留了下来</b>，
 * 理由是当时就有、而且现在仍然成立的那两条（只读、运维视图），
 * 外加一条 M2 特有的：<b>M2 的目标是「业务无回归」，而把大盘改成跨服务
 * 拼装是行为变更</b>（数字的口径会变、多出失败分支），
 * 那和「一个字节都不变」直接冲突。
 *
 * <p>★ 代价必须说清楚，别让后人以为是白拿的：
 * <ul>
 *   <li>它读的 {@code sys_user} / {@code author_apply} 归 novel-auth，
 *       {@code novel_comment} 归 novel-interaction。这三个跨表读
 *       <b>依赖「一个 novel schema」这个决定</b>（决定 1）——
 *       <b>哪天真的物理拆库，这个类会是第一个炸的</b>，而且是
 *       启动就报 {@code Table doesn't exist} 的那种炸，不是静默出错。</li>
 *   <li>它让 content 仍然「知道」另外两个服务的表结构。表结构一改，
 *       这里要跟着改，而编译器不会提醒。</li>
 * </ul>
 *
 * <p><b>★ 记为 M5 展示项</b>（拆库时的处理方案：改成调接口、
 * 或走 MQ 汇总表、或干脆把大盘做成独立的查询服务）。
 * 上面那两条路仍然有效，只是<b>不在 M2 做</b>。
 *
 * <p>★ 对照：M2 里另一处跨表读 {@code NovelCommentMapper#selectAdminPage}
 * <b>被修了</b>（拆成两次查询）。两处的判据是
 * 「<b>它在业务链路上吗</b>」—— 评论列表是管理端天天用的功能，
 * 大盘是看一眼的运维视图。这一条判据要沿用到 M3/M4。
 *
 * <p><b>★ 为什么建一个独立的 Mapper 而不是往 {@code NovelMapper} 里塞：</b>
 * {@code NovelMapper} 是「作品」这个领域对象的仓储，它的职责边界是
 * 「一本书的增删改查」。统计是另一件事 —— 它读的是聚合值，不是实体。
 * 混进去之后，将来拆分时没人能把「哪些方法是业务、哪些是报表」分开。
 *
 * <p><b>★★ 手写 SQL 里 {@code deleted = 0} 必须自己写，一个都不能漏。</b>
 * {@code @TableLogic} 只对 MyBatis-Plus <b>自动生成</b>的 SQL 生效
 * （{@code selectById} / {@code deleteById} 这些），对手写 {@code @Select}
 * <b>完全没有作用</b>。漏掉的后果是统计数字把已删除的行也算进去 ——
 * 而这个数字<b>看起来永远是对的</b>，除非有人刚好删过东西又去核对。
 *
 * <p><b>★★ 返回值不是 {@code Map<String, Object>}，是明确的 VO。</b>
 * 用 Map 的话，取值靠字符串 key（{@code get("cnt")}），而列名的大小写
 * 取决于驱动和配置 —— {@code get("CNT")} 拿到 {@code null} 之后是 NPE，
 * 且这种错误在本机（Windows、大小写不敏感）经常<b>蒙混过关</b>，
 * 换一台机器才炸。用 VO 走的是「列别名 → 属性名」的映射，稳定得多。
 *
 * @author MickeyChen
 */
@Mapper
public interface DashboardMapper {

    /* ==================== 顶部卡片 ==================== */

    /**
     * 八个数字，一条 SQL 查完。
     *
     * <p><b>★ 为什么用标量子查询而不是发八次 COUNT：</b>
     * 八次查询就是八个来回，而这是首屏要用的数字 —— 每次几毫秒，
     * 加起来就是用户能感觉到的白屏。而且这八个数字本该是<b>同一个瞬间</b>
     * 的快照，分八次查的话，两次查询之间有人提交了作品，
     * 就会出现「作品总数 86，其中待审 3，但列表点进去只有 2 本」这种
     * 对不上的情况 —— 虽然无害，但很难向人解释。
     *
     * <p><b>★★ 状态码是参数传进来的，不是写死的 1。</b>
     * 写成 {@code AND status = 1} 的话，哪天 {@code NovelStatus.PENDING}
     * 改了编号，这个统计会<b>静默地</b>统计错 —— 数字照样是一个正整数，
     * 看起来永远正常。由调用方传枚举的 {@code getCode()}，
     * 至少保证了「这里数的就是那个状态」，且改枚举时 IDE 能找到引用。
     *
     * @param todayStart        今天的 0 点，由应用时区算出（见 DashboardServiceImpl）。
     *                          ★ 不用 SQL 的 {@code CURDATE()} —— 那是数据库时区
     * @param novelPending      {@code NovelStatus.PENDING.getCode()}
     * @param chapterPending    {@code ChapterStatus.PENDING.getCode()}
     * @param commentPending    {@code CommentStatus.PENDING.getCode()}
     * @param authorApplyPending {@code AuthorApplyStatus.PENDING.getCode()}
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM novel
                 WHERE deleted = 0)                                        AS novelTotal,
                (SELECT COUNT(*) FROM novel
                 WHERE deleted = 0 AND status = #{novelPending})            AS novelPending,
                (SELECT COUNT(*) FROM chapter
                 WHERE deleted = 0 AND status = #{chapterPending})          AS chapterPending,
                (SELECT COUNT(*) FROM sys_user
                 WHERE deleted = 0)                                        AS userTotal,
                (SELECT COUNT(*) FROM sys_user
                 WHERE deleted = 0 AND create_time >= #{todayStart})        AS userToday,
                (SELECT COUNT(*) FROM novel_comment
                 WHERE deleted = 0)                                        AS commentTotal,
                (SELECT COUNT(*) FROM novel_comment
                 WHERE deleted = 0 AND status = #{commentPending})          AS commentPending,
                (SELECT COUNT(*) FROM author_apply
                 WHERE deleted = 0 AND status = #{authorApplyPending})      AS authorApplyPending
            """)
    DashboardStatsVO selectStats(@Param("todayStart") LocalDateTime todayStart,
                                 @Param("novelPending") Integer novelPending,
                                 @Param("chapterPending") Integer chapterPending,
                                 @Param("commentPending") Integer commentPending,
                                 @Param("authorApplyPending") Integer authorApplyPending);

    /*
     * ★★ 注意上面那段 SQL 里【没有 join novel】来排除「父作品已删的章节」。
     *   这不是漏了，是因为那条不变式由别处保证了：
     *   NovelServiceImpl#delete 在面对「还有章节的书」时直接拒绝删除
     *   （返回 NOVEL_HAS_CHAPTERS），所以不可能存在
     *   「章节还在、父作品已逻辑删除」的行。
     *
     *   哪天这个守卫被去掉、或者改成级联删除，这里就必须补上
     *      AND novel_id IN (SELECT id FROM novel WHERE deleted = 0)
     *   否则「待审章节」会算进一批审核员根本点不进去的章节
     *   （作品列表里已经找不到那本书了）。
     */

    /* ==================== 趋势 ==================== */

    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDay,
                   COUNT(*)                             AS statCount
            FROM novel
            WHERE deleted = 0
              AND create_time >= #{start}
              AND create_time < #{end}
            GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')
            """)
    List<DayCount> countNovelByDay(@Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDay,
                   COUNT(*)                             AS statCount
            FROM sys_user
            WHERE deleted = 0
              AND create_time >= #{start}
              AND create_time < #{end}
            GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')
            """)
    List<DayCount> countUserByDay(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDay,
                   COUNT(*)                             AS statCount
            FROM novel_comment
            WHERE deleted = 0
              AND create_time >= #{start}
              AND create_time < #{end}
            GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')
            """)
    List<DayCount> countCommentByDay(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    /*
     * ★ 三条 SQL 的两点说明：
     *
     * ① 【范围用半开区间 [start, end)】，不是 BETWEEN。
     *    BETWEEN 是闭区间，end 得写成「今天 23:59:59」——
     *    那个值会丢掉最后一秒里的小数秒，于是 23:59:59.500 创建的行
     *    统计不到。半开区间没有这个缝，而且 #{end} 用「明天 0 点」
     *    是个干净的边界值。
     *    ★ 另一个好处：`create_time >= ...` 能走上 create_time 上的索引，
     *      而 `DATE(create_time) BETWEEN ...` 对列做了函数运算，用不了索引。
     *
     * ② 【GROUP BY 的是 DATE_FORMAT(...) 而不是 DATE(create_time)】，
     *    且 SELECT 的也是同一个表达式 —— 两者必须一致。
     *    写成 GROUP BY DATE(create_time) 而 SELECT DATE_FORMAT(...) 的话，
     *    在 MySQL 8 默认开启的 ONLY_FULL_GROUP_BY 下会直接报错
     *    （MySQL 认不出这两个表达式有函数依赖关系）。
     *    而格式化成字符串再返回，是为了让「数据库算出的日期键」
     *    和「Java 生成的日期轴」用同一个格式 —— 见 DashboardServiceImpl。
     */

    /* ==================== 状态分布 ==================== */

    /**
     * 作品按状态计数。<b>只返回存在的那些状态</b>，缺的由 Service 补 0。
     *
     * <p>★ 补 0 不能在这里做：SQL 没法凭空造出「一个都没有」的行
     * （要造就得写 {@code UNION SELECT 0 UNION SELECT 1 ...}，
     * 那等于把枚举抄进 SQL，改了枚举就悄悄错）。
     * 所以这里只管有什么返回什么，缺的由 Java 侧补齐 ——
     * 值的来源是 {@code NovelStatus.values()}。
     */
    @Select("""
            SELECT status AS status, COUNT(*) AS statCount
            FROM novel
            WHERE deleted = 0
            GROUP BY status
            """)
    List<StatusCount> countNovelByStatus();

    @Select("""
            SELECT status AS status, COUNT(*) AS statCount
            FROM chapter
            WHERE deleted = 0
            GROUP BY status
            """)
    List<StatusCount> countChapterByStatus();

    /* ==================== 查询结果的载体 ==================== */

    /**
     * 「某一天有多少条」。
     *
     * <p><b>★ 为什么定义成嵌套类，而不是放进 {@code modules/content/vo/}：</b>
     * 它是这个 Mapper 的<b>内部行类型</b>，不是对外的接口契约 ——
     * 它永远不会出现在任何 Controller 的返回值里（出去的是
     * {@code TrendVO} 的三个平行数组）。放进 vo 包会让人以为它是个 DTO，
     * 然后有人拿它当接口出参用，于是「日期键的格式」这件事就散布到两个地方了。
     *
     * <p>{@code statDay} 是 <b>String</b> 不是 {@code LocalDate}：
     * SQL 里已经用 {@code DATE_FORMAT} 格式化成 {@code yyyy-MM-dd} 了，
     * 直接按字符串接住，不用再依赖类型转换器把 DATE 转成 LocalDate ——
     * 少一层转换就少一处「在某个驱动版本上行为不同」的可能。
     */
    @Data
    class DayCount {
        /** 形如 {@code 2026-09-21} */
        private String statDay;
        private Long statCount;
    }

    /** 「某个状态有多少条」。字段名同样是 SQL 别名，不用 Map 的理由见类注释 */
    @Data
    class StatusCount {
        private Integer status;
        private Long statCount;
    }
}
