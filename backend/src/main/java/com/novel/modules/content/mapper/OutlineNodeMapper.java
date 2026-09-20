package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.OutlineNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 大纲树 Mapper。
 *
 * <p><b>★ 这里只有一条手写 SQL，是为了行锁 —— 这一点值得解释清楚。</b>
 *
 * <p>本项目有一条惯例：Mapper 尽量保持空接口（参见 {@link NovelMapper}），
 * 因为 {@code LambdaQueryWrapper} 会自动补 {@code deleted = 0}，
 * 而手写 {@code @Select} 一旦漏了它，已删除的行就会混进结果。
 * <p>大纲树的所有查询本来都能落进这条惯例（查树就是 {@code selectList} +
 * 两个 {@code orderBy}，改节点就是 {@code update(null, wrapper)}），
 * <b>唯一的例外是 {@link #selectByIdForUpdate}</b> ——
 * {@code SELECT ... FOR UPDATE} 是 MyBatis-Plus 的 Wrapper <b>表达不出来</b>的
 * （可以用 {@code .last("FOR UPDATE")} 拼，但 {@code last()} 是拼接原生 SQL 的口子，
 * 本项目已经因为它踩过「把用户输入拼进 LIMIT」的坑，见 P2 方案；
 * 为一个固定字符串重新打开这个口子不划算，也不利于以后审查）。
 *
 * <p>所以这里保留<b>一条</b>手写 SQL，并且它自己写了 {@code deleted = 0}。
 * 除了它之外的任何查询都不该加到这个接口里 —— 需要新查询时先问一句
 * 「Wrapper 真的表达不了吗」。
 *
 * @author MickeyChen
 */
@Mapper
public interface OutlineNodeMapper extends BaseMapper<OutlineNode> {

    /**
     * 按 id 取节点，<b>并对这一行加排他锁</b>，直到事务提交。
     *
     * <p><b>★ 它解决的是「连点两下『创建正文』」：</b>
     * 从大纲节点创建正文章节时，要先读 {@code chapter_id} 判空、
     * 再决定是新建还是走幂等分支。不加锁的话两个并发请求<b>都读到 null</b>，
     * 都去调 {@code ChapterService#create}，第二次撞
     * {@code uk_novel_chapter_no}（{@code MAX+1} 在 REPEATABLE READ 下读的是同一份快照，
     * 两个事务算出同一个序号）—— 用户看到的是
     * <b>{@code 3004「该章节序号已存在」}</b>，一句和他的动作毫无关系的话。
     * <p>锁上之后，后到的那个请求会阻塞，等第一个事务提交后读到新的
     * {@code chapter_id}，直接走幂等分支返回「已经有了」。
     *
     * <p><b>★★ 必须在事务里调用，否则锁形同虚设。</b>
     * MySQL 的 InnoDB 在<b>自动提交</b>模式下，语句一结束就释放锁 ——
     * 也就是说没有 {@code @Transactional} 的话，这个方法拿到的锁
     * 在它返回的瞬间就没了，而且<b>不会有任何报错或警告</b>，
     * 看起来一切正常，并发问题照旧。调用方
     * （{@code OutlineServiceImpl#createChapter}）上的
     * {@code @Transactional} 是这个方法能生效的<b>前提</b>，不是巧合。
     *
     * <p><b>★ 加锁顺序：本方法锁的是 {@code novel_outline} 行。</b>
     * 调用方拿到锁之后才去写 {@code chapter}。将来若有人写一个
     * <b>反过来</b>的方法（先写 chapter 再锁 outline），那就是经典的 AB-BA 死锁。
     * 第二个这样的方法出现时不会有人想起这条注释，所以在这里写死：
     * <b>涉及两张表的写操作，一律先锁 novel_outline、后写 chapter。</b>
     *
     * <p>{@code deleted = 0} 不能省（见类注释）：已逻辑删除的节点
     * 在这里必须等同于不存在，否则会锁住一行垃圾数据、
     * 并在一个已经删掉的节点上建出正文章节。
     *
     * @return 节点；不存在或已删除时返回 {@code null}（调用方负责抛 3006）
     */
    @Select("""
            SELECT *
            FROM novel_outline
            WHERE id = #{id}
              AND deleted = 0
            FOR UPDATE
            """)
    OutlineNode selectByIdForUpdate(@Param("id") Long id);
}
