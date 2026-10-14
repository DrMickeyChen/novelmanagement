package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.modules.agent.bookfinder.model.*;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

/** The entire existing business regression suite also runs through the actual AI Services loop. */
class BookFinderFrameworkRegressionTest extends BookFinderAgentTest {
    @BeforeEach void useFramework() {
        var agent=(BookFinderAgent)ReflectionTestUtils.getField(this,"agent");
        var model=(BookFinderModel)ReflectionTestUtils.getField(this,"model");
        var config=(AiConfigService)ReflectionTestUtils.getField(this,"config");
        var json=(ObjectMapper)ReflectionTestUtils.getField(this,"json");
        var recorder=mock(AiUsageRecorder.class,withSettings().mockMaker("mock-maker-subclass"));
        when(model.mock()).thenReturn(true);
        agent.framework(new BookFinderAiServices(json,new LangChain4jModelFactory(config),recorder));
        ReflectionTestUtils.setField(agent,"engine","langchain4j");
    }
}
