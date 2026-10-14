package com.novel.modules.agent.bookfinder.knowledge;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceRankingTest {
    private BookFinderBook book=new BookFinderBook("99","书","作者","","悬疑","推理","调查谜团",true,300000);
    private Document document(String id,String text,List<Double> vector,String model) {
        var chunk=new Chunk(id,"12",1,"第一章",0,text.length(),text,vector);
        return new Document("99",book,"version",10,1,30,text.length(),false,"2026-10-13T00:00:00Z",model,List.of(chunk));
    }
    @Test void chunksCoverEveryCharacterAndKeepExactOverlappingOffsets() {
        String text="探案".repeat(1400);
        var s=new Snapshot(book,"v",10,30,List.of(new ChapterText("12",1,"章",text,"v",false)),false);
        var chunks=EvidenceRanking.split(s); var coverage=new BitSet(text.length());
        for(var c:chunks) { assertEquals(text.substring(c.start(),c.end()),c.text()); assertTrue(c.text().length()<=1000); coverage.set(c.start(),c.end()); }
        assertEquals(text.length(),coverage.cardinality()); assertEquals(850,chunks.get(1).start());
    }
    @Test void emptyChaptersProduceNoFabricatedEvidence() {
        var s=new Snapshot(book,"v",1,1,List.of(new ChapterText("12",1,"空章","","v",false)),false);
        assertTrue(EvidenceRanking.split(s).isEmpty());
    }
    @Test void lexicalRetrievalHasNoHitsForUnrelatedTerms() {
        assertTrue(EvidenceRanking.rank(List.of(document("a","田园种菜",List.of(),"")),"星际战争",List.of(),"").isEmpty());
    }
    @Test void hybridFindsMeaningFromVectorsWithoutLiteralOverlap() {
        var relevant=document("a","侦探进入现场",List.of(1.0,0.0),"model-v1");
        var other=document("b","田园种菜",List.of(0.0,1.0),"model-v1");
        assertEquals("a",EvidenceRanking.rank(List.of(other,relevant),"调查谜案",List.of(1.0,0.0),"model-v1").get(0).chunk().id());
    }
    @Test void modelOrDimensionChangesCannotReuseOldVectors() {
        var doc=document("a","田园种菜",List.of(1.0,0.0),"old-model");
        assertTrue(EvidenceRanking.rank(List.of(doc),"调查谜案",List.of(1.0,0.0),"new-model").isEmpty());
        assertEquals(-2,EvidenceRanking.cosine(List.of(1.0),List.of(1.0,0.0)));
    }
    @Test void contractsRejectUnboundedOrInjectedIds() {
        assertThrows(com.novel.common.exception.BusinessException.class,()->new EvidenceQuery(List.of("99 OR 1=1"),"调查"));
        assertThrows(com.novel.common.exception.BusinessException.class,()->new EvidenceQuery(Collections.nCopies(21,"99"),"调查"));
        assertThrows(com.novel.common.exception.BusinessException.class,()->new EvidenceQuery(List.of("99"),""));
    }
}
