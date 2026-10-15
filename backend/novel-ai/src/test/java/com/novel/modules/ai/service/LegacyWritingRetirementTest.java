package com.novel.modules.ai.service;

import com.novel.common.exception.BusinessException;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.dto.AiPromptSaveDTO;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiPromptTemplateMapper;
import com.novel.modules.ai.service.impl.AiPromptServiceImpl;
import com.novel.modules.ai.service.impl.DeepSeekAiStreamSource;
import com.novel.modules.ai.service.impl.MockAiStreamSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyWritingRetirementTest {
    private static final AiScene[] RETIRED = {AiScene.OUTLINE, AiScene.WRITE, AiScene.INSPIRE, AiScene.POLISH};

    @Test void sourcesRejectOldScenesBeforeAnyModelAccess() {
        var config = mock(AiConfigService.class);
        var recorder = mock(AiUsageRecorder.class);
        var client = mock(AiModelClient.class);
        var real = new DeepSeekAiStreamSource(config, recorder, client);
        var fake = new MockAiStreamSource(0);
        for (var scene : RETIRED) {
            assertThrows(IllegalStateException.class, () -> real.stream(scene, new AiRequestDTO(), null));
            assertThrows(IllegalStateException.class, () -> fake.stream(scene, new AiRequestDTO(), null));
        }
        verifyNoInteractions(config, recorder, client);
    }

    @Test void retiredTemplatesCannotBeLoadedOrSaved() {
        var mapper = mock(AiPromptTemplateMapper.class);
        var service = new AiPromptServiceImpl(mapper);
        for (var scene : RETIRED) {
            var dto = new AiPromptSaveDTO();
            dto.setScene(scene);
            assertThrows(BusinessException.class, () -> service.requireForScene(scene));
            assertThrows(BusinessException.class, () -> service.list(scene));
            assertThrows(BusinessException.class, () -> service.create(dto));
            assertThrows(BusinessException.class, () -> service.update(1L, dto));
        }
        verifyNoInteractions(mapper);
    }
}
