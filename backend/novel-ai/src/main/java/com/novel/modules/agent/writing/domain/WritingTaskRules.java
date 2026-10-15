package com.novel.modules.agent.writing.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.exception.BusinessException;
import java.util.Set;

/** Pure validation rules; no database, model or HTTP dependencies. */
public final class WritingTaskRules {
    private WritingTaskRules() {}
    public static final Set<String> RUNNING=Set.of("PLANNING","GENERATING","CHECKING");
    public static BusinessException error(int code,String message){return new BusinessException(code,message,code);}
    public static void require(String status,String expected){if(!status.equals(expected))throw error(409,"当前状态不能执行此操作");}
    public static long positive(JsonNode req,String field){long n=req.path(field).asLong(-1);if(n<=0)throw error(422,"缺少 "+field);return n;}
    public static boolean anyLong(JsonNode array,int length){for(var v:array)if(!v.isTextual()||v.asText().length()>length)return true;return false;}
    public static JsonNode validatePlan(JsonNode plan) {
        if(!plan.isObject() || plan.path("needsInput").asBoolean() || plan.path("summary").asText().isBlank()
                || !plan.path("events").isArray() || plan.path("events").isEmpty() || plan.path("events").size()>10
                || plan.path("ending").asText().isBlank() || plan.toString().length()>6000)
            throw error(422,"大纲或目标信息不足，请补充主要事件与结尾后重新创建");
        return plan;
    }
}
