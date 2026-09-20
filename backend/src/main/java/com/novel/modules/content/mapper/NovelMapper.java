package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.Novel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说表 Mapper。
 *
 * <p><b>这个 Mapper 一条手写 SQL 都没有，是刻意的</b> —— 作品的所有查询
 * （列表筛选、状态过滤、关键词模糊）都能用 {@code LambdaQueryWrapper} 在 Service 层拼出来，
 * 而 Wrapper 的好处是 {@code @TableLogic} 会自动补 {@code deleted = 0}。
 * 一旦手写成 {@code @Select}，这个保护就没了，得自己记得写。
 *
 * <p>对比 {@code ChapterMapper} —— 那边有三条必须手写（{@code MAX} / {@code SUM} /
 * 改序号腾唯一键），所以那边的注释要长得多。
 *
 * @author MickeyChen
 */
@Mapper
public interface NovelMapper extends BaseMapper<Novel> {
}
