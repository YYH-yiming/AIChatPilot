package com.yyh.agent.service;

import com.yyh.agent.client.KnowledgeServiceClient;
import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeBaseClientResponse;
import com.yyh.agent.dto.AgentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseSelectorTest {

    @Mock
    private KnowledgeServiceClient knowledgeServiceClient;

    @InjectMocks
    private KnowledgeBaseSelector knowledgeBaseSelector;

    @Test
    void shouldUseRequestKbIdFirst() {
        AgentRequest request = new AgentRequest();
        request.setKbId(99L);
        request.setQuery("退款多久到账");

        KnowledgeAskClientResponse askResponse = new KnowledgeAskClientResponse();
        askResponse.setAnswer("ok");

        when(knowledgeServiceClient.ask(eq(99L), eq("退款多久到账"), eq(5))).thenReturn(askResponse);

        KnowledgeBaseSelector.SelectionResult result = knowledgeBaseSelector.askAcrossKnowledgeBases(request, "policy", 2L, 5);

        assertEquals(99L, result.kbId());
        assertEquals("request-kbid", result.strategy());
    }

    @Test
    void shouldPreferMatchedKnowledgeBaseByName() {
        AgentRequest request = new AgentRequest();
        request.setQuery("员工差旅报销政策里，酒店标准如何规定？");

        KnowledgeBaseClientResponse policyBase = new KnowledgeBaseClientResponse();
        policyBase.setId(20L);
        policyBase.setName("客户服务政策库");
        policyBase.setDescription("退款 发票 服务规则 差旅报销政策");
        policyBase.setStatus(1);
        policyBase.setDocCount(5);
        policyBase.setChunkCount(20);

        KnowledgeBaseClientResponse introBase = new KnowledgeBaseClientResponse();
        introBase.setId(21L);
        introBase.setName("公司介绍与销售口径库");
        introBase.setDescription("公司介绍 客户类型");
        introBase.setStatus(1);
        introBase.setDocCount(5);
        introBase.setChunkCount(20);

        KnowledgeAskClientResponse askResponse = new KnowledgeAskClientResponse();
        askResponse.setAnswer("酒店标准按差旅制度执行");
        askResponse.setGrounded(true);
        askResponse.setReferenceCount(2);

        when(knowledgeServiceClient.listKnowledgeBases()).thenReturn(List.of(introBase, policyBase));
        when(knowledgeServiceClient.ask(eq(20L), eq("员工差旅报销政策里，酒店标准如何规定？"), eq(5))).thenReturn(askResponse);

        KnowledgeBaseSelector.SelectionResult result = knowledgeBaseSelector.askAcrossKnowledgeBases(request, "policy", 2L, 5);

        assertEquals(20L, result.kbId());
        assertEquals("matched-by-selector", result.strategy());
        verify(knowledgeServiceClient).ask(eq(20L), eq("员工差旅报销政策里，酒店标准如何规定？"), eq(5));
    }
}

