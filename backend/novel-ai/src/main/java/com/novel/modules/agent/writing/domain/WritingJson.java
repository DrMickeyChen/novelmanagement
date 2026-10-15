package com.novel.modules.agent.writing.domain;


import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Accept one JSON object only. Never guesses a plan from arbitrary prose. */
public final class WritingJson {
    private WritingJson() {}
    public static ObjectNode parse(ObjectMapper mapper,String text,String phase) {
        try {
            if(text==null || text.isBlank() || text.length()>20000)throw new IllegalArgumentException();
            String clean=text.trim().replaceAll("(?i)^```(?:json)?\\s*","").replaceAll("\\s*```$","");
            var value=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(clean);
            if(!(value instanceof ObjectNode object))throw new IllegalArgumentException();
            switch(phase) {
                case "RECAP" -> {string(object,"summary");if(!object.path("sourceIds").isArray())throw new IllegalArgumentException();}
                case "PLAN" -> {
                    string(object,"summary");
                    if(!object.path("needsInput").asBoolean()) {
                        string(object,"ending");
                        if(!object.path("events").isArray() || object.path("events").isEmpty() || object.path("events").size()>10)throw new IllegalArgumentException();
                        for(var event:object.path("events"))if(!event.isTextual() || event.asText().isBlank())throw new IllegalArgumentException();
                    }
                }
                case "CHECK" -> {if(!object.path("issues").isArray() || object.path("issues").size()>20)throw new IllegalArgumentException();for(var issue:object.path("issues"))if(!issue.isObject())throw new IllegalArgumentException();}
                default -> throw new IllegalArgumentException();
            }
            return object;
        } catch(Exception e) {throw WritingTaskRules.error(502,"模型"+label(phase)+"输出格式不正确，已保留记录");}
    }
    private static void string(ObjectNode object,String field){if(!object.path(field).isTextual() || object.path(field).asText().isBlank())throw new IllegalArgumentException();}
    public static String label(String phase){return switch(phase){case "RECAP"->"前文回顾";case "PLAN"->"情节计划";case "CHECK"->"检查";default->"结构化";};}
}
