package com.yyh.agent.dto;

import lombok.Data;

@Data
public class KnowledgeReference {
    private Long chunkId;
    private Long docId;
    private Long kbId;
    private Integer chunkIndex;
    private Integer tokenCount;
    private String content;
    private Double score;
    private Double denseScore;
    private Double sparseScore;
    private String source;
}
