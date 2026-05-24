package com.yyh.chat.client.dto;

import lombok.Data;

@Data
public class KnowledgeSearchHitClientResponse {

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
