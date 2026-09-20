package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审核流水，对应 {@code audit_log}。每一次状态流转留一条。
 *
 * <p><b>★ 这张表是「追加」的：只 INSERT，从不 UPDATE、从不 DELETE。</b>
 * 流水表一旦能被改写就失去了追责价值 —— 出问题时「谁在什么时候把这本书
 * 下架了」是查账的依据，能被事后改掉就不算证据了。
 *
 * <p><b>★ 两个字段必须注意：</b>
 * <ol>
 *   <li>{@code action} 用 {@link AuditAction}（1提交 2通过 3驳回 4下架 5重新上架），
 *       <b>不要用 {@code AuditStatus}</b>。后者是 0待审/1通过/2驳回，
 *       是给 {@code author_apply.status} 和 {@code novel_comment.status} 用的，
 *       写进来会是一个非法的 0，日志页显示成空白或错位且不报错。</li>
 *   <li><b>没有 {@code updateTime} 字段，这是故意的。</b>
 *       {@code audit_log} 表只有 {@code create_time}，没有 {@code update_time} 列。
 *       加上这个字段，{@code MybatisMetaObjectHandler} 会把值填进去，
 *       MyBatis-Plus 生成的 INSERT 就会带上 {@code update_time} 列 →
 *       {@code Unknown column 'update_time' in 'field list'}。
 *       （顺带一提，表里也没有 {@code deleted}，所以也没有 {@code @TableLogic}。）</li>
 * </ol>
 *
 * <p><b>★ 谁来写这张表：只有 {@code AuditLogService#record}，而它只在
 * {@code NovelService#transitStatus} / {@code ChapterService#transitStatus} 里被调用。</b>
 * 这个链路是刻意收窄的 —— 想绕开审核日志，就只剩「绕开状态机」一条路，
 * 而状态机是唯一能改状态的入口。所以<b>不要在任何 Controller 里直接写 audit_log</b>，
 * 那会让留痕出现缺口，而且缺口不会报错、不会有人发现。
 *
 * @author MickeyChen
 */
@Data
@TableName("audit_log")
public class AuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审核对象类型：1小说 2章节 3评论 4作者申请 */
    private AuditTargetType targetType;

    /** 审核对象的 id（哪个作品 / 哪一章）。★ 和 targetType 一起定位，没有外键 */
    private Long targetId;

    /** 做了什么动作。★ 用 AuditAction，不是 AuditStatus，见类注释 */
    private AuditAction action;

    /** 操作人 {@code sys_user.id}。★ 管理员审的是谁的书、作者自己提交的，都记在这里 */
    private Long operatorId;

    /** 备注。驳回时是驳回理由，通过 / 上下架时一般是空 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
