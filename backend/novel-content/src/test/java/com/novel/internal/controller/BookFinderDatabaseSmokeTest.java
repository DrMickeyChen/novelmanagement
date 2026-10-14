package com.novel.internal.controller;

import com.novel.common.internal.BookFinderQuery;
import com.novel.modules.content.importing.*;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.ContentUserLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly opt-in, read-only validation against the configured development database. */
@EnabledIfSystemProperty(named="bookfinder.live",matches="true")
@SpringBootTest(classes=BookFinderDatabaseSmokeTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.profiles.active=dev","spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.enabled=false",
                "knife4j.enable=false","logging.level.root=WARN","logging.level.org.springframework=WARN",
                "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
class BookFinderDatabaseSmokeTest {
    @Autowired InternalBookFinderController controller;
    @Autowired InternalBookKnowledgeController knowledge;
    @Test void realDatabaseSnapshotHasBoundedPublicBodiesAndStableFingerprint() {
        var books=controller.search(new BookFinderQuery(null,null,null,null,null,null,List.of(),20)).getData();
        assertFalse(books.isEmpty()); var first=books.get(0);
        var snapshot=knowledge.snapshot(first.id(),10).getData(); assertNotNull(snapshot);
        assertEquals(first.id(),snapshot.book().id()); assertTrue(snapshot.chapters().size()<=10);
        assertTrue(snapshot.chapters().stream().mapToInt(c->c.text().length()).sum()<=100000);
        assertTrue(snapshot.chapters().stream().allMatch(c->c.text().length()<=20000));
        assertEquals(snapshot.fingerprint(),knowledge.snapshot(first.id(),10).getData().fingerprint());
    }
    @Test void realDatabaseSupportsFiltersSourcesAndFinalDetails() {
        var all=controller.search(new BookFinderQuery(null,null,null,null,null,null,List.of(),20)).getData();
        assertFalse(all.isEmpty(),"Development database should contain imported published books");
        var first=all.get(0);
        var filtered=controller.search(new BookFinderQuery(null,first.category(),null,first.completed(),first.wordCount(),null,List.of(),20)).getData();
        assertTrue(filtered.stream().allMatch(b->b.completed()==first.completed()&&b.wordCount()>=first.wordCount()&&Objects.equals(b.category(),first.category())));
        var details=controller.details(List.of(first.id())).getData();
        assertEquals(1,details.size());assertEquals(first.title(),details.get(0).title());
        var excluded=controller.search(new BookFinderQuery(null,null,null,null,null,null,List.of(first.id()),20)).getData();
        assertTrue(excluded.stream().noneMatch(b->b.id().equals(first.id())));
        var keyword=controller.search(new BookFinderQuery(first.title(),null,null,null,null,null,List.of(),20)).getData();
        assertTrue(keyword.stream().anyMatch(b->b.id().equals(first.id())));
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration.class)
    @MapperScan(basePackageClasses={NovelMapper.class,NovelSourceMapper.class})
    @Import({InternalBookFinderController.class,InternalBookKnowledgeController.class,NovelSourceService.class})
    static class Fixture {
        @Bean ContentUserLookup users() {
            var users=mock(ContentUserLookup.class,withSettings().mockMaker("mock-maker-subclass"));
            when(users.nicknamesOf(any())).thenAnswer(invocation-> {
                Map<Long,String> names=new HashMap<>();
                Collection<Long> ids=invocation.getArgument(0); ids.forEach(id->names.put(id,"本地验收昵称"));return names;
            });
            return users;
        }
    }
}
