package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 小说分类，对应 {@code novel_category}。
 *
 * <p><b>★ 这张表有两个「删不掉」的陷阱，动删除逻辑前先看这里：</b>
 * <ol>
 *   <li><b>{@code uk_name} 这个唯一键不包含 {@code deleted}。</b>
 *       逻辑删除只是把 {@code deleted} 置 1，那一行<b>还在索引里</b> ——
 *       于是「删掉『玄幻』之后再也建不出『玄幻』」。
 *       解法是把名字本身改掉（见 {@code NovelCategoryMapper#logicDeleteAndFreeName}）。</li>
 *   <li><b>已被小说引用的分类不能删。</b>
 *       {@code novel.category_id} 是 {@code NOT NULL}，分类没了作品就成孤儿，
 *       读者端会出现「列表里有这本书、分类筛选里却没有它」的怪状。
 *       所以要抛 {@code CATEGORY_HAS_NOVELS} 让管理员先把书挪走。</li>
 * </ol>
 * 想「下架一个分类但保留数据」请用 {@code status = 0}（停用），不要删除 ——
 * 停用只是让读者端列表不显示它，已有作品照常挂着。
 *
 * @author MickeyChen
 */
@Data
@TableName("novel_category")
public class NovelCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名，全局唯一（★ 唯一键不含 deleted，见类注释第 1 点） */
    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：1 启用，0 停用。★ 停用 ≠ 删除，停用不做引用检查 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
