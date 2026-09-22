package com.novel.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 章节<b>摘要</b>出参：只够渲染「第 12 章 · 风起」这七个字。
 *
 * <h3>★★ M2：这个类为什么从 modules/content 搬到了 novel-common</h3>
 *
 * <p>理由与 {@link NovelBriefVO} 完全相同（那边写了完整论证），一句话：
 * interaction 的书架 / 阅读历史也要渲染章节名，两边必须共用同一个类型，
 * 否则 interaction 就得 import content 的内部包，两个服务纠缠在一起。
 * <p>★ 静态工厂的签名同样【被改过】：原来是 {@code of(Chapter chapter)}，
 * 而 {@code Chapter} 是 content 的表实体，必须留在 content 那边。
 * 现在收展开的字段值。
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

    /**
     * 组装。★ 参数是<b>展开的字段</b>而不是 {@code Chapter} 实体，
     * 见类注释「静态工厂的签名被改过」。
     */
    public static ChapterBriefVO of(Long id, Long novelId, Integer chapterNo, String title) {
        ChapterBriefVO vo = new ChapterBriefVO();
        vo.setId(id);
        vo.setNovelId(novelId);
        vo.setChapterNo(chapterNo);
        vo.setTitle(title);
        return vo;
    }
}
