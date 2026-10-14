package com.novel.internal.controller;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.novel.common.internal.*;
import com.novel.common.result.Result;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalBookKnowledgeControllerTest {
    private static <T>T mockType(Class<T> type) { return mock(type,withSettings().mockMaker("mock-maker-subclass")); }
    private final InternalBookFinderController books=mockType(InternalBookFinderController.class);
    private final ChapterMapper chapters=mockType(ChapterMapper.class);
    private final ChapterContentMapper bodies=mockType(ChapterContentMapper.class);
    private final InternalBookKnowledgeController controller=new InternalBookKnowledgeController(books,chapters,bodies);
    private final BookFinderBook book=new BookFinderBook("99","书","作者","","悬疑","","简介",true,300000);
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),Chapter.class);
        when(books.details(List.of("99"))).thenReturn(Result.success(List.of(book)));
        when(chapters.selectCount(any())).thenReturn(1L); when(chapters.selectList(any())).thenReturn(List.of());
    }
    @Test void hiddenBooksNeverReadBody() {
        when(books.details(any())).thenReturn(Result.success(List.of()));
        assertNull(controller.snapshot("99",10).getData()); verifyNoInteractions(chapters,bodies);
    }
    @Test void bodyReadsOnlyPublishedChaptersAndAreSqlBounded() {
        var chapter=new Chapter(); chapter.setId(12L); chapter.setChapterNo(1); chapter.setTitle("章");
        var body=new ChapterContent(); body.setChapterId(12L); body.setContent("文".repeat(20001));
        when(chapters.selectList(any())).thenReturn(List.of(chapter)); when(bodies.selectList(any())).thenReturn(List.of(body));
        var snapshot=controller.snapshot("99",10).getData();
        assertEquals(20000,snapshot.chapters().get(0).text().length()); assertTrue(snapshot.truncated());
        var query=ArgumentCaptor.forClass(Wrapper.class);verify(chapters).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("status ="));assertTrue(query.getValue().getSqlSegment().contains("LIMIT 10"));
        verify(bodies).selectList(argThat(w->w.getSqlSelect().contains("SUBSTRING(content,1,20001)")));
    }
    @Test void unpublishedDuringReadFailsClosed() {
        when(books.details(any())).thenReturn(Result.success(List.of(book)),Result.success(List.of()));
        assertNull(controller.snapshot("99",10).getData());
    }
    @Test void metadataChangesInvalidateFingerprint() {
        String first=controller.snapshot("99",10).getData().fingerprint();
        var changed=new BookFinderBook("99","新书名","作者","","悬疑","","简介",true,300000);
        when(books.details(any())).thenReturn(Result.success(List.of(changed)));
        assertNotEquals(first,controller.snapshot("99",10).getData().fingerprint());
    }
}
