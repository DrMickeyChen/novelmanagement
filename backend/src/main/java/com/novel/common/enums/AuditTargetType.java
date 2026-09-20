package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 审核对象的类型 —— 说明这条审核记录是审什么的。
 *
 * <p>作品、章节、评论、作者申请共用一张 {@code audit_log} 表（{@code audit_log.target_type} 列），
 * 靠这个字段区分。好处是管理员的「审核日志」一次查询就能把
 * 所有类型混在一起按时间倒序排出来，不用 union 四张表。
 *
 * @author MickeyChen
 */
@Getter
public enum AuditTargetType {

    NOVEL(1, "作品"),
    CHAPTER(2, "章节"),
    COMMENT(3, "评论"),
    AUTHOR_APPLY(4, "作者申请");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    AuditTargetType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** ★ 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException} —— 理由见 {@link NovelStatus#fromCode} */
    public static AuditTargetType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的审核对象类型：" + code));
    }
}
