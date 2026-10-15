package com.novel.modules.agent.writing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.WritingText;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

/** Author preferences are frozen per task, never interpreted as tool or permission configuration. */
@Service
public class WritingAssistantSettings {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WritingAssetService assets;
    private final String defaults;

    public WritingAssistantSettings(JdbcTemplate jdbc,ObjectMapper json,WritingAssetService assets) {
        this.jdbc=jdbc;this.json=json;this.assets=assets;
        try(var in=new ClassPathResource("writing/assistant-default.md").getInputStream()) {
            defaults=new String(in.readAllBytes(),StandardCharsets.UTF_8).strip();
        } catch(java.io.IOException e) {throw new IllegalStateException("缺少写作助手默认指令",e);}
    }

    public ObjectNode get(long novel) {
        authorize(novel);
        var personal=read(0);var book=novel==0?empty():read(novel);
        var result=json.createObjectNode().put("defaultInstructions",defaults).put("maxLength",6000);
        result.set("personal",personal);result.set("book",book);
        result.set("effective",effective(personal,book));return result;
    }

    @Transactional
    public ObjectNode save(long novel,ObjectNode request) {
        authorize(novel);
        if(!request.path("enabled").isBoolean()||!request.path("instructions").isTextual()
                ||!request.path("expectedVersion").isIntegralNumber()||request.path("expectedVersion").asLong()<0)
            throw WritingTaskRules.error(422,"助手设置参数无效，请刷新后重试");
        boolean enabled=request.path("enabled").asBoolean();
        String body=request.path("instructions").asText().strip();long version=request.path("expectedVersion").asLong();
        if(body.length()>6000||enabled&&body.isBlank())throw WritingTaskRules.error(422,"自定义指令需填写 1～6000 字");
        long owner=UserContext.requireUserId();
        int changed=version==0?jdbc.update("INSERT IGNORE INTO writing_assistant_settings(owner_user_id,novel_id,enabled,instructions,version,updated_at) VALUES(?,?,?,?,1,UTC_TIMESTAMP(3))",owner,novel,enabled,body)
                :jdbc.update("UPDATE writing_assistant_settings SET enabled=?,instructions=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE owner_user_id=? AND novel_id=? AND version=?",enabled,body,owner,novel,version);
        if(changed!=1)throw WritingTaskRules.error(409,"助手设置已在其他窗口修改，请重新加载后保存");
        return get(novel);
    }

    public ObjectNode snapshot(long novel) {return (ObjectNode)get(novel).get("effective");}

    private void authorize(long novel) {
        if(novel<0)throw WritingTaskRules.error(422,"作品编号无效");
        assets.access(0);if(novel>0)assets.access(novel);
    }

    private ObjectNode read(long novel) {
        var rows=jdbc.queryForList("SELECT enabled,instructions,version FROM writing_assistant_settings WHERE owner_user_id=? AND novel_id=?",UserContext.requireUserId(),novel);
        if(rows.isEmpty())return empty();
        var row=rows.getFirst();return json.createObjectNode().put("enabled",Boolean.TRUE.equals(row.get("enabled"))||row.get("enabled") instanceof Number n&&n.intValue()!=0)
                .put("instructions",row.get("instructions").toString()).put("version",((Number)row.get("version")).longValue());
    }

    private ObjectNode empty() {return json.createObjectNode().put("enabled",false).put("instructions",defaults).put("version",0);}

    private ObjectNode effective(ObjectNode personal,ObjectNode book) {
        boolean override=book.path("enabled").asBoolean(),custom=personal.path("enabled").asBoolean();
        var selected=override?book:personal;String body=override||custom?selected.path("instructions").asText():defaults;
        return json.createObjectNode().put("scope",override?"BOOK":custom?"PERSONAL":"DEFAULT")
                .put("version",override||custom?selected.path("version").asLong():0)
                .put("instructions",body).put("hash",WritingText.hash(body));
    }
}
