package com.novel.common.internal;
import java.time.LocalDateTime;
public record AiDispositionQuery(Long rowId, Integer targetType, Long targetId, LocalDateTime since) {}
