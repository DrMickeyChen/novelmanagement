package com.novel.modules.content.service.impl;

import com.novel.common.internal.CommentBriefDTO;
import com.novel.common.internal.InternalResults;
import com.novel.modules.content.client.CommentBriefClient;
import com.novel.modules.content.service.CommentTextLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * {@link CommentTextLookup} 的实现 —— <b>M2 起它是一次 Feign 调用。</b>
 *
 * <h3>★★ 这个类在 M2 里「搬了个家，换了个身子」</h3>
 *
 * <p>改造前它住在 <b>interaction</b> 那一侧
 * （{@code com.novel.modules.interaction.service.impl.CommentTextLookupImpl}），
 * 是一段直接查 {@code novel_comment} 表的逻辑 —— 因为要保证
 * {@code content} 的代码里找不到对 interaction 的 import。
 *
 * <p>M2 把两个模块拆成两个进程之后，那个形状就不成立了：
 * <b>它查的表现在在另一个进程里。</b>于是这个类被拆成两半
 * （见 {@code docs/M2-摘出content.md}）：
 * <ul>
 *   <li><b>查库的逻辑</b> → 搬到 interaction 的
 *       {@code InternalCommentQueryService}（数据在谁那儿，查询就归谁写）；</li>
 *   <li><b>这个类</b> → 留在 content，身子换成「发一次 HTTP 请求」。</li>
 * </ul>
 * <p><b>★★ 铁律 1 的 M1 版表述在这里得到最完整的一次体现：</b>
 * <blockquote>
 * 业务语义的接口住在<b>消费方</b>（{@code CommentTextLookup} 接口留在这里）；
 * 提供方只暴露 URL，不暴露类型（interaction 只有 {@code InternalCommentController}，
 * 它<b>根本不知道</b> {@code CommentTextLookup} 这个接口存在）。
 * </blockquote>
 * <p>★ <b>interaction 里那份旧实现必须删掉</b> —— 留着就是铁律 1 点名的
 * 「能编译、能被扫描、永远不会被调用的死 bean」。M2 已删（S6）。
 *
 * <h3>★ 为什么不是 {@code @Service}</h3>
 *
 * <p>沿改造前的判断：它不是业务服务，没有对应的业务接口给 Controller 用 ——
 * 它是一个<b>适配器</b>（端口-适配器里的适配器）。
 * {@code @Component} 是准确的：它是个普通的 Spring bean，
 * 只是碰巧实现了同一个模块定义的接口。
 * <p>用 {@code @Service} 也不会出错，但会让人以为「这里有个业务服务」，
 * 而去 {@code service} 包里找一个叫 {@code CommentTextLookup} 的<b>业务</b>接口
 * —— 然后发现它是给另一个服务用的接缝，白跑一趟。
 *
 * <h3>★★ 这里【只能】用 {@code InternalResults.unwrap}</h3>
 *
 * <p>不能用 {@code unwrapBusiness}（那个会把对端的业务码原样抛出）。
 * 判据见 {@code InternalResults#unwrapBusiness} 的注释：
 * <b>这次调用是「对端替我查一次数据」，不是「对端替我执行一次校验」。</b>
 * <p>对端（{@code InternalCommentQueryService}）唯一的失败方式是它自己崩了
 * —— 它<b>没有</b>任何业务拒绝的语义（查不到评论不是错误，
 * 是「这条评论被删了」，以「不在 Map 里」表达）。
 * 所以这里把失败一律翻成 {@code SYSTEM_ERROR}（500）是对的：
 * 它能且只能表示「兄弟服务不可用」。
 *
 * <p>★ <b>不降级</b>（失败不返回空 Map）—— 理由同 {@code ContentUserLookup}：
 * 降级的症状是「审核日志里所有评论都显示成『评论 #5（已删除）』」，
 * 它<b>看起来像数据被删了</b>，排查方向会一路跑到数据库上去，
 * 而真正的原因是互动服务不可用。<b>宁可 500，也不要一个看起来正常
 * 但内容是错的页面。</b>
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentTextLookupImpl implements CommentTextLookup {

    private final CommentBriefClient commentBriefClient;

    @Override
    public Map<Long, CommentBriefDTO> briefsOf(Collection<Long> commentIds) {
        /*
         * ★ 空集合短路 —— 改造前它的理由是「selectByIds(空集合) 会生成
         *   WHERE id IN () 语法错误」；M2 之后它多了一个更值钱的理由：
         *   ★ 省下一次【完全没有意义的跨服务往返】。
         *   而「一页审核日志里恰好没有 COMMENT 类型」是非常正常的情况，
         *   所以短路是必须的，不是优化。
         *   （对端也判了空，但那省的是它的 SQL，这里省的是这次 HTTP 本身
         *    —— 见 ContentUserLookup#nicknamesOf 里同一条注释。）
         */
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, CommentBriefDTO> result = InternalResults.unwrap(
                commentBriefClient.commentBriefs(commentIds),
                "批量取评论素材 count=" + commentIds.size());

        /*
         * ★ 对端返回 null 时兜成空 Map，而不是把 null 透给调用方：
         *   AuditLogServiceImpl 直接拿它去迭代，透 null 过去就是一个 NPE 现场。
         *   HTTP 这一层多了一个「可能什么都没返回」的状态，是本地调用没有的
         *   —— 这一类兜底正是接缝类该吸收掉的东西（同 ContentUserLookup）。
         */
        return result == null ? Map.of() : result;
    }
}
