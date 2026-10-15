package com.novel.modules.agent.writing.context;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.Snapshot;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.application.WritingAssetService;
import com.novel.modules.agent.writing.infrastructure.client.WritingSyncClient;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class WritingExtendedContext {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WritingAssetService assets;private final WritingSyncClient content;
    public ObjectNode freeze(Snapshot snapshot,JsonNode input){
        long novel=Long.parseLong(snapshot.novelId());var extras=json.createObjectNode();var styles=extras.putArray("styles");
        if(input.has("styleIds")&&(!input.path("styleIds").isArray()||input.path("styleIds").size()>2))throw WritingTaskRules.error(422,"最多选择 2 个文风配置");
        for(var selected:input.path("styleIds")){var row=assets.get(novel,selected.asText());if(!row.get("kind").equals("STYLE")||!row.get("state").equals("APPROVED"))throw WritingTaskRules.error(409,"文风配置尚未确认或已失效");styles.addObject().put("assetId",row.get("id").toString()).put("version",((Number)row.get("version")).longValue()).put("hash",row.get("content_hash").toString()).put("instructions",row.get("body").toString());}
        validate(snapshot,extras);
        if(input.path("includeCumulative").asBoolean(true)){
            var rows=jdbc.queryForList("SELECT * FROM writing_cumulative_recap WHERE owner_user_id=? AND novel_id=? AND through_chapter_no<? AND state='READY' ORDER BY through_chapter_no DESC LIMIT 1",UserContext.requireUserId(),novel,snapshot.chapterNo());
            if(!rows.isEmpty()){var row=rows.getFirst();try{var memory=extras.putObject("memory").put("kind","MODEL_RECAP").put("verified",false).put("throughChapterNo",((Number)row.get("through_chapter_no")).intValue());memory.set("sources",json.readTree(row.get("source_keys").toString()));memory.set("recap",json.readTree(row.get("summary").toString()));}catch(Exception e){throw new IllegalStateException("累计摘要格式错误",e);}}
        }
        try{validateMemory(snapshot,extras);}catch(com.novel.common.exception.BusinessException e){if(e.getCode()!=409)throw e;extras.remove("memory");extras.put("memoryNotice","累计回顾的来源已变化，本次不用旧摘要；仍读取有效正文资料。");}return extras;
    }
    public void validate(Snapshot snapshot,JsonNode extras){
        long novel=Long.parseLong(snapshot.novelId());for(var style:extras.path("styles")){var row=assets.get(novel,style.path("assetId").asText());if(!row.get("state").equals("APPROVED")||!row.get("content_hash").equals(style.path("hash").asText())||((Number)row.get("version")).longValue()!=style.path("version").asLong())throw WritingTaskRules.error(409,"文风配置已变化，请创建新会话");}
        validateMemory(snapshot,extras);
    }
    private void validateMemory(Snapshot snapshot,JsonNode extras){
        if(!extras.path("memory").has("sources"))return;long novel=Long.parseLong(snapshot.novelId());int through=extras.path("memory").path("throughChapterNo").asInt();
        var catalog=InternalResults.unwrapBusiness(content.catalog(novel),"cumulative context ACL");var prefix=catalog.stream().filter(r->((Number)r.get("chapter_no")).intValue()<=through&&Set.of(0,2,4).contains(((Number)r.get("status")).intValue())).toList();
        if(through>=snapshot.chapterNo()||prefix.size()!=extras.path("memory").path("sources").size())throw WritingTaskRules.error(409,"累计摘要的章节范围已变化");
        for(var source:extras.path("memory").path("sources")){var row=prefix.stream().filter(r->r.get("id").toString().equals(source.path("chapterId").asText())).findFirst().orElseThrow(()->WritingTaskRules.error(409,"摘要来源已删除"));if(((Number)row.get("chapter_no")).intValue()!=source.path("chapterNo").asInt(-1)||((Number)row.get("content_version")).longValue()!=source.path("version").asLong()||!row.get("content_hash").equals(source.path("hash").asText()))throw WritingTaskRules.error(409,"累计摘要来源已变化");}
    }
}
