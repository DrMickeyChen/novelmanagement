package com.novel.modules.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.novel.modules.interaction.entity.NovelComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 评论 Mapper。
 *
 * <p>四个方法里只有一个手写 SQL（{@link #selectAdminPage}），其余走 {@code BaseMapper}。
 * 这样安排是为了让「哪一条查询不受 {@code @TableLogic} 保护」这件事
 * <b>一眼可见</b> —— 手写 SQL 在本项目里是有含义的，不是随手写的。
 *
 * @author MickeyChen
 */
@Mapper
public interface NovelCommentMapper extends BaseMapper<NovelComment> {

    /**
     * 管理端评论列表。<b>★★ 这一条查询【故意】不带 {@code deleted = 0}。</b>
     *
     * <p><b>为什么要看得见已删除的评论：</b>
     * 「读者的某条评论不见了」是一类真实的管理诉求 —— 有人投诉一条评论，
     * 管理员打开列表却找不到它，因为评论人自己先删了。
     * <b>「读者看不见了」和「无从查证」是两件事</b>，
     * 逻辑删除的意义正是「留痕」，管理端是这个「痕」唯一的用处。
     * <p>而且这个诉求不可能用 MP 的 wrapper 实现：
     * <ul>
     *   <li>{@code selectPage(wrapper)} 生成的 SQL 由 MP 的注入器拼，
     *       它会<b>无条件</b>加上 {@code deleted = 0}（这是 {@code @TableLogic} 的机制），
     *       wrapper 里没有任何开关能关掉它 ——
     *       写 {@code .eq(NovelComment::getDeleted, 1)} 也只是再加一个
     *       {@code deleted = 1}，和自动那个 AND 在一起永远是空集；</li>
     *   <li>所以「绕开 {@code @TableLogic}」只能靠手写 SQL。</li>
     * </ul>
     * <p><b>★ 这是全项目唯一一处刻意不写 {@code deleted = 0} 的手写查询。</b>
     * P2 定的规矩是「手写 SQL 必须自己补 {@code deleted = 0}」，那条规矩针对的是
     * 「查的是正常数据、但忘了补」。这里方向正好相反：查到已删除的行<b>就是需求本身</b>。
     * 读到这里的人请先确认这一点，再决定要不要「顺手补上」——
     * 补上不会报错，只会让这个接口悄悄退化成和 MP 自动生成的一模一样。
     *
     * <p><b>★ 关于 {@code <script>}：</b>三个筛选条件都可选，用
     * {@code <script>} 让 MyBatis 按 XML 的方式拼动态 SQL（这是它给注解方式留的口子）。
     * {@code <where>} 会自动吃掉开头的 {@code AND}，所以每个 {@code <if>} 里
     * 都可以放心从 {@code AND} 写起。
     *
     * <p><b>★ 分页和 count 由 {@code PaginationInnerInterceptor} 负责</b>：
     * 它拿到上面拼好的最终 SQL，用 JSqlParser 生成
     * {@code SELECT COUNT(*) FROM novel_comment WHERE ...}（并自动去掉 ORDER BY）。
     * 所以 {@code status} / {@code novelId} / {@code keyword} 的筛选在
     * 「总数」和「当页数据」上是同一套条件，不会出现「显示共 30 条但翻到第 3 页是空的」。
     *
     * <p><b>★★ 按作品筛选走的是 {@code target_id}，而【不是】{@code novel_id}
     * —— 这张表里根本没有 {@code novel_id} 这一列。</b>
     * 它存的是「评论挂在谁身上」：{@code target_type = 1} 时是作品 id，
     * {@code = 2} 时是章节 id（见建表语句的
     * {@code target_type '1书评 2章评'} 与 {@code target_id 'novel_id 或 chapter_id'}）。
     * <p>这两个条件必须<b>一起</b>写，缺了 {@code target_type = 1} 的话，
     * 「作品 105 的评论」会把「章节 105 的章评」一起捞出来 ——
     * 而两边的 id 空间是独立的，撞号是常态，且<b>结果看起来完全正常</b>
     * （就是多了几条不相干的评论）。
     * <p>顺带：条件顺序和索引 {@code idx_target (target_type, target_id, status)}
     * 的前缀一致，所以这个筛选是走索引的，不是全表扫。
     * <p>★ 这个坑是 S9 验收时真的踩到的：写成了 {@code novel_id = #{novelId}}，
     * MySQL 报 {@code Unknown column 'novel_id'} → 接口 500。
     * 而<b>不带这个筛选条件时一切正常</b>（另外两条查询都没问题），
     * 所以只有「按作品筛」这一个入口会挂。
     *
     * <p><b>★ 按 {@code id} 倒序而不是 {@code create_time}：</b>
     * 同一秒里发出的两条评论用时间排是不稳定的，翻页时会出现
     * 「某一条重复出现、另一条永远看不到」（同 {@code AdminUserController} 的理由）。
     * {@code novel_comment.id} 是自增主键，倒序就是「最新评论在最上面」，
     * 而且严格全序。
     *
     * @param page    分页参数。★ 必须是【第一个】参数，MP 靠它识别这是分页查询
     * @param status  状态筛选，{@code null} = 全部（含已删除）
     * @param novelId 作品 id 筛选，{@code null} = 全部。
     *                只筛<b>书评</b>（{@code target_type = 1}）—— 章评不参与，
     *                理由见下面那条 ★★
     * @param keyword 正文关键词，{@code null} / 空串 = 不筛
     */
    @Select("""
            <script>
            SELECT * FROM novel_comment
            <where>
                <if test="status != null"> AND status = #{status} </if>
                <if test="novelId != null">
                    AND target_type = 1 AND target_id = #{novelId}
                </if>
                <if test="keyword != null and keyword != ''">
                    AND content LIKE CONCAT('%', #{keyword}, '%')
                </if>
            </where>
            ORDER BY id DESC
            </script>
            """)
    IPage<NovelComment> selectAdminPage(IPage<NovelComment> page,
                                        @Param("status") Integer status,
                                        @Param("novelId") Long novelId,
                                        @Param("keyword") String keyword);

    /**
     * 按 id 取评论，<b>包括已被逻辑删除的</b>。
     *
     * <p><b>★ 只给「删除自己的评论」用，为的是区分两个错误码：</b>
     * <pre>
     *   4001 评论不存在      —— 这一行根本不存在
     *   4003 评论已经被删过  —— 存在，但 deleted = 1（上一次点击其实成功了）
     * </pre>
     * {@code selectById}（带 {@code @TableLogic}）会把第二种也当成「不存在」，
     * 于是 4003 这个码<b>永远不可能被触发</b> —— 一个建好了却用不上的错误码，
     * 比没有这个码更糟：读代码的人会以为它被用着。
     *
     * <p><b>★★ 这是全项目第二处刻意不写 {@code deleted = 0} 的手写查询</b>
     * （第一处是 {@link #selectAdminPage}）。两处的理由不同，别记混：
     * <ul>
     *   <li>{@code selectAdminPage} —— 管理端<b>要列出</b>已删除的评论（查证用）；</li>
     *   <li>本方法 —— 只为了把「已删除」和「不存在」<b>分辨出来</b>，
     *       好返回一个更准确的错误码。它取到的行只用于判断，不会返回给前端。</li>
     * </ul>
     *
     * <p><b>★ 这个方法是安全的，因为它不改变可见性口径：</b>
     * 调用方 {@code CommentServiceImpl#delete} 拿到行之后
     * <b>先做归属校验</b>（不是自己的直接 4002），
     * 所以对「别人的评论」无论删没删都返回同一个码 ——
     * 这个查询不会变成一份「哪些评论被删过」的探针。
     * <b>把归属校验挪到「是否已删除」之后，这个性质立刻消失。</b>
     */
    @Select("SELECT * FROM novel_comment WHERE id = #{id}")
    NovelComment selectByIdIncludingDeleted(@Param("id") Long id);

    /*
     * ================================================================
     *  ★ 为什么这里【没有】给审核日志补正文用的方法
     * ================================================================
     * 那个需求（{@code AuditLogServiceImpl} 要给评论流水补一个「标题」）
     * 用的是 {@code BaseMapper#selectByIds}，它已经在上面继承下来了 ——
     * 评论没有「标题」这一列，正文开头十几个字就是它的名字，
     * 而 {@code selectByIds} 返回的实体里就有 {@code content}。
     *
     * ★ 更关键的是那道绕路：调 {@code selectByIds} 的是
     * {@code modules/interaction} 里的一个实现类，它实现的是
     * {@code modules/content} 定义的 {@code CommentTextLookup} 接口 ——
     * 于是依赖方向是 interaction → content，而不是反过来。
     * 如果让 {@code AuditLogServiceImpl} 直接注入本 Mapper，
     * 就造出了一条 content → interaction，M3 摘 interaction 时
     * 会和 interaction → content 撞成双向依赖（见 D-1）。
     *
     * ★ 所以【不要】在这里加一个「给日志用」的方法 ——
     * 那个方法一旦存在，下一个人就会绕过接口直接调它。
     * 声明一个没有 @Select / XML 的抽象方法更糟：MyBatis 启动时
     * 会报 Invalid bound statement (not found)，而且是启动即失败。
     */
}
