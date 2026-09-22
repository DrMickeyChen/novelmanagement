package com.novel.auth.client;

import com.novel.common.enums.AuditAction;
import com.novel.common.internal.AuthorApplyAuditDTO;
import com.novel.common.internal.InternalResults;
import com.novel.modules.system.service.AuthorApplyAuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link AuthorApplyAuditRecorder} 的实现（M1 搬到 auth 侧，改成一次 Feign 调用）。
 *
 * <p><b>★★ 这个类的地址变了，但它的【角色】一个字都没变 —— 这是理解 M1 的关键。</b>
 *
 * <p>改造前：
 * <pre>
 *   接口  modules/system/service/AuthorApplyAuditRecorder      ← 消费方（system）
 *   实现  modules/content/service/impl/...RecorderImpl        ← 提供方（content）
 *   连接方式：同进程 DI，Spring 把 content 的那个 bean 注进 system
 * </pre>
 *
 * <p>改造后：
 * <pre>
 *   接口  novel-auth  modules/system/service/...Recorder      ← 消费方，没动
 *   实现  novel-auth  auth/client/...RecorderImpl             ← ★ 搬过来了
 *   连接方式：一次 HTTP 调用（Feign → novel-backend 的 /internal/...）
 * </pre>
 *
 * <p><b>实现为什么必须搬到消费方这一侧：</b>换成 HTTP 之后，「实现」的内容
 * 就是「<b>发一次请求</b>」—— 它只能住在调用发生的地方。留在 content 那边
 * 的话，novel-auth 拿到的会是一个「能编译、能被扫描、但永远不会被调用」
 * 的死 bean，而且没有任何报错。<b>content 里那份已经删掉了</b>，
 * 留着就是上面那个死 bean。
 *
 * <p><b>★ 为什么它这么薄（三行）：</b>因为「记流水」这件事本身在
 * novel-backend 那边已经有 {@code AuditLogService} 了，这里只做两件事 ——
 * 把窄接口的三个参数装进 DTO、把结果解包。<b>薄是它的特性</b>：
 * 任何往这里加业务逻辑的改动都意味着「审核流程漏到传输层来了」。
 *
 * <p><b>★ 它是 {@code @Component} 而不是 {@code @Service}</b>（沿用原来的选择）：
 * 它不是业务服务，没有对应的 Controller —— 它是一个<b>适配器</b>
 * （端口-适配器里的适配器），和 {@code CommentTextLookupImpl} 一样。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorApplyAuditRecorderImpl implements AuthorApplyAuditRecorder {

    private final BackendAuditClient backendAuditClient;

    @Override
    public void record(Long applyId, AuditAction action, String remark) {
        AuthorApplyAuditDTO dto = new AuthorApplyAuditDTO();
        /*
         * ★ 三个字段原样搬运，一个都不加工 —— 尤其【不要】在这里塞操作人。
         *   流水的 operator_id 由 novel-backend 从 UserContext 取，
         *   而那个 UserContext 是靠 FeignIdentityConfig 把当前身份
         *   写成 X-User-* 头带过去的。见 AuthorApplyAuditDTO 的类注释：
         *   在请求体里加一个 operatorId 字段，会把「操作人不可伪造」
         *   降级成「调用方自觉」。
         */
        dto.setApplyId(applyId);
        dto.setAction(action);
        dto.setRemark(remark);

        /*
         * ★ 用 InternalResults.unwrap 而不是直接 getData()：
         *   内部接口返回的是 Result 信封，HTTP 200 不代表调用成功
         *   （铁律 3）。不解包的话，「对端说这条申请不存在」会变成一个
         *   null，然后被当成「记好了」继续往下走。
         */
        InternalResults.unwrap(backendAuditClient.recordAuthorApplyAudit(dto),
                "记录作者申请审核流水 applyId=" + applyId);
    }
}
