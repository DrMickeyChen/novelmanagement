package com.novel.modules.content.importing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("novel_source")
public class NovelSource {
    @TableId(type = IdType.INPUT) private Long novelId;
    private String sourceType;
    private String originalAuthor;
    private Long importedBy;
    private String fileHash;
    private LocalDateTime createTime;
}
