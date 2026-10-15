package com.novel.modules.ai.controller;

import com.novel.common.result.Result;
import com.novel.modules.ai.dto.AiSummaryGenerateDTO;
import com.novel.modules.ai.service.AiSummaryService;
import com.novel.modules.ai.vo.AiSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 章节摘要的独立入口；旧写作助手的四个生成端点已删除。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "18. 章节摘要", description = "需要 AUTHOR 或 ADMIN 角色")
public class AiSummaryController {
    private final AiSummaryService summaryService;

    @Operation(summary = "生成前情摘要", description = "按章节范围生成摘要并落库，返回实际覆盖范围及跳过的章节。")
    @PostMapping("/api/ai/summary")
    public Result<AiSummaryVO> summary(@Valid @RequestBody AiSummaryGenerateDTO dto) {
        return Result.success(summaryService.generate(dto));
    }
}
