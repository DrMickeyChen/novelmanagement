package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者新建作品的入参。
 *
 * <p><b>★ 这里没有 {@code status} 字段，而且不能加。</b>
 * 新建的作品<b>永远是草稿</b>（{@code status = 0}），由 Service 写死。
 * 如果 DTO 里放一个 {@code status}，前端就能传 {@code {"status": 2}}
 * 直接把书「建」成已上架 —— 审核流程形同虚设。这就是「过度提交」
 * （mass assignment）的典型形态，和 {@code LoginDTO} 里不放 {@code id} 是同一个道理。
 *
 * <p>同理没有 {@code authorId}：作者是谁由 {@code UserContext} 取当前登录用户决定，
 * <b>绝不能让前端指定</b>，否则任何人都能替别人发书。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者新建作品入参")
public class NovelCreateDTO {

    @Schema(description = "书名", example = "测试书", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "书名不能为空")
    @Size(max = 100, message = "书名最多 100 个字")
    private String title;

    @Schema(description = "封面 URL", example = "/uploads/cover/xxx.jpg")
    @Size(max = 255, message = "封面地址过长")
    private String cover;

    /**
     * 分类 id。
     *
     * <p><b>{@code @NotNull} 是必须的</b>：{@code novel.category_id} 是
     * {@code NOT NULL} 列，而且它和 {@code status} 一起构成
     * {@code idx_category_status} 索引 —— 读者端「按分类筛选」就靠它。
     * 漏了它，书进去就是残废的（读者端分类页永远找不到它）。
     */
    @Schema(description = "分类 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请选择分类")
    private Long categoryId;

    @Schema(description = "简介", example = "一个关于测试的故事……")
    @Size(max = 1000, message = "简介最多 1000 个字")
    private String intro;

    @Schema(description = "标签，逗号分隔", example = "热血,升级流")
    @Size(max = 200, message = "标签最多 200 个字")
    private String tags;

    @Schema(description = "是否完结：0 连载，1 完结", example = "0", defaultValue = "0")
    @Min(value = 0, message = "isFinish 只能是 0 或 1")
    @Max(value = 1, message = "isFinish 只能是 0 或 1")
    private Integer isFinish = 0;
}
