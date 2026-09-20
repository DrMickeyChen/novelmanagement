package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

/**
 * 审核记录的处理结果，用在 {@code sys_audit_log} 表上。
 *
 * <p><b>和 NovelStatus / ChapterStatus 的区别：</b>
 * 那两个是「东西现在是什么状态」，这个是「这一次审核做了什么动作」。
 * 作品状态只有 5 个值，但审核记录会攒很多条 —— 比如一个作品被驳回两次
 * 又通过一次，就产生 3 条审核记录，用它来回答
 * 「这个作品被谁、什么时候、因为什么改过状态」。
 *
 * @author MickeyChen
 */
@Getter
public enum AuditStatus {

    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已驳回");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    AuditStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AuditStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的审核状态：" + code));
    }
}
