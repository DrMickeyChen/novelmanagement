package com.novel.modules.ai.service;

import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.enums.AiScene;

/**
 * AI 文本生成的来源 —— <b>P4 里最重要的一条接缝</b>。
 *
 * <p><b>★★ 这个接口存在的唯一理由是：让「接真实模型」这件事变成加一个类，
 * 而不是改一遍代码。</b>
 *
 * <pre>
 *   P4（现在）：AiStreamSource ← MockAiStreamSource   （演示数据）
 *   E1（以后）：AiStreamSource ← DeepSeekAiStreamSource（真实模型）
 *                              ↑ 只加这一个类，改一行配置
 * </pre>
 * Controller、SSE 管道、前端 {@code utils/sse.js}、AiPanel 的逐字渲染、
 * 中断、采纳……<b>全部不动</b>。这不是「顺便设计得好」，这就是这一期
 * 把 AI 面板做成「真管道 + 假数据」的全部意义：
 * 如果 P4 只在界面上放几个假文字，E1 接模型时那一整条链路要从头写、
 * 从头测，而流式中断这类问题<b>恰恰是最容易写错的</b>。
 *
 * <p><b>★ 为什么是「推送式回调」而不是返回一个 {@code Flux} 或 {@code List}：</b>
 * <ul>
 *   <li>{@code Flux} 要 WebFlux，而本项目是 Spring MVC（引它等于把整个
 *       web 栈换掉，代价远超收益）</li>
 *   <li>返回 {@code List<String>} 是<b>先全部生成完、再一次性返回</b> ——
 *       那就不是流式了，用户要盯着转圈等 15 秒才看到第一个字。
 *       ★ 而流式恰好是这个功能体验上唯一重要的东西</li>
 *   <li>回调式还有一个好处：真实模型的 HTTP 客户端<b>本来就是回调/逐行读</b>的，
 *       DeepSeek 的流式响应也是「一行一行吐」。这个接口的形状和它天然对齐，
 *       接的时候不用做「把流攒成 List」这种反过来的适配</li>
 * </ul>
 *
 * <p><b>★★ 实现者必须遵守的两条约定（都在 {@link Handler} 上）：</b>
 * <ol>
 *   <li><b>恰好调用一次终结方法</b>：{@link Handler#onComplete()} 和
 *       {@link Handler#onError} 二选一，且只能调一次。
 *       两个都不调 = 前端的流永远不结束（转圈转到用户放弃）；
 *       调两次 = SSE 连接被重复关闭，日志里报
 *       {@code IllegalStateException: ResponseBodyEmitter has already completed}，
 *       而<b>第一次的日志会把人引向完全错误的方向</b>。</li>
 *   <li><b>允许被随时打断</b>：{@link Handler#onChunk} 在客户端断开之后
 *       会抛异常，实现者<b>不要 catch 它</b>，让它把生成循环冲掉即可 ——
 *       那正是「用户点了中断，服务端停止烧 token」的实现方式。
 *       ★ 这一点对 mock 无所谓（不烧钱），但对 E1 是<b>真金白银</b>：
 *       用户关了页面，服务端还在生成，那几百个 token 是白花的。</li>
 * </ol>
 *
 * <p><b>★ 这个包为什么独立于 {@code modules/content}：</b>
 * 「一个包 = 一个将来要拆出去的微服务」。E1 之后 AI 大概率是独立的
 * {@code novel-ai}（它要用 GPU/外部 API、配额、独立扩容，和内容服务的
 * 生命周期完全不同）。放进 {@code modules/content} 将来要连着
 * 大纲树、章节服务一起拆，而它们本不该在同一个进程里。
 * 现在多建一个包，省掉的是将来一次跨模块重构。
 *
 * @author MickeyChen
 */
public interface AiStreamSource {

    /**
     * 生成内容，边生成边通过 {@code handler} 推给调用方。
     *
     * <p><b>★ 这个方法是【阻塞】的</b>：它返回时表示生成已经结束
     * （正常结束或失败）。调用方<b>必须把它放到一个独立线程上跑</b> ——
     * 见 {@code AiController} 里那句「为什么必须换线程」。
     *
     * <p>★ 实现里<b>不要</b>自己 try-catch 掉异常然后吞掉：
     * 出了错要通过 {@link Handler#onError} 告诉调用方，
     * 否则前端只能看到一个「没有 {@code [DONE]} 就断掉的流」，
     * 表现是<b>永远转圈</b>——用户分不清是「还在生成」还是「早就挂了」。
     *
     * @param scene   场景。★ 由调用方按 URL 决定，不是从请求体里读的
     * @param request 请求参数（prompt / context）
     * @param handler 增量回调
     */
    void stream(AiScene scene, AiRequestDTO request, Handler handler);

    /**
     * 增量回调。实现者调用它，调用方（Controller）把它翻译成 SSE 帧。
     *
     * <p><b>★ 为什么要拆成「三个方法」而不是一个
     * {@code Consumer<String>} 加 try-catch：</b>
     * 「正常结束」和「出错结束」是两件必须区分的事 ——
     * 正常结束要发 {@code data: [DONE]} 哨兵，出错要发 {@code event: error}。
     * 用一个 Consumer + 异常的话，「流跑到一半断了」这件事在回调里
     * 表达不出来，最后只能靠「连接断了」这个模糊的信号，前端分不清原因。
     */
    interface Handler {

        /**
         * 推一段<b>增量</b>文本。
         *
         * <p>★ 是增量不是全量：实现者传「这次新生成的几个字」，
         * 而不是「到目前为止的全部内容」。传全量的话，
         * 前端每收到一帧都要把已有内容整个替换掉 ——
         * 打字机效果就没了（变成整段闪一下），而且流量是 O(n²)。
         *
         * <p>★ 实现者不要假设 chunk 一定是「一个字」：真实模型返回的是
         * token，可能一次给几个字、也可能给半个词。前端必须按
         * 「追加任意长度的字符串」来写，不能按「一个字一个字」来写。
         */
        void onChunk(String text);

        /** 生成完毕。★ 调用方收到它之后会补一个 {@code data: [DONE]} 再关闭连接 */
        void onComplete();

        /**
         * 生成失败。
         *
         * @param cause 失败原因。★ 调用方会记 error 日志，但<b>不会</b>
         *              把 {@code cause.getMessage()} 发给前端 ——
         *              上游 API 的报错里常带着 key 片段、内部地址这类信息。
         *              前端只收到一句固定的「生成失败，请重试」
         */
        void onError(Throwable cause);
    }
}
