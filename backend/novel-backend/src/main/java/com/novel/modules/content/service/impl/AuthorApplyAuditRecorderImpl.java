package com.novel.modules.content.service.impl;

import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.system.service.AuthorApplyAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link AuthorApplyAuditRecorder} 的实现 —— <b>content 侧对 system 的「回答」。</b>
 *
 * <p><b>★★ 这个类存在的全部意义，是让依赖方向指向 content → system 而不是反过来。</b>
 * 接口在 {@code modules/system/service}（消费方），实现在这里（提供方），
 * 于是 {@code system} 的代码里<b>找不到任何对 content 的 import</b> ——
 * 二期 M1 摘 auth 服务时，{@code AuthorApplyServiceImpl} 可以整个搬走。
 *
 * <p><b>★ 它是 {@code @Component} 而不是 {@code @Service}：</b>
 * 它不是业务服务，没有对应的业务接口给 Controller 用 ——
 * 它是一个<b>适配器</b>（端口-适配器里的适配器），
 * 和 {@code CommentTextLookupImpl} 一样。
 * 用 {@code @Service} 也不会出错，但会让人以为「这里有个业务服务」，
 * 而去 {@code service} 包里找一个叫 {@code AuthorApplyAuditRecorder} 的业务接口
 * —— 然后发现那个接口在 {@code system} 包里。
 *
 * <p><b>★ 为什么它这么薄（一行）：</b>因为「记流水」这件事在 content 侧
 * 已经有现成的 {@link AuditLogService}，这里只是把 system 侧的窄接口
 * （固定 {@code AUTHOR_APPLY}、不传 targetType）翻译成全项目的通用入口。
 * <b>薄是它的特性</b> —— 任何往这里加业务逻辑的改动都意味着
 * 「system 的审核流程漏到 content 里来了」，那是方向反了的信号。
 *
 * @author MickeyChen
 */
@Component
@RequiredArgsConstructor
public class AuthorApplyAuditRecorderImpl implements AuthorApplyAuditRecorder {

    private final AuditLogService auditLogService;

    @Override
    public void record(Long applyId, AuditAction action, String remark) {
        /*
         * ★ 目标类型写死成 AUTHOR_APPLY —— 这正是这个接口比
         *   AuditLogService 更窄的地方，也是它值得单独存在的理由：
         *   system 侧拿不到「记一条作品流水」的能力。
         */
        auditLogService.record(AuditTargetType.AUTHOR_APPLY, applyId, action, remark);
    }
}
