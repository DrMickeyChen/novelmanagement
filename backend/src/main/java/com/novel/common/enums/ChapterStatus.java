package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

/**
 * 章节状态机。见 {@code 项目大纲.md §5.3}。
 *
 * <pre>
 *   0 草稿 ──提交审核──▶ 1 待审核 ──管理员通过──▶ 2 已发布
 *                          │
 *                          └──管理员驳回──▶ 4 已驳回 ──改完重新提交──▶ 1 待审核
 * </pre>
 *
 * <p>注意章节<b>没有</b>「已下架」状态 —— 章节不能单独下架，
 * 只能跟着作品一起下架（作品下架后读者根本进不来）。
 *
 * @author MickeyChen
 */
@Getter
public enum ChapterStatus {

    DRAFT(0, "草稿"),
    PENDING(1, "待审核"),
    PUBLISHED(2, "已发布"),
    REJECTED(4, "已驳回");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    ChapterStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ChapterStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的章节状态：" + code));
    }

    public boolean canTransitTo(ChapterStatus target) {
        return switch (this) {
            // 草稿可以提交审核，也可以删掉（不在这里表达）
            case DRAFT -> target == PENDING;
            case PENDING -> target == PUBLISHED || target == REJECTED;
            // 已发布的章节要改内容，得先退回草稿再走一遍审核
            case PUBLISHED -> target == DRAFT;
            case REJECTED -> target == PENDING;
        };
    }

    /** 读者能不能看到这一章（只有已发布的对外可见） */
    public boolean isVisibleToReader() {
        return this == PUBLISHED;
    }
}
