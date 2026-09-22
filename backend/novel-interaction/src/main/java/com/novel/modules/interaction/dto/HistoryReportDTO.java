package com.novel.modules.interaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 上报阅读进度的入参。
 *
 * <p><b>★★ 这个 DTO 上没有 {@code userId}，这是刻意的。</b>
 * 读者 id 只能来自 {@code UserContext}（JWT）。让它出现在请求体里，
 * 即使 Service 里「记得」忽略它，也只差一次重构就会变成
 * 「按调用方说的 userId 写进度」—— 那是往任意用户的阅读记录里写数据。
 * 字段不存在，这条路在类型层面就是封死的（同 P2 的
 * {@code NovelCreateDTO} 不接收 {@code authorId} 和 {@code status}）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "阅读进度上报入参")
public class HistoryReportDTO {

    @Schema(description = "作品 id", example = "1001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "作品 id 不能为空")
    private Long novelId;

    /**
     * 读到了哪一章。
     *
     * <p>★ <b>必填，且会被后端校验</b>：它必须属于 {@link #novelId} 这本书、
     * 且状态为已发布。「必填」不是因为前端一定会传，
     * 而是因为这个值一旦错了，进度就会指到一个读者本来读不到的位置。
     */
    @Schema(description = "章节 id", example = "100101", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "章节 id 不能为空")
    private Long chapterId;

    /**
     * 章内进度百分比。
     *
     * <p><b>★ 这里没有 {@code @Min(0) @Max(100)}，是刻意的。</b>
     * 加了它，一个因为像素除法四舍五入算出 {@code 101} 的客户端
     * 会收到 HTTP 400，<b>整条进度都丢掉</b> —— 而这个字段的重要性远不值得
     * 让读者的进度因为一个越界数字而丢失。服务端夹到 {@code [0, 100]}
     * （见 {@code HistoryServiceImpl#clampProgress}），既保住了进度的语义，
     * 又不会把这次上报整条拒掉。
     *
     * <p>不传按 0 处理（列是 {@code NOT NULL DEFAULT 0}）。
     */
    @Schema(description = "章内进度百分比 0~100，超出会被夹到边界；不传按 0", example = "42")
    private Integer progress;
}
