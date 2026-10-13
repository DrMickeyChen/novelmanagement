package com.novel.common.internal;
import lombok.Data;
/** AI service content protocol; no persistence annotations. */
@Data
public class AiChapterContentDTO {
    private Long chapterId;
    private String content;
}
