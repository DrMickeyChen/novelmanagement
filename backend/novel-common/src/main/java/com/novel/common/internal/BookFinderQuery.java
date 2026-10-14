package com.novel.common.internal;

import com.novel.common.exception.BusinessException;
import java.util.List;

/** Reader search contract. All fields are optional except the bounded result limit. */
public record BookFinderQuery(String keyword, String category, String tag, Boolean completed,
                              Integer minWords, Integer maxWords, List<String> excludeIds, Integer limit) {
    public BookFinderQuery {
        keyword = clean(keyword, 100); category = clean(category, 40); tag = clean(tag, 40);
        excludeIds = excludeIds == null ? List.of() : List.copyOf(excludeIds);
        limit = limit == null ? 20 : limit;
        if (limit < 1 || limit > 20 || excludeIds.size() > 500
                || minWords != null && minWords < 0 || maxWords != null && maxWords < 0
                || minWords != null && maxWords != null && minWords > maxWords
                || excludeIds.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,18}"))) {
            throw new BusinessException(400, "找书条件或候选数量不合法");
        }
        for (String id : excludeIds) {
            try { Long.parseLong(id); } catch (NumberFormatException e) { throw new BusinessException(400, "作品 ID 不合法"); }
        }
    }
    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        if (s.length() > max) throw new BusinessException(400, "找书条件过长");
        return s.trim();
    }
}
