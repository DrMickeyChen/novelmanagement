package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违禁词（管理端读出用）。
 *
 * <p>★ 有一层 VO 而不是直接返回实体，是本项目一贯的做法 ——
 * 它挡住的是「实体加了个内部字段，管理端接口<b>自动</b>多返回一列」这种被动泄漏。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "违禁词")
public class SensitiveWordVO {

    @Schema(description = "词 id")
    private Long id;

    @Schema(description = "违禁词")
    private String word;

    /** ★ 由服务端从枚举取，前端不要自己映射 */
    @Schema(description = "风险等级：1低 2中 3高")
    private Integer level;

    /** ★ 等级的中文名。同 {@code AiPromptTemplateVO.sceneLabel} 的理由：
     *  前端自己维护一份「1 → 低」的映射，漏改的症状是<b>表格里显示错的等级</b>，而接口一切正常 */
    @Schema(description = "风险等级名")
    private String levelLabel;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "1 启用，0 停用")
    private Integer enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后修改时间")
    private LocalDateTime updateTime;
}
