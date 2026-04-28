package com.yyh.knowledge.dto;

import lombok.Data;

import java.util.List;

@Data
public class KnowledgeAskResponse {

    private Long kbId;
    private String query;
    private Integer topK;
    private String answer;
    private Boolean grounded;
    private Integer referenceCount;
    private String model;
    private List<KnowledgeSearchHitVO> references;
}
