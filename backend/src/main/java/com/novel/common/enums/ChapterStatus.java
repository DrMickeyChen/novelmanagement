package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
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

    /**
     * 按数字码取枚举，用于解析<b>查询参数</b>（如 {@code ?status=2}）。
     *
     * <p>★ 和 {@link NovelStatus#fromCode} 是同一套规则，理由见那里的注释：
     * query 参数统一用数字（和 JSON body 保持一致），
     * 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException}。
     *
     * <p><b>★ 注意章节没有 3。</b>编号是 0/1/2/4 —— 3 是作品独有的「已下架」，
     * 章节不能单独下架（见本类开头的注释）。所以 {@code ?status=3} 会得到
     * 「未知的章节状态：3」，这是<b>正确</b>行为，不是漏配。
     */
    public static ChapterStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的章节状态：" + code));
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

    /**
     * 作者能不能<b>直接</b>改这一章的正文。
     *
     * <p><b>和 {@code NovelStatus#isEditable} 的规则对齐，但多一种情况要处理：</b>
     * <ul>
     *   <li>{@code DRAFT} / {@code REJECTED} —— 可以直接改，这是最正常的情况。</li>
     *   <li>{@code PENDING} 待审核 —— <b>不能改。</b>审核员弹窗预览的是 A 版正文，
     *       作者在审核期间换成 B 版，审核员点通过，读者读到的是 B ——
     *       <b>审核被绕过</b>，而日志上是一次完全正常的审核。</li>
     *   <li>{@code PUBLISHED} 已发布 —— <b>这里返回 false，但不代表不能改</b>，
     *       而是「不能<b>直接</b>改」：要先把章节退回草稿
     *       （{@code PUBLISHED → DRAFT}，见 {@link #canTransitTo}），
     *       改完再重新走一遍审核。<b>这个退回动作由
     *       {@code ChapterServiceImpl#saveContent} 自动完成</b> ——
     *       因为已发布的章节想改内容是合理需求，让作者为它先找个
     *       「退回草稿」按钮（大纲的接口表里根本没有这个按钮）等于把人堵死。</li>
     * </ul>
     * 所以调用方看到 {@code false} 时要分清楚是哪一种，别一律当成「不许改」。
     */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }
}
