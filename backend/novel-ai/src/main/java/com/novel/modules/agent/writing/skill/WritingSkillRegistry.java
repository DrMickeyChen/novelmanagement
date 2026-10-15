package com.novel.modules.agent.writing.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.WritingText;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deployment-owned Markdown only. No scripts, includes, dynamic paths or external downloads. */
@Component
public class WritingSkillRegistry {
    public record Skill(String id,String name,String description,String version,String hash,String instructions) {}
    public record Descriptor(String id,String name,String description,String version,String hash) {}
    private final Map<String,Skill> skills;
    public WritingSkillRegistry() {
        this(resources());
    }
    public WritingSkillRegistry(Resource[] resources) {
        if(resources.length==0)throw new IllegalStateException("未找到写作 Skill 文件：writing/skills/*/SKILL.md");
        var loaded=new TreeMap<String,Skill>();
        for(var resource:resources) {
            try(var stream=resource.getInputStream()) {
                byte[] bytes=stream.readNBytes(16385);
                if(bytes.length>16384)throw new IllegalArgumentException("Skill 文件超过 16 KB");
                var skill=parse(new String(bytes,StandardCharsets.UTF_8));
                if(loaded.putIfAbsent(skill.id(),skill)!=null)throw new IllegalArgumentException("Skill ID 重复："+skill.id());
            } catch(Exception e) {throw new IllegalStateException("写作 Skill 加载失败："+resource.getDescription()+"；"+e.getMessage(),e);}
        }
        skills=Collections.unmodifiableMap(loaded);
    }
    private static Resource[] resources() {
        try{return new PathMatchingResourcePatternResolver().getResources("classpath*:writing/skills/*/SKILL.md");}
        catch(IOException e){throw new IllegalStateException("无法读取写作 Skill 目录",e);}
    }
    public static Skill parse(String raw) {
        String text=raw.replace("\r\n","\n").replaceFirst("^\uFEFF","");
        if(!text.startsWith("---\n"))throw new IllegalArgumentException("缺少 Skill 元信息头");
        int end=text.indexOf("\n---\n",4);if(end<0)throw new IllegalArgumentException("Skill 元信息未闭合");
        var meta=new HashMap<String,String>();
        for(String line:text.substring(4,end).split("\n")) {
            int colon=line.indexOf(':');if(colon<1)throw new IllegalArgumentException("元信息必须是 key: value");
            String key=line.substring(0,colon).trim(),value=line.substring(colon+1).trim();
            if(!Set.of("id","name","description","version").contains(key)||value.isBlank()||meta.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("未知、重复或空的元信息字段："+key);
        }
        if(meta.size()!=4||!meta.get("id").matches("[a-z][a-z0-9-]{0,39}")||meta.get("name").length()>60||meta.get("description").length()>300||!meta.get("version").matches("[A-Za-z0-9._-]{1,32}"))throw new IllegalArgumentException("Skill 元信息格式无效");
        String body=text.substring(end+5).trim();if(body.isBlank()||body.length()>6000)throw new IllegalArgumentException("Skill 正文须为 1～6000 字");
        return new Skill(meta.get("id"),meta.get("name"),meta.get("description"),meta.get("version"),WritingText.hash(text),body);
    }
    public List<Descriptor> list(){return skills.values().stream().map(s->new Descriptor(s.id(),s.name(),s.description(),s.version(),s.hash())).toList();}
    public void validate(JsonNode ids) {
        if(!ids.isArray()||ids.size()>2)throw error("Skill 必须为数组，最多选择 2 个");
        var seen=new HashSet<String>();for(var id:ids)if(!id.isTextual()||!skills.containsKey(id.asText())||!seen.add(id.asText()))throw error("不支持或重复的 Skill");
    }
    /** Existing selections retain their exact rules; new selections capture the deployed file version. */
    public void freeze(ObjectNode input,JsonNode ids) {
        validate(ids);var previous=input.path("skillSnapshots");var frozen=input.arrayNode();
        for(var id:ids){JsonNode old=null;for(var item:previous)if(item.path("id").asText().equals(id.asText())){old=item;break;}
            if(old!=null)frozen.add(old.deepCopy());
            else {var s=skills.get(id.asText());frozen.addObject().put("id",s.id()).put("name",s.name()).put("version",s.version()).put("hash",s.hash()).put("instructions",s.instructions());}
        }
        input.set("skillIds",ids.deepCopy());input.set("skillSnapshots",frozen);
    }
    public String instructions(JsonNode input) {
        if(input.has("skillSnapshots")){var text=new StringBuilder();for(var s:input.path("skillSnapshots"))text.append(s.path("instructions").asText());return text.toString();}
        // Historical tasks only persisted IDs. Built-in file bodies preserve their original rules.
        var ids=input.path("skillIds");if(ids.isMissingNode())return "";validate(ids);
        var text=new StringBuilder();for(var id:ids)text.append(skills.get(id.asText()).instructions());return text.toString();
    }
    private BusinessException error(String message){return new BusinessException(422,message,422);}
}
