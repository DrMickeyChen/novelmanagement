package com.novel.modules.agent.bookfinder.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import java.util.*;

/** A model judgement is accepted only against fresh, server-validated references. */
public final class PreferenceEvidence {
    private PreferenceEvidence() {}
    public static List<Assessment> assess(List<Preference> preferences, JsonNode proposals, Set<String> freshIds) {
        var result=new ArrayList<Assessment>();
        for(var preference:preferences) {
            JsonNode match=null;
            if(proposals.isArray()) for(var p:proposals) if(preference.criterion().equals(p.path("criterion").asText())) { match=p; break; }
            String verdict="UNKNOWN"; var ids=new LinkedHashSet<String>();
            if(match!=null && match.path("evidenceIds").isArray() && match.path("evidenceIds").size()<=2) {
                boolean valid=true;
                for(var id:match.path("evidenceIds")) {
                    if(!id.isTextual() || !freshIds.contains(id.asText())) valid=false;
                    else ids.add(id.asText());
                }
                String proposed=match.path("verdict").asText();
                if(valid && !ids.isEmpty() && Set.of("SUPPORTED","CONTRADICTED").contains(proposed)) verdict=proposed;
            }
            // Sampled opening chapters cannot establish an all-book absence claim.
            if(verdict.equals("SUPPORTED") && preference.criterion().matches(".*(全书|从不|永不|始终|完全没有).*")) verdict="UNKNOWN";
            if(verdict.equals("UNKNOWN")) ids.clear();
            result.add(new Assessment(preference.criterion(),preference.required(),verdict,List.copyOf(ids),
                    "模型依据本轮已覆盖正文片段判断；不代表全书结论"));
        }
        return result;
    }
}
