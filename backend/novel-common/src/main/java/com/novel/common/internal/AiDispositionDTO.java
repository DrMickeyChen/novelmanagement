package com.novel.common.internal;
import java.time.LocalDateTime;
public record AiDispositionDTO(Long rowId, Integer status, Long operatorId, LocalDateTime at) {}
