package com.novel.internal.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.BookFinderQuery;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.importing.NovelSourceService;
import com.novel.modules.content.service.ContentUserLookup;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalBookFinderControllerTest {
    private final NovelMapper novels=mock(NovelMapper.class,withSettings().mockMaker("mock-maker-subclass"));
    private final NovelCategoryMapper categories=mock(NovelCategoryMapper.class,withSettings().mockMaker("mock-maker-subclass"));
    private final InternalBookFinderController controller=new InternalBookFinderController(novels,categories,
            mock(ContentUserLookup.class,withSettings().mockMaker("mock-maker-subclass")),mock(NovelSourceService.class,withSettings().mockMaker("mock-maker-subclass")));
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),Novel.class);
        when(novels.selectList(any())).thenReturn(List.of());
    }
    @Test void emptyKeywordStillUsesBoundedPublishedQueryWithHardFilters() {
        controller.search(new BookFinderQuery(null,null,null,true,1000000,2000000,List.of("99"),10));
        var capture=ArgumentCaptor.forClass(Wrapper.class);verify(novels).selectList(capture.capture());
        String sql=capture.getValue().getSqlSegment();
        assertTrue(sql.contains("status =")); assertTrue(sql.contains("is_finish ="));
        assertTrue(sql.contains("word_count >="));assertTrue(sql.contains("word_count <="));
        assertTrue(sql.contains("NOT IN"));assertTrue(sql.contains("LIMIT 10"));
    }
    @Test void detailsReadChecksPublishedStatusAgain() {
        assertTrue(controller.details(List.of("99")).getData().isEmpty());
        var capture=ArgumentCaptor.forClass(Wrapper.class);verify(novels).selectList(capture.capture());
        assertTrue(capture.getValue().getSqlSegment().contains("status ="));
    }
    @Test void exampleCategoriesOnlyComeFromPublishedBooks() {
        when(novels.selectObjs(any())).thenReturn(List.of(9L));
        var category=new NovelCategory(); category.setId(9L); category.setName("悬疑推理");
        when(categories.selectBatchIds(List.of(9L))).thenReturn(List.of(category));
        assertEquals(List.of("悬疑推理"),controller.availableCategories().getData());
        var capture=ArgumentCaptor.forClass(Wrapper.class);verify(novels).selectObjs(capture.capture());
        assertTrue(capture.getValue().getSqlSegment().contains("status ="));
        assertEquals("DISTINCT category_id",capture.getValue().getSqlSelect());
    }
    @Test void oversizedAndInvalidIdsNeverReachDatabase() {
        assertThrows(BusinessException.class,()->controller.details(Collections.nCopies(21,"99")));
        assertThrows(BusinessException.class,()->controller.details(List.of("99 OR 1=1")));
        verifyNoInteractions(novels);
    }
}
