package com.yyh.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
    private Integer parseStatus;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createdAt;
}
