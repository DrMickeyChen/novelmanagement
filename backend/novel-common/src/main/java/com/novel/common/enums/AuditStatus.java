package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 「待审核的东西现在被处理到哪一步了」—— 待审核 / 已通过 / 已驳回。
 *
 * <p><b>★★ 这个枚举不用在 {@code audit_log} 表上，别被名字骗了。</b>
 * 它的 0/1/2 正好匹配这两列，它们才是它的用武之地：
 * <pre>
 *   author_apply.status   作者申请（P5）
 *   novel_comment.status  书评审核（P6）
 * </pre>
 * 两列都是「0待审 / 1通过 / 2驳回」，和这里一模一样。
 *
 * <p>{@code audit_log} 那一列叫 {@code action} 不叫 {@code status}，
 * 编码是 <b>1提交 / 2通过 / 3驳回</b>，用的是另一个枚举 {@link AuditAction}。
 * 名字看着像就拿这个去写 {@code audit_log.action}，会写进去一个 0 ——
 * 不在合法值域里，审核日志页显示成空白或错位，而且不会报错。
 *
 * <p><b>为什么明知名字起得含糊也不改名：</b>改名要动 P5/P6 的所有引用点，
 * 而 P2 一行都用不到它 —— 现在改只有风险没有收益。等 P5 真正开始用它、
 * 有了实际调用点时再一并改名更划算。
 *
 * <p><b>和 NovelStatus / ChapterStatus 的区别：</b>
 * 那两个是「东西本身现在是什么状态」，这个是「<b>审核这件事</b>走到哪一步了」。
 * 作品被驳回两次又通过一次会产生 3 条审核记录，用它来回答
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

    /** ★ 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException} —— 理由见 {@link NovelStatus#fromCode} */
    public static AuditStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的审核状态：" + code));
    }
}
