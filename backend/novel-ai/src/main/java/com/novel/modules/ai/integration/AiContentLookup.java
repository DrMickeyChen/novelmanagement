package com.novel.modules.ai.integration;
import com.novel.modules.ai.client.AiContentClient;
import com.novel.common.internal.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class AiContentLookup {
 private final AiContentClient client;
 public AiNovelDTO requireOwned(Long id) { return InternalResults.unwrapBusiness(client.ownedNovel(id), "AI novel ownership"); }
 public AiChapterDTO requireOwnedChapter(Long id) { return InternalResults.unwrapBusiness(client.ownedChapter(id), "AI chapter ownership"); }
 public AiOutlineDTO requireOwnedOutlineNode(Long id) { return InternalResults.unwrapBusiness(client.ownedOutline(id), "AI outline ownership"); }
 public List<AiOutlineDTO> outlinesOfChapter(Long id) { return InternalResults.unwrapBusiness(client.chapterOutlines(id), "AI chapter outlines"); }
 public List<AiChapterDTO> chapterRange(Long id, int start, int end) { return InternalResults.unwrapBusiness(client.range(id,start,end), "AI chapter range"); }
 public List<AiChapterContentDTO> ownedContents(Collection<Long> ids) { return InternalResults.unwrapBusiness(client.ownedContents(ids), "AI owned contents"); }
 public List<AiChapterDTO> chapters(Collection<Long> ids) { return ids.isEmpty()?List.of():InternalResults.unwrap(client.chapters(ids), "AI chapter facts"); }
 public List<AiNovelDTO> novels(Collection<Long> ids) { return ids.isEmpty()?List.of():InternalResults.unwrap(client.novels(ids), "AI novel facts"); }
 public AiChapterDTO chapter(Long id) { return InternalResults.unwrap(client.chapter(id), "AI audit chapter"); }
 public AiChapterContentDTO chapterContent(Long id) { return InternalResults.unwrap(client.chapterContent(id), "AI audit text"); }
 public List<AiDispositionDTO> dispositions(List<AiDispositionQuery> rows) { return rows.isEmpty()?List.of():InternalResults.unwrap(client.dispositions(rows), "AI human dispositions"); }
}
