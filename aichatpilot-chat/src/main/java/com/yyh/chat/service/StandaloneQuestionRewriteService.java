package com.yyh.chat.service;

import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.llm.ChatLlmService;
import com.yyh.chat.memory.ChatMemoryService.ConversationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneQuestionRewriteService {

    private final ChatLlmService chatLlmService;
    private final ChatProperties chatProperties;

    public String rewrite(String currentQuestion, List<ConversationMessage> history) {
        if (!chatProperties.getContext().isRewriteEnabled()
                || !StringUtils.hasText(currentQuestion)
                || CollectionUtils.isEmpty(history)) {
            return currentQuestion;
        }

        try {
            String systemPrompt = """
                    你是对话改写助手。
                    请根据最近对话上下文，把用户当前问题改写成一个独立、完整、适合知识库检索的问题。
                    只输出改写后的问题，不要解释，不要加引号。
                    """;

            StringBuilder userPrompt = new StringBuilder("最近对话如下：\n");
            for (ConversationMessage message : history) {
                userPrompt.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
            }
            userPrompt.append("\n用户当前问题：").append(currentQuestion);
            return chatLlmService.chat(systemPrompt, userPrompt.toString()).trim();
        } catch (Exception ex) {
            log.warn("对话问题改写失败，将退回原问题: {}", ex.getMessage());
            return currentQuestion;
        }
    }
}
