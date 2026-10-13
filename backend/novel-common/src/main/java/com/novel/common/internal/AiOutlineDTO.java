package com.novel.common.internal;
import lombok.Data;
/** AI service content protocol; no persistence annotations. */
@Data
public class AiOutlineDTO {
    private Long id;
    private Long novelId;
    private String title;
    private String content;
}
