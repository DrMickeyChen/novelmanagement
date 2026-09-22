package com.novel.modules.content.service;

import com.novel.common.internal.AuthorApplyBriefDTO;
import com.novel.common.internal.InternalResults;
import com.novel.modules.content.client.AuthUserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>content 模块望向 system 模块的那【唯一一个窗口】—— M1 起它变成了一条 HTTP 接缝。</b>
 *
 * <p>它主要做「按 userId 取昵称」，但不止于此 —— S11 起它也负责
 * 「按作者申请 id 取一句可读的标题」（见 {@link #authorApplyTitlesOf}）。
 * <b>★ 类名因此变得不够准确，这是有意接受的</b>：准确的命名应该是
 * {@code SystemModuleFacade} 之类，但那要改 5 个调用点换不来任何功能。
 * <b>真正的规矩是：跨模块的耦合只能有一个出入口，出入口在消费方那一侧。</b>
 *
 * <h3>★★ M1 改了什么：只改了「怎么拿到数据」，没改「拿到什么」</h3>
 *
 * <table border="1">
 *   <caption>改造前后对照</caption>
 *   <tr><th></th><th>改造前（P2~P6）</th><th>M1 之后</th></tr>
 *   <tr>
 *     <td>数据怎么来</td>
 *     <td>注入 {@code SysUserMapper}，<b>直接查库</b></td>
 *     <td>注入 {@link AuthUserClient}，<b>Feign 调 novel-auth</b></td>
 *   </tr>
 *   <tr>
 *     <td>四个方法签名</td>
 *     <td colspan="2"><b>一字未改</b> —— 调用方一行都没动</td>
 *   </tr>
 *   <tr><td>SQL 写在哪</td>
 *       <td>这个类里</td>
 *       <td>novel-auth 的 {@code InternalUserQueryService}</td></tr>
 * </table>
 *
 * <p><b>★ 为什么 SQL 必须跟着表走：</b>M1 把 {@code modules/system} 搬进
 * novel-auth 之后，{@code SysUserMapper} 就不在 novel-backend 的 classpath
 * 上了 —— 编译期直接报「找不到符号」（这是好事：错误当场可见，
 * 而不是等到运行时才 500）。于是那些查询只能搬到 auth 那一侧去。
 * <b>数据在谁那儿，查询就归谁写。</b>
 *
 * <p><b>★★ 这个类存在的意义，在 M1 之后反而更大了。</b>
 * 改造前它挡的是「M2 拆服务时要翻遍整个 content 包去改散落的 Mapper 调用」；
 * 现在它挡的是「content 直接去调 auth 的 HTTP 接口」。两者是同一件事：
 * <b>把跨模块的耦合收进一个类</b>，这样 M2 摘 content 时，
 * 需要检查的跨服务调用点仍然只有这几处。
 *
 * <p><b>依赖方向提醒：</b>只能 content → system，绝不能反过来。
 * system 模块（认证/用户）不该知道小说的存在，否则二期的 auth 服务
 * 就反向依赖 content 了，拆分顺序（M1 先拆 auth、M2 再拆 content）直接作废。
 * <p>★ <b>M3 之后这条规矩的例外从两个减到了一个，而且剩下的那个不在这儿：
 * {@code CommentBriefClient}（content 读 interaction）。</b>
 * 原来还有第二个反向的接缝 —— {@code AuthorApplyAuditRecorder}
 * （auth 审完作者申请要 content 记流水），M3 把它换成了一条事务消息，
 * 于是它连同 {@code novel-auth} 里全部的 Feign 依赖一起消失了。
 * <p>★★ <b>这次删除顺带说明了一件容易搞混的事</b>：
 * 那个接缝在 M1/M2 期间是铁律 1 原文里明确列出的例外，<b>但它同时是一个
 * 服务级的环</b>（{@code novel-auth ⇄ novel-content}）——
 * 「代码依赖合规」和「服务级没有环」是两件事，M1 两条都记了。
 * M3 解掉的是后一条。完整的对照写在 {@code InternalCommentController} 的类注释里。
 *
 * <h3>★ 失败时不降级，直接抛</h3>
 *
 * <p>逐个方法看：跨服务调用失败（auth 挂了、网络断了、超时）会抛
 * {@code BusinessException(SYSTEM_ERROR)} → HTTP 500，
 * <b>而不是「返回空的昵称」</b>。
 *
 * <p>为什么不做降级：降级的症状是「首页上所有书的作者名变成空白」，
 * 它<b>看起来像数据坏了</b>，于是排查方向会一路跑到数据库上去，
 * 而真正的原因是认证服务不可用。<b>宁可 500，也不要一个看起来正常
 * 但内容是错的页面。</b>
 * <p>★ 代价要说清楚（记录在 {@code docs/M1-摘出auth.md}）：
 * novel-auth 不可用时，<b>连匿名首页也会 500</b>（因为首页书列表要作者昵称）。
 * 改造前这个失败面是不存在的（本地查询不会「连不上」）。
 * 这是共享库 + 同步调用换来的<b>可用性耦合</b>，M1 接受它、
 * 记为 M5 治理项（缓存兜底 / 熔断降级）。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentUserLookup {

    private final AuthUserClient authUserClient;

    /**
     * 取一个用户的昵称。查不到返回 {@code null}（用户可能已被注销）。
     *
     * <p>用于单条记录的场景（比如小说详情页的作者名）。
     */
    public String nicknameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return InternalResults.unwrap(authUserClient.nicknameOf(userId), "取用户昵称 userId=" + userId);
    }

    /**
     * 批量取昵称，返回 {@code userId → nickname}。
     *
     * <p><b>★ 列表接口必须用这个，不要循环调 {@link #nicknameOf}：</b>
     * 一页 20 本小说就是 20 次调用 —— 而 M1 之后每次调用是<b>一次跨进程
     * HTTP 请求</b>，N+1 的代价从「20 次 SQL」变成了「20 次网络往返」。
     * 这个类改造前的那条注释就写着「这是典型的 N+1」，现在更成立。
     *
     * <p>★ 下面这两段本地短路（空集合 / 全是 null）在 M1 之后<b>更值钱</b>了：
     * 它们省下的从「一次无用 SQL」变成了「一次无用 HTTP」。
     * <b>不要因为「反正对端也会判空」就把它们删掉</b> —— 对端判空省的是它的
     * SQL，这里判空省的是这次往返本身。
     *
     * @param userIds 用户 id 集合，允许为空
     */
    public Map<Long, String> nicknamesOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 去重：同一页里好几本书是同一个作者很常见，重复 id 没必要重复传
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, String> result =
                InternalResults.unwrap(authUserClient.nicknamesOf(ids), "批量取用户昵称 count=" + ids.size());

        /*
         * ★ 对端返回 null 时兜成空 Map，而不是把 null 透给调用方：
         *   调用方（AuditLogServiceImpl、NovelServiceImpl、CommentServiceImpl）
         *   直接 .get(id) 用，透 null 过去就是三个 NPE 现场。
         *   HTTP 这一层多了一个「可能什么都没返回」的状态，是本地调用没有的
         *   —— 这一类兜底正是接缝类该吸收掉的东西。
         */
        return result == null ? Collections.emptyMap() : result;
    }

    /**
     * 反查：昵称模糊匹配 → 用户 id 列表。<b>给「按作者名搜索作品」用。</b>
     *
     * <p><b>★★ 真正的查询（以及它为什么是一次全表扫描、为什么有条数上限）
     * 现在写在 novel-auth 的 {@code InternalUserQueryService#userIdsByNickname}
     * —— 请去那里读。</b>这里只留一句结论：这是 P3 用 MySQL 做搜索的已知短板，
     * 二期 M4 上 ES 正是要解决它。
     *
     * @param keyword 昵称关键词。★ 仍然在本地判空：
     *                <b>空关键词会匹配所有人</b>，对端虽然也判了，
     *                但这里挡住能省下一次「注定要丢弃结果」的往返
     * @return 命中的用户 id，<b>可能为空列表</b>（调用方要短路，不能拿空集合去 {@code IN}）
     */
    public List<Long> userIdsByNickname(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        List<Long> ids = InternalResults.unwrap(
                authUserClient.userIdsByNickname(keyword), "按昵称反查用户 keyword=" + keyword);
        return ids == null ? List.of() : ids;
    }

    /**
     * 批量取作者申请的「标题」，返回 {@code applyId → "墨白（张三）"}。
     * <b>给审核日志补「作者申请 #5」之外的可读信息用。</b>
     *
     * <p><b>★★ 为什么不直接用 {@link #nicknameOf}：</b>
     * {@code audit_log.target_id} 对 {@code AUTHOR_APPLY} 存的是<b>申请表的 id</b>，
     * 不是申请人的 {@code user_id}。拿它去调 {@code nicknameOf(applyId)}
     * <b>不会报错</b> —— 它会去 {@code sys_user} 里找 id 等于这个申请号的人，
     * 而那多半是<b>另一个真实存在的人</b>，于是日志页上显示的是
     * 「别人的申请」。两份申请都真实存在，从流水上根本看不出记错了。
     * <p>所以必须走 {@code apply_id → user_id → nickname} 两跳。
     * <b>★ M1 之后这个坑的形状变了一点，但危险性没变</b>：
     * 现在是「对端返回的 DTO 里同时带着两个 id」，照旧不能混用 ——
     * 这也是 {@link AuthorApplyBriefDTO} 必须同时给
     * {@code id} 和 {@code userId} 的原因。
     *
     * <p><b>★ 标题为什么是「笔名（昵称）」而不是只有昵称：</b>
     * 昵称可以重复、可以随时改，而审核员在日志页要找的是「哪一份申请」。
     * 笔名是那份申请自己的内容，昵称说明了是谁 —— 两个都给才够定位。
     * （同一页里同一个人申请了两次、笔名不同，只有这样才分得开。）
     *
     * <p><b>★★ 这个方法是 M1 里唯一一个「一次页面渲染打两次跨服务调用」的地方</b>
     * （先取申请、再取昵称），而这是<b>改造前就有的形状</b>——
     * 原来也是两次 SQL。M1 没有把它变好也没有变坏，只是把「两次查询」
     * 变成了「两次往返」。
     * <p>★ 想优化的话有一个现成的方向：<b>让 {@code /internal/author-applies/briefs}
     * 直接返回笔名加昵称</b>，一次往返解决。M1 没做，因为那会把
     * 「标题怎么拼」这个展示决策挪进 auth 服务（见 {@code InternalUserQueryService#briefsOf}
     * 的注释：那边只给<b>事实</b>，怎么呈现由消费方决定）。
     * <b>这是一个可以商量的取舍，不是遗漏。</b>
     *
     * <p><b>★ 查不到的 id 不会出现在 Map 里</b>：申请被逻辑删除、
     * 或者申请还在但申请人已注销导致笔名取不到任何佐证 ——
     * 前者返回 {@code null}，日志页显示成「作者申请 #5（已删除）」，
     * 和作品 / 章节 / 评论的处理完全一致
     * （见 {@code AuditLogVO#targetTitle}：<b>为了补出已删除对象的标题
     * 而绕过逻辑删除是错误的修法</b>）。
     * <p>★ 申请人注销但申请还在时，<b>标题仍然给得出</b>（只有笔名）——
     * 申请本身没有消失，不该因为作者走人就显示成「已删除」。
     *
     * @param applyIds 申请 id 集合，允许为空
     *                （<b>空集合直接返回空 Map，不发这次请求</b>）
     */
    public Map<Long, String> authorApplyTitlesOf(Collection<Long> applyIds) {
        if (applyIds == null || applyIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = applyIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<AuthorApplyBriefDTO> applies = InternalResults.unwrap(
                authUserClient.briefsOf(ids), "取作者申请摘要 count=" + ids.size());
        if (applies == null || applies.isEmpty()) {
            return Collections.emptyMap();
        }

        /*
         * ★ 第二次调用：申请人昵称。同样是一次批量，不是循环 ——
         *   一页 20 条日志最多 20 个申请人，逐个查就是 20 次往返。
         * ★ 用 nicknamesOf（本类自己的方法）而不是绕开它：
         *   昵称的取法只能有一处实现。
         */
        Map<Long, String> nicknames = nicknamesOf(
                applies.stream().map(AuthorApplyBriefDTO::getUserId).toList());

        Map<Long, String> result = new LinkedHashMap<>();
        for (AuthorApplyBriefDTO apply : applies) {
            String penName = apply.getPenName();
            String nickname = nicknames.get(apply.getUserId());

            /*
             * ★ 申请人已注销时只有笔名 —— 降级，但不跳过：
             *   申请本身还在，不该因为作者走人就显示成「已删除」。
             *   （pen_name 列是 NOT NULL，所以这个分支一定给得出东西。）
             */
            String title = penName == null ? nickname : penName;
            if (title == null) {
                /*
                 * ★ 两样都没有，只能是不放进去 —— 调用方 get 出 null，
                 *   按「已删除」处理。这正是我们要的兜底，不是错误。
                 */
                continue;
            }
            /*
             * ★ 昵称和笔名相同时不写成「墨白（墨白）」——
             *   那看起来像数据重复，而不是「笔名恰好等于昵称」。
             */
            result.put(apply.getId(), nickname == null || nickname.equals(penName)
                    ? title
                    : title + "（" + nickname + "）");
        }
        return result;
    }
}
