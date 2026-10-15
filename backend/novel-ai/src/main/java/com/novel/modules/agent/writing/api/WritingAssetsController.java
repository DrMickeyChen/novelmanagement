package com.novel.modules.agent.writing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.result.Result;
import com.novel.common.internal.WritingText;
import com.novel.modules.agent.writing.application.WritingAssetService;
import com.novel.modules.agent.writing.skill.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/writing/assets/novels/{novel}")
public class WritingAssetsController {
    private final WritingAssetService assets;private final WritingSkills skills;private final ObjectMapper json;
    @GetMapping public Result<?> list(@PathVariable long novel){return Result.success(assets.list(novel));}
    @GetMapping("/skills") public Result<?> skills(@PathVariable long novel){return Result.success(skills.list(novel));}
    @PostMapping public Result<?> create(@PathVariable long novel,@RequestBody ObjectNode req){return Result.success(assets.save(novel,null,req));}
    @PutMapping("/{id}") public Result<?> update(@PathVariable long novel,@PathVariable String id,@RequestBody ObjectNode req){return Result.success(assets.save(novel,id,req));}
    @GetMapping("/{id}/history") public Result<?> history(@PathVariable long novel,@PathVariable String id){return Result.success(assets.history(novel,id));}
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable long novel,@PathVariable String id,@RequestParam long expectedVersion){assets.delete(novel,id,expectedVersion);return Result.success();}
    @PostMapping("/import/preview") public Result<?> preview(@PathVariable long novel,@RequestPart MultipartFile file)throws Exception{assets.access(novel);return Result.success(WritingSkillImport.read(file.getOriginalFilename(),file.getBytes()));}
    @PostMapping("/import") public Result<?> importSkill(@PathVariable long novel,@RequestPart MultipartFile file)throws Exception{
        assets.access(novel);var bytes=file.getBytes();var imported=WritingSkillImport.read(file.getOriginalFilename(),bytes);var definition=imported.skill();
        var req=json.createObjectNode().put("kind","SKILL").put("name",definition.name()).put("body",definition.instructions()).put("state","PENDING");
        req.putObject("metadata").put("description",definition.description()).put("sourceVersion",definition.version()).set("references",json.valueToTree(imported.references()));
        ((ObjectNode)req.get("metadata")).putObject("import").put("hash",WritingText.hash(WritingSkillImport.utf8(definition.instructions().getBytes(java.nio.charset.StandardCharsets.UTF_8)))).put("filename",Objects.toString(file.getOriginalFilename(),"SKILL.md"));
        return Result.success(assets.save(novel,null,req));
    }
}
