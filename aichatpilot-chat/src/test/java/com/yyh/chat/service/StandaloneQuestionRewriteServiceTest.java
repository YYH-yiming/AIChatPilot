package com.yyh.chat.service;

import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.llm.ChatLlmService;
import com.yyh.chat.memory.ChatMemoryService.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StandaloneQuestionRewriteServiceTest {

    private final ChatLlmService chatLlmService = mock(ChatLlmService.class);
    private final ChatProperties chatProperties = new ChatProperties();
    private final StandaloneQuestionRewriteService service =
            new StandaloneQuestionRewriteService(chatLlmService, chatProperties);

    @Test
    void shouldReturnOriginalQuestionWhenHistoryIsEmpty() {
        String result = service.rewrite("那生产环境呢？", List.of());

        assertEquals("那生产环境呢？", result);
        verifyNoInteractions(chatLlmService);
    }

    @Test
    void shouldFallbackToOriginalQuestionWhenRewriteFails() {
        List<ConversationMessage> history = List.of(
                new ConversationMessage("user", "本地环境怎么切配置？", LocalDateTime.now()),
                new ConversationMessage("assistant", "可以通过 profile 切换。", LocalDateTime.now())
        );
        when(chatLlmService.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("llm error"));

        String result = service.rewrite("那生产环境呢？", history);

        assertEquals("那生产环境呢？", result);
        verify(chatLlmService).chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
