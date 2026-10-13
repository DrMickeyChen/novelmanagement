package com.novel.modules.content.importing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.modules.content.entity.Novel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Batch-enrich content objects before VO/index assembly; no per-book queries. */
@Service
@RequiredArgsConstructor
public class NovelSourceService {
    private final NovelSourceMapper mapper;
    private final NovelImportTaskMapper tasks;
    public void ensureNotImporting(Long novelId) {
        if (tasks.selectCount(new LambdaQueryWrapper<NovelImportTask>()
                .eq(NovelImportTask::getNovelId, novelId)
                .in(NovelImportTask::getStatus, "QUEUED", "IMPORTING")) > 0) {
            throw new com.novel.common.exception.BusinessException(
                    com.novel.common.result.ResultCode.PARAM_ERROR, "作品正在导入，请等待导入完成");
        }
    }
    public void enrich(Collection<Novel> novels) {
        if (novels.isEmpty()) return;
        Map<Long, NovelSource> sources = new HashMap<>();
        mapper.selectList(new LambdaQueryWrapper<NovelSource>().in(NovelSource::getNovelId,
                novels.stream().map(Novel::getId).toList())).forEach(s -> sources.put(s.getNovelId(), s));
        for (Novel novel : novels) {
            NovelSource source = sources.get(novel.getId());
            novel.setSourceType(source == null ? "ORIGINAL" : source.getSourceType());
            novel.setOriginalAuthor(source == null ? null : source.getOriginalAuthor());
        }
    }
    public void enrich(Novel novel) { enrich(List.of(novel)); }
    public String authorName(Novel novel, String accountName) {
        return "EXTERNAL".equals(novel.getSourceType()) ? novel.getOriginalAuthor() : accountName;
    }
}
