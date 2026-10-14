package com.novel.modules.agent.bookfinder.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.*;
import com.novel.common.result.Result;
import com.novel.modules.agent.bookfinder.client.*;
import com.novel.modules.agent.bookfinder.model.BookFinderModel;
import com.novel.modules.ai.service.AiConfigService;
import org.springframework.data.redis.core.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeNotesTest {
    private static <T>T mockType(Class<T> type) { return mock(type,withSettings().mockMaker("mock-maker-subclass")); }
    private final BookFinderSearchClient search=mockType(BookFinderSearchClient.class);
    private final BookFinderContentClient content=mockType(BookFinderContentClient.class);
    private final BookFinderModel model=mockType(BookFinderModel.class);
    private final StringRedisTemplate redis=mockType(StringRedisTemplate.class);
    private final HashOperations<String,Object,Object> hashes=mockType(HashOperations.class);
    private final Map<Object,Object> values=new HashMap<>();
    private final KnowledgeNotes notes=new KnowledgeNotes(search,content,model,mockType(AiConfigService.class),redis,new ObjectMapper());
    private final BookFinderBook book=new BookFinderBook("99","书","作者","","悬疑","","简介",true,300000);
    private final Chunk chunk=new Chunk("99:12:0","12",1,"章",0,8,"侦探调查案件线索",List.of());
    private final Document doc=new Document("99",book,"v1",10,1,30,8,false,"2026-10-13T00:00:00Z","",List.of(chunk));
    private final Hit hit=new Hit("99",chunk.id(),"12",1,chunk.text(),0.5,1,30,false,"v1","KEYWORD");
    @BeforeEach void setup() {
        doReturn(hashes).when(redis).opsForHash();
        when(hashes.values(anyString())).thenAnswer(i->new ArrayList<>(values.values()));
        doAnswer(i->{values.put(i.getArgument(1),i.getArgument(2));return null;}).when(hashes).put(anyString(),any(),any());
        when(search.knowledge(List.of("99"))).thenReturn(Result.success(List.of(doc)));
        when(content.snapshot("99",10)).thenReturn(Result.success(new Snapshot(book,"v1",10,30,List.of(),false)));
    }
    @Test void pendingAndRejectedNotesCannotEnterRetrieval() {
        notes.saveDraft("99","开篇出现调查场景",chunk.id(),"调查案件");
        assertTrue(notes.reviewed(List.of(hit)).isEmpty());
        notes.review("99",false,1); assertTrue(notes.reviewed(List.of(hit)).isEmpty());
        verifyNoInteractions(model);
    }
    @Test void humanReviewOnlyWorksForCurrentEvidenceVersion() {
        notes.saveDraft("99","开篇出现调查场景",chunk.id(),"调查案件"); notes.review("99",true,1);
        assertEquals(1,notes.reviewed(List.of(hit)).size());
        var newer=new Hit("99",chunk.id(),"12",1,chunk.text(),0.5,1,30,false,"v2","KEYWORD");
        assertTrue(notes.reviewed(List.of(newer)).isEmpty());
        when(content.snapshot("99",10)).thenReturn(Result.success(new Snapshot(book,"v2",10,30,List.of(),false)));
        assertThrows(com.novel.common.exception.BusinessException.class,()->notes.review("99",true,1));
    }
    @Test void inventedQuoteOrChunkIsRejectedBeforeSaving() {
        assertThrows(com.novel.common.exception.BusinessException.class,()->notes.saveDraft("99","主角无敌",chunk.id(),"主角无敌"));
        assertThrows(com.novel.common.exception.BusinessException.class,()->notes.saveDraft("99","调查","other-chunk","调查"));
        assertTrue(values.isEmpty());
    }
}
