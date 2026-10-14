package com.novel.modules.agent.bookfinder.model;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/** AI Services owns the model/tool loop. JSON is validated by the business result boundary. */
public interface BookFinderAssistant {
    String chat(@MemoryId String conversationKey,@UserMessage String message);
}
