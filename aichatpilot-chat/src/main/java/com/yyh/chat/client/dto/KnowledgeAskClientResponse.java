package com.yyh.chat.client.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeAskClientResponse {

    private Long kbId;
    private String query;
    private Integer topK;
    private String answer;
    private Boolean grounded;
    private Integer referenceCount;
    private String model;
    private List<KnowledgeSearchHitClientResponse> references = new ArrayList<>();
}
