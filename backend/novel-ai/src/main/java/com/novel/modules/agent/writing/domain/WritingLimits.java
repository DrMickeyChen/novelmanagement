package com.novel.modules.agent.writing.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record WritingLimits(
 @Value("${novel.ai.writing.max-input-tokens:48000}") int maxInputTokens,
 @Value("${novel.ai.writing.hard-token-budget:220000}") int hardTokenBudget,
 @Value("${novel.ai.writing.soft-token-budget:160000}") int softTokenBudget,
 @Value("${novel.ai.writing.max-model-calls:10}") int maxModelCalls
) { public static WritingLimits defaults(){return new WritingLimits(48000,220000,160000,10);} }
