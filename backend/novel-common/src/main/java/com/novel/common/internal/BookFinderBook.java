package com.novel.common.internal;

/** Only public book data; string IDs preserve JavaScript precision. */
public record BookFinderBook(String id, String title, String author, String cover, String category,
                             String tags, String intro, boolean completed, int wordCount) {
    public boolean matches(BookFinderQuery q) {
        return (q.category() == null || q.category().equals(category))
                && (q.completed() == null || q.completed() == completed)
                && (q.minWords() == null || wordCount >= q.minWords())
                && (q.maxWords() == null || wordCount <= q.maxWords())
                && (q.tag() == null || tags != null && java.util.Arrays.stream(tags.split("[,，]")).anyMatch(q.tag()::equals));
    }
}
