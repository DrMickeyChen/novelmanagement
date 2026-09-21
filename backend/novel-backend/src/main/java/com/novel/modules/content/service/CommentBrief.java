package com.novel.modules.content.service;

/**
 * {@link CommentTextLookup} 的返回值：<b>一条评论在审核日志里用得上的全部素材。</b>
 *
 * <p><b>★★ 为什么是「素材」而不是拼好的标题串 —— 这是 P6 修掉一个 Bean 环之后的结果。</b>
 * P6 起初让 interaction 侧把「《书名》第N章 · 正文」整个拼好再返回，
 * 于是 {@code CommentTextLookupImpl} 要注入 content 的
 * {@code NovelService} / {@code ChapterService}，而
 * {@code NovelServiceImpl} 审作品时要记流水（注入 {@code AuditLogService}）
 * —— 绕成一个环：
 * <pre>
 *   AuditLogServiceImpl → CommentTextLookupImpl → NovelServiceImpl → AuditLogService
 * </pre>
 * 环的两条边都不能删（一条是业务动作，一条是展示需求）。真正的原因是<b>位置拼接站错了边</b>：
 * 它是审核日志页的版式问题，而 {@code AuditLogServiceImpl} 本来就同时持有
 * {@code NovelMapper} / {@code ChapterMapper}，另外三种 targetType 的标题也都在那里拼。
 * <p>★ 所以把「事实」和「版式」分开：interaction 只说<b>这条评论说了什么、挂在什么对象上</b>，
 * content 自己拼位置。拆服务之后这个形状才对 —— M3 之后 interaction 是远程的，
 * 让它为了拼一行字再回调 content 就成了一次请求打回调用方。
 *
 * <p><b>★ 没有 {@code targetType} 字段</b>：那会让 content 反过来理解
 * {@code novel_comment} 的表内取值（1 书评 / 2 章评）。用两个可空 id 表达更朴素 ——
 * 哪个非空就是哪种评论。
 *
 * @param text      正文，<b>已经截断过</b>（截断规则只在 {@code CommentTextLookup} 的实现里）
 * @param novelId   书评：它评的那本书；章评：{@code null}
 * @param chapterId 章评：它评的那一章；书评：{@code null}
 * @author MickeyChen
 */
public record CommentBrief(String text, Long novelId, Long chapterId) {
}
