package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.infrastructure.client.WritingSearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import com.novel.modules.agent.bookfinder.knowledge.RetrievalModels;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WritingRetrievalTest {
    Snapshot snapshot=new Snapshot("10","1",4,"本章",0,"","20","本章笔记","h",List.of());
    @Test void freshVersionsAndAclAreValidatedBeforeExternalReranking()throws Exception{
        var content=mock(WritingContentClient.class);var search=mock(WritingSearchClient.class);var models=mock(RetrievalModels.class);
        when(content.chapters(10L,20L)).thenReturn(Result.success(List.of(Map.of("id",2,"version",1,"chapterNo",2))));
        var stale=new Source("chapter:2:0","2",2,"旧章",0,0,2,true,"秘密");
        when(search.search(any())).thenReturn(Result.success(new PrivateEvidence(List.of(stale),"BM25")));when(content.validate(10L,20L,List.of(stale))).thenReturn(Result.success(List.of()));when(models.rerankEnabled()).thenReturn(true);
        var retrieval=new WritingRetrieval(content,search,models,new ObjectMapper());assertTrue(retrieval.search(snapshot,"秘密",3).sources().isEmpty());
        var request=ArgumentCaptor.forClass(PrivateRecall.class);verify(search).search(request.capture());assertEquals(List.of("2:1"),request.getValue().sourceKeys());assertEquals("1",request.getValue().novelId());
        verify(models,never()).rerank(anyString(),anyList());
    }
    @Test void modelMemoryProposalCannotUseUnloadedOrInventedSources(){
        var content=mock(WritingContentClient.class);var retrieval=new WritingRetrieval(content,mock(WritingSearchClient.class),mock(RetrievalModels.class),new ObjectMapper());
        assertThrows(RuntimeException.class,()->retrieval.propose(snapshot,"人物","CHARACTER","会飞","chapter:99:0","会飞"));verifyNoInteractions(content);
    }
    @Test void indexBuildRejectsFutureIdsBeforeBodyOrEmbeddingCalls()throws Exception{
        var content=mock(WritingContentClient.class);var models=mock(RetrievalModels.class);
        when(content.chapters(10L,20L)).thenReturn(Result.success(List.of(Map.of("id",2,"version",1))));
        var retrieval=new WritingRetrieval(content,mock(WritingSearchClient.class),models,new ObjectMapper());
        assertThrows(RuntimeException.class,()->retrieval.build(10,20,List.of(99L),false));verify(content,never()).chunks(anyLong(),anyLong(),anyLong());verify(models,never()).embed(anyList());
    }
}
