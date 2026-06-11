package com.yyh.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.client.AgentServiceClient;
import com.yyh.chat.client.KnowledgeServiceClient;
import com.yyh.chat.client.dto.AgentChatClientRequest;
import com.yyh.chat.client.dto.AgentChatClientResponse;
import com.yyh.chat.client.dto.AgentKnowledgeReferenceClientResponse;
import com.yyh.chat.client.dto.KnowledgeAskClientResponse;
import com.yyh.chat.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.dto.ChatMessageVO;
import com.yyh.chat.dto.ChatReferenceVO;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import com.yyh.chat.dto.ChatSessionCreateRequest;
import com.yyh.chat.dto.ChatSessionDetailVO;
import com.yyh.chat.dto.ChatSessionVO;
import com.yyh.chat.entity.ChatMessage;
import com.yyh.chat.entity.ChatSession;
import com.yyh.chat.mapper.ChatMessageMapper;
import com.yyh.chat.mapper.ChatSessionMapper;
import com.yyh.chat.memory.ChatMemoryService;
import com.yyh.chat.memory.ChatMemoryService.ConversationMessage;
import com.yyh.chat.support.ChatConstants;
import com.yyh.chat.support.SecurityUtils;
import com.yyh.common.dto.PageResult;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final TypeReference<List<ChatReferenceVO>> REFERENCE_TYPE = new TypeReference<>() {
    };

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final AgentServiceClient agentServiceClient;
    private final KnowledgeBaseSelector knowledgeBaseSelector;
    private final StandaloneQuestionRewriteService rewriteService;
    private final ChatMemoryService chatMemoryService;
    private final ChatAnalyticsEventPublisher chatAnalyticsEventPublisher;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    public ChatSessionVO createSession(ChatSessionCreateRequest request) {
        Long userId = SecurityUtils.currentUserId();
        Long tenantId = SecurityUtils.currentTenantId();
        String mode = normalizeMode(request.getMode());

        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setTitle(resolveTitle(request.getTitle(), null));
        session.setMode(mode);
        session.setKbId(request.getKbId());
        session.setStatus(ChatConstants.SESSION_STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setLastMessageAt(now);
        chatSessionMapper.insert(session);
        chatAnalyticsEventPublisher.publishSessionCreated(session);
        return toSessionVO(session);
    }

    public PageResult<ChatSessionVO> pageSessions(int pageNum, int pageSize) {
        Long userId = SecurityUtils.currentUserId();
        Long tenantId = SecurityUtils.currentTenantId();
        int safePageNum = normalizePageNum(pageNum);
        int safePageSize = normalizePageSize(pageSize, 50);
        int offset = (safePageNum - 1) * safePageSize;

        long total = chatSessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getTenantId, tenantId));
        if (total == 0) {
            return PageResult.empty(safePageNum, safePageSize);
        }

        List<ChatSessionVO> records = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getTenantId, tenantId)
                        .orderByDesc(ChatSession::getLastMessageAt)
                        .orderByDesc(ChatSession::getId)
                        .last("limit " + offset + "," + safePageSize))
                .stream()
                .map(this::toSessionVO)
                .toList();
        return PageResult.of(total, safePageNum, safePageSize, records);
    }

    public ChatSessionDetailVO getSessionDetail(Long sessionId, Integer messageLimit) {
        ChatSession session = requireOwnedSession(sessionId);
        ChatSessionDetailVO detail = new ChatSessionDetailVO();
        detail.setSession(toSessionVO(session));
        detail.setMessages(listRecentMessageVos(sessionId, normalizePageSize(messageLimit == null ? 20 : messageLimit, 100)));
        return detail;
    }

    public PageResult<ChatMessageVO> pageMessages(Long sessionId, int pageNum, int pageSize) {
        requireOwnedSession(sessionId);
        int safePageNum = normalizePageNum(pageNum);
        int safePageSize = normalizePageSize(pageSize, 100);
        int offset = (safePageNum - 1) * safePageSize;
        Long userId = SecurityUtils.currentUserId();
        Long tenantId = SecurityUtils.currentTenantId();

        long total = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getTenantId, tenantId));
        if (total == 0) {
            return PageResult.empty(safePageNum, safePageSize);
        }

        List<ChatMessageVO> records = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getUserId, userId)
                        .eq(ChatMessage::getTenantId, tenantId)
                        .orderByAsc(ChatMessage::getId)
                        .last("limit " + offset + "," + safePageSize))
                .stream()
                .map(this::toMessageVO)
                .toList();
        return PageResult.of(total, safePageNum, safePageSize, records);
    }

    public ChatReplyVO sendMessage(Long sessionId, ChatSendMessageRequest request) {
        ChatSession session = requireOwnedSession(sessionId);
        if (session.getStatus() == null || session.getStatus() != ChatConstants.SESSION_STATUS_ACTIVE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前会话已关闭，无法继续发送消息");
        }

        List<ConversationMessage> history = loadConversationHistory(sessionId);
        ChatMessage userMessage = buildUserMessage(session, request.getContent());
        chatMessageMapper.insert(userMessage);
        chatMemoryService.appendMessage(userMessage);
        chatAnalyticsEventPublisher.publishUserMessage(userMessage);

        long replyStart = System.currentTimeMillis();
        ChatReplyVO reply = ChatConstants.MODE_AGENT.equals(session.getMode())
                ? handleAgentMode(session, request)
                : handleKnowledgeMode(session, request, history);
        if (reply.getDurationMs() == null || reply.getDurationMs() <= 0L) {
            reply.setDurationMs(System.currentTimeMillis() - replyStart);
        }

        ChatMessage assistantMessage = buildAssistantMessage(session, reply);
        chatMessageMapper.insert(assistantMessage);
        chatMemoryService.appendMessage(assistantMessage);
        chatAnalyticsEventPublisher.publishAssistantMessage(assistantMessage, reply);

        LocalDateTime now = LocalDateTime.now();
        chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                .eq("id", session.getId())
                .eq("user_id", session.getUserId())
                .eq("tenant_id", session.getTenantId())
                .set("title", resolveTitle(session.getTitle(), request.getContent()))
                .set("updated_at", now)
                .set("last_message_at", now));

        reply.setSessionId(sessionId);
        reply.setUserMessageId(userMessage.getId());
        reply.setAssistantMessageId(assistantMessage.getId());
        return reply;
    }

    /**
     * 流式发送：与 {@link #sendMessage} 完全相同的副作用（落库用户消息/记忆/埋点 → 生成回复 → 落库助手消息/记忆/埋点 → 更新会话），
     * 区别仅在于回复阶段通过 {@code sink} 逐 token 推送。返回值与 sendMessage 一致（含三个消息 id），供调用方发 done。
     */
    public ChatReplyVO sendMessageStreaming(Long sessionId, ChatSendMessageRequest request, ChatStreamSink sink) {
        ChatSession session = requireOwnedSession(sessionId);
        if (session.getStatus() == null || session.getStatus() != ChatConstants.SESSION_STATUS_ACTIVE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前会话已关闭，无法继续发送消息");
        }

        List<ConversationMessage> history = loadConversationHistory(sessionId);
        ChatMessage userMessage = buildUserMessage(session, request.getContent());
        chatMessageMapper.insert(userMessage);
        chatMemoryService.appendMessage(userMessage);
        chatAnalyticsEventPublisher.publishUserMessage(userMessage);

        long replyStart = System.currentTimeMillis();
        ChatReplyVO reply = ChatConstants.MODE_AGENT.equals(session.getMode())
                ? handleAgentModeStreaming(session, request, sink)
                : handleKnowledgeModeStreaming(session, request, history, sink);
        if (reply.getDurationMs() == null || reply.getDurationMs() <= 0L) {
            reply.setDurationMs(System.currentTimeMillis() - replyStart);
        }

        ChatMessage assistantMessage = buildAssistantMessage(session, reply);
        chatMessageMapper.insert(assistantMessage);
        chatMemoryService.appendMessage(assistantMessage);
        chatAnalyticsEventPublisher.publishAssistantMessage(assistantMessage, reply);

        LocalDateTime now = LocalDateTime.now();
        chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                .eq("id", session.getId())
                .eq("user_id", session.getUserId())
                .eq("tenant_id", session.getTenantId())
                .set("title", resolveTitle(session.getTitle(), request.getContent()))
                .set("updated_at", now)
                .set("last_message_at", now));

        reply.setSessionId(sessionId);
        reply.setUserMessageId(userMessage.getId());
        reply.setAssistantMessageId(assistantMessage.getId());
        return reply;
    }

    public void closeSession(Long sessionId) {
        ChatSession session = requireOwnedSession(sessionId);
        chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                .eq("id", sessionId)
                .eq("user_id", SecurityUtils.currentUserId())
                .eq("tenant_id", SecurityUtils.currentTenantId())
                .set("status", ChatConstants.SESSION_STATUS_CLOSED)
                .set("updated_at", LocalDateTime.now()));
        chatAnalyticsEventPublisher.publishSessionClosed(session);
    }

    private ChatReplyVO handleKnowledgeMode(ChatSession session,
                                            ChatSendMessageRequest request,
                                            List<ConversationMessage> history) {
        String rewrittenQuery = rewriteService.rewrite(request.getContent(), history);
        Integer topK = request.getTopK() != null ? request.getTopK() : chatProperties.getKnowledge().getAskTopK();
        KnowledgeBaseSelector.SelectionResult selectionResult = knowledgeBaseSelector.ask(rewrittenQuery, session.getKbId(), topK);
        KnowledgeAskClientResponse response = selectionResult.response();
        if (session.getKbId() == null || !session.getKbId().equals(selectionResult.kbId())) {
            chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                    .eq("id", session.getId())
                    .eq("user_id", session.getUserId())
                    .eq("tenant_id", session.getTenantId())
                    .set("kb_id", selectionResult.kbId())
                    .set("updated_at", LocalDateTime.now()));
            session.setKbId(selectionResult.kbId());
        }

        ChatReplyVO reply = new ChatReplyVO();
        reply.setMode(session.getMode());
        reply.setAnswerSource(ChatConstants.SOURCE_KNOWLEDGE);
        reply.setAnswer(response.getAnswer());
        reply.setRewrittenQuery(rewrittenQuery);
        reply.setKbId(response.getKbId());
        reply.setTopK(topK);
        reply.setGrounded(response.getGrounded());
        reply.setReferenceCount(response.getReferenceCount());
        reply.setTokenUsed(0);
        reply.setReferences(toKnowledgeReferenceVos(response.getReferences()));
        return reply;
    }

    private ChatReplyVO handleAgentMode(ChatSession session, ChatSendMessageRequest request) {
        AgentChatClientRequest clientRequest = new AgentChatClientRequest();
        clientRequest.setQuery(request.getContent());
        clientRequest.setSessionId(session.getId());
        clientRequest.setKbId(session.getKbId());
        clientRequest.setTenantId(session.getTenantId());

        AgentChatClientResponse response = agentServiceClient.chat(clientRequest);

        ChatReplyVO reply = new ChatReplyVO();
        reply.setMode(session.getMode());
        reply.setAnswerSource(ChatConstants.SOURCE_AGENT);
        reply.setAnswer(response.getAnswer());
        reply.setIntent(response.getIntent());
        reply.setKbId(response.getKbId());
        reply.setTokenUsed(response.getTokenUsed());
        reply.setDurationMs(response.getDurationMs());
        reply.setToolsCalled(response.getToolsCalled() == null ? new ArrayList<>() : response.getToolsCalled());
        reply.setReferenceCount(response.getReferences() == null ? 0 : response.getReferences().size());
        reply.setReferences(toAgentReferenceVos(response.getReferences()));
        return reply;
    }

    /**
     * 知识库模式流式：复用 {@link #handleKnowledgeMode} 的改写/选库/换库副作用，
     * 但用 {@code selectKbId} 只选库、再走 knowledge 的流式 ask，逐 token 经 sink 推送，meta 先于 token。
     */
    private ChatReplyVO handleKnowledgeModeStreaming(ChatSession session,
                                                     ChatSendMessageRequest request,
                                                     List<ConversationMessage> history,
                                                     ChatStreamSink sink) {
        String rewrittenQuery = rewriteService.rewrite(request.getContent(), history);
        Integer topK = request.getTopK() != null ? request.getTopK() : chatProperties.getKnowledge().getAskTopK();
        Long kbId = knowledgeBaseSelector.selectKbId(rewrittenQuery, session.getKbId(), topK);
        if (session.getKbId() == null || !session.getKbId().equals(kbId)) {
            chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                    .eq("id", session.getId())
                    .eq("user_id", session.getUserId())
                    .eq("tenant_id", session.getTenantId())
                    .set("kb_id", kbId)
                    .set("updated_at", LocalDateTime.now()));
            session.setKbId(kbId);
        }

        ChatReplyVO reply = new ChatReplyVO();
        reply.setMode(session.getMode());
        reply.setAnswerSource(ChatConstants.SOURCE_KNOWLEDGE);
        reply.setRewrittenQuery(rewrittenQuery);
        reply.setKbId(kbId);
        reply.setTopK(topK);
        reply.setTokenUsed(0);

        StringBuilder answerBuf = new StringBuilder();
        KnowledgeServiceClient.StreamAskResult result = knowledgeServiceClient.askStream(
                kbId, rewrittenQuery, topK,
                meta -> {
                    reply.setKbId(meta.getKbId() != null ? meta.getKbId() : kbId);
                    reply.setGrounded(meta.getGrounded());
                    reply.setReferenceCount(meta.getReferenceCount());
                    reply.setReferences(toKnowledgeReferenceVos(meta.getReferences()));
                    sink.meta(buildMetaPayload(reply, rewrittenQuery));
                },
                token -> {
                    answerBuf.append(token);
                    sink.token(token);
                });

        reply.setAnswer(StringUtils.hasText(result.answer()) ? result.answer() : answerBuf.toString());
        return reply;
    }

    /**
     * Agent 模式流式：调 agent 的 /chat/stream SSE，逐 token 经 sink 转发（faq/policy 真流式；其它意图整段单 token，由 agent 侧降级）。
     * 与 {@link #handleAgentMode} 等价的回复落库（answer/intent/kbId/tokenUsed/references），但答案是边到边推。
     */
    private ChatReplyVO handleAgentModeStreaming(ChatSession session,
                                                 ChatSendMessageRequest request,
                                                 ChatStreamSink sink) {
        AgentChatClientRequest clientRequest = new AgentChatClientRequest();
        clientRequest.setQuery(request.getContent());
        clientRequest.setSessionId(session.getId());
        clientRequest.setKbId(session.getKbId());
        clientRequest.setTenantId(session.getTenantId());

        ChatReplyVO reply = new ChatReplyVO();
        reply.setMode(session.getMode());
        reply.setAnswerSource(ChatConstants.SOURCE_AGENT);

        StringBuilder answerBuf = new StringBuilder();
        AgentServiceClient.StreamChatResult result = agentServiceClient.chatStream(
                clientRequest,
                meta -> sink.meta(meta),
                token -> {
                    answerBuf.append(token);
                    sink.token(token);
                });

        reply.setAnswer(StringUtils.hasText(result.answer()) ? result.answer() : answerBuf.toString());
        reply.setIntent(result.intent());
        reply.setKbId(result.kbId());
        reply.setTokenUsed(result.tokenUsed());
        List<ChatReferenceVO> references = extractReferencesFromMeta(result.meta());
        reply.setReferences(references);
        reply.setReferenceCount(references.size());
        return reply;
    }

    /** 从 agent 转发的 meta 里取出 references（List&lt;Map&gt;）并转成 ChatReferenceVO；缺失/异常返回空。 */
    private List<ChatReferenceVO> extractReferencesFromMeta(Map<String, Object> meta) {
        if (meta == null || meta.get("references") == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(meta.get("references"), REFERENCE_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> buildMetaPayload(ChatReplyVO reply, String rewrittenQuery) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", reply.getMode());
        meta.put("answerSource", reply.getAnswerSource());
        meta.put("kbId", reply.getKbId());
        meta.put("topK", reply.getTopK());
        meta.put("grounded", reply.getGrounded());
        meta.put("referenceCount", reply.getReferenceCount());
        meta.put("rewrittenQuery", rewrittenQuery);
        meta.put("references", reply.getReferences());
        return meta;
    }

    private ChatSession requireOwnedSession(Long sessionId) {
        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getUserId, SecurityUtils.currentUserId())
                .eq(ChatSession::getTenantId, SecurityUtils.currentTenantId())
                .last("limit 1"));
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private List<ConversationMessage> loadConversationHistory(Long sessionId) {
        int historySize = Math.max(chatProperties.getContext().getWindowRounds(), 1) * 2;
        List<ConversationMessage> cached = chatMemoryService.loadRecentMessages(sessionId, historySize);
        if (!cached.isEmpty()) {
            return cached;
        }

        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, SecurityUtils.currentUserId())
                .eq(ChatMessage::getTenantId, SecurityUtils.currentTenantId())
                .orderByDesc(ChatMessage::getId)
                .last("limit " + historySize));
        if (messages.isEmpty()) {
            return List.of();
        }
        Collections.reverse(messages);
        return messages.stream()
                .map(item -> new ConversationMessage(item.getRole(), item.getContent(), item.getCreatedAt()))
                .toList();
    }

    private ChatMessage buildUserMessage(ChatSession session, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setTenantId(session.getTenantId());
        message.setUserId(session.getUserId());
        message.setRole(ChatConstants.ROLE_USER);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ChatMessage buildAssistantMessage(ChatSession session, ChatReplyVO reply) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setTenantId(session.getTenantId());
        message.setUserId(session.getUserId());
        message.setRole(ChatConstants.ROLE_ASSISTANT);
        message.setContent(reply.getAnswer());
        message.setAnswerSource(reply.getAnswerSource());
        message.setIntent(reply.getIntent());
        message.setKbId(reply.getKbId());
        message.setTokenUsed(reply.getTokenUsed());
        message.setDurationMs(reply.getDurationMs());
        message.setReferenceData(writeReferenceData(reply.getReferences()));
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private String writeReferenceData(List<ChatReferenceVO> references) {
        try {
            return objectMapper.writeValueAsString(references == null ? List.of() : references);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<ChatReferenceVO> toKnowledgeReferenceVos(List<KnowledgeSearchHitClientResponse> references) {
        if (references == null) {
            return List.of();
        }
        return references.stream().map(item -> {
            ChatReferenceVO reference = new ChatReferenceVO();
            reference.setChunkId(item.getChunkId());
            reference.setDocId(item.getDocId());
            reference.setKbId(item.getKbId());
            reference.setChunkIndex(item.getChunkIndex());
            reference.setTokenCount(item.getTokenCount());
            reference.setContent(item.getContent());
            reference.setScore(item.getScore());
            reference.setDenseScore(item.getDenseScore());
            reference.setSparseScore(item.getSparseScore());
            reference.setSource(item.getSource());
            return reference;
        }).toList();
    }

    private List<ChatReferenceVO> toAgentReferenceVos(List<AgentKnowledgeReferenceClientResponse> references) {
        if (references == null) {
            return List.of();
        }
        return references.stream().map(item -> {
            ChatReferenceVO reference = new ChatReferenceVO();
            reference.setChunkId(item.getChunkId());
            reference.setDocId(item.getDocId());
            reference.setKbId(item.getKbId());
            reference.setChunkIndex(item.getChunkIndex());
            reference.setTokenCount(item.getTokenCount());
            reference.setContent(item.getContent());
            reference.setScore(item.getScore());
            reference.setDenseScore(item.getDenseScore());
            reference.setSparseScore(item.getSparseScore());
            reference.setSource(item.getSource());
            return reference;
        }).toList();
    }

    private List<ChatMessageVO> listRecentMessageVos(Long sessionId, int limit) {
        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, SecurityUtils.currentUserId())
                .eq(ChatMessage::getTenantId, SecurityUtils.currentTenantId())
                .orderByDesc(ChatMessage::getId)
                .last("limit " + limit));
        Collections.reverse(messages);
        return messages.stream().map(this::toMessageVO).toList();
    }

    private ChatSessionVO toSessionVO(ChatSession session) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setSessionId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setMode(session.getMode());
        vo.setKbId(session.getKbId());
        vo.setStatus(session.getStatus());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setUpdatedAt(session.getUpdatedAt());
        vo.setLastMessageAt(session.getLastMessageAt());
        return vo;
    }

    private ChatMessageVO toMessageVO(ChatMessage message) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setMessageId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setAnswerSource(message.getAnswerSource());
        vo.setIntent(message.getIntent());
        vo.setKbId(message.getKbId());
        vo.setTokenUsed(message.getTokenUsed());
        vo.setDurationMs(message.getDurationMs());
        vo.setCreatedAt(message.getCreatedAt());
        vo.setReferences(readReferences(message.getReferenceData()));
        return vo;
    }

    private List<ChatReferenceVO> readReferences(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, REFERENCE_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return ChatConstants.MODE_AGENT;
        }
        return mode.trim().toLowerCase();
    }

    private String resolveTitle(String title, String firstQuestion) {
        if (StringUtils.hasText(title) && !"新会话".equals(title.trim())) {
            return title.trim();
        }
        if (StringUtils.hasText(firstQuestion)) {
            String normalized = firstQuestion.trim();
            return normalized.length() > 20 ? normalized.substring(0, 20) : normalized;
        }
        return "新会话";
    }

    private int normalizePageNum(int pageNum) {
        return Math.max(pageNum, 1);
    }

    private int normalizePageSize(int pageSize, int maxSize) {
        int safe = pageSize <= 0 ? 10 : pageSize;
        return Math.min(safe, maxSize);
    }
}
