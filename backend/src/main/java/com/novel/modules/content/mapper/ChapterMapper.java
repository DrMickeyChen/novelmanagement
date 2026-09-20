package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.Chapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 章节表 Mapper。
 *
 * <p>这个 Mapper 是 P2 里手写 SQL 最多的地方（3 条），因为没有一条能用
 * {@code LambdaQueryWrapper} 干净地表达。★ <b>每一条都自己写了 {@code deleted = 0}</b> ——
 * {@code @TableLogic} 的自动过滤只对 MyBatis-Plus 自己生成的方法生效，
 * 手写 SQL 漏了它，已删除的章节就会混进结果里。
 *
 * @author MickeyChen
 */
@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> {

    /**
     * 取某本作品当前最大的章节序号，用于给新章节分配 {@code MAX + 1}。<b>查不到返回 {@code null}</b>。
     *
     * <p>调用方要判空 —— 一本书的第一章没有「上一个序号」，
     * 直接把 {@code null} 拆箱成 int 会 NPE。
     *
     * <p>{@code deleted = 0} 不能省：已删除章节的序号被改成了 {@code -id}（见
     * {@link #logicDeleteAndFreeChapterNo}），不排除掉的话 {@code MAX} 会把它们
     * 也考虑进去，新章号会无谓地跳号。
     */
    @Select("""
            SELECT MAX(chapter_no)
            FROM chapter
            WHERE novel_id = #{novelId}
              AND deleted = 0
            """)
    Integer selectMaxChapterNo(@Param("novelId") Long novelId);

    /**
     * 统计某本作品<b>已发布</b>章节的总字数，用于重算 {@code novel.word_count}。
     *
     * <p>{@code COALESCE(..., 0)} 不能省：一本书一章都没发布时 {@code SUM} 返回的是
     * {@code NULL}，映射到 Java 的 {@code Integer} 就是个 {@code null}，
     * 往 {@code novel.word_count}（{@code NOT NULL} 列）里写会直接报错。
     *
     * <p>条件 {@code (novel_id, status)} 正好命中 {@code idx_novel_status} 索引。
     */
    @Select("""
            SELECT COALESCE(SUM(word_count), 0)
            FROM chapter
            WHERE novel_id = #{novelId}
              AND status = 2
              AND deleted = 0
            """)
    Integer sumPublishedWordCount(@Param("novelId") Long novelId);

    /**
     * 逻辑删除一章，<b>同时把序号改成负数以腾出唯一键</b>。
     *
     * <p><b>★★ 这是本项目最危险的一个约束，务必看懂再改：</b>
     * {@code chapter} 上有 {@code UNIQUE KEY uk_novel_chapter_no (novel_id, chapter_no)}，
     * 它<b>不包含 {@code deleted}</b>。{@code deleteById} 生成的
     * 「{@code SET deleted = 1}」只标记了行，<b>那一行还占着索引里的
     * {@code (novel_id, chapter_no)}</b>。于是：
     * <pre>
     *   作者删掉草稿「第 5 章」 → 以后这本书再也建不出第 5 章
     *                          → Duplicate entry '3-5' for key 'uk_novel_chapter_no'
     * </pre>
     * 删一次就必然复现。而且它<b>只在真的删过章节之后才出现</b>，
     * 本地新库无论怎么测都是通的。
     *
     * <p><b>为什么改成 {@code -id} 就一定不冲突：</b>
     * <ul>
     *   <li>{@code id} 是自增主键、全局唯一 → 所有 {@code -id} 两两不同</li>
     *   <li>存活章节的 {@code chapter_no} <b>永远是正数</b>（分配逻辑只给正数），
     *       所以负数段和存活行不可能撞上</li>
     * </ul>
     * 顺带一提，{@code novel} 表没有章节序号这种唯一键，所以删作品不需要这一招。
     *
     * <p><b>★ 代价（必须记住）：</b>已删除章节的 {@code chapter_no} 不再等于它的真实序号。
     * 将来若要做「回收站 / 恢复已删除章节」，<b>恢复时必须重新分配序号</b> ——
     * 不能简单把负数取回正数，因为删除期间可能已经有人建了同号的章节。
     *
     * @return 影响行数。0 表示这一章不存在或已被删除
     */
    @Update("""
            UPDATE chapter
            SET deleted = 1,
                chapter_no = -id
            WHERE id = #{id}
              AND deleted = 0
            """)
    int logicDeleteAndFreeChapterNo(@Param("id") Long id);
}
