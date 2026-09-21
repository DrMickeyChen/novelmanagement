package com.novel.modules.interaction.service.impl;

import com.novel.modules.content.service.CommentBrief;
import com.novel.modules.content.service.CommentTextLookup;
import com.novel.modules.interaction.entity.NovelComment;
import com.novel.modules.interaction.mapper.NovelCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CommentTextLookup} 的实现 —— <b>interaction 侧对 content 的「回答」。</b>
 *
 * <p><b>★★ 这个类存在的全部意义，是让依赖方向指向 interaction → content 而不是反过来。</b>
 * 接口在 {@code modules/content/service}（消费方），实现在这里（提供方），
 * 于是 {@code content} 的代码里<b>找不到任何对 interaction 的 import</b>。
 * 二期 M3 把 interaction 拆成独立服务时，被替换掉的只有这个类，
 * {@code AuditLogServiceImpl} 一行都不用动。
 *
 * <p><b>★ 为什么不是把类做成 {@code @Service}：</b>
 * 它不是业务服务，没有对应的业务接口给 Controller 用 ——
 * 它是一个<b>适配器</b>（端口-适配器里的适配器）。
 * {@code @Component} 是准确的：它是个普通的 Spring bean，
 * 只是碰巧实现了另一个模块定义的接口。
 * <p>用 {@code @Service} 也不会出错，但会让人以为「这里有个业务服务」，
 * 而去 {@code service} 包里找一个叫 {@code CommentTextLookup} 的业务接口 ——
 * 然后发现那个接口在 {@code content} 包里，白跑一趟。
 *
 * <p><b>★ 它为什么不去实现 {@code CommentService}、不放在 {@code service/} 下：</b>
 * 见上面那条。这个类只做「commentId → 审核日志要的素材」一件小事，
 * 和 {@link CommentServiceImpl} 那十个业务方法没有任何关系。
 *
 * <p><b>★ P6 起这个类【变小了】——它不再自己拼位置串。</b>
 * 拼「《书名》第N章 · 正文」要查 novel / chapter，而那两个查询一注入进来
 * 就绕出一个 Bean 环（{@code AuditLogServiceImpl} →
 * 本类 → {@code NovelServiceImpl} → {@code AuditLogService}）。
 * 环的根因是位置拼接站错了边，改法见 {@link CommentBrief} 的注释：
 * <b>本类只回答表内的事实（正文 + 挂在什么对象上），版式留给消费方。</b>
 *
 * <p><b>★ 截断长度 {@value #MAX_TEXT_LENGTH} 字</b>：审核日志页的一行大约能放
 * 二十来个汉字，取 30 留一点余量给省略号，同时保证一条 500 字的评论
 * 不会把那一行撑成三行高、把表格的其它行挤歪。这个数字只在这一个地方出现。
 *
 * @author MickeyChen
 */
@Component
@RequiredArgsConstructor
public class CommentTextLookupImpl implements CommentTextLookup {

    /** 正文部分的最大字数。见类注释 */
    private static final int MAX_TEXT_LENGTH = 30;

    private final NovelCommentMapper commentMapper;

    @Override
    public Map<Long, CommentBrief> briefsOf(Collection<Long> commentIds) {
        /*
         * ★ 空集合短路。不短路的话 selectByIds(空集合) 会生成
         *   WHERE id IN ()，那是 SQL 语法错误 —— 注意它【不是】返回空列表，
         *   是直接抛异常。而这里的入参来自「一页审核日志里恰好没有 COMMENT 类型」
         *   这个非常正常的情况，所以短路是必须的，不是优化。
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

        Map<Long, CommentBrief> result = new LinkedHashMap<>();
        for (NovelComment comment : comments) {
            /*
             * ★ 只判一次「是不是章评」，然后二选一填一个可空 id ——
             *   而不是把 targetType 原样交给消费方去 switch：
             *   那等于让 content 反过来理解 novel_comment 的表内取值。
             */
            boolean chapterComment = isChapterComment(comment);
            result.put(comment.getId(), new CommentBrief(
                    truncate(comment.getContent()),
                    chapterComment ? null : comment.getTargetId(),
                    chapterComment ? comment.getTargetId() : null));
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
