package com.yyh.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.IntentResult;
import com.yyh.agent.llm.AgentLlmResult;
import com.yyh.agent.llm.AgentLlmService;
import com.yyh.agent.memory.ShortTermMemory;
import com.yyh.agent.trace.AgentTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouterAgentTest {

    @Mock
    private AgentLlmService agentLlmService;

    @Mock
    private ShortTermMemory shortTermMemory;

    @Mock
    private AgentTraceService agentTraceService;

    private RouterAgent routerAgent;

    @BeforeEach
    void setUp() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.getRouter().setConfidenceThreshold(0.65);
        routerAgent = new RouterAgent(agentLlmService, agentProperties, shortTermMemory, agentTraceService, new ObjectMapper());
        doNothing().when(agentTraceService).record(org.mockito.ArgumentMatchers.any());

        Map<String, Object> details = new HashMap<>();
        details.put("tenantId", 2L);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(2L, null);
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void shouldFallbackToRuleWhenLlmResponseInvalid() {
        AgentRequest request = new AgentRequest();
        request.setQuery("帮我查一下订单12345的物流状态");

        when(shortTermMemory.buildConversationContext(null, 2L)).thenReturn("");
        when(agentLlmService.chat(anyString(), anyString())).thenReturn(new AgentLlmResult("not-json", 8));

        IntentResult result = routerAgent.route(request);

        assertEquals("order", result.getIntent());
    }
}
