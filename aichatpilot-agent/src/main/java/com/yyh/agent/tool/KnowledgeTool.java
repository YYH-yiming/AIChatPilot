package com.yyh.agent.tool;

import com.yyh.agent.client.KnowledgeServiceClient;
import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeTool {

    private final KnowledgeServiceClient knowledgeServiceClient;

    public KnowledgeAskClientResponse askKnowledge(String query, Long kbId, Integer topK) {
        return knowledgeServiceClient.ask(kbId, query, topK);
    }

    public List<KnowledgeSearchHitClientResponse> searchKnowledge(String query, Long kbId, Integer topK) {
        return knowledgeServiceClient.search(kbId, query, topK);
    }
}
