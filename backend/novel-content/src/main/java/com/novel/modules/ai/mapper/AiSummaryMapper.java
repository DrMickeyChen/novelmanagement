package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.AiSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 前情摘要 Mapper（E1）。
 *
 * <p>★ 只有一条手写 SQL，因为 {@code BaseMapper} 表达不了它唯一要干的事：
 * <b>「按截止章节号取最新的一条」</b>。
 *
 * <p>★★ <b>为什么不用 {@code selectOne(wrapper)}：</b>那需要调用方自己保证
 * 只有一行 —— 而这张表的唯一键是 {@code (novel_id, end_chapter_no)}，
 * 也就是说<b>同一本书本来就有很多行</b>（每压一次多一行）。
 * 用 {@code selectOne} 的写法只有在「排序 + LIMIT 1」都写对时才不出错，
 * 而它出错的方式是 {@code TooManyResultsException} → 500。
 * 把 LIMIT 1 钉死在 SQL 里，这个错法就构造不出来。
 *
 * @author MickeyChen
 */
@Mapper
public interface AiSummaryMapper extends BaseMapper<AiSummary> {

    /**
     * 取某本书<b>覆盖章节最靠后</b>的那条摘要，没有则返回 {@code null}。
     *
     * <p>★★ <b>{@code ORDER BY end_chapter_no DESC}，不是 {@code ORDER BY id DESC}。</b>
     * 两者只在「有人补压了一段更早的区间」时分道扬镳 ——
     * 而那时按 {@code id} 取到的是<b>更晚写、却覆盖更早剧情</b>的那条：
     * 模型拿着它续写，接的是一段已经被跳过的前情，<b>而且从提示词里看不出来</b>。
     * ★ 判据：这一列是「这段摘要把故事讲到了哪」，{@code id} 只是「谁后写进来」——
     * 我们要的是前者。
     *
     * <p>★ 与 {@code AiConfigServiceImpl.currentRow()} 同一条：
     * <b>把「有多行时用哪一行」变成一个可预期的答案</b>，而不是交给执行计划。
     *
     * <p>★ 手写 {@code @Select} 时 {@code @TableLogic} 不生效（本项目已记在案）——
     * 这张表本来就没有 {@code deleted} 列，所以这里不需要那个条件。
     *
     * @return 摘要行；该书还没有任何摘要时返回 {@code null}
     *         —— ★ {@code null} 是<b>正常返回</b>，不是异常：S10 之前整个库都是空的，
     *         而 {@code {{summary}}} 是可选变量，无值就整行移除（判断 2.2）
     */
    @Select("""
            SELECT * FROM ai_summary
            WHERE novel_id = #{novelId}
            ORDER BY end_chapter_no DESC
            LIMIT 1
            """)
    AiSummary selectLatestByNovelId(@Param("novelId") Long novelId);
}
