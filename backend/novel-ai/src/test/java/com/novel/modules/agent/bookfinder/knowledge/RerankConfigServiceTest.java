package com.novel.modules.agent.bookfinder.knowledge;

import com.novel.common.exception.BusinessException;
import com.novel.modules.ai.mapper.RerankConfigMapper;
import com.novel.modules.ai.service.AiCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RerankConfigServiceTest {
    private final RerankConfigMapper mapper=mock(RerankConfigMapper.class,withSettings().mockMaker("mock-maker-subclass"));
    private final AiCrypto crypto=new AiCrypto("isolated-test-encryption-secret");
    private final RerankConfigService service=new RerankConfigService(mapper,crypto);
    private final AtomicReference<RerankConfigMapper.Row> stored=new AtomicReference<>();
    @BeforeEach void setup() {
        when(mapper.current()).thenAnswer(i->stored.get());
        doAnswer(i->{ stored.set(i.getArgument(0)); return null; }).when(mapper).save(any());
    }
    private RerankConfigService.Save input(String key,boolean clear) {
        return new RerankConfigService.Save("https://api.example.com/v1/embeddings","test-model",key,clear,8);
    }
    @Test void keyIsEncryptedAndOnlyMaskedInResponse() throws Exception {
        var view=service.save(input("test-secret-abcd",false));
        assertNotEquals("test-secret-abcd",stored.get().apiKey);
        assertEquals("test-secret-abcd",crypto.decrypt(stored.get().apiKey));
        assertEquals("****abcd",view.apiKeyMasked()); assertTrue(view.configured());
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("test-secret"));
        assertFalse(service.runtime().toString().contains("test-secret"));
        assertFalse(input("test-secret-abcd",false).toString().contains("test-secret"));
    }
    @Test void blankPreservesAndExplicitClearPersistsNull() {
        service.save(input("test-secret-abcd",false)); String cipher=stored.get().apiKey;
        service.save(input("",false)); assertEquals(cipher,stored.get().apiKey);
        var view=service.save(input(null,true));
        assertNull(stored.get().apiKey); assertFalse(view.apiKeyConfigured());
        assertEquals("",service.runtime().key()); // A saved clear never falls back to an environment key.
    }
    @Test void missingEncryptionSecretCannotPersistPlaintext() {
        var missing=new RerankConfigService(mapper,new AiCrypto(""));
        assertThrows(BusinessException.class,()->missing.save(input("secret",false)));
        verify(mapper,never()).save(any());
    }
    @Test void conflictingKeysInvalidUrlAndBatchAreRejected() {
        assertThrows(BusinessException.class,()->service.save(input("secret",true)));
        assertThrows(BusinessException.class,()->service.save(new RerankConfigService.Save("https://user:pass@example.com/embeddings","m",null,false,8)));
        assertThrows(BusinessException.class,()->service.save(new RerankConfigService.Save("https://example.com/embeddings","m",null,false,17)));
        verify(mapper,never()).save(any());
    }
    @Test void unreadableCipherProducesNoticeWithoutLeakingIt() {
        var row=new RerankConfigMapper.Row(); row.url="https://example.com/embeddings"; row.model="m"; row.batchSize=8; row.apiKey="not-a-real-cipher"; stored.set(row);
        var view=service.get(); assertTrue(view.apiKeyConfigured()); assertNotNull(view.apiKeyNotice()); assertNull(view.apiKeyMasked());
        assertFalse(view.configured()); assertThrows(BusinessException.class,service::runtime);
    }
}
