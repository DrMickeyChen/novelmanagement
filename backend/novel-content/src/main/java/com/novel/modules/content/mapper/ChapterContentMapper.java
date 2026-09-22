package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.ChapterContent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 章节正文 Mapper。
 *
 * <p>读写都只用 {@code selectById} / {@code insert} / {@code updateById} 三件事，
 * 不需要自定义 SQL。
 *
 * <p><b>★ 注意这张表没有 {@code deleted} 列</b>，所以没有逻辑删除 ——
 * 正文的「删除」是跟着 {@code chapter} 走的：chapter 被逻辑删了，
 * 它的正文就永远查不到了（这里没有外键，也就没有级联，正文行会留在表里
 * 成为孤儿数据。可以接受：占的是空间，不影响正确性，
 * 真要清理就等二期做数据归档时一起做）。
 *
 * @author MickeyChen
 */
@Mapper
public interface ChapterContentMapper extends BaseMapper<ChapterContent> {
}
