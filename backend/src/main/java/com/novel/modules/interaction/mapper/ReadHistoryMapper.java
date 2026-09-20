package com.novel.modules.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.interaction.entity.ReadHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 阅读进度 Mapper。
 *
 * <p>这个接口只有一个手写方法（{@link #upsert}），其余全部走 {@code BaseMapper}
 * 自动生成的方法。<b>这里的手写和 P2 那些「手写 SQL 必须自己写 {@code deleted = 0}」
 * 的规矩不冲突</b> —— 参见下面的注释。
 *
 * @author MickeyChen
 */
@Mapper
public interface ReadHistoryMapper extends BaseMapper<ReadHistory> {

    /**
     * 上报进度：<b>没有就插入，有就覆盖</b>，一条 SQL 原子完成。
     *
     * <p><b>★ 为什么用 {@code ON DUPLICATE KEY UPDATE} 而不是「先 selectOne 判断，
     * 再决定 insert 还是 update」：</b>
     * 后者是「查一次 + 写一次」两个步骤，两个标签页同时上报时都查到「没有记录」，
     * 然后都去 insert，第二个撞 {@code uk_user_novel} 报
     * {@code DuplicateKeyException} —— 一个用户的正常操作变成了 500。
     * 要修就得再加 try/catch 重试，代码里从此多出一条「只在并发下才走到」的路径。
     * <p>交给数据库裁决是一步到位的：唯一键冲突是<b>预期内的正常情况</b>，
     * 让它直接变成 UPDATE，应用层看不到任何异常。
     *
     * <p><b>★★ 关于 P2 定的「手写 SQL 必须自己写 {@code deleted = 0}」这条规矩：</b>
     * 那条规定的前提是「表上有 {@code deleted} 列，而 {@code @TableLogic} 的自动过滤
     * 只对 MP 自己生成的方法生效」。<b>{@code read_history} 表根本没有 {@code deleted} 列</b>
     * （见 {@link ReadHistory} 的类注释），所以这里没有可漏的东西 ——
     * 不是「忘了写」，是这张表就不存在逻辑删除这回事。
     * 更要紧的是反过来：如果照抄惯性在这里写上 {@code AND deleted = 0}，
     * 会立刻报 {@code Unknown column 'deleted'}。
     *
     * <p><b>★ {@code AS incoming} 这段别名语法要求 MySQL 8.0.19+</b>（本机 8.0.46）。
     * 老写法 {@code ON DUPLICATE KEY UPDATE col = VALUES(col)} 在 8.0.20 起被标记为
     * 不推荐，会在每次执行时产生一条 deprecation warning，且已在移除计划中。
     * 别名写法是官方推荐的替代，而且能在一列里同时引用「要插入的新值」（{@code incoming.x}）
     * 和「表里已有的旧值」（直接写列名）。别名取 {@code incoming} 而不是官方文档里的
     * {@code new}，是为了<b>避开保留字</b> —— 在触发器里 {@code NEW} 是有特殊含义的，
     * 换一个没有任何歧义的词，读代码的人不必去查它算不算保留字。
     *
     * <p><b>★ 为什么把 {@code updateTime} 做成入参、而不是在 SQL 里写 {@code NOW()}：</b>
     * <ol>
     *   <li>时区口径统一由应用决定，和 {@code MybatisMetaObjectHandler} 的做法一致；</li>
     *   <li>建表语句里那一列虽然有 {@code ON UPDATE CURRENT_TIMESTAMP}，
     *       但 MySQL 只在<b>值真的变了</b>时才刷新它。读者重新打开同一章、
     *       进度和上次一模一样时，那一列不会动 —— 而「重读了一次」这件事
     *       对 P6 的阅读历史列表（按 {@code update_time} 倒序）是有意义的，
     *       它应该被顶到最前面。显式写入可以保证这一点。</li>
     * </ol>
     *
     * <p><b>★ {@code progress} 是「覆盖」不是「取较大值」。</b>读者主动往回翻，
     * 他的当前位置就是往前的那一章，把它改回后面才是错的。
     * 「读过的最大进度」是另一个产品概念（P6 若要做「已读/未读」标记再单开字段），
     * 不该复用这一列。
     *
     * @param userId    来自 {@code UserContext}，不是请求参数
     * @param now       由调用方传入，见上面第 2 条
     * @return 影响行数。<b>1 = 新插入，2 = 覆盖已有行</b>，两者都是成功
     */
    @Insert("""
            INSERT INTO read_history (user_id, novel_id, chapter_id, progress, update_time)
            VALUES (#{userId}, #{novelId}, #{chapterId}, #{progress}, #{now}) AS incoming
            ON DUPLICATE KEY UPDATE
                chapter_id  = incoming.chapter_id,
                progress    = incoming.progress,
                update_time = incoming.update_time
            """)
    int upsert(@Param("userId") Long userId,
               @Param("novelId") Long novelId,
               @Param("chapterId") Long chapterId,
               @Param("progress") Integer progress,
               @Param("now") LocalDateTime now);
}
