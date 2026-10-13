package com.novel.ai.config;
import com.novel.common.sentinel.SentinelRules;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
@Configuration
public class AiSentinelConfig {
 @PostConstruct public void loadRules() {
  SentinelRules.loadSlowCallRules("novel-ai",
   "POST:http://novel-auth/internal/users/nicknames",
   "GET:http://novel-auth/internal/users/{id}/nickname",
   "POST:http://novel-interaction/internal/comment-briefs",
   "POST:http://novel-interaction/internal/comment-texts",
   "GET:http://novel-content/internal/ai-content/owned-novels/{id}",
   "GET:http://novel-content/internal/ai-content/owned-chapters/{id}",
   "GET:http://novel-content/internal/ai-content/owned-outlines/{id}",
   "GET:http://novel-content/internal/ai-content/chapter-outlines/{id}",
   "GET:http://novel-content/internal/ai-content/chapter-range",
   "POST:http://novel-content/internal/ai-content/owned-contents",
   "POST:http://novel-content/internal/ai-content/chapters",
   "POST:http://novel-content/internal/ai-content/novels",
   "GET:http://novel-content/internal/ai-content/chapters/{id}",
   "GET:http://novel-content/internal/ai-content/chapter-contents/{id}",
   "POST:http://novel-content/internal/ai-content/dispositions");
 }
}
