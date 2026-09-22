package com.novel.auth.client;

import com.novel.common.internal.AuthorApplyAuditDTO;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * <b>auth 侧调 novel-content 的 Feign 客户端 —— 只为了记一条审核流水。</b>
 *
 * <p><b>★★ 这个接口是整个二期里唯一一个「反向」的调用</b>
 * （auth → content，而别的都是 content / interaction → auth）。
 * 它为什么必须存在、以及它制造的那个<b>服务级的环</b>，
 * 完整的论证写在 {@code InternalAuditLogController} 的类注释里，
 * 这里只留一句结论：
 * <blockquote>
 * 铁律 1 禁的是<b>代码依赖</b>，而这个接缝是铁律 1 原文里
 * 明确列出的例外（{@code AuthorApplyAuditRecorder}）。
 * M1 把「同进程 DI」换成了「一次 HTTP 调用」，接缝没变、
 * 服务级却成了环 —— <b>解药是大纲 §M3 的 MQ</b>，记录并接受。
 * </blockquote>
 *
 * <p><b>★ 接口住在消费方（auth）</b>，理由同 {@code AuthUserClient} ——
 * 「发一次 HTTP 请求」这件事必须住在调用发生的地方。
 *
 * <p><b>★★ M2：类名从 {@code BackendAuditClient} 改成了本名，{@code name}
 * 从 {@code novel-backend} 改成了 {@code novel-content}。</b>
 * 那句话改了不算「顺手重命名」—— <b>不改就是一句假话</b>：
 * 这个端点（{@code /internal/author-apply-audit-logs}）在 M2 之前住在
 * 那个装着 content + interaction + ai 的单体进程里，之后跟着 {@code modules/content}
 * 搬进了 {@code novel-content}。留着旧名字的代价是排查时去翻一个不存在的服务。
 *
 * <p><b>★ 漏改的症状值得单独写一笔：</b>Nacos 找不到 {@code novel-backend}
 * 实例 → 写不进流水 → 审核接口 500。而<b>「作者申请审核」是个低频操作</b>
 * （一个人一辈子申请一次作者），<b>它极容易在回归测试里被整条跳过</b>。
 * 所以 M2 的验收清单里它是必测项，不是可选项。
 *
 * <p><b>★★ 这个客户端的超时和重试配置和别的都不一样，见
 * {@code FeignIdentityConfig}：</b>它调的是一个<b>写</b>接口，
 * Feign 默认的重试会让一次失败变成两条流水。
 *
 * @author MickeyChen
 */
@FeignClient(name = "novel-content", path = "/internal")
public interface ContentAuditClient {

    /**
     * 记一条「作者申请审核」流水。
     *
     * <p>★ 请求体里<b>刻意没有操作人</b>：操作人由 novel-content 从
     * {@code UserContext} 取，而那个 {@code UserContext} 是
     * {@code FeignIdentityConfig} 把当前身份写成 {@code X-User-*} 头
     * 带过去的。见 {@link AuthorApplyAuditDTO} 的类注释。
     */
    @PostMapping("/author-apply-audit-logs")
    Result<Void> recordAuthorApplyAudit(@RequestBody AuthorApplyAuditDTO dto);
}
