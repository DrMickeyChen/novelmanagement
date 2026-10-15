package com.novel.common.internal;

import java.util.List;

/** Private writer protocol. All IDs are decimal strings at HTTP boundaries. */
public final class WritingContent {
    private WritingContent() {}
    public record Source(String id, String chapterId, int chapterNo, String title,
                         long version, int start, int end, boolean truncated, String text) {}
    public record Snapshot(String chapterId, String novelId, int chapterNo, String title,
                           long version, String content, String nodeId, String outline,
                           String outlineHash, List<Source> sources, List<Note> notes,
                           List<Setting> settings, List<Coverage> coverage, String catalogHash) {
        public Snapshot { sources=sources==null?List.of():sources;notes=notes==null?List.of():notes;settings=settings==null?List.of():settings;coverage=coverage==null?List.of():coverage; }
        public Snapshot(String chapterId,String novelId,int chapterNo,String title,long version,String content,String nodeId,String outline,String outlineHash,List<Source> sources) {
            this(chapterId,novelId,chapterNo,title,version,content,nodeId,outline,outlineHash,sources,List.of(),List.of(),List.of(),"");
        }
    }
    public record Note(String id,String level,String title,String text,String hash) {}
    public record Setting(String id,String kind,String name,String text,String meaning,long version,
                          int effectiveFrom,Integer effectiveTo,boolean core,String reason) {}
    public record Coverage(String chapterId,int chapterNo,String title,long version,int totalChars,int readChars,String reason) {}
    public record ContextRequest(long nodeId,List<Long> chapterIds,List<String> settingIds,String goal) {}
    public record ResearchRequest(long nodeId,String query,int limit) {}
    public record PrivateChunk(String novelId,Source source,List<Double> vector,String embeddingId) {}
    public record PrivateRecall(String novelId,List<String> sourceKeys,String query,List<Double> vector,String embeddingId,int limit) {}
    public record PrivateEvidence(List<Source> sources,String mode) {}
    public record Patch(String taskId, String draftAttemptId, long expectedVersion,
                        int start, int end, String original, String replacement, String key) {}
    public record Undo(String taskId, String adoptionId, long expectedVersion, String key) {}
    public record MergePatch(String taskId,String draftAttemptId,long expectedVersion,long baseVersion,
                             String baseContent,int start,int end,String original,String replacement,
                             String resolved,boolean approveResolved,String key) {}
    public record MergeView(long currentVersion,WritingMerge.Preview preview) {}
    public record Applied(String adoptionId, String chapterId, long version, String content, String title) {}
}
