package com.novel.common.internal;

/**
 * 「一条评论在审核日志里用得上的全部素材」—— 跨服务传输用（M2）。
 *
 * <h3>★★ M2：这个 record 为什么搬进了 novel-common，而且改了名</h3>
 *
 * <p>它原来是 {@code com.novel.modules.content.service.CommentBrief}，
 * 一个 content 内部的 record。M2 之后它的<b>生产方变成了 novel-interaction</b>
 * （评论表在那边），而<b>消费方仍然是 novel-content</b>（审核日志在那边）。
 *
 * <p>★ 提供方要构造它，所以它绝不能留在 content ——
 * 留着就意味着 interaction 要 import content 的内部类型，
 * 那正是铁律 1 禁止的方向。
 * <p>★ 改名成 {@code ...DTO} 是跟随本包既有的命名范式
 * （{@code AuthorApplyAuditDTO} / {@code AuthorApplyBriefDTO}）：
 * 这个包里的东西都是「跨服务传的信封」，看到 DTO 就知道它会在 HTTP 上跑。
 *
 * <p><b>★ 三个访问器的名字 {@code text()} / {@code novelId()} / {@code chapterId()}
 * 一个都没改</b> —— record 的访问器名就是 JSON 的字段名，改了它
 * 两边就对不上了。所以 {@code AuditLogServiceImpl} 里那几处读取是零改动的。
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
 * content 自己拼位置。
 * <p>★★ <b>M2 之后这条论证更强了</b>：两个模块已经变成了两个进程，
 * 让 interaction 为了拼一行字再回调 content，就是「一次请求打回调用方」——
 * 不只是 Bean 环，而是一次真实的往返网络调用。
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
public record CommentBriefDTO(String text, Long novelId, Long chapterId) {
}
