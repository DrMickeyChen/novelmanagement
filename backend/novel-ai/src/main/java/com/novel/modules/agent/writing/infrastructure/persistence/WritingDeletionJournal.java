package com.novel.modules.agent.writing.infrastructure.persistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent metadata-only tombstones. Keep this persistent volume outside a MySQL restore. */
@Component
public class WritingDeletionJournal {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Path path;
    public WritingDeletionJournal(JdbcTemplate jdbc,ObjectMapper json,@Value("${novel.ai.writing.deletion-journal:./data/writing-deletions.ndjson}") String path){this.jdbc=jdbc;this.json=json;this.path=Path.of(path).toAbsolutePath().normalize();}
    public synchronized List<Map<String,Object>> entries()throws Exception{
        if(!Files.exists(path))return List.of();if(Files.size(path)>10485760)throw new IllegalStateException("删除账本超过10MB，请归档后重放");var entries=new LinkedHashMap<String,Map<String,Object>>();
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){if(line.isBlank())continue;@SuppressWarnings("unchecked")var entry=(Map<String,Object>)json.readValue(line,Map.class);String id=Objects.toString(entry.get("id"),"");if(!id.matches("[A-Za-z0-9:.-]{1,64}"))throw new IllegalStateException("删除账本记录无效");entries.put(id,entry);}return List.copyOf(entries.values());
    }
    @Scheduled(fixedDelay=30000) public void mirror(){try{flush();}catch(org.springframework.jdbc.BadSqlGrammarException ignored){}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("写作删除独立账本未同步，请检查持久目录；类型={}",e.getClass().getSimpleName());}}
    public synchronized Map<String,Object> flush()throws Exception{
        var existing=new HashSet<String>();for(var entry:entries())existing.add(entry.get("id").toString());Files.createDirectories(path.getParent());int added=0;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM writing_delete_ledger WHERE state='DONE'",Integer.class)>10000)throw new IllegalStateException("删除账本需分批归档，尚未完成独立备份同步");
        var records=jdbc.queryForList("SELECT id,owner_user_id,novel_id,source_kind,source_id FROM writing_delete_ledger WHERE state='DONE' ORDER BY created_at LIMIT 10000");
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);var lock=channel.lock()){
            for(var record:records){String stable=com.novel.common.internal.WritingText.hash(record.get("owner_user_id")+"|"+record.get("novel_id")+"|"+record.get("source_kind")+"|"+record.get("source_id"));record.put("id",stable);if(existing.add(stable)){byte[] bytes=(json.writeValueAsString(record)+"\n").getBytes(StandardCharsets.UTF_8);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);added++;}}channel.force(true);
        }return Map.of("added",added,"records",existing.size(),"ready",true);
    }
}
