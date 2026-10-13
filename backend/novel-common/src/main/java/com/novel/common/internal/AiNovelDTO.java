package com.novel.common.internal;
import lombok.Data;
/** AI service content protocol; no persistence annotations. */
@Data
public class AiNovelDTO {
    private Long id;
    private String title;
}
