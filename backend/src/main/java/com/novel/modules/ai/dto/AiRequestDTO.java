package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 请求。四个场景共用这一个 DTO。
 *
 * <p><b>★ 没有 {@code scene} 字段 —— 场景由 URL 决定</b>，理由见 {@code AiScene}。
 *
 * <p><b>★★ 两个字段的分工（这是这个 DTO 唯一需要想清楚的事）：</b>
 * <table border="1">
 *   <tr><th>字段</th><th>是什么</th><th>各场景里的内容</th></tr>
 *   <tr>
 *     <td>{@code prompt}</td>
 *     <td><b>要处理的东西</b> / 用户的指令</td>
 *     <td>outline：节点标题或「给这一卷写五章」<br>
 *         write：写作要求（如「节奏再慢一点」）<br>
 *         inspire：卡住的地方<br>
 *         polish：<b>选中的那段正文</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code context}</td>
 *     <td><b>背景资料</b>，可为空</td>
 *     <td>当前章节已有的正文、大纲节点的剧情笔记、作品简介</td>
 *   </tr>
 * </table>
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
}
