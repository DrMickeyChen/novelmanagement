package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

/**
 * 作品状态机。见 {@code 项目大纲.md §5.3}。
 *
 * <pre>
 *   0 草稿 ──提交审核──▶ 1 待审核 ──管理员通过──▶ 2 已上架
 *                          │                      │
 *                          └──管理员驳回──▶ 4 已驳回   管理员下架
 *                                             │         ▼
 *                                             └──▶ 0 草稿   3 已下架
 *                                                        │
 *                                                   重新上架
 *                                                        ▼
 *                                                      2 已上架
 * </pre>
 *
 * <p><b>为什么要用枚举而不是散落的 0/1/2/3/4：</b>
 * 判断状态的地方会有几十处，写成 {@code if (novel.getStatus() == 2)}
 * 三个月后没人记得 2 是什么；而 {@code NOVEL_STATUS_ILLEGAL} 这类
 * 非法流转必须在 Service 层拦住，用一个 {@link #canTransitTo} 方法
 * 比到处写 if 靠谱得多。
 *
 * @author MickeyChen
 */
@Getter
public enum NovelStatus {

    DRAFT(0, "草稿"),
    PENDING(1, "待审核"),
    PUBLISHED(2, "已上架"),
    OFFLINE(3, "已下架"),
    REJECTED(4, "已驳回");

    /** 存进数据库的值 */
    @EnumValue
    @JsonValue
    private final Integer code;

    /** 给用户看的文案 */
    private final String desc;

    NovelStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static NovelStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的作品状态：" + code));
    }

    /**
     * 当前状态能不能流转到目标状态。
     *
     * <p>所有改状态的接口都必须先过这一关，例如：
     * <pre>
     * if (!novel.getStatus().canTransitTo(NovelStatus.PUBLISHED)) {
     *     throw new BusinessException(ResultCode.NOVEL_STATUS_ILLEGAL);
     * }
     * </pre>
     * 少了这一句，「已上架的作品又被提交审核」这种脏数据就会进库，
     * 后面查起来非常痛苦。
     */
    public boolean canTransitTo(NovelStatus target) {
        return switch (this) {
            // 草稿可以提交审核，也可以直接删除（不在这里表达）
            case DRAFT -> target == PENDING;
            // 待审核：通过 → 上架；驳回 → 已驳回
            case PENDING -> target == PUBLISHED || target == REJECTED;
            // 已上架：可以下架
            case PUBLISHED -> target == OFFLINE;
            // 已下架：可以重新提交审核
            case OFFLINE -> target == PENDING;
            // 已驳回：作者改完可以重新提交
            case REJECTED -> target == PENDING;
        };
    }

    /** 读者能不能看到这个作品（只有已上架的对外可见） */
    public boolean isVisibleToReader() {
        return this == PUBLISHED;
    }
}
