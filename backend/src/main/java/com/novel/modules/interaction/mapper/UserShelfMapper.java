package com.novel.modules.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.interaction.entity.UserShelf;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 书架收藏的写入口。<b>只有一条自定义 SQL，因为「加入书架」必须原子。</b>
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
}
