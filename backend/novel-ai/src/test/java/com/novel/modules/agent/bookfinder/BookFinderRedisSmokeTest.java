package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="bookfinder.live",matches="true")
@SpringBootTest(classes=BookFinderRedisSmokeTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.profiles.active=dev","spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.enabled=false",
                "knife4j.enable=false","logging.level.root=WARN","logging.level.org.springframework=WARN"})
class BookFinderRedisSmokeTest {
    @Autowired BookFinderStore store;
    @Autowired StringRedisTemplate redis;
    @Test void realRedisEnforcesOwnershipLockQuotaAndSerialization() {
        long uid=9000000000000013L;
        var id=UUID.randomUUID().toString(); var req=UUID.randomUUID().toString();
        var keys=List.of("novel:book-finder:session:"+uid+":"+id,"novel:book-finder:request:"+uid+":"+req,
                "novel:book-finder:lock:"+uid,"novel:book-finder:quota:"+uid+":"+LocalDate.now());
        assertTrue(keys.stream().noneMatch(redis::hasKey),"One-off test keys must be unused");
        try {
            var c=new Conversation(); c.setId(id); store.save(uid,c);
            assertEquals(id,store.load(uid,id).getId());
            assertThrows(BusinessException.class,()->store.load(uid+1,id));
            store.lock(uid,req);
            assertThrows(BusinessException.class,()->store.lock(uid,"other"));
            store.unlock(uid,"wrong");
            assertThrows(BusinessException.class,()->store.lock(uid,"other"));
            store.unlock(uid,req);
            store.acquireQuota(uid,2);store.acquireQuota(uid,2);
            assertThrows(BusinessException.class,()->store.acquireQuota(uid,2));
            assertEquals(0,store.quota(uid,2).get("remaining"));
            store.request(uid,req,new RequestRecord("fp","running",null));
            assertEquals("fp",store.request(uid,req).fingerprint());
            var book=new com.novel.common.internal.BookFinderBook("9007199254740993","验收小说","验收作者",null,"悬疑",null,"测试简介",true,1000000);
            var answer=new Answer(id,req,"验收回答",c.getConditions(),List.of(new Recommendation(book,"来自简介","测试简介",List.of())),false,true,new Usage(3,2,0,10));
            c.setLastAnswer(answer);c.setLastBooks(List.of(book));
            store.complete(uid,req,"fp",c,answer);
            assertEquals(book.id(),store.load(uid,id).getLastBooks().get(0).id());
            assertEquals("done",store.request(uid,req).status());
            assertEquals(book.id(),store.request(uid,req).answer().recommendations().get(0).book().id());
            assertTrue(redis.getExpire(keys.get(0))>0);
        } finally { redis.delete(keys); }
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration.class)
    static class Fixture {
        @Bean BookFinderStore store(StringRedisTemplate redis,ObjectMapper json) { return new BookFinderStore(redis,json); }
    }
}
