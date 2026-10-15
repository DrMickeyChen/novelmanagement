package com.novel.modules.agent.writing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.modules.agent.writing.api.*;
import com.novel.modules.agent.writing.application.*;
import com.novel.modules.agent.writing.context.*;
import com.novel.modules.agent.writing.domain.WritingLimits;
import com.novel.modules.agent.writing.infrastructure.client.*;
import com.novel.modules.agent.writing.infrastructure.job.*;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.skill.WritingSkillRegistry;
import com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory;
import com.novel.modules.agent.bookfinder.knowledge.RetrievalModels;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WritingWiringTest {
    @Test void reorganizedComponentsWireAndCapabilitiesComeFromFiles(){
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("novel.ai.writing.enabled","true")));
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            context.registerBean(JdbcTemplate.class,()->mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));
            context.registerBean(TransactionTemplate.class,()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
            context.registerBean(WritingContentClient.class,()->mock(WritingContentClient.class));
            context.registerBean(WritingSearchClient.class,()->mock(WritingSearchClient.class));
            context.registerBean(WritingSyncClient.class,()->mock(WritingSyncClient.class));
            context.registerBean(AiCrypto.class,()->new AiCrypto("test-only-secret"));
            context.registerBean(RetrievalModels.class,()->mock(RetrievalModels.class));
            context.registerBean(AiConfigService.class,()->mock(AiConfigService.class));
            context.registerBean(AiQuotaService.class,()->mock(AiQuotaService.class));
            context.registerBean(AiUsageRecorder.class,()->mock(AiUsageRecorder.class));
            context.register(WritingService.class,WritingTaskWorker.class,WritingIndexJobs.class,WritingBudgetFactory.class,
                    WritingContext.class,WritingRetrieval.class,WritingModel.class,WritingSkillRegistry.class,WritingLimits.class,
                    WritingStore.class,WritingController.class,WritingResourcesController.class,LangChain4jModelFactory.class);
            context.register(WritingAssetService.class,WritingDeletion.class,WritingDrafts.class,WritingDerivations.class,WritingSynchronization.class,
                WritingMcp.class,WritingExtendedContext.class,com.novel.modules.agent.writing.skill.WritingSkills.class,
                com.novel.modules.agent.writing.infrastructure.model.WritingDerivedModel.class,WritingMcpTransport.class,
                WritingExtensionsWorker.class,WritingAssetsController.class,WritingMcpController.class,WritingSynchronizationController.class,
                WritingAssistantSettings.class,WritingAssistantSettingsController.class);
            context.refresh();
            var capabilities=context.getBean(ObjectMapper.class).valueToTree(context.getBean(WritingController.class).capabilities().getData());
            assertTrue(capabilities.path("enabled").asBoolean());assertEquals(2,capabilities.path("skills").size());
            assertTrue(capabilities.path("skills").get(0).has("description"));assertTrue(capabilities.path("skills").get(0).has("hash"));
            assertNotNull(context.getBean(WritingTaskWorker.class));
            assertNotNull(context.getBean(WritingExtensionsWorker.class));assertNotNull(context.getBean(WritingAssetsController.class));
            verifyNoInteractions(context.getBean(WritingContentClient.class),context.getBean(WritingSearchClient.class));
        }
    }
}
