package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者编辑作品信息的入参。
 *
 * <p><b>为什么和 {@code NovelCreateDTO} 分成两个类，明明是同样的字段：</b>
 * 它们<b>现在</b>一样，但将来分开演进的概率很高 —— 比如「书名在通过审核后
 * 不允许再改」（防止作者上架后改名蹭流量），那条规则只该加在编辑上。
 * 共用一个类的话，加字段时两边一起变，
 * 迟早会出现「新增时用不到的字段被要求必填」这种别扭事。
 * 两个类各 20 行，分开的代价几乎为零。
 *
 * <p><b>★ 同样没有 {@code status} 和 {@code authorId}</b>：
 * 改状态只能走 {@code NovelService#transitStatus}，
 * 改作者归属这件事本身就不该存在（换作者 = 换一本书）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者编辑作品入参")
public class NovelUpdateDTO {

    @Schema(description = "书名", example = "测试书（修订版）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "书名不能为空")
    @Size(max = 100, message = "书名最多 100 个字")
    private String title;

    @Schema(description = "封面 URL")
    @Size(max = 255, message = "封面地址过长")
    private String cover;

    @Schema(description = "分类 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请选择分类")
    private Long categoryId;

    @Schema(description = "简介")
    @Size(max = 1000, message = "简介最多 1000 个字")
    private String intro;

    @Schema(description = "标签，逗号分隔")
    @Size(max = 200, message = "标签最多 200 个字")
    private String tags;

    @Schema(description = "是否完结：0 连载，1 完结", example = "0")
    @Min(value = 0, message = "isFinish 只能是 0 或 1")
    @Max(value = 1, message = "isFinish 只能是 0 或 1")
    private Integer isFinish;
}
