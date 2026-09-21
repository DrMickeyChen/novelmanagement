package com.novel.modules.content.vo;

import com.novel.modules.content.entity.Chapter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 章节<b>摘要</b>出参：只够渲染「第 12 章 · 风起」这七个字。
 *
 * <p>调用方手里只有一串 chapterId（来自 {@code read_history}），
 * 要的只是「这一章叫什么、是第几章」。{@code ChapterVO} 带着
 * {@code status} / {@code wordCount} / {@code viewCount} / {@code novelTitle}
 * 这些给作者端和管理端列表看的字段，对一个读者的书架没有意义。
 *
 * <p>★ <b>{@code chapterNo} 是必须的，不能只给标题。</b>
 * 「读到《风起》」和「读到第 12 章《风起》」给读者的信息量差很远 ——
 * 前者他不知道离看完还有多远。而章节号是<b>书内的序号</b>，
 * 前端拿 {@code novel.chapterCount} 一减就是「还剩 N 章」。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "章节摘要（阅读进度用）")
public class ChapterBriefVO {

    @Schema(description = "章节 id", example = "120")
    private Long id;

    @Schema(description = "所属作品 id", example = "1")
    private Long novelId;

    @Schema(description = "书内章节序号", example = "12")
    private Integer chapterNo;

    @Schema(description = "章节标题", example = "风起")
    private String title;

    public static ChapterBriefVO of(Chapter chapter) {
        ChapterBriefVO vo = new ChapterBriefVO();
        vo.setId(chapter.getId());
        vo.setNovelId(chapter.getNovelId());
        vo.setChapterNo(chapter.getChapterNo());
        vo.setTitle(chapter.getTitle());
        return vo;
    }
}
