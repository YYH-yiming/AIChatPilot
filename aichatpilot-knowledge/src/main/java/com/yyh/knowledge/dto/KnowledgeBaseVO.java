package com.yyh.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseVO {
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Integer docCount;
    private Integer chunkCount;
    private String embeddingModel;
    private Integer status;
    private LocalDateTime createdAt;
}
