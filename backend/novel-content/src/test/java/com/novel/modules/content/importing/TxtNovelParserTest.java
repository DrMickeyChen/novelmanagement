package com.novel.modules.content.importing;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class TxtNovelParserTest {
    private final TxtNovelParser parser = new TxtNovelParser();

    @Test void parsesQuotedMetadataAndPreservesChapterBody() throws Exception {
        var chapters = new ArrayList<TxtNovelParser.ChapterText>();
        var result = parser.parse(new StringReader("『神秘复苏/作者:佛前献花』\n『状态:已完结』\n『内容简介:\n一个故事。\n』\n第1章论坛里的故事。\n　　正文第一行。\n第二行。\n第二章 新的故事\n后续。"), "download.txt", "AUTO", chapters::add);
        assertEquals("神秘复苏", result.title());
        assertEquals("佛前献花", result.originalAuthor());
        assertEquals(1, result.isFinish());
        assertEquals("一个故事。", result.intro());
        assertEquals(2, result.chapterCount());
        assertTrue(chapters.getFirst().content().contains("第二行。"));
        assertFalse(chapters.getFirst().content().contains("内容简介"));
    }

    @Test void recognizesSeparateFieldsAndEnglishChapters() throws Exception {
        var result = parser.parse(new StringReader("书名：测试书\n作者：测试作者\n分类：悬疑\n标签：推理,成长\n状态：连载\nChapter 1: Beginning\ntext\nChapter II Next\nmore"), "x.txt", "AUTO", c -> {});
        assertEquals("测试作者", result.originalAuthor());
        assertEquals("悬疑", result.category());
        assertEquals(0, result.isFinish());
        assertEquals(2, result.chapterCount());
    }

    @Test void ambiguousNumberedHeadingsRequireExplicitRule() throws Exception {
        String input = "《测试》\n一、开始\n正文\n2.继续\n更多";
        assertEquals(0, parser.parse(new StringReader(input), "x.txt", "AUTO", c -> {}).chapterCount());
        assertEquals(2, parser.parse(new StringReader(input), "x.txt", "NUMBERED", c -> {}).chapterCount());
    }

    @Test void processesFiveMillionCharactersWithoutCollectingBodies() throws Exception {
        StringBuilder input = new StringBuilder();
        for (int chapter = 1; chapter <= 1000; chapter++)
            input.append("第").append(chapter).append("章 测试\n").append("文".repeat(5000)).append('\n');
        int[] emitted = {0};
        var result = parser.parse(new StringReader(input.toString()), "large.txt", "AUTO", c -> {
            assertEquals(5000, c.wordCount()); emitted[0]++;
        });
        assertEquals(1000, emitted[0]);
        assertEquals(5_000_000, result.wordCount());
        assertEquals(1200, result.sample().length());
    }

    @Test void flagsEmptyContentsAndRejectsInvalidText() throws Exception {
        var chapters = new ArrayList<TxtNovelParser.ChapterText>();
        var result = parser.parse(new StringReader("第1章 目录\n第2章 正文\n文字"), "x.txt", "AUTO", chapters::add);
        assertEquals(2, chapters.size());
        assertEquals("第1章 目录", chapters.getFirst().title());
        assertEquals("", chapters.getFirst().content());
        assertEquals("文字", chapters.get(1).content());
        assertEquals(0, result.chapters().getFirst().wordCount());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("空章节")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader("\0"), "x.txt", "AUTO", c -> {}));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader("文".repeat(200001)), "x.txt", "AUTO", c -> {}));
    }
}
