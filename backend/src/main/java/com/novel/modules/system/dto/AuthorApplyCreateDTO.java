package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 读者提交作者申请的入参。
 *
 * <p><b>★ 为什么只有两个字段，没有 {@code userId}：</b>
 * 申请人一律从 {@link com.novel.common.security.UserContext} 取当前登录用户。
 * 让它成为入参就等于给了「替别人申请」的能力 —— 而审核通过会给
 * <b>那个人</b>发 AUTHOR 角色，一次越权提交就是一次越权授权。
 * 和 {@code AuditLogService#record} 不收 {@code operatorId} 是同一条规矩。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者申请入参")
public class AuthorApplyCreateDTO {

    /**
     * 笔名，必填。
     *
     * <p>★ <b>必填是产品判断，不是数据洁癖</b>：审核员看到一个没有笔名的申请，
     * 唯一的操作就是驳回，而那一次驳回消耗的是一个真实用户的等待。
     * 与其让审核员替他补，不如提交时就拦住。
     */
    @Schema(description = "笔名", example = "墨白", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写笔名")
    @Size(max = 50, message = "笔名最多 50 个字")
    private String penName;

    /** 创作说明，可空（理由见 {@code AuthorApply#intro}） */
    @Schema(description = "创作说明，可空", example = "写过两本都市文，想在这里连载一本长篇")
    @Size(max = 500, message = "创作说明最多 500 个字")
    private String intro;
}
