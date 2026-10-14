package com.novel.search.controller;

import com.novel.common.internal.*;
import com.novel.common.result.Result;
import com.novel.search.client.BookFinderContentClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Accurate bounded structured queries. Existing keyword ES search remains unchanged. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/book-finder")
public class InternalBookFinderSearchController {
    private final BookFinderContentClient content;
    @PostMapping("/search")
    public Result<List<BookFinderBook>> search(@RequestBody BookFinderQuery query) {
        return Result.success(InternalResults.unwrapBusiness(content.search(query), "找书条件检索"));
    }
}
