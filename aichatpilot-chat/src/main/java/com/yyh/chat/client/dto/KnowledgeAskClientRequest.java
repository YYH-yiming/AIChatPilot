package com.yyh.chat.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class KnowledgeAskClientRequest {

    private String query;
    private Integer topK;
}
