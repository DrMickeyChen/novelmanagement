package com.novel.internal.controller;

import com.novel.common.enums.AuditTargetType;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.AuthorApplyAuditDTO;
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
 * <b>供兄弟服务（当前只有 novel-auth）写审核流水的内部接口。</b>
 *
 * <p>M1 新增。它存在的原因：novel-auth 的 {@code AuthorApplyServiceImpl}
 * 在审核完一条作者申请之后要「记一条流水」，而 {@code audit_log} 表
 * 和写它的 {@link AuditLogService} 都在 content 这边
 * （流水表横跨所有业务对象，归 content 是合理的）。
 *
 * <p><b>★★ 这个接口是刻意收窄的：它只接受「作者申请审核」这一种事件，
 * 不是通用的日志写入口。</b>
 * 理由写在 {@link AuthorApplyAuditDTO} 的类注释里 —— 一句话：
 * <b>流水表是追责依据，不该把「往任意行写」的能力交给另一个服务。</b>
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
 * {@code novel-backend → novel-auth}（内容要作者昵称）与
 * {@code novel-auth → novel-backend}（审核要写流水）同时存在。
 * 这是「同进程接缝变成 HTTP 调用」的必然产物，
 * <b>解药是大纲 §M3 的 MQ</b>（审核事件异步化，环就断了）。
 * M1 显式接受它，并记录在 {@code docs/M1-摘出auth.md} 里，
 * 不留给后人去发现。
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
}
