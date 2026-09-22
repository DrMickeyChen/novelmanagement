package com.novel.modules.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.interaction.entity.UserShelf;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 书架收藏的写入口。<b>两条自定义 SQL：一条是「加入书架」必须原子，
 * 一条是「每本书几个人收藏」（M3 的对账真值）。</b>
 *
 * @author MickeyChen
 */
@Mapper
public interface UserShelfMapper extends BaseMapper<UserShelf> {

    /**
     * 加入书架：<b>没有就插入，已有就什么也不做</b>，一条 SQL 原子完成。
     *
     * <p><b>★ 为什么不是「先 selectOne 判断，再决定 insert」：</b>
     * 那是「查一次 + 写一次」两个步骤，两个标签页同时点「加入书架」时
     * 都查到「没有」，然后都去 insert，第二个撞 {@code uk_user_novel} 报
     * {@code DuplicateKeyException} —— 用户正常的点两下变成了 500。
     * 交唯一键裁决是一步到位的：冲突是<b>预期内的正常情况</b>，
     * 让它直接变成 UPDATE，应用层看不到异常。同 {@code ReadHistoryMapper#upsert}。
     *
     * <p><b>★ {@code create_time = user_shelf.create_time} 是一个有意的空操作。</b>
     * 别名 {@code incoming} 是「要插入的新行」，表名 {@code user_shelf} 是「已有行」，
     * 所以这一句的意思是「保留原来的加入时间」——
     * 再点一下「加入书架」（比如从别的标签页、或者前端没刷新）不该把这本书
     * 顶到书架最上面。写成 {@code incoming.create_time} 就会顶上去。
     *
     * <p><b>★ 这里绝不能补 {@code deleted = 0}</b> —— {@code user_shelf} 表
     * <b>没有</b> {@code deleted} 列（见 {@link UserShelf} 的类注释），
     * 补上会立刻 {@code Unknown column 'deleted'}。这是从别的表带过来的惯性。
     *
     * <p><b>★ {@code AS incoming} 别名语法要求 MySQL 8.0.19+</b>（本机 8.0.46），
     * 不用已被标记为不推荐的 {@code VALUES(col)}。别名取 {@code incoming}
     * 而不是官方文档里的 {@code new}，是为了避开保留字。详见
     * {@code ReadHistoryMapper#upsert} 里那段完整论证。
     *
     * @return 影响行数。<b>1 = 新插入，0 = 本来就在书架里</b>，两者都是成功
     */
    @Insert("""
            INSERT INTO user_shelf (user_id, novel_id, create_time)
            VALUES (#{userId}, #{novelId}, #{now}) AS incoming
            ON DUPLICATE KEY UPDATE create_time = user_shelf.create_time
            """)
    int addIfAbsent(@Param("userId") Long userId,
                    @Param("novelId") Long novelId,
                    @Param("now") java.time.LocalDateTime now);

    /**
     * <b>每本书各有几个人收藏</b> —— 收藏数的<b>真值</b>。
     *
     * <p><b>★★ 这是对账任务（{@code NovelStatReconcileJob}）的锚点，它决定了
     * 那条任务能修好什么、修不好什么，所以这段要读全。</b>
     *
     * <p>为什么是「一次 GROUP BY 拿全集」而不是「某本书现在几个人收藏」：
     * 收藏数写回 content 走的是 {@code NOVEL_STAT} 消息（M3），
     * 而消息<b>可能丢</b>（发送时 Broker 抖动、进程被杀）。丢了的现场
     * 在两边都不留痕迹 —— interaction 这边以为已经发出去了，
     * content 那边停在旧值。于是「哪本书漂了」这个问题
     * <b>没有任何 id 列表能回答</b>，只能把两边的全量摊开比一遍。
     *
     * <p><b>★★ 注意这里【没有】{@code deleted = 0} —— 而这是对的，不是漏写：</b>
     * {@code user_shelf} 表<b>根本没有</b> {@code deleted} 列（同 {@link #addIfAbsent}
     * 里那条警告），补上会立刻 {@code Unknown column 'deleted'}。
     * <p>★ 更值得记一句的是<b>它的后果</b>：移出书架是<b>物理 DELETE</b>，
     * 所以一次「取消收藏」在这张表里<b>不留任何痕迹</b> ——
     * 「找最近变动的 novel 再重算」这条最自然的路<b>走不通</b>
     * （被删的那一行已经不存在了，扫不出来）。
     * 本方法之所以仍然够用，是因为对账的锚点是<b>绝对值本身</b>：
     * 一本书的最后一行被删掉之后，它就从本方法的结果里消失了 → 真值 0，
     * 而 content 那边还停在旧的非零值 → 两边一比就发现了。
     * <b>「移除」是靠「缺席」表达出来的</b>，这一点不显然，改动本方法前先想清楚。
     *
     * <p>★ 不加 {@code ORDER BY}：调用方要的是「id → 人数」的查找表，
     * 顺序没有意义，而排序会让这条本来能走 {@code idx_novel_id} 的
     * 分组查询多一次排序。
     *
     * @return 每行一本书。<b>收藏数为 0 的书不在结果里</b>（GROUP BY 只产出有行的组）——
     *         调用方必须把「不在结果里」当成 0，见
     *         {@code NovelService#collectCountsAfter} 里那段对照说明
     */
    @Select("""
            SELECT novel_id AS novelId, COUNT(*) AS collectCount
            FROM user_shelf
            GROUP BY novel_id
            """)
    List<NovelCollectCount> countGroupByNovel();

    /**
     * 「某本书有几个人收藏」—— {@link #countGroupByNovel} 的行类型。
     *
     * <p><b>★ 为什么是嵌套类，不是 {@code modules/interaction/vo/} 下的一个 VO：</b>
     * 它是这个 Mapper 的<b>内部行类型</b>，永远不会出现在任何 Controller 的返回值里
     * （对账任务用它，而它不是接口）。放进 vo 包会让人以为它是个出参 DTO，
     * 然后有人拿它当接口契约用。同 {@code DashboardMapper.DayCount} 的理由。
     *
     * <p><b>★ 为什么不是 {@code Map<String, Object>}：</b>
     * 取值要靠字符串 key，而列名的大小写取决于驱动和配置，
     * {@code get("collectCount")} 拿到 {@code null} 之后是 NPE ——
     * 且这种错在 Windows（大小写不敏感）经常<b>蒙混过关</b>，换台机器才炸。
     * 完整论证在 {@code DashboardMapper} 的类注释里（那里是同一个决定的先例）。
     *
     * <p>★ 字段类型跟着 SQL 走：{@code novel_id} 是 BIGINT → {@code Long}；
     * {@code COUNT(*)} 在 MySQL 里是 BIGINT → 也是 {@code Long}
     * （<b>不是 {@code Integer}</b>，虽然计数不可能溢出 —— 类型对不上时
     * MyBatis 靠自动转换兜，而那是「碰巧能用」）。
     */
    @Data
    class NovelCollectCount {
        private Long novelId;
        private Long collectCount;
    }
}
