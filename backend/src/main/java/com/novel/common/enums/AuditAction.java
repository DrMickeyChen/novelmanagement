package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 审核日志的「动作」—— 这一次操作对目标做了什么。用在 {@code audit_log.action} 列上。
 *
 * <p><b>★★ 注意别和 {@link AuditStatus} 搞混，这两个枚举长得像但用途完全不同：</b>
 * <pre>
 *   AuditAction  回答「管理员做了【什么】」→ 提交 / 通过 / 驳回 / 下架 / 重新上架
 *                编码 1/2/3 来自 audit_log 表的列注释（大纲 §531）
 *
 *   AuditStatus  回答「这东西现在【是】什么」→ 待审核 / 已通过 / 已驳回
 *                编码 0/1/2，用在 author_apply.status 和 novel_comment.status 上
 * </pre>
 * 看着名字像就拿来往 {@code audit_log.action} 里写，会写进去一个 <b>0</b>，
 * 而这一列的合法值是 1/2/3 —— 审核日志页会显示成空白或错位，且不报错。
 * 所以 {@link AuditStatus} 的注释里也反向写了一句警示。
 *
 * <p><b>1/2/3 是 DB 列注释里就有的三个值</b>（{@code action(1提交 2通过 3驳回)}），
 * 严格对齐、不能改。
 *
 * <p><b>★ 4/5 是本项目在原三值上的扩展，DB 列注释没有跟着改</b> ——
 * 改列注释要重跑 DDL，为一句注释不值得。扩展的理由：上架 / 下架同样是管理员
 * 改变作品对外可见性的动作，如果只记审核不记上下架，那么「这本书怎么突然
 * 从读者端消失了」在日志里永远查不出来 —— 而这恰恰是运营最常问的问题。
 *
 * <p><b>★ 连带影响（将来做统计时要记得）：</b>若按 {@code audit_log.action}
 * 做「审核通过率」之类的统计，<b>只统计 1/2/3</b>，把 4/5/6 算进分母会让通过率虚低。
 *
 * <p><b>REVERT(6) 是给章节用的，作品用不到：</b>
 * 已发布的章节要改正文，必须先退回草稿再重走审核（见 {@code ChapterStatus}）——
 * 这一步是个状态流转，就得留痕，否则日志上会出现「章节还是已发布，
 * 但内容变了」的空档，说不清是谁什么时候改的。
 * 作品没有这一步：作品改信息不需要经过内容审核，只有上下架。
 *
 * @author MickeyChen
 */
@Getter
public enum AuditAction {

    SUBMIT(1, "提交"),
    APPROVE(2, "通过"),
    REJECT(3, "驳回"),
    OFFLINE(4, "下架"),
    REPUBLISH(5, "重新上架"),
    REVERT(6, "退回草稿");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    AuditAction(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** ★ 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException} —— 理由见 {@link NovelStatus#fromCode} */
    public static AuditAction fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(a -> a.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的审核动作：" + code));
    }
}
