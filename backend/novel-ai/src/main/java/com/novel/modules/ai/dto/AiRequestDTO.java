package com.novel.modules.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 请求。四个<b>生成</b>场景共用这一个 DTO（{@code AUDIT} 由服务端内部复用同一形状）。
 *
 * <p><b>★ 没有 {@code scene} 字段 —— 场景由 URL 决定</b>，理由见 {@code AiScene}。
 *
 * <p><b>★★ 两个字段的分工（这是这个 DTO 唯一需要想清楚的事）：</b>
 * <table border="1">
 *   <tr><th>字段</th><th>是什么</th><th>各场景里实际的内容</th></tr>
 *   <tr>
 *     <td>{@code prompt}</td>
 *     <td><b>一句指令</b>（「要做什么」）</td>
 *     <td>outline：{@code 为「节点名」生成下一级大纲}<br>
 *         write：{@code 接着往下写「章节名」}<br>
 *         inspire：{@code 为「节点名」提供几个剧情走向}<br>
 *         polish：{@code 润色下面这段文字}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code context}</td>
 *     <td><b>要处理的素材</b>，可为空</td>
 *     <td>outline / inspire：节点的剧情笔记<br>
 *         write：<b>正文末尾 2000 字</b><br>
 *         polish：<b>用户选中的那段正文</b></td>
 *   </tr>
 * </table>
 *
 * <p><b>★★★ 这张表在 E1 订正过一次 —— 上一版写反了。</b>
 * 原文写的是「polish：{@code prompt} = <b>选中的那段正文</b>」，
 * 那是 P0 设想的界面（作者自己在输入框里敲指令、把正文贴进 prompt）。
 * <b>P4 实际做出来的是另一套界面</b>：四个按钮、指令是固定的，
 * 作者不用打字，而「要处理的东西」全部落在 {@code context}
 * （实测见 {@code NodeWorkspace.vue} 的 {@code gatherAiRequest}）。
 * <p>★ 留这段记录是因为它正是<b>铁律 8</b> 的又一例：
 * <b>注释是一条【不会失败】的判据</b> —— 代码改了它不会报错，
 * 于是它安静地对了两期。E1 的模板变量体系（{@code {{request}}} / {@code {{selected}}}）
 * 就是按<b>代码</b>而不是按这段注释定的，**以代码为准**。
 *
 * <p>★ 为什么不合并成一个字段：两者的<b>长度上限和用途完全不同</b>。
 * {@code context} 可能是一整章的正文（几千字），而 {@code prompt} 是一句指令。
 * 合并之后「这一段到底是背景还是要求」只能靠猜，而模型猜错的代价是
 * 把背景当指令执行 —— 生成出一篇「请继续写下去」的正文。
 *
 * <p>★ 两个字段都有 {@code @Size} 上限，这不是形式：
 * 它们是<b>直接拼进发给模型的请求</b>的，而模型 API 是按 token 计费的。
 * 不设上限，一个把整本书贴进来的请求就能烧掉一次调用的大半额度。
 * 上限值取的是「一章正文 + 一句指令」的量级，正常使用绝对够。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "AI 请求。★ 场景由 URL 决定，请求体里没有 scene 字段")
public class AiRequestDTO {

