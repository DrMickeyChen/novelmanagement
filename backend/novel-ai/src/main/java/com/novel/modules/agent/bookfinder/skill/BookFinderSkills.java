package com.novel.modules.agent.bookfinder.skill;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** App task strategies, not executable scripts. IDs/files/tools are a closed server whitelist. */
@Service @RequiredArgsConstructor
public class BookFinderSkills {
    private final StringRedisTemplate redis;
    public record Skill(String id,String name,String version,boolean enabled,String instructions) {}
    private static final Map<String,String> NAMES=new LinkedHashMap<>();
    static { NAMES.put("preference","按偏好找书"); NAMES.put("similar","相似书推荐"); NAMES.put("refresh","换一批"); }
    public List<Skill> list() {
        var result=new ArrayList<Skill>();
        for(var entry:NAMES.entrySet()) {
            try(var in=new ClassPathResource("agent/bookfinder/skills/"+entry.getKey()+".md").getInputStream()) {
                result.add(new Skill(entry.getKey(),entry.getValue(),"1.0",!"false".equals(redis.opsForHash().get("agent:skills:enabled",entry.getKey())),new String(in.readAllBytes(),StandardCharsets.UTF_8)));
            } catch(Exception e) { throw new IllegalStateException("任务策略加载失败"); }
        }
        return result;
    }
    public void toggle(String id,boolean enabled) {
        if(!NAMES.containsKey(id)) throw new com.novel.common.exception.BusinessException(400,"未知任务策略");
        redis.opsForHash().put("agent:skills:enabled",id,Boolean.toString(enabled));
    }
    public Skill select(String input) {
        String requested=input.contains("换一批")?"refresh":input.contains("类似")||input.contains("相似")||input.contains("第二本")?"similar":"preference";
        return list().stream().filter(s->s.enabled()&&s.id().equals(requested)).findFirst().orElse(null);
    }
}
