package com.novel.modules.agent.writing.skill;

import com.novel.common.exception.BusinessException;
import java.nio.charset.*;
import java.nio.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Parses in memory; never extracts an archive, executes files or resolves remote references. */
public final class WritingSkillImport {
    private WritingSkillImport() {}
    public record Preview(WritingSkillRegistry.Skill skill,Map<String,String> references) {}
    public static Preview read(String filename,byte[] bytes){
        if(bytes==null||bytes.length>262144)throw error("导入文件最多 256 KB");
        if(filename!=null&&filename.toLowerCase(Locale.ROOT).endsWith(".md"))return new Preview(parse(utf8(bytes)),Map.of());
        if(filename==null||!filename.toLowerCase(Locale.ROOT).endsWith(".zip"))throw error("仅支持 SKILL.md 或 ZIP");
        var files=new LinkedHashMap<String,String>();int total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){String path=entry.getName();
                if(path.startsWith("/")||path.startsWith("\\")||path.contains("\\")||path.contains(":"))throw error("ZIP 路径不合法");
                for(String segment:path.split("/"))if(segment.equals("..")||segment.equals("."))throw error("ZIP 含越界路径");
                if(entry.isDirectory())continue;
                if(files.size()>=20||!(path.endsWith(".md")||path.endsWith(".txt")))throw error("ZIP 仅允许最多 20 个 Markdown／TXT 文件");
                byte[] content=zip.readNBytes(65537);total+=content.length;
                if(content.length>65536||total>131072)throw error("ZIP 单文件最多 64 KB，解压总量最多 128 KB");
                if(files.putIfAbsent(path,utf8(content))!=null)throw error("ZIP 含重复文件");
            }
        }catch(IOException e){throw error("ZIP 无法读取");}
        var definitions=files.keySet().stream().filter(p->p.equals("SKILL.md")||p.endsWith("/SKILL.md")).toList();
        if(definitions.size()!=1)throw error("ZIP 必须且只能包含一个 SKILL.md");
        String definition=definitions.getFirst(),directory=definition.substring(0,definition.length()-8);
        if(files.keySet().stream().anyMatch(p->!p.startsWith(directory)))throw error("参考文件必须位于 Skill 所在目录");
        String markdown=files.remove(definition);
        return new Preview(parse(markdown),Collections.unmodifiableMap(files));
    }
    private static WritingSkillRegistry.Skill parse(String raw){
        String text=raw.replace("\r\n","\n").replaceFirst("^\uFEFF","");
        if(text.startsWith("---\n")){int end=text.indexOf("\n---\n",4);if(end>=0){var meta=new LinkedHashMap<String,String>();for(String line:text.substring(4,end).split("\n")){int colon=line.indexOf(':');if(colon<1)throw error("外部 Skill 仅支持平铺文本元信息，请在编辑器转换复杂 YAML");String key=line.substring(0,colon).trim(),value=line.substring(colon+1).trim();if(!Set.of("id","name","description","version").contains(key)||meta.putIfAbsent(key,value)!=null)throw error("不支持或重复的元信息："+key);}
            if(meta.containsKey("name")&&meta.containsKey("description")){String normalized="---\nid: "+meta.getOrDefault("id","imported-"+com.novel.common.internal.WritingText.hash(text).substring(0,12))+"\nname: "+meta.get("name")+"\ndescription: "+meta.get("description")+"\nversion: "+meta.getOrDefault("version","1")+"\n---\n"+text.substring(end+5);try{return WritingSkillRegistry.parse(normalized);}catch(IllegalArgumentException e){throw error(e.getMessage());}}
        }}
        try{return WritingSkillRegistry.parse(text);}catch(IllegalArgumentException e){throw error(e.getMessage());}
    }
    public static String utf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw error("文件必须为有效 UTF-8 文本");}}
    private static BusinessException error(String message){return new BusinessException(422,message,422);}
}
