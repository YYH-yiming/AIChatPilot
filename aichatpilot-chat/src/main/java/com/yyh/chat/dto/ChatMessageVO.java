package com.yyh.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatMessageVO {

    private Long messageId;
    private Long sessionId;
    private String role;
    private String content;
    private String answerSource;
    private String intent;
    private Long kbId;
    private Integer tokenUsed;
    private Long durationMs;
    private LocalDateTime createdAt;
    private List<ChatReferenceVO> references = new ArrayList<>();
}
