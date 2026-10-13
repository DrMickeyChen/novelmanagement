package com.novel.modules.ai.integration;
import com.novel.modules.ai.client.AiContentClient;
import com.novel.common.result.*;
import com.novel.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiContentLookupTest {
 private final AiContentClient client=org.mockito.Mockito.mock(AiContentClient.class,withSettings().mockMaker("mock-maker-subclass"));
 private final AiContentLookup lookup=new AiContentLookup(client);
 @Test void ownershipBusinessCodeSurvivesHttpBoundary(){
  when(client.ownedNovel(99L)).thenReturn(Result.error(ResultCode.NOVEL_NOT_YOURS));
  BusinessException e=assertThrows(BusinessException.class,()->lookup.requireOwned(99L));
  assertEquals(ResultCode.NOVEL_NOT_YOURS.getCode(),e.getCode());
 }
 @Test void unavailableContentIsNeverPresentedAsMissingBook(){
  when(client.chapter(99L)).thenThrow(new IllegalStateException("service unavailable"));
  assertThrows(IllegalStateException.class,()->lookup.chapter(99L));
 }
}