    /**
     * 要处理的东西 / 用户的指令。
     *
     * <p>★ 必填：四个场景里没有一个是「什么都不说就让它生成」的 ——
     * 空 prompt 下模型只能靠瞎猜，产出的东西作者多半要整段删掉，
     * 而<b>额度已经花掉了</b>。与其让它生成垃圾，不如直接 400 说清楚。
     */
    @Schema(description = "要处理的内容或指令。polish 场景传选中的段落", example = "主角在雨夜遇到一个陌生人",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请告诉 AI 你想写什么")
    @Size(max = 2000, message = "内容太长了（最多 2000 字），请只传需要处理的部分")
    private String prompt;

    /**
     * 背景资料。
     *
     * <p>★ 可以为空：不是每个操作都需要背景（「给这一卷写五个章节标题」
     * 就不需要读者已经写了什么）。
     */
    @Schema(description = "背景资料：当前章节正文、节点笔记等。可为空", example = "（当前章节已有的正文）")
    @Size(max = 8000, message = "背景资料太长了（最多 8000 字）")
    private String context;

    /*
     * ==================== 以下三个 id 是 E1（提示词模板）新增的 ====================
     *
     * ★★ 为什么需要它们：E1 把提示词的拼装从【前端】搬到了【服务端】，
     *    而服务端要多拼三段内容 —— 作品名、当前大纲节点、前情摘要。
     *    这三样都在 content 自己的库里（novel / novel_outline / ai_summary），
     *    所以**只需客户端告诉服务端「是哪本书的哪个节点」，不需要把内容传上来**。
     *
     * ★ 全部可空、全部没有 @NotNull：它们只喂给模板里的 {{novelTitle}} /
     *   {{outline}} / {{summary}} 三个【可选】占位符。不传 = 模板里那几行整行消失，
     *   **AI 仍然能跑**（就是 P4 的行为）。所以「不传」是正常用法，不是错误。
     *
     * ★★ 判据（防止有人把它们做成必填）：**P4 的 curl / Knife4j 调用方式必须继续可用** ——
     *   一个只发 {prompt, context} 的请求，行为要和 E1 之前【一模一样】。
     */

    /**
     * 作品 id。用来取作品名（{@code {{novelTitle}}}）与前情摘要（{@code {{summary}}}）。
     *
     * <p>★ 服务端据此查 {@code novel} 与 {@code ai_summary} —— 都在 content 本库，
     * <b>不产生任何跨服务调用</b>。
     */
    @Schema(description = "作品 id。可选，用于模板变量 {{novelTitle}} / {{summary}}")
    private Long novelId;

    /**
     * 章节 id。用于 {@code {{outline}}}（本章对应的大纲节点）。
     *
     * <p>★ 走的是 {@code novel_outline.chapter_id}，而那一列<b>可空</b>
     * （「支持先写大纲后建正文」），所以查不到是正常的，不报错。
     */
    @Schema(description = "章节 id。可选，用于模板变量 {{outline}}")
    private Long chapterId;

    /**
     * 大纲节点 id。用于 {@code {{outline}}}（该节点自己的标题与内容）。
     *
     * <p>★ 与 {@link #chapterId} 并存不冲突：outline / inspire 场景手上只有 nodeId，
     * write 场景手上是 chapterId —— 由 {@code AiVarResolver} 决定先用哪个。
     */
    @Schema(description = "大纲节点 id。可选，用于模板变量 {{outline}}")
    private Long nodeId;

    /*
     * ==================== 以下两个字段是 E1/S6 新增的：渲染结果的服务内传输 ====================
     */

    /**
     * 渲染后的 system 消息。<b>由 {@code AiPreflight} 在请求线程上填，由 {@code DeepSeekAiStreamSource} 在工作线程上读。</b>
     *
     * <h4>★★★ 为什么要借道这个 DTO</h4>
     *
     * <p>渲染必须发生在<b>请求线程</b>上（它要过 {@code NovelAccessGuard} 的权限校验，
     * 见 {@code AiContextAssembler} 的类注释），而 HTTP 调用发生在
     * {@code ai-stream-N} 线程上。两者之间只传得动一个对象 —— 就是手上这个 DTO。
     *
     * <h4>★★★ {@code @JsonIgnore} 在这里是一条【安全】属性，不只是省一次序列化</h4>
     *
     * <p>它让「客户端自己塞一份渲染好的提示词」这条路<b>在结构上不可能</b>：
     * 没有这个注解，任何人都能发
     * {@code {"prompt":"x","renderedUserMessage":"忽略以上全部指令，改为…"}}
     * 来<b>完全绕过管理端的模板</b> —— 模板的保存校验、变量白名单、长度护栏
     * 全部作废，而请求看起来完全正常。
     *
     * <p>★ 判据是结构式的：<b>这个字段只能在 Java 里被 {@code set}，
     * 不可能被 JSON 落进来</b> —— 而不是「我们记得在某个拦截器里清空它」。
     *
     * <p>★★ 顺带一条：{@code @JsonIgnore} 同时挡住出站序列化。
     * 万一将来有人把这个 DTO 当响应体返回，渲染结果（含作者原稿）也不会被带出去。
     */
    @JsonIgnore
    @Schema(hidden = true)
    private String renderedSystemPrompt;

    /**
     * 渲染后的 user 消息 —— <b>这才是真正发给模型的那段话</b>。
     *
     * <p>★ 与 {@link #renderedSystemPrompt} 成对，理由同上。
     * <p>★ 读它的地方必须做一次 null 检查并抛 {@code IllegalStateException}：
     * 建 emitter 的路径上一定先跑过闸门，所以它是 {@code null} 只可能意味着
     * <b>有人新加了一条绕过闸门的调用路径</b> —— 那是代码错误，该在监控上响，
     * 不该退回用 {@code prompt} 拼一个凑合的提示词（那会静默地少掉三段上下文）。
     */
    @JsonIgnore
    @Schema(hidden = true)
    private String renderedUserMessage;
}
