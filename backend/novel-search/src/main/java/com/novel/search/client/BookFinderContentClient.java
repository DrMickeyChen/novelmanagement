package com.novel.search.client;

import com.novel.common.internal.BookFinderBook;
import com.novel.common.internal.BookFinderQuery;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "novel-content", contextId = "bookFinderContent", url = "${novel.book-finder.content-url:}")
public interface BookFinderContentClient {
    @PostMapping("/internal/book-finder/search")
    Result<List<BookFinderBook>> search(@RequestBody BookFinderQuery query);
}
