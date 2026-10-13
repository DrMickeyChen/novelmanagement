package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑违禁词的入参（E1/S7）。两个接口共用 —— 字段完全一样。
 *
 * <p>★ <b>字段长度校验必须和库里的列对齐</b>（同 {@code CategorySaveDTO} 的论证）：
 * 不对齐就是「一直传到 MySQL 才报 {@code Data too long}」，那是个 500，
 * 管理员看到的是「服务器开小差了」，完全不知道是自己写长了。
 *
 * <p>★★ <b>这里没有 {@code deleted}，也没有 {@code id}</b>：删除这件事本产品不提供，
 * 只提供 {@link #enabled} 停用 —— 见 {@code SensitiveWord} 的类注释。
 * ★ 少一个「物理删除」入口，就少一条「谁把词删了」无法回答的路径。
 * （库里的行确实可以被 {@code DELETE} 掉，那走的是 S7 验收脚本，
 * 不是管理端 —— 验收脚本用字面 id 的 {@code IN (...)}，见铁律 5。）
 *
 * <p>★★★ <b>保存【不】自动 reload 快照</b>，这是刻意的：
 * 改库和「改动生效」是两件事，中间隔着一个显式的 reload（或 10 分钟后的定时兜底）。
 * <b>自动 reload 会把「快照真的被替换过」这件事变得无法验证</b> ——
 * 每次保存都换一份快照，就再也分不出「生效是因为我保存了」还是「因为它本来就该生效」。
 * 管理端的词库页会并排显示「库里的启用条数」与「快照里的词条数」，
 * 两个数不相等时页面自己会说出来。<b>「我改了」和「生效了」必须是两个能分开的动作。</b>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "违禁词新增 / 编辑入参")
public class SensitiveWordSaveDTO {

    /**
     * 违禁词本身。
     *
     * <p>★ {@code @NotBlank} 而不是 {@code @NotNull}：一个纯空格的词是<b>合法输入</b>吗？
     * 不是 —— 它会命中任何一处空格，把整站的内容全判成高危。
     * ★ 空串更糟：它<b>在 DFA 里无法表示</b>（构建时会直接被当成根节点的终结标记），
     * 那种形状的匹配结果是「文本的第一个字符就算命中」，而日志上一切正常。
     * 所以这一条<b>必须</b>在入库前挡住，不能靠「反正运营不会这么填」。
     */
    @Schema(description = "违禁词，两端空白会被去掉", example = "占位敏感词戊",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "违禁词不能为空")
    @Size(max = 64, message = "违禁词最多 64 个字")
    private String word;

    /** 1 低 2 中 3 高。★ 与 {@code ai_audit_result.risk_level} 同刻度，别在这里开第二套 */
    @Schema(description = "风险等级：1低 2中 3高。★ 与 ai_audit_result.risk_level 同刻度",
            example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "风险等级不能为空")
    @Min(value = 1, message = "风险等级只能是 1~3")
    @Max(value = 3, message = "风险等级只能是 1~3")
    private Integer level;

    /** 分类。★ 只给运营看，不参与任何判定 —— 别拿它做筛选条件的粒度 */
    @Schema(description = "分类：政治/色情/暴力/广告…，只给运营看", example = "演示")
    @Size(max = 32, message = "分类最多 32 个字")
    private String category;

    /** 1 启用 0 停用。不传按 1。★ 停用后 reload 即立刻不再命中 */
    @Schema(description = "1 启用，0 停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "启用状态只能是 0 或 1")
    @Max(value = 1, message = "启用状态只能是 0 或 1")
    private Integer enabled = 1;

    @Schema(description = "备注，给运营写来源和时效")
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
