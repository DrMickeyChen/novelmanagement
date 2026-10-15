package com.novel.modules.agent.writing.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.result.Result;
import com.novel.modules.agent.writing.application.WritingAssistantSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/ai/writing/settings/novels")
public class WritingAssistantSettingsController {
    private final WritingAssistantSettings settings;
    @GetMapping("/{novel}") public Result<?> get(@PathVariable long novel){return Result.success(settings.get(novel));}
    @PutMapping("/{novel}") public Result<?> save(@PathVariable long novel,@RequestBody ObjectNode request){return Result.success(settings.save(novel,request));}
}
