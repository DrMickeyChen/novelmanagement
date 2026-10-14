package com.novel.internal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.enums.NovelStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.BookFinderBook;
import com.novel.common.internal.BookFinderQuery;
import com.novel.common.result.Result;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.entity.NovelCategory;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.mapper.NovelCategoryMapper;
import com.novel.modules.content.importing.NovelSourceService;
import com.novel.modules.content.service.ContentUserLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

/** Internal-token protected queries. Public status is checked on every database read. */
@RestController
@RequestMapping("/internal/book-finder")
@RequiredArgsConstructor
public class InternalBookFinderController {
    private final NovelMapper novels;
    private final NovelCategoryMapper categories;
    private final ContentUserLookup users;
    private final NovelSourceService sources;

    @GetMapping("/categories")
    public Result<List<String>> availableCategories() {
        var ids = novels.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Novel>()
                .select("DISTINCT category_id").eq("status", NovelStatus.PUBLISHED).isNotNull("category_id"));
        if (ids.isEmpty()) return Result.success(List.of());
        return Result.success(categories.selectBatchIds(ids.stream().map(id -> ((Number) id).longValue()).toList()).stream().map(NovelCategory::getName)
                .filter(Objects::nonNull).distinct().sorted().toList());
    }

    @PostMapping("/search")
    public Result<List<BookFinderBook>> search(@RequestBody BookFinderQuery q) {
        var wrapper = new LambdaQueryWrapper<Novel>().eq(Novel::getStatus, NovelStatus.PUBLISHED);
        if (q.category() != null) {
            var found = categories.selectList(new LambdaQueryWrapper<NovelCategory>().eq(NovelCategory::getName, q.category()));
            if (found.isEmpty()) return Result.success(List.of());
            wrapper.in(Novel::getCategoryId, found.stream().map(NovelCategory::getId).toList());
        }
        if (q.completed() != null) wrapper.eq(Novel::getIsFinish, q.completed() ? 1 : 0);
        if (q.minWords() != null) wrapper.ge(Novel::getWordCount, q.minWords());
        if (q.maxWords() != null) wrapper.le(Novel::getWordCount, q.maxWords());
        if (!q.excludeIds().isEmpty()) wrapper.notIn(Novel::getId, q.excludeIds());
        if (q.tag() != null) wrapper.apply("FIND_IN_SET({0}, REPLACE(tags, '，', ',')) > 0", q.tag());
        if (q.keyword() != null) wrapper.and(w -> w.like(Novel::getTitle, q.keyword())
                .or().like(Novel::getIntro, q.keyword())
                .or().apply("id IN (SELECT novel_id FROM novel_source WHERE original_author LIKE {0})", "%"+q.keyword()+"%")
                .or().like(Novel::getTags, q.keyword()));
        wrapper.orderByDesc(Novel::getViewCount).orderByDesc(Novel::getId).last("LIMIT " + q.limit());
        return Result.success(assemble(novels.selectList(wrapper)));
    }

    @PostMapping("/details")
    public Result<List<BookFinderBook>> details(@RequestBody List<String> ids) {
        if (ids == null || ids.size() > 20) throw new BusinessException(400, "每次最多查询 20 本书");
        // Reuse the contract's numeric ID validation.
        new BookFinderQuery(null, null, null, null, null, null, ids, 20);
        if (ids.isEmpty()) return Result.success(List.of());
        var books = assemble(novels.selectList(new LambdaQueryWrapper<Novel>()
                .eq(Novel::getStatus, NovelStatus.PUBLISHED).in(Novel::getId, ids)));
        var byId = books.stream().collect(Collectors.toMap(BookFinderBook::id, b -> b));
        return Result.success(ids.stream().distinct().map(byId::get).filter(Objects::nonNull).toList());
    }

    private List<BookFinderBook> assemble(List<Novel> rows) {
        if (rows.isEmpty()) return List.of();
        sources.enrich(rows);
        var categoryIds = rows.stream().map(Novel::getCategoryId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = categoryIds.isEmpty() ? Map.of() : categories.selectBatchIds(categoryIds)
                .stream().collect(Collectors.toMap(NovelCategory::getId, NovelCategory::getName));
        var authors = users.nicknamesOf(rows.stream().map(Novel::getAuthorId).filter(Objects::nonNull).distinct().toList());
        return rows.stream().map(n -> new BookFinderBook(n.getId().toString(), n.getTitle(),
                sources.authorName(n, authors.get(n.getAuthorId())), n.getCover(), names.get(n.getCategoryId()),
                n.getTags(), bounded(n.getIntro()), Objects.equals(n.getIsFinish(), 1),
                n.getWordCount() == null ? 0 : n.getWordCount())).toList();
    }
    private static String bounded(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 600)); }
}
