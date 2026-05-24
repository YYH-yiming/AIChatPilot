package com.yyh.chat.client.dto;

import lombok.Data;

@Data
public class KnowledgeBaseClientResponse {

    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Integer docCount;
    private Integer chunkCount;
    private String embeddingModel;
    private Integer status;
}
