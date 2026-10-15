package com.novel.modules.agent.infrastructure.model;

import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.service.AiRuntimeConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;

/** Shared model construction for book finding and future writing. Never caches or logs keys. */
@Component @RequiredArgsConstructor
public class LangChain4jModelFactory {
    private final AiConfigService config;
    public ChatModel create(AiRuntimeConfig settings,long remainingMs) {
        var timeout=Duration.ofMillis(Math.max(1,Math.min(30000,remainingMs)));
        var builder=OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl().replaceAll("/+$","")+"/")
                .apiKey(config.decryptCurrentApiKey()).modelName(settings.model())
                .maxTokens(Math.min(2048,settings.maxTokens()==null?2048:settings.maxTokens()))
                .httpClientBuilder(new JdkHttpClientBuilder().connectTimeout(Duration.ofMillis(Math.min(5000,timeout.toMillis()))).readTimeout(timeout))
                .timeout(timeout).maxRetries(0).logRequests(false).logResponses(false)
                .strictTools(false).strictJsonSchema(false);
        if(settings.temperature()!=null) builder.temperature(settings.temperature().doubleValue());
        if(settings.model().startsWith("deepseek")) builder.customParameters(Map.of("thinking",Map.of("type","disabled")));
        return builder.build();
    }

    public ChatModel writer(AiRuntimeConfig settings,int maxTokens) {
        var builder=OpenAiChatModel.builder().baseUrl(settings.baseUrl().replaceAll("/+$","")+"/")
                .apiKey(config.decryptCurrentApiKey()).modelName(settings.model()).maxTokens(maxTokens)
                .httpClientBuilder(new JdkHttpClientBuilder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(90)))
                .timeout(Duration.ofSeconds(90)).maxRetries(0).logRequests(false).logResponses(false);
        if(settings.temperature()!=null) builder.temperature(settings.temperature().doubleValue());
        if(settings.model().startsWith("deepseek")) builder.customParameters(Map.of("thinking",Map.of("type","disabled")));
        return builder.build();
    }

    public dev.langchain4j.model.chat.StreamingChatModel writerStream(AiRuntimeConfig settings,int maxTokens) {
        var builder=dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl().replaceAll("/+$","")+"/").apiKey(config.decryptCurrentApiKey())
                .modelName(settings.model()).maxTokens(maxTokens)
                .httpClientBuilder(new JdkHttpClientBuilder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(90)))
                .timeout(Duration.ofSeconds(90)).logRequests(false).logResponses(false);
        if(settings.temperature()!=null) builder.temperature(settings.temperature().doubleValue());
        if(settings.model().startsWith("deepseek")) builder.customParameters(Map.of("thinking",Map.of("type","disabled")));
        return builder.build();
    }
}
