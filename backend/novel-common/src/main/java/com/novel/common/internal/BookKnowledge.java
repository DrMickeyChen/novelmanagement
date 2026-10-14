package com.novel.common.internal;

import java.util.*;

/** Internal-only contracts: body excerpts must never be serialized to reader responses. */
public final class BookKnowledge {
    private BookKnowledge() {}
    public record ChapterText(String id, int number, String title, String text, String version, boolean truncated) {}
    public record Snapshot(BookFinderBook book, String fingerprint, int requestedChapters,
                           int totalChapters, List<ChapterText> chapters, boolean truncated) {}
    public record Chunk(String id, String chapterId, int chapterNumber, String chapterTitle,
                        int start, int end, String text, List<Double> vector) {}
    public record Document(String novelId, BookFinderBook book, String fingerprint, int requestedChapters,
                           int coveredChapters, int totalChapters, int characterCount, boolean truncated,
                           String builtAt, String embeddingModel, List<Chunk> chunks) {}
    public record EvidenceQuery(List<String> ids, String query) {
        public EvidenceQuery {
            if(ids==null || ids.isEmpty() || ids.size()>20 || query==null || query.isBlank() || query.length()>200)
                throw new com.novel.common.exception.BusinessException(400,"证据查询需要 1～20 本候选书和最多 200 字的问题");
            new BookFinderQuery(null,null,null,null,null,null,ids,20);
            ids=ids.stream().distinct().toList(); query=query.trim();
        }
    }
    public record Hit(String novelId, String chunkId, String chapterId, int chapterNumber, String quote,
                      double relevance, int coveredChapters, int totalChapters, boolean truncated,
                      String fingerprint, String mode) {}
    public record RecallQuery(List<String> ids, String query, List<Double> vector, String embeddingModel) {
        public RecallQuery {
            if(ids==null || ids.isEmpty() || ids.size()>1000 || query==null || query.isBlank() || query.length()>200)
                throw new com.novel.common.exception.BusinessException(400,"Invalid recall query");
            if(vector==null) vector=List.of();
            if(vector.size()>4096 || vector.stream().anyMatch(v->v==null || !Double.isFinite(v)))
                throw new com.novel.common.exception.BusinessException(400,"Invalid query vector");
            for(int i=0;i<ids.size();i+=20) new BookFinderQuery(null,null,null,null,null,null,ids.subList(i,Math.min(i+20,ids.size())),20);
        }
    }
    public record RecallHit(Document document, Chunk chunk, double score) {}
    public record Recall(List<RecallHit> hits,String mode) {}
}
