package com.novel.modules.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.novel.modules.interaction.entity.NovelComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评论 Mapper。
 *
 * <p>手写 SQL 的只有三条：{@link #selectAdminPage}（要看得见已删除的）、
 * {@link #selectByIdIncludingDeleted}（要分辨「已删除」和「不存在」）、
 * {@link #avgScoreOfNovel}（聚合），其余走 {@code BaseMapper}。
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
     * <p>★ 唯一的例外是下面那个「待审核」分支，它<b>必须</b>补 {@code deleted = 0}，
     * 理由见那段 {@code <if>} 上的注释。别把这两处看成自相矛盾：
     * 一处是<b>检索</b>（要能找到），一处是<b>队列</b>（要能处理）。
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
     * <p>★ 这个坑是 S9 验收时真的踩到的：写成了 {@code novel_id = #{novelId}}，
     * MySQL 报 {@code Unknown column 'novel_id'} → 接口 500。
     * 而<b>不带这个筛选条件时一切正常</b>（另外两条查询都没问题），
     * 所以只有「按作品筛」这一个入口会挂。
     *
     * <p><b>★★ P6 加章评时这里必须跟着改，否则「按作品筛」会静默少一半。</b>
     * 章评的 {@code target_id} 是 <b>chapterId</b>，和作品 id 是两个独立的
     * id 空间、撞号是常态，所以不能简单地写成「{@code target_id = novelId}」——
     * 那会把「章节 105 的章评」当成「作品 105 的评论」捞出来。
     * 条件是两项相或：书评直接比 target_id，章评要比一本作品的章节 id 集合。
     * <p>★ 章节 id 那半边<b>刻意不写 {@code deleted = 0}</b>
     * （手写 SQL，{@code @TableLogic} 在这里完全不生效，写不写全看这一行的意图）。
     * 理由和外面那条「故意不滤评论的 deleted」是同一个取向：
     * 「这本书的评论」问的是<b>这本书曾经有过哪些评论</b>，
     * 章节被作者删掉之后那些章评仍然属于这本书 ——
     * 而管理员打开这个筛选，往往正是要查那些说不清来源的旧评论。
     * ★ 这个「不写 deleted = 0」的口径在 M2 之后由
     * {@code ChapterMapper#idsOfNovel} 承载（子查询拆成了两次查询），
     * 见那边的注释。
     * <p>★ <b>少了整段 OR 的症状是「筛出来有东西，只是少了一半」</b>：
     * 不报错、不空、条数还挺合理，除非有人特意去数，否则永远发现不了。
     * 这是本模块最容易写错、且错了不报错的一处。
     *
     * <h3>★★ M2：那段 {@code SELECT id FROM chapter} 子查询被拆成了两次查询</h3>
     *
     * <p>{@code chapter} 是 <b>content 的表</b>，而 {@code novel_comment} 是
     * interaction 的表。M2 把两个模块拆成了两个进程之后，这条 SQL
     * <b>是本模块里唯一一处真正的表归属违规</b>（决定 1：一个 schema +
     * 表归属独占）。于是它必须先跨服务把章节 id 拿回来
     * （{@code ContentGateway#chapterIdsOfNovel}），再用 {@code <foreach>}
     * 展开成 {@code IN (...)} 传进来。
     *
     * <p><b>★★ 空集合不能走 {@code IN ()}，但也【不能】直接返回空页。</b>
     * 直觉上「这本书没有章节 → 没有章评 → 空页」是对的，<b>但它是错的</b>：
     * 一本已上架、<b>一章都还没发</b>的新书完全可以有书评
     * （书评走的是 {@code target_type = 1}，不经过章节）。
     * 直接返回空页会把那些书评一起吞掉 —— 这正是「静默少一半」的翻版。
     * <p>所以下面用 {@code <if>} 把<b>整段 OR 分支</b>包起来：
     * 章节 id 为空时它整个消失，条件退化成「只要书评」——
     * <b>和原来那个空子查询的语义逐字相同</b>（空子查询也匹配不到任何行）。
     * <p>★ 这个「退化」也意味着：<b>{@code novelId} 非空时
     * {@code chapterIds} 必须是【非 null】的集合</b>，否则会静默退化成
     * 只查书评。调用方（{@code CommentServiceImpl#adminPage}）负责这件事，
     * 空结果用 {@code List.of()} 表达而不是 {@code null}。
     *
     * <p>顺带：书评那一项的条件顺序和索引 {@code idx_target (target_type, target_id, status)}
     * 的前缀一致，所以它仍然走索引；章评那一项从子查询换成了
     * {@code IN} 常量列表，走主键索引。
     *
     * <p><b>★ 按 {@code id} 倒序而不是 {@code create_time}：</b>
     * 同一秒里发出的两条评论用时间排是不稳定的，翻页时会出现
     * 「某一条重复出现、另一条永远看不到」（同 {@code AdminUserController} 的理由）。
     * {@code novel_comment.id} 是自增主键，倒序就是「最新评论在最上面」，
     * 而且严格全序。
     *
     * @param page    分页参数。★ 必须是【第一个】参数，MP 靠它识别这是分页查询
     * @param status  状态筛选，{@code null} = 全部（含已删除）。
     *                ★ 传 {@code 0}（待审核）时会**额外排除已删除的**，见那段 {@code <if>}
     * @param novelId 作品 id 筛选，{@code null} = 全部。
     *                <b>书评和这本书下面的章评都会返回</b> —— 见下面那条 ★★
     * @param chapterIds 该作品的章节 id 集合，<b>由调用方跨服务取</b>
     *                （{@code ContentGateway#chapterIdsOfNovel}）。
     *                ★ <b>{@code novelId} 非空时它必须非 null</b>；空集合用
     *                {@code List.of()} 表示，此时章评那一项整体消失。
     *                {@code novelId} 为 {@code null} 时本参数被忽略（可以传 null）
     * @param keyword 正文关键词，{@code null} / 空串 = 不筛
     */
    @Select("""
            <script>
            SELECT * FROM novel_comment
            <where>
                <if test="status != null"> AND status = #{status} </if>
                <!--
                  ★★ 「待审核」这一档必须额外滤掉已删除的（P6 S8 发现）。

                  一条评论被评论人自己删掉时，它的 `status` 原封不动 ——
                  「待审核时被作者反悔删掉」是完全正常的一条路径，
                  于是这些行**永远留在待审队列里**，而且永远处理不了
                  （审核走 selectById，对它们返回 4001，所以按钮是禁用的）。

                 这不是检索视图，是**队列**：待审核的意思是「等着我处置」，
                  而作者已经撤回的评论不等着任何人。检索视图是「全部状态」，
                  那一档P5 定的「要能看到已删除的」照旧有效。

                  ★ 少了这一行的症状：队列越用越脏，而脏行看起来和正常行一样，
                  只是点了没反应（按钮禁用 + 悬浮说明能解释，但没人会去读）。
                -->
                <if test="status != null and status == 0"> AND deleted = 0 </if>
                <if test="novelId != null">
                    AND (
                        (target_type = 1 AND target_id = #{novelId})
                        <!--
                          ★★ M2：这一段原来是内联的子查询
                             `target_id IN (SELECT id FROM chapter WHERE novel_id = ?)`，
                             chapter 是 content 的表，拆服务之后读不到了。
                             章节 id 改由调用方跨服务取好传进来。

                          ★ 整段（含 OR）必须一起 <if> 掉，不能只 <if> 里面的 foreach：
                            空集合会生成 `IN ()`，那是**语法错误**（不是「返回空」，
                            是直接抛异常 → 一本书都没章节时管理端评论页 500）。
                          ★ 但也不能因此就「空集合直接返回空页」——
                            那样会把这本书的**书评**一起吞掉（书评不经章节）。
                            整段消失 = 条件退化成「只要书评」，
                            和原来那个空子查询的行为逐字一致。
                        -->
                        <if test="chapterIds != null and chapterIds.size() > 0">
                        OR
                        (target_type = 2 AND target_id IN
                            <foreach collection="chapterIds" item="chapterId"
                                     open="(" separator="," close=")">
                                #{chapterId}
                            </foreach>
                        )
                        </if>
                    )
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
                                        @Param("chapterIds") List<Long> chapterIds,
                                        @Param("keyword") String keyword);

    /**
     * 某本书的<b>平均分</b>，给 {@code novel.score} 用。没有可计分的书评时返回 {@code 0}。
     *
     * <p><b>★★ 四个条件一个都不能少</b>：
     * {@code target_type = 1}（章评的 {@code target_id} 是 chapterId，和作品 id
     * <b>是两个 id 空间、撞号是常态</b> —— 少了它就是「拿章节 105 的分养作品 105」）；
     * {@code status = 1}（待审和被隐藏的书评不能加分）；{@code score IS NOT NULL}
     * （书评可以不打分）；{@code deleted = 0}（手写 SQL，
     * {@code @TableLogic} 在这里完全不生效 —— 漏了它，被删掉的书评会一直算在平均分里，
     * 而<b>分数看起来永远合理</b>，除非有人特意删过一条带分的书评）。
     *
     * <p>★ {@code IFNULL} 不能省：一行都没匹配上时 {@code AVG} 返回 null，
     * 而 {@code novel.score} 是 {@code NOT NULL DEFAULT 0.0}。
     */
    @Select("""
            SELECT IFNULL(ROUND(AVG(score), 1), 0) FROM novel_comment
            WHERE target_type = 1 AND target_id = #{novelId}
              AND status = 1 AND score IS NOT NULL AND deleted = 0
            """)
    BigDecimal avgScoreOfNovel(@Param("novelId") Long novelId);

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
     * 那个需求（content 的 {@code AuditLogServiceImpl} 要给评论流水补一个「标题」）
     * 用的是 {@code BaseMapper#selectByIds}，它已经在上面继承下来了 ——
     * 评论没有「标题」这一列，正文开头十几个字就是它的名字，
     * 而 {@code selectByIds} 返回的实体里就有 {@code content}。
     *
     * ★ 调用它的是 {@link com.novel.internal.service.InternalCommentQueryService}，
     * 而那个类是【本服务以 HTTP 回答 content】的落点 ——
     * 「数据在谁那儿，查询就归谁写」，所以这段逻辑在 interaction 这一侧。
     *
     * <p><b>★★ M2 把这条依赖的方向翻了个面，值得记一笔。</b>
     * 拆服务之前，调 {@code selectByIds} 的是 interaction 里一个
     * <b>实现了 content 的 {@code CommentTextLookup} 接口</b>的类，
     * 方向是 interaction → content。现在接口和它的实现<b>都</b>住在 content
     * （实现里只有一次 Feign 调用），本服务只暴露
     * {@code POST /internal/comment-briefs} 这个 URL，
     * <b>根本不知道那个接口存在</b> —— 这正是铁律 1 的 M1 版表述。
     * ★ 也就是说：这一段原来写的理由是「绕开一次 interaction → content」，
     * 现在那条边反过来了；<b>结论没变，理由换了一条</b>。
     *
     * ★ 所以【不要】在这里加一个「给日志用」的方法 ——
     * 那个方法一旦存在，下一个人就会绕过接缝直接调它。
     * 而 M2 之后 {@code AuditLogServiceImpl} 住在 content 进程里，
     * 本来也看不到本 Mapper：要调它只能再造一次跨服务调用，
     * 那还不如直接用现有的那条。
     * 声明一个没有 @Select / XML 的抽象方法更糟：MyBatis 启动时
     * 会报 Invalid bound statement (not found)，而且是启动即失败。
     */
}
