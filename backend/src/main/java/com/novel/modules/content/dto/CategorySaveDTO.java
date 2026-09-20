package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑分类的入参。两个接口共用 —— 字段完全一样，没必要拆成两份。
 *
 * <p><b>{@code sort} 和 {@code status} 给了默认值，所以新增时可以只传一个名字。</b>
 * 注意默认值写在字段初始化里，不是靠数据库的 {@code DEFAULT}：
 * {@code @RequestParam} 那套和实体入库是两回事，这里给的是「DTO 层面的默认」，
 * 保证 Service 拿到的一定不是 {@code null}（{@code sort} 是 {@code NOT NULL} 列）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "分类新增 / 编辑入参")
public class CategorySaveDTO {

    /**
     * 分类名。
     *
     * <p><b>{@code @Size(max = 50)} 必须和库里 {@code VARCHAR(50)} 对齐。</b>
     * 不对齐的后果是：前端不校验、后端也不校验，一直传到 MySQL 才报
     * {@code Data too long for column 'name'} —— 那是个 500 错误，
     * 用户看到的是「服务器开小差了」，完全不知道是自己名字打长了。
     * 在入参这层拦住，提示才是人话。
     */
    @Schema(description = "分类名称", example = "玄幻", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称最多 50 个字")
    private String name;

    /** 排序值，越小越靠前。不传按 0，会排在最前面 */
    @Schema(description = "排序值，越小越靠前", example = "1", defaultValue = "0")
    @Min(value = 0, message = "排序值不能为负数")
    @Max(value = 9999, message = "排序值最大 9999")
    private Integer sort = 0;

    /**
     * 状态：1 启用，0 停用。
     *
     * <p><b>停用和删除是两件事</b>（见 {@code NovelCategory} 的类注释）：
     * 停用只是让读者端的分类列表不显示它，已有作品照常挂着 ——
     * 想「下架这个分类但保留数据」就用停用，不要删除。
     *
     * <p><b>★ 为什么用 {@code Integer} 而不是 {@code Boolean}：</b>
     * 库里是 {@code TINYINT}，将来可能不止 0/1 两种值（比如加个「隐藏但可直链」）。
     * 而 {@code @Min/@Max} 把范围卡死在 0~1，既留了余地又挡住了非法值 ——
     * 传个 7 进去，如果只按「非 0 即真」处理，会静默存下 7，
     * 以后所有 {@code status = 1} 的判断都会漏掉这一行。
     */
    @Schema(description = "状态：1 启用，0 停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status = 1;
}
