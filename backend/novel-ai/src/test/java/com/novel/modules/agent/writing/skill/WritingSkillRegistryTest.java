package com.novel.modules.agent.writing.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.writing.domain.WritingLimits;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WritingSkillRegistryTest {
    ObjectMapper json=new ObjectMapper();
    Resource resource(String id,String body){return new ByteArrayResource(("---\nid: "+id+"\nname: 测试\ndescription: 测试说明\nversion: 1\n---\n"+body).getBytes(StandardCharsets.UTF_8));}
    @Test void deployedFilesPreserveBothOriginalRules(){
        var registry=new WritingSkillRegistry();assertEquals(2,registry.list().size());
        var input=json.createObjectNode();input.putArray("skillIds").add("restrained").add("dialogue");
        assertEquals("克制叙述，少解释，用动作表现情绪。对话推进剧情，角色语言有区别，避免信息堆砌。",registry.instructions(input));
        assertTrue(registry.list().stream().allMatch(s->!s.description().isBlank()&&s.hash().length()==64));
    }
    @Test void missingDuplicateMalformedAndOversizeFilesFailExplicitly(){
        assertThrows(IllegalStateException.class,()->new WritingSkillRegistry(new Resource[0]));
        assertThrows(IllegalStateException.class,()->new WritingSkillRegistry(new Resource[]{resource("same","规则"),resource("same","规则")}));
        assertThrows(IllegalArgumentException.class,()->WritingSkillRegistry.parse("no metadata"));
        assertThrows(IllegalStateException.class,()->new WritingSkillRegistry(new Resource[]{resource("oversize","大".repeat(6000))}));
    }
    @Test void metadataCannotDeclareUnknownFieldsOrTraversalIds(){
        assertThrows(IllegalArgumentException.class,()->WritingSkillRegistry.parse("---\nid: safe\nname: 测试\ndescription: 测试\nversion: 1\nscript: run\n---\n规则"));
        assertThrows(IllegalStateException.class,()->new WritingSkillRegistry(new Resource[]{resource("../other","规则")}));
    }
    @Test void selectionsRejectUnknownDuplicateAndTooManyIds(){
        var registry=new WritingSkillRegistry();
        for(var ids:List.of(List.of("absent"),List.of("restrained","restrained"),List.of("restrained","dialogue","other")))assertThrows(BusinessException.class,()->registry.validate(json.valueToTree(ids)));
        assertThrows(BusinessException.class,()->registry.validate(json.getNodeFactory().textNode("restrained")));
    }
    @Test void filesExtendTheCatalogWithoutChangingJavaAllowLists(){
        var registry=new WritingSkillRegistry(new Resource[]{resource("new-style","新规则")});
        var input=json.createObjectNode();registry.freeze(input,json.valueToTree(List.of("new-style")));
        assertEquals("新规则",registry.instructions(input));assertEquals("new-style",registry.list().getFirst().id());
    }
    @Test void taskSnapshotsRemainStableAcrossDeploymentAndSelectionChanges(){
        var first=new WritingSkillRegistry(new Resource[]{resource("style","旧规则"),resource("other","另一规则")});
        var updated=new WritingSkillRegistry(new Resource[]{resource("style","新规则"),resource("other","另一规则")});
        var input=json.createObjectNode();first.freeze(input,json.valueToTree(List.of("style")));
        updated.freeze(input,json.valueToTree(List.of("style","other")));
        assertEquals("旧规则另一规则",updated.instructions(input));assertTrue(input.path("skillSnapshots").get(0).has("hash"));
        updated.freeze(input,json.valueToTree(List.of("other")));assertEquals("另一规则",updated.instructions(input));
    }
    @Test void springConstructsRegistryAndDefaultLimitsWithoutOtherServices(){
        try(var context=new AnnotationConfigApplicationContext(WritingSkillRegistry.class,WritingLimits.class)){
            assertEquals(2,context.getBean(WritingSkillRegistry.class).list().size());assertEquals(10,context.getBean(WritingLimits.class).maxModelCalls());
        }
    }
}
