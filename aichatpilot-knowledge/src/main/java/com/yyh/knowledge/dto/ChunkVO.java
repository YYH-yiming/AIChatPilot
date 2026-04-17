package com.yyh.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChunkVO {
    private Long id;
    private Long docId;
    private Long kbId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String metadata;
    private LocalDateTime createdAt;
}
