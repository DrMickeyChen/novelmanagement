package com.novel.modules.content.importing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("novel_import_task")
public class NovelImportTask {
    @TableId(type = IdType.INPUT) private String id;
    private Long operatorId;
    private String filename;
    private String fileHash;
    private String encoding;
    private String parseMode;
    private String status;
    private String previewJson;
    private String optionsJson;
    private Integer totalChapters;
    private Integer importedChapters;
    private Long novelId;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
