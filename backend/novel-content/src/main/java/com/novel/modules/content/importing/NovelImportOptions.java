package com.novel.modules.content.importing;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class NovelImportOptions {
    @NotBlank @Size(max = 100) private String title;
    @NotBlank @Size(max = 100) private String originalAuthor;
    @NotNull private Long categoryId;
    @Size(max = 1000) private String intro;
    @Size(max = 200) private String tags;
    @Size(max = 255) private String cover;
    @NotNull @Min(0) @Max(1) private Integer isFinish;
}
