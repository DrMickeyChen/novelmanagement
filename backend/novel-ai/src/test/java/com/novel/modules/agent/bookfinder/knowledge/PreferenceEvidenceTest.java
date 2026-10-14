package com.novel.modules.agent.bookfinder.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.modules.agent.bookfinder.BookFinderTypes.Preference;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PreferenceEvidenceTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void similarityOrMissingCitationsNeverConfirmsPreference() throws Exception {
        var prefs=List.of(new Preference("虐文",false),new Preference("修仙",true));
        var rows=PreferenceEvidence.assess(prefs,json.readTree("""
            [{"criterion":"虐文","verdict":"SUPPORTED","evidenceIds":[]},{"criterion":"修仙","verdict":"SUPPORTED","evidenceIds":["invented"]}]
            """),Set.of("real"));
        assertTrue(rows.stream().allMatch(a->a.verdict().equals("UNKNOWN") && a.evidenceIds().isEmpty()));
        assertTrue(rows.get(1).required());
    }
    @Test void OnlyFreshCitationsCanSupportOrContradictAndOtherCriteriaStayUnknown() throws Exception {
        var rows=PreferenceEvidence.assess(List.of(new Preference("虐文",false),new Preference("无敌",false),new Preference("慢热",false)),json.readTree("""
            [{"criterion":"虐文","verdict":"SUPPORTED","evidenceIds":["1"]},{"criterion":"无敌","verdict":"CONTRADICTED","evidenceIds":["2"]}]
            """),Set.of("1","2"));
        assertEquals(List.of("SUPPORTED","CONTRADICTED","UNKNOWN"),rows.stream().map(a->a.verdict()).toList());
    }
    @Test void openingEvidenceCannotEstablishAllBookAbsence() throws Exception {
        var result=PreferenceEvidence.assess(List.of(new Preference("全书没有恋爱",true)),json.readTree("""
          [{"criterion":"全书没有恋爱","verdict":"SUPPORTED","evidenceIds":["1"]}]
          """),Set.of("1"));
        assertEquals("UNKNOWN",result.get(0).verdict());
    }
}
