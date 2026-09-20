package com.novel.modules.content.vo;

import com.novel.modules.content.entity.NovelCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分类出参。读者端和管理端<b>共用这一个</b>。
 *
 * <p><b>为什么读者端和管理端不各做一个 VO：</b>读者端只要 {@code status = 1} 的分类，
 * 管理端要全部（含停用的，否则停用之后没法改回来）—— 但那<b>是查询条件不同，
 * 不是返回的字段不同</b>。两个 VO 会把「同一个东西」写成两份定义，
 * 以后加一个「分类图标」字段就得改两处，漏一处就是读者端有管理端没有。
 *
 * <p><b>★ 读者端不会拿到「停用的分类」，这件事由 {@code WHERE status = 1} 保证，
 * 不靠前端过滤</b> —— 见 {@code CategoryServiceImpl#listEnabled}。
 * 如果靠前端过滤，停用的分类依然会出现在接口返回里，
 * 任何人打开 F12 都能看见（包括还没准备好对外公布的分类）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "分类信息")
public class CategoryVO {

    @Schema(description = "分类 id", example = "1")
    private Long id;

    @Schema(description = "分类名称", example = "玄幻")
    private String name;

    /**
     * 排序值，<b>越小越靠前</b>。
     *
     * <p>★ 这里不叫 {@code order}，因为 {@code ORDER} 是 SQL 关键字 ——
     * 字段名映射成列名时不用转义的话会直接语法错误。
     * 实体和表列都叫 {@code sort}，保持一致。
     */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    /**
     * 1 启用 / 0 停用。
     *
     * <p>用 {@code Integer} 而不是 {@code Boolean}：列类型是 {@code TINYINT}，
     * 而且将来可能不止两个值（比如 2 = 仅作者可见）。
     *
     * <p>★ 读者端拿到的这个字段<b>恒为 1</b>（查询条件已经过滤了），
     * 但依然返回它 —— 前端两个端共用一份渲染逻辑时不用做分支。
     */
    @Schema(description = "状态：1 启用，0 停用。读者端恒为 1", example = "1")
    private Integer status;

    /**
     * 创建时间。
     *
     * <p><b>读者端用不到它</b>（分类列表页不显示创建时间），管理端要显示。
     * 值不值得为这 ~20 字节分两个 VO？不值得 —— 8 条分类，
     * 读者端多拿的是 160 字节，而分成两个类要多维护一个文件加一个转换方法。
     *
     * <p>格式由 {@code JacksonConfig} 统一成 {@code yyyy-MM-dd HH:mm:ss}。
     */
    @Schema(description = "创建时间", example = "2026-09-20 10:30:00")
    private LocalDateTime createTime;

    /**
     * 从实体转换。
     *
     * <p><b>★ 没有 {@code deleted} 字段，也不需要判断它</b>：
     * {@code NovelCategory} 上有 {@code @TableLogic}，被逻辑删除的行
     * 根本查不出来（MP 生成的 SELECT 会自动带 {@code deleted = 0}）。
     * 这里再判一次是多余的，更重要的是会给人「实体里带 deleted，
     * 所以每个 VO 都得自己过滤一遍」的错觉。
     */
    public static CategoryVO of(NovelCategory entity) {
        CategoryVO vo = new CategoryVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setSort(entity.getSort());
        vo.setStatus(entity.getStatus());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
