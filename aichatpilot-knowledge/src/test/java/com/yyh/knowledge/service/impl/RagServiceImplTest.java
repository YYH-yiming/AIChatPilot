package com.yyh.knowledge.service.impl;

import com.yyh.knowledge.cache.FaqCacheService;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.llm.LlmService;
import com.yyh.knowledge.search.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private LlmService llmService;

    @Mock
    private FaqCacheService faqCacheService;

    @InjectMocks
    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ragService, "defaultTopK", 5);
        ReflectionTestUtils.setField(ragService, "emptyAnswer", "我没有在知识库中找到相关信息");
        ReflectionTestUtils.setField(ragService, "systemPrompt", "");
    }

    @Test
    void shouldReturnEmptyAnswerWhenNoReferenceFound() {
        KnowledgeAskRequest request = new KnowledgeAskRequest();
        request.setQuery("退款政策");

        when(faqCacheService.get(1L, "退款政策", 5)).thenReturn(null);
        when(retrievalService.search("退款政策", 1L, 5)).thenReturn(List.of());
        when(llmService.currentModel()).thenReturn("doubao-test");

        KnowledgeAskResponse response = ragService.ask(1L, request);

        assertEquals("我没有在知识库中找到相关信息", response.getAnswer());
        assertFalse(response.getGrounded());
        assertEquals(0, response.getReferenceCount());
        verify(llmService, never()).chat(anyString(), anyString());
        verify(faqCacheService).put(response);
    }

    @Test
    void shouldCallLlmWhenReferencesExist() {
        KnowledgeAskRequest request = new KnowledgeAskRequest();
        request.setQuery("退款政策");
        request.setTopK(3);

        KnowledgeSearchHitVO hit = new KnowledgeSearchHitVO();
        hit.setChunkId(11L);
        hit.setDocId(21L);
        hit.setSource("hybrid");
        hit.setContent("退款需在7天内提交申请。");

        when(faqCacheService.get(2L, "退款政策", 3)).thenReturn(null);
        when(retrievalService.search("退款政策", 2L, 3)).thenReturn(List.of(hit));
        when(llmService.currentModel()).thenReturn("doubao-test");
        when(llmService.chat(anyString(), anyString())).thenReturn("根据知识库，退款需在7天内提交申请。");

        KnowledgeAskResponse response = ragService.ask(2L, request);

        assertTrue(response.getGrounded());
        assertEquals(1, response.getReferenceCount());
        assertEquals("根据知识库，退款需在7天内提交申请。", response.getAnswer());
        assertEquals("doubao-test", response.getModel());
        assertEquals(1, response.getReferences().size());
        verify(faqCacheService).put(response);
    }

    @Test
    void shouldReturnCachedAnswerWhenFaqCacheHit() {
        KnowledgeAskRequest request = new KnowledgeAskRequest();
        request.setQuery("退款政策");

        KnowledgeAskResponse cached = new KnowledgeAskResponse();
        cached.setKbId(3L);
        cached.setQuery("退款政策");
        cached.setAnswer("缓存答案");
        cached.setGrounded(true);
        cached.setReferenceCount(1);

        when(faqCacheService.get(3L, "退款政策", 5)).thenReturn(cached);

        KnowledgeAskResponse response = ragService.ask(3L, request);

        assertEquals("缓存答案", response.getAnswer());
        verify(retrievalService, never()).search("退款政策", 3L, 5);
        verify(llmService, never()).chat(anyString(), anyString());
    }
}
