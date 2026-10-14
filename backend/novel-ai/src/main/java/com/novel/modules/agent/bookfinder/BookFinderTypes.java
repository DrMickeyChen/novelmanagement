package com.novel.modules.agent.bookfinder;

import com.novel.common.internal.BookFinderBook;
import com.novel.common.internal.BookFinderQuery;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;

public final class BookFinderTypes {
    private BookFinderTypes() {}
    public record ChatRequest(@NotBlank @Size(max=1200) String message,
                              @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId,
                              @Pattern(regexp="[0-9a-fA-F-]{36}") String conversationId) {}
    public record Source(String id, String type, int chapterNumber, int coveredChapters, int totalChapters,
                         boolean truncated, String retrievalMode, String notice, String fingerprint) {
        public Source(String id,String type,int chapterNumber,int coveredChapters,int totalChapters,boolean truncated,String retrievalMode,String notice) { this(id,type,chapterNumber,coveredChapters,totalChapters,truncated,retrievalMode,notice,""); }
    }
    public record Preference(String criterion, boolean required) {}
    public record Assessment(String criterion, boolean required, String verdict, List<String> evidenceIds, String scope) {}
    public record Recommendation(BookFinderBook book, String reason, String evidence, List<String> matches, List<Source> sources, List<Assessment> assessments) {
        public Recommendation(BookFinderBook book,String reason,String evidence,List<String> matches,List<Source> sources) { this(book,reason,evidence,matches,sources,List.of()); }
        public Recommendation(BookFinderBook book,String reason,String evidence,List<String> matches) { this(book,reason,evidence,matches,List.of()); }
    }
    public record Usage(int modelCalls, int toolCalls, int totalTokens, long durationMs, int promptTokens, int completionTokens) {
        public Usage(int modelCalls,int toolCalls,int totalTokens,long durationMs) { this(modelCalls,toolCalls,totalTokens,durationMs,0,0); }
    }
    public record Answer(String conversationId, String requestId, String answer, BookFinderQuery conditions,
                         List<Recommendation> recommendations, boolean needsClarification, boolean mock, Usage usage) {}
    public record Turn(String user, String assistant) {}
    @Data
    public static class Conversation {
        private String id;
        private BookFinderQuery conditions = new BookFinderQuery(null,null,null,null,null,null,List.of(),20);
        private List<String> shown = new ArrayList<>();
        private List<BookFinderBook> lastBooks = new ArrayList<>();
        private List<Turn> history = new ArrayList<>();
        private Answer lastAnswer;
        private String preference = "";
        private List<Preference> preferences = new ArrayList<>();
    }
    public record RequestRecord(String fingerprint, String status, Answer answer) {}
}
