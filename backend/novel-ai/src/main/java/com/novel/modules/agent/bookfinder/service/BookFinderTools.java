package com.novel.modules.agent.bookfinder.service;

import com.novel.common.internal.*;
import com.novel.modules.agent.bookfinder.client.*;
import com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared read-only business tools for the internal agent and MCP adapter. */
@Service @RequiredArgsConstructor
public class BookFinderTools {
    private final BookFinderSearchClient search;
    private final BookFinderContentClient content;
    private final BookKnowledgeService knowledge;
    public List<BookFinderBook> searchBooks(BookFinderQuery q) {
        return InternalResults.unwrapBusiness(search.search(q),"找书检索").stream()
                .filter(b->b.matches(q) && !q.excludeIds().contains(b.id())).limit(q.limit()).toList();
    }
    public List<BookFinderBook> getBookDetails(List<String> ids) {
        new BookFinderQuery(null,null,null,null,null,null,ids,20);
        if(ids.isEmpty() || ids.size()>20) throw new com.novel.common.exception.BusinessException(400,"最多查询20本作品");
        return InternalResults.unwrapBusiness(content.details(ids),"找书详情");
    }
    public BookKnowledgeService.Retrieval searchBookEvidence(BookKnowledge.EvidenceQuery q) throws Exception {
        var visible=getBookDetails(q.ids()).stream().map(BookFinderBook::id).toList();
        if(visible.size()!=q.ids().size()) throw new com.novel.common.exception.BusinessException(404,"作品不存在或不可见");
        return knowledge.retrieve(q);
    }
}
