package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

/**
 * 评论审核状态。见 {@code 项目大纲.md §5.3}。
 *
 * <pre>
 *   0 待审 ──通过──▶ 1 已显示
 *      │
 *      └──驳回/管理员隐藏──▶ 2 已隐藏
 * </pre>
 *
 * <p><b>注意和「已删除」的区别：</b>「已隐藏」是管理员的操作（违规内容），
 * 「已删除」是用户自己删的，走的是表的 {@code deleted} 逻辑删除字段，
 * 两者是不同维度 —— 所以这个枚举里没有 DELETED。
 *
 * @author MickeyChen
 */
@Getter
public enum CommentStatus {

    PENDING(0, "待审核"),
    VISIBLE(1, "已显示"),
    HIDDEN(2, "已隐藏");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    CommentStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static CommentStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的评论状态：" + code));
    }

    public boolean canTransitTo(CommentStatus target) {
        return switch (this) {
            // 待审：可以放行，也可以隐藏
            case PENDING -> target == VISIBLE || target == HIDDEN;
            // 已显示的可以被管理员事后隐藏（比如后来发现违规）
            case VISIBLE -> target == HIDDEN;
            // 隐藏后可以恢复
            case HIDDEN -> target == VISIBLE;
        };
    }

    public boolean isVisibleToReader() {
        return this == VISIBLE;
    }
}
