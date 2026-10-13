package com.novel.modules.content.importing;

import com.novel.common.enums.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.NovelService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Each batch commits independently. The book remains a draft until final publication commits. */
@Service
@RequiredArgsConstructor
public class NovelImportWriter {
    private final JdbcTemplate jdbc;
    private final NovelMapper novels;
    private final NovelCategoryMapper categories;
    private final NovelSourceMapper sources;
    private final NovelImportTaskMapper tasks;
    private final NovelService novelService;

    public void validateCategory(Long id) {
        NovelCategory category = categories.selectById(id);
        if (category == null || !Integer.valueOf(1).equals(category.getStatus()))
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择有效且已启用的分类");
    }
    @Transactional(rollbackFor = Exception.class)
    public Long begin(NovelImportTask task, NovelImportOptions options) {
        validateCategory(options.getCategoryId());
        Novel book = new Novel();
        book.setTitle(options.getTitle().strip()); book.setAuthorId(task.getOperatorId());
        book.setCategoryId(options.getCategoryId()); book.setIntro(options.getIntro());
        book.setTags(options.getTags()); book.setCover(options.getCover());
        book.setIsFinish(options.getIsFinish()); book.setStatus(NovelStatus.DRAFT);
        novels.insert(book);
        NovelSource source = new NovelSource();
        source.setNovelId(book.getId()); source.setSourceType("EXTERNAL");
        source.setOriginalAuthor(options.getOriginalAuthor().strip()); source.setImportedBy(task.getOperatorId());
        source.setFileHash(task.getFileHash()); source.setCreateTime(LocalDateTime.now()); sources.insert(source);
        task.setNovelId(book.getId()); task.setStatus("IMPORTING"); task.setUpdateTime(LocalDateTime.now());
        tasks.updateById(task);
        return book.getId();
    }
    @Transactional(rollbackFor = Exception.class)
    public void batch(NovelImportTask task, int start, List<TxtNovelParser.ChapterText> batch) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            var c = batch.get(i);
            rows.add(new Object[]{task.getNovelId(), start + i, c.title(), c.wordCount()});
        }
        jdbc.batchUpdate("INSERT INTO chapter(novel_id,chapter_no,title,word_count,status,is_free) VALUES(?,?,?,?,0,1)", rows);
        Map<Integer, Long> ids = new HashMap<>();
        jdbc.query("SELECT id,chapter_no FROM chapter WHERE novel_id=? AND chapter_no>=? AND chapter_no<? AND deleted=0",
                rs -> { ids.put(rs.getInt("chapter_no"), rs.getLong("id")); }, task.getNovelId(), start, start + batch.size());
        List<Object[]> contents = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) contents.add(new Object[]{ids.get(start + i), batch.get(i).content()});
        jdbc.batchUpdate("INSERT INTO chapter_content(chapter_id,content) VALUES(?,?)", contents);
        task.setImportedChapters(start + batch.size() - 1); task.setUpdateTime(LocalDateTime.now()); tasks.updateById(task);
    }
    @Transactional(rollbackFor = Exception.class)
    public void complete(NovelImportTask task) {
        // Dedicated administrator import path: draft chapters are published as one audited operation.
        int updated = jdbc.update("UPDATE chapter SET status=2,publish_time=NOW(),update_time=NOW() WHERE novel_id=? AND status=0 AND deleted=0", task.getNovelId());
        if (updated != task.getTotalChapters()) throw new IllegalStateException("章节数量不一致，未发布");
        jdbc.update("INSERT INTO audit_log(target_type,target_id,action,operator_id,remark,create_time) " +
                "SELECT 2,id,2,?,'管理员外部 TXT 导入并发布',NOW() FROM chapter WHERE novel_id=? AND deleted=0",
                task.getOperatorId(), task.getNovelId());
        novelService.publishImported(task.getNovelId());
        task.setStatus("COMPLETED"); task.setUpdateTime(LocalDateTime.now()); tasks.updateById(task);
    }
    @Transactional(rollbackFor = Exception.class)
    public void fail(NovelImportTask task, String message) {
        if (task.getNovelId() != null) {
            // Only clean up this task's unpublished, newly-created book. Never existing/public content.
            Novel book = novels.selectById(task.getNovelId());
            if (book != null && book.getStatus() == NovelStatus.PUBLISHED) {
                task.setStatus("COMPLETED"); task.setUpdateTime(LocalDateTime.now()); tasks.updateById(task);
                return; // Commit succeeded; a subsequent notification/connection failure is not an import rollback.
            }
            if (book != null && book.getStatus() == NovelStatus.DRAFT) {
                jdbc.update("DELETE cc FROM chapter_content cc JOIN chapter c ON c.id=cc.chapter_id WHERE c.novel_id=?", book.getId());
                jdbc.update("DELETE FROM chapter WHERE novel_id=?", book.getId());
                jdbc.update("DELETE FROM novel_source WHERE novel_id=?", book.getId());
                jdbc.update("DELETE FROM novel WHERE id=? AND status=0", book.getId());
            }
        }
        task.setStatus("FAILED"); task.setErrorMessage(message); task.setUpdateTime(LocalDateTime.now()); tasks.updateById(task);
    }
}
