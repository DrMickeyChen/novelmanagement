package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.NovelCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 分类表 Mapper。
 *
 * <p>只有「删除」这一件事需要手写 SQL —— 原因见下面那个方法的注释，
 * 是 {@code uk_name} 这个唯一键逼出来的。其余增删改查全部白送。
 *
 * @author MickeyChen
 */
@Mapper
public interface NovelCategoryMapper extends BaseMapper<NovelCategory> {

    /**
     * 逻辑删除一个分类，<b>同时把名字改掉以腾出唯一键</b>。
     *
     * <p><b>★★ 为什么不直接用 {@code deleteById}：</b>
     * {@code novel_category} 上有 {@code UNIQUE KEY uk_name (name)}，而这个键
     * <b>不包含 {@code deleted}</b>。{@code deleteById} 生成的
     * {@code UPDATE ... SET deleted = 1} 只是把行标记成已删除，
     * <b>那一行还照样占着索引里的那个名字</b>。结果就是：
     * <pre>
     *   删掉「玄幻」 → 以后再也建不出叫「玄幻」的分类 → Duplicate entry '玄幻'
     * </pre>
     * 这不是理论风险，是删一次就必然发生的。
     * <p>而且这个坑<b>只会在真的删过东西之后才复现</b> —— 本地新库怎么测都是好的，
     * 上线后管理员删了一个分类，问题才冒出来。
     *
     * <p><b>解法：删的时候顺手把名字改成带后缀的。</b>
     * {@code LEFT(name, 20)} 是为了不超长：{@code name} 是 {@code VARCHAR(50)}，
     * 后缀 {@code _del_} 占 5 字符、{@code id} 最多 20 位，20 + 5 + 20 = 45 ≤ 50，
     * 不截断的话会报 {@code Data too long for column 'name'}。
     *
     * <p><b>★ 手写 SQL 必须自己写 {@code deleted = 0}：</b>
     * {@code @TableLogic} 的自动过滤<b>只对 MyBatis-Plus 自己生成的方法生效</b>。
     * 这里少了它，重复删除一个已删除的分类会「成功」（影响行数 0 也是成功），
     * 名字被改两次。
     *
     * <p><b>代价（要记住）：</b>已删除分类的 {@code name} 不再是它的真实名字。
     * 将来若要「恢复已删除的分类」，恢复时得把后缀去掉，而且要处理
     * 「期间有人建了同名分类」的冲突。
     *
     * @return 影响行数。0 表示这个分类不存在或已被删除
     */
    @Update("""
            UPDATE novel_category
            SET deleted = 1,
                name    = CONCAT(LEFT(name, 20), '_del_', id)
            WHERE id = #{id}
              AND deleted = 0
            """)
    int logicDeleteAndFreeName(@Param("id") Long id);
}
