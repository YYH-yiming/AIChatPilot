package com.yyh.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.client.AgentServiceClient;
import com.yyh.chat.client.KnowledgeServiceClient;
import com.yyh.chat.client.dto.KnowledgeAskClientResponse;
import com.yyh.chat.service.KnowledgeBaseSelector.SelectionResult;
import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import com.yyh.chat.entity.ChatMessage;
import com.yyh.chat.entity.ChatSession;
import com.yyh.chat.mapper.ChatMessageMapper;
import com.yyh.chat.mapper.ChatSessionMapper;
import com.yyh.chat.memory.ChatMemoryService;
import com.yyh.chat.support.ChatConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final KnowledgeServiceClient knowledgeServiceClient = mock(KnowledgeServiceClient.class);
    private final AgentServiceClient agentServiceClient = mock(AgentServiceClient.class);
    private final KnowledgeBaseSelector knowledgeBaseSelector = mock(KnowledgeBaseSelector.class);
    private final StandaloneQuestionRewriteService rewriteService = mock(StandaloneQuestionRewriteService.class);
    private final ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
    private final ChatAnalyticsEventPublisher chatAnalyticsEventPublisher = mock(ChatAnalyticsEventPublisher.class);
    private final ChatProperties chatProperties = new ChatProperties();
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);

    private final ChatService chatService = new ChatService(
            chatSessionMapper,
            chatMessageMapper,
            knowledgeServiceClient,
            agentServiceClient,
            knowledgeBaseSelector,
            rewriteService,
            chatMemoryService,
            chatAnalyticsEventPublisher,
            chatProperties,
            objectMapper
    );

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSendMessageThroughKnowledgeMode() throws Exception {
        mockLoginContext();

        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setTenantId(2L);
        session.setUserId(3L);
        session.setMode(ChatConstants.MODE_KNOWLEDGE);
        session.setKbId(11L);
        session.setTitle("新会话");
        session.setStatus(ChatConstants.SESSION_STATUS_ACTIVE);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        session.setLastMessageAt(LocalDateTime.now());

        when(chatSessionMapper.selectOne(any())).thenReturn(session);
        when(chatMemoryService.loadRecentMessages(eq(1L), eq(chatProperties.getContext().getWindowRounds() * 2)))
                .thenReturn(List.of());
        when(rewriteService.rewrite(eq("那生产环境呢？"), eq(List.of()))).thenReturn("生产环境如何切换配置？");
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");
        when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (ChatConstants.ROLE_USER.equals(message.getRole())) {
                message.setId(100L);
            } else {
                message.setId(101L);
            }
            return 1;
        });
        doNothing().when(chatMemoryService).appendMessage(any(ChatMessage.class));

        KnowledgeAskClientResponse response = new KnowledgeAskClientResponse();
        response.setKbId(11L);
        response.setAnswer("生产环境建议走独立 env 文件。");
        response.setGrounded(true);
        response.setReferenceCount(1);
        when(knowledgeBaseSelector.ask("生产环境如何切换配置？", 11L, 5))
                .thenReturn(new SelectionResult(11L, response, "request-kbid"));
        when(chatSessionMapper.update(any(), any())).thenReturn(1);

        ChatSendMessageRequest request = new ChatSendMessageRequest();
        request.setContent("那生产环境呢？");
        request.setTopK(5);

        ChatReplyVO result = chatService.sendMessage(1L, request);

        assertEquals("knowledge", result.getAnswerSource());
        assertEquals("生产环境如何切换配置？", result.getRewrittenQuery());
        assertEquals("生产环境建议走独立 env 文件。", result.getAnswer());
        assertEquals(100L, result.getUserMessageId());
        assertEquals(101L, result.getAssistantMessageId());
        verify(knowledgeBaseSelector).ask("生产环境如何切换配置？", 11L, 5);
    }

    private void mockLoginContext() {
        Map<String, Object> details = new HashMap<>();
        details.put("tenantId", 2L);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(3L, null, List.of());
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
