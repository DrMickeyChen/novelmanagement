package com.novel.internal.service;

import com.novel.common.internal.CommentBriefDTO;
import com.novel.modules.interaction.entity.NovelComment;
import com.novel.modules.interaction.mapper.NovelCommentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「按评论 id 取它在审核日志里用得上的素材」—— <b>interaction 侧对 content 的「回答」。</b>
 *
 * <h3>★★ M2：这段逻辑是从 {@code CommentTextLookupImpl} 原样搬过来的</h3>
 *
 * <p>改造前它是一个实现了 content 接口（{@code CommentTextLookup}）的
 * {@code @Component}，靠同进程 DI 把答案递过去。M2 之后两个模块在两个进程里，
 * 原来的类<b>被拆成了两半</b>：
 * <ul>
 *   <li><b>接口那一半</b>留在 content（消费方），实现换成一次 Feign 调用 ——
 *       铁律 1 的 M1 版表述：<b>业务语义的接口住在消费方，
 *       提供方只暴露 URL，不暴露类型。</b></li>
 *   <li><b>这段逻辑</b>（本类）留在 interaction（提供方），因为
 *       {@code novel_comment} 是 interaction 的表。<b>数据在谁那儿，查询就归谁写。</b></li>
 * </ul>
 * <p>★ 改造前那份 {@code CommentTextLookupImpl} <b>必须删掉</b>，不能留着 ——
 * 留着就是一个「能编译、能被扫描、永远不会被调用」的死 bean，
 * 正是铁律 1 点名的那种东西。它没有任何报错，只会让下一个读代码的人
 * 以为这条接缝还是同进程的。
 *
 * <h3>★ 为什么这个类叫 QueryService 而不是 Controller 里直接写</h3>
 *
 * <p>同 auth 的 {@code InternalUserQueryService}：Controller 的职责是
 * 「把 HTTP 翻译成方法调用」，而下面这段里带着取舍（空集合短路、
 * 截断长度、为什么用 {@code selectByIds}），那些属于数据访问。
 * 放进 Controller 会在下一次改接口时被顺手删掉。
 *
 * <h3>★ 它为什么只回答「事实」，不回答「版式」</h3>
 *
 * <p>见 {@link CommentBriefDTO} 的类注释 —— 那里记着 P6 修掉一个 Bean 环的完整过程，
 * 以及 M2 之后这条论证为什么更强（从「Bean 环」升级成「一次真实的往返网络调用」）。
 * <p>一句话：<b>位置拼接（{@code 《书名》第N章 ·}）是审核日志页的版式，
 * 属于消费方；这里只说「这条评论说了什么、挂在什么对象上」。</b>
 *
 * <p><b>★ 截断长度 {@value #MAX_TEXT_LENGTH} 字</b>：审核日志页的一行大约能放
 * 二十来个汉字，取 30 留一点余量给省略号，同时保证一条 500 字的评论
 * 不会把那一行撑成三行高、把表格的其它行挤歪。这个数字只在这一个地方出现。
 *
 * <h3>★★★ E1：本类有【两个】出口，而它们是两种截然不同的东西</h3>
 *
 * <p>同一个 Controller、同一张表、同一把主键索引，但<b>两个方法服务于两个目的，
 * 而且失败语义相反</b>：
 * <pre>
 *   briefsOf()  → 审核日志的【一个展示格】   → 截断到 30 字，本来就是给人扫一眼的
 *   textsOf()   → AI 违禁审核的【输入】      → 全文，一个字节都不能少
 * </pre>
 * ★ <b>为什么不把 {@code comment-briefs} 加个参数放大长度</b>：
 * <ol>
 *   <li>那个 30 是<b>展示契约</b>，不是技术限制。加参数等于把「截多少」
 *       这个决定推给每一个调用方 —— 而它现在只在一个地方出现；</li>
 *   <li>更实际的是流量：一页 100 条评论、每条 500 字，为了渲染一行
 *       要多传 50KB 过网；</li>
 *   <li>而<b>最要紧的是失败语义</b>：截断过的正文送进审核，AI 看不到后半段
 *       —— 一条把敏感词写在第 31 个字之后的评论会被判成「无风险」，
 *       且<b>没有任何一处报错</b>。这是铁律 8 那条「不会失败的护栏」的
 *       又一形态：判据（前 30 字干净）永远为真。</li>
 * </ol>
 * ★★ 所以宁可多一个端点，也不让「展示用的截断」和「审核用的输入」共用一条路。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalCommentQueryService {

    /** 正文部分的最大字数。见类注释 */
    private static final int MAX_TEXT_LENGTH = 30;

    private final NovelCommentMapper commentMapper;

    /**
     * 批量取评论素材，返回 {@code commentId → 素材}。
     *
     * <p>★ 这是 M2 之前 {@code CommentTextLookupImpl#briefsOf} 的<b>逐行搬迁</b> ——
     * 逻辑一个字节没改，只有返回类型从 content 的 {@code CommentBrief}
     * 换成了 common 的 {@link CommentBriefDTO}（同一个 record，改了名和包）。
     *
     * @param commentIds 评论 id 集合，允许为空
     */
    public Map<Long, CommentBriefDTO> briefsOf(Collection<Long> commentIds) {
        /*
         * ★ 空集合短路。不短路的话 selectByIds(空集合) 会生成
         *   WHERE id IN ()，那是 SQL 语法错误 —— 注意它【不是】返回空列表，
         *   是直接抛异常。而这里的入参来自「一页审核日志里恰好没有 COMMENT 类型」
         *   这个非常正常的情况，所以短路是必须的，不是优化。
         *   ★ M2 之后这一条更值钱：它挡掉的是一次跨服务往返。
         */
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }

        /*
         * ★ 用 selectByIds（BaseMapper 自带）而不是手写 @Select：
         *   ① 手写就得自己记得补 deleted = 0，忘了的话已删除的评论会把正文补出来 ——
         *      而审核日志里「（已删除）」这个标记正是靠补不出来才显示出来的；
         *   ② MP 3.5.9 里 selectBatchIds 已经 @Deprecated（内部转调 selectByIds），
         *      用新的那个。
         *   ★ 返回完整实体（所有列）是有一点浪费，但一页日志最多几十个 id，
         *   走主键索引，早就不用在意这点列宽了。
         */
        List<NovelComment> comments = commentMapper.selectByIds(commentIds);

        Map<Long, CommentBriefDTO> result = new LinkedHashMap<>();
        for (NovelComment comment : comments) {
            /*
             * ★ 只判一次「是不是章评」，然后二选一填一个可空 id ——
             *   而不是把 targetType 原样交给消费方去 switch：
             *   那等于让 content 反过来理解 novel_comment 的表内取值。
             */
            boolean chapterComment = isChapterComment(comment);
            result.put(comment.getId(), new CommentBriefDTO(
                    truncate(comment.getContent()),
                    chapterComment ? null : comment.getTargetId(),
                    chapterComment ? comment.getTargetId() : null));
        }
        return result;
    }

    /**
     * 批量取评论<b>正文全文</b>（不截断），返回 {@code commentId → 正文} —— <b>E1 新增。</b>
     *
     * <p>唯一的调用方是 content 的 AI 违禁审核消费者（{@code AiAuditService}）。
     * 它要的是<b>原文本身</b>：关键词层要在整段文字上跑 DFA，
     * AI 层要把整段送给模型 —— 截断过任何一个字，这条判定就不再是对这篇内容说的。
     *
     * <p>★ <b>今天每次只传一个 id</b>（MQ 一条消息带一个 {@code targetId}），
     * 但仍然做成批量：它与 {@link #briefsOf} <b>逐字同形</b> ——
     * 同一张表、同一把主键索引、同一个「空集合短路」的坑。
     * 做成两个形状等于把同一条查询写两遍，而第二遍一定会有人忘了那个短路。
     *
     * <p>★★ <b>不截断、不兜底、不降级</b>：拿不到正文就必须让调用方看见失败。
     * 空正文在审核链上的后果是「没有命中任何词」→ 被机器判成「排除」——
     * 一句<b>对一条从没被审过的评论说的「通过」</b>。
     *
     * @param commentIds 评论 id 集合，允许为空（空集合直接返回空 Map，
     *                   ★ 不短路的话 {@code selectByIds(空)} 生成 {@code WHERE id IN ()}，
     *                   那是 SQL 语法错误，不是空结果）
     * @return {@code commentId → 正文全文}。<b>查不到的 id 不会出现在 Map 里</b>
     *         （{@code selectByIds} 走 {@code @TableLogic}，逻辑删除的行被滤掉），
     *         调用方按「这条评论已不存在」处理
     */
    public Map<Long, String> textsOf(Collection<Long> commentIds) {
        // ★ 与 briefsOf 同一个短路，理由见上面 @param（这里挡掉的同样是一次跨服务往返）
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }

        List<NovelComment> comments = commentMapper.selectByIds(commentIds);

        Map<Long, String> result = new LinkedHashMap<>();
        for (NovelComment comment : comments) {
            /*
             * ★ 原样取 content，不做判空兜底：那一列是 NOT NULL。
             *   为「一个不可能出现的值」写一条策略，只会多出一个没人能测的分支
             *   （briefsOf 里那句判空是给展示用的 —— 那里的代价是整页 500，
             *   这里没有对应的问题）。
             */
            result.put(comment.getId(), comment.getContent());
        }
        return result;
    }

    /**
     * ★ 用 {@code Integer.valueOf(...).equals(...)} 而不是 {@code ==}：
     * {@code targetType} 是包装类型，直接和 {@code int} 常量比会拆箱，
     * 而这一列虽然 {@code NOT NULL}，实体字段仍可能因为某个查询的
     * {@code select()} 投影而没有它（{@code docs/P5} 实施记录 ⑥ 的同类坑）。
     */
    private boolean isChapterComment(NovelComment comment) {
        return Integer.valueOf(NovelComment.TARGET_TYPE_CHAPTER).equals(comment.getTargetType());
    }

    /**
     * 截断到 {@link #MAX_TEXT_LENGTH} 字，超出部分用省略号。
     *
     * <p>★ <b>用 {@code String.length()} 而不是按「显示宽度」算</b>：
     * 一个汉字和一个字母在这里都算一个字，所以中文评论大约截到 30 个汉字、
     * 英文评论能显示 30 个字母。差别存在，但为它引入「汉字算两个宽度」
     * 这套逻辑，换来的是「英文评论看起来短一点」—— 不值得。
     *
     * <p>★ {@code content != null} 的判断不能省：列是 {@code NOT NULL}，
     * 但那是「今天」的保证。少一个判空，代价是以后某次迁移里
     * 一条 null 就让整个审核日志页 500。
     */
    private String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= MAX_TEXT_LENGTH
                ? content
                : content.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
