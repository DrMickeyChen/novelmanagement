package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 章节正文，对应 {@code chapter_content}。和 {@link Chapter} 一对一。
 *
 * <p><b>为什么要单独一张表（大纲 §1343、§230）：</b>目录页一次要查几十上百章，
 * 正文和元信息同表的话 {@code SELECT *} 会把几百 KB 正文一起拖出来。
 * 分离后目录查询只扫小表，只有打开具体章节才读正文。
 *
 * <p><b>★ 两个和别的实体不一样的地方，写代码时容易踩：</b>
 * <ol>
 *   <li><b>主键是 {@code IdType.INPUT} 不是 {@code AUTO}。</b>
 *       这里的主键 {@code chapter_id} 是「所属章节的 id」，由调用方传进来，
 *       数据库这边不是自增列。写成 {@code AUTO} 的话 MyBatis-Plus 会
 *       <b>忽略你传的值</b>，让数据库去生成，插进去的正文就挂到别的章节上了。</li>
 *   <li><b>没有 {@code deleted} 列，所以没有 {@code @TableLogic}。</b>
 *       加了的话 MyBatis-Plus 会往 {@code WHERE} 里拼一个不存在的字段，
 *       直接 {@code Unknown column}。正文的删除跟着 {@code chapter} 走 ——
 *       chapter 被逻辑删了，它的正文就永远查不到了（没有外键，也就没有级联，
 *       这行正文会留在表里成为孤儿数据，可以接受：它占的是空间，不影响正确性）。</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Data
@TableName("chapter_content")
public class ChapterContent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 与 {@code chapter.id} 一对一。★ 手工传入，不是自增 */
    @TableId(type = IdType.INPUT)
    private Long chapterId;

    /** 正文。纯文本，不做富文本 —— 富文本要额外处理 XSS（大纲 §1347） */
    private String content;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
