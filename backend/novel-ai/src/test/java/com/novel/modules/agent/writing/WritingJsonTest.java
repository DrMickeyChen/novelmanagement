package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.domain.WritingJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WritingJsonTest {
    ObjectMapper json=new ObjectMapper();
    String plan="{\"summary\":\"相遇\",\"events\":[\"雨夜相遇\"],\"ending\":\"分别\",\"needsInput\":false}";
    @Test void validObjectAndSingleCodeFenceAreAccepted(){assertEquals("相遇",WritingJson.parse(json,plan,"PLAN").path("summary").asText());assertEquals(1,WritingJson.parse(json,"```JSON\n"+plan+"\n```","PLAN").path("events").size());}
    @Test void proseMultipleObjectsNullAndArrayAreRejected(){for(String text:new String[]{"先相遇再分别",plan+plan,"null","[]","说明："+plan})assertThrows(RuntimeException.class,()->WritingJson.parse(json,text,"PLAN"));}
    @Test void invalidSchemaDoesNotBecomeAPlan(){assertThrows(RuntimeException.class,()->WritingJson.parse(json,"{\"summary\":\"相遇\",\"events\":[1],\"ending\":\"分别\"}","PLAN"));assertThrows(RuntimeException.class,()->WritingJson.parse(json,"{\"summary\":\"相遇\"}","PLAN"));}
    @Test void clarificationIsValidStructuredOutput(){assertTrue(WritingJson.parse(json,"{\"summary\":\"请补充结尾目标\",\"needsInput\":true}","PLAN").path("needsInput").asBoolean());}
}
