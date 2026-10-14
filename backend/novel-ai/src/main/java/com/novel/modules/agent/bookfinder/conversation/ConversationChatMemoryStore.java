package com.novel.modules.agent.bookfinder.conversation;

import com.novel.modules.agent.bookfinder.BookFinderTypes.Conversation;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.*;

/**
 * Redis-backed canonical history is owned by BookFinderStore.complete.
 * Stage tool messages only for this request; failed executions never persist them.
 * Each successful request persists the user/validated answer pair, not unvalidated model text.
 */
public final class ConversationChatMemoryStore implements ChatMemoryStore {
    private final String id;
    private List<ChatMessage> messages=new ArrayList<>();
    public ConversationChatMemoryStore(String id,Conversation conversation) {
        this(id,conversation,null);
    }
    public ConversationChatMemoryStore(String id,Conversation conversation,String system) {
        this.id=id;
        if(system!=null) messages.add(SystemMessage.from(system));
        for(var turn:conversation.getHistory()) {
            messages.add(UserMessage.from(turn.user()));
            messages.add(AiMessage.from(turn.assistant()));
        }
    }
    private void requireId(Object requested) {
        if(!id.equals(requested)) throw new IllegalArgumentException("Conversation memory scope mismatch");
    }
    @Override public List<ChatMessage> getMessages(Object requested) { requireId(requested); return new ArrayList<>(messages); }
    @Override public void updateMessages(Object requested,List<ChatMessage> updated) { requireId(requested); messages=new ArrayList<>(updated); }
    @Override public void deleteMessages(Object requested) { requireId(requested); messages.clear(); }
}
