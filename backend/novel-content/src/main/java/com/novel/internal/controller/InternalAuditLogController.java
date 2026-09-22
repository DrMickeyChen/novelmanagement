package com.novel.internal.controller;

import com.novel.common.enums.AuditTargetType;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.AuthorApplyAuditDTO;
import com.novel.common.internal.CommentAuditLogDTO;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.content.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>供兄弟服务（novel-auth 与 M2 起的 novel-interaction）写审核流水的内部接口。</b>
 *
 * <p>M1 新增（当时只有作者申请一种）。它存在的原因：novel-auth 的
 * {@code AuthorApplyServiceImpl} 在审核完一条作者申请之后要「记一条流水」，
 * 而 {@code audit_log} 表 和写它的 {@link AuditLogService} 都在 content 这边
 * （流水表横跨所有业务对象，归 content 是合理的）。
 * <p><b>★ M2 起了第二个调用方</b>：novel-interaction 审完一条评论也要记流水
 * —— 于是这个类上有了<b>两个各自收窄的写入口</b>（见下面两个方法）。
 *
 * <p><b>★★ 这两个接口都是刻意收窄的：一个只接受「作者申请审核」，
 * 另一个只接受「评论审核」，没有一个是通用的日志写入口。</b>
 * 理由写在 {@link AuthorApplyAuditDTO} 和 {@link CommentAuditLogDTO}
 * 的类注释里 —— 一句话：
 * <b>流水表是追责依据，不该把「往任意行写」的能力交给另一个服务。</b>
 * <p>★ <b>「多一个窄接口，成本远低于一个宽接口被用错」</b> ——
 * M2 没有把 {@code AuthorApplyAuditDTO} 加宽成通用的，正是兑现了
 * 它在 M1 就写下的那句预言。
 *
 * <h3>★ 依赖方向：为什么这个接口在 content 侧，而不是让 auth 直接连库</h3>
 *
 * <p>{@code auth → content} 这个方向，和铁律 1 的字面（{@code content → system}
 * 允许、反向禁止）<b>看起来是冲突的</b>，所以必须说清楚：
 *
 * <p>铁律 1 禁的是<b>代码依赖</b>，而它<b>自己就把这个接缝列为受认可的例外</b>
 * ——原话是「{@code AuthorApplyAuditRecorder}（system 读 content，
 * 实现在 content）」。改造前接口定义在 {@code modules/system}、
 * 实现在 {@code modules/content}，靠同进程 DI 注入；M1 把「实现」
 * 换成了「发一次 HTTP 请求」，接缝本身没变。
 *
 * <p><b>★ 但它确实制造了一个【服务级的环】</b>：
 * {@code novel-content → novel-auth}（内容要作者昵称）与
 * {@code novel-auth → novel-content}（审核要写流水）同时存在。
 * 这是「同进程接缝变成 HTTP 调用」的必然产物，
 * <b>解药是大纲 §M3 的 MQ</b>（审核事件异步化，环就断了）。
 * M1 显式接受它，并记录在 {@code docs/M1-摘出auth.md} 里，
 * 不留给后人去发现。
 *
 * <p><b>★★ M2 之后本项目有【两个】服务级的环，别把它们搞混：</b>
 * <ol>
 *   <li>{@code novel-auth ⇄ novel-content}（就是上面这一个）——
 *       M1 造出来的，<b>M3 的 MQ 能解</b>。</li>
 *   <li>{@code novel-content ⇄ novel-interaction} —— M2 造出来的
 *       （内容要评论正文 + 评论要写流水）。
 *       ★ <b>这个 MQ 不一定解得掉</b>：「管理端审核日志要显示评论正文」
 *       是一次同步读，页面就在等它，异步化没有意义。
 *       完整的分析写在 {@code InternalCommentController} 的类注释里。
 *       <b>它很可能活到 M5。</b></li>
 * </ol>
 * <p>★ 上面第 1 条原来写的是 {@code novel-backend}（M1 时的进程名），
 * M2 已按新拓扑改成 {@code novel-content} —— M0 / M1 的文档里那些
 * {@code novel-backend} 提法<b>不要回头改</b>，那是当时的事实。
 *
 * <h3>★★ 为什么这里必须校验登录态</h3>
 *
 * <p>{@code AuditLogService#record} 内部调 {@code UserContext.requireUserId()}
 * 取操作人（<b>不接受调用方传入</b>，见那边的注释）。
 * 而 Feign 调用是一个<b>全新的 HTTP 请求</b> —— 它的 {@code UserContext}
 * 不会自动带上，得靠出站拦截器把当前身份写成 {@code X-User-*} 头
 * （见 {@code FeignIdentityConfig}）。
 *
 * <p>所以下面那段显式检查存在的意义是<b>把「拦截器忘了配」变成一个说得清的错</b>：
 * 不做检查的话，{@code requireUserId()} 抛的是
 * {@code ResultCode.UNAUTHORIZED} → HTTP 401 → 调用方 Feign 收到 401 →
 * 抛 {@code FeignException} → 最终管理员看到「服务器开小差了」，
 * 而日志里是一堆 Feign 的堆栈，指不到「拦截器没配」这件事上。
 *
 * @author MickeyChen
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalAuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 记一条「作者申请审核」流水。
     *
     * <p>★ 返回 {@code Result.success()}（data 为 null）—— 调用方只关心
     * 「记上了没有」，不需要回执数据。
     *
     * <p>★ <b>这个接口必须配 {@code Retryer.NEVER_RETRY}</b>（在
     * {@code novel-auth} 的 Feign 配置里）。Feign 默认对失败请求会重试，
     * 而这是一次<b>写</b>操作：重试一次就是两条一模一样的流水记录，
     * 而且在流水表「只追加、不可改」的设计下<b>删不掉</b>。
     */
    @PostMapping("/author-apply-audit-logs")
    public Result<Void> recordAuthorApplyAudit(@RequestBody AuthorApplyAuditDTO dto) {
        /*
         * ★★ 见类注释最后一段：把「身份没传过来」变成一条说得清的 error。
         *   这条日志出现 = Feign 出站拦截器没配 / 没生效，
         *   而不是「有个管理员没登录」—— 内部接口不可能有匿名流量。
         */
        if (UserContext.get() == null) {
            log.error("内部接口没收到调用方身份，无法记录操作人 —— "
                            + "novel-auth 的 Feign 出站拦截器可能没配置 | applyId={}", dto.getApplyId());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试");
        }

        /*
         * ★ targetType 写死成 AUTHOR_APPLY —— 这正是这个接口比
         *   AuditLogService 更窄的地方（见类注释）。
         */
        auditLogService.record(AuditTargetType.AUTHOR_APPLY, dto.getApplyId(),
                dto.getAction(), dto.getRemark());

        log.info("收到跨服务审核流水 | applyId={} action={} operatorId={}",
                dto.getApplyId(), dto.getAction(), UserContext.getUserId());
        return Result.success();
    }

    /**
     * 记一条「评论审核」流水。<b>M2 新增，第二个跨服务写流水的调用方。</b>
     *
     * <p><b>★★ 为什么新开一个方法，而不是把上面那个加宽成通用的：</b>
     * {@code AuthorApplyAuditDTO} 的类注释里早就写过这条规矩
     * （「将来如果真出现了第二种跨服务的流水……那时再开一个，
     * 而不是把这个 DTO 加宽成通用的」），M2 就是那一刻。
     * {@link CommentAuditLogDTO} 里写了完整理由，一句话：
     * <b>流水表是追责依据，不该把「往任意行写」的能力交给另一个服务。</b>
     *
     * <p><b>★ 和上面那个方法唯一的差别是 {@code targetType}：</b>
     * 那边写死 {@code AUTHOR_APPLY}，这边写死 {@code COMMENT}。
     * 两个方法各自把类型钉死，于是「调用方传错类型」这件事
     * <b>在协议层面就不可能发生</b> —— 而不是靠调用方自觉。
     *
     * <p>★ <b>调用方是 novel-interaction</b>（评论表在那边），
     * 这又是一条 {@code interaction → content} 的边，和上面那个
     * {@code auth → content} 一样，都是铁律 1 原文列出的接缝。
     *
     * <p><b>★★ 这个接口必须配 {@code Retryer.NEVER_RETRY}</b>（在
     * {@code novel-interaction} 的 {@code FeignIdentityConfig} 里）——
     * 理由和上面那个方法一字不差：它是<b>写</b>操作，
     * 重试一次就是两条一模一样的流水，而流水表「只追加、不可改」，
     * 那两条删不掉。
     */
    @PostMapping("/comment-audit-logs")
    public Result<Void> recordCommentAudit(@RequestBody CommentAuditLogDTO dto) {
        /*
         * ★★ 和上面那个方法同一段检查，理由也同一套（见类注释最后一段）：
         *   把「身份没传过来」变成一条说得清的 error，而不是一个
         *   指向 Feign 堆栈的 500。
         */
        if (UserContext.get() == null) {
            log.error("内部接口没收到调用方身份，无法记录操作人 —— "
                            + "novel-interaction 的 Feign 出站拦截器可能没配置 | commentId={}",
                    dto.getCommentId());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试");
        }

        /*
         * ★ targetType 写死成 COMMENT —— 这正是这个接口比
         *   AuditLogService 更窄的地方（见类注释）。
         */
        auditLogService.record(AuditTargetType.COMMENT, dto.getCommentId(),
                dto.getAction(), dto.getRemark());

        log.info("收到跨服务评论审核流水 | commentId={} action={} operatorId={}",
                dto.getCommentId(), dto.getAction(), UserContext.getUserId());
        return Result.success();
    }
}
