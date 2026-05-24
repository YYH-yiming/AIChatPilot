package com.yyh.chat.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatReplyVO {

    private Long sessionId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String mode;
    private String answerSource;
    private String answer;
    private String rewrittenQuery;
    private String intent;
    private Long kbId;
    private Integer topK;
    private Boolean grounded;
    private Integer referenceCount;
    private Integer tokenUsed;
    private Long durationMs;
    private List<String> toolsCalled = new ArrayList<>();
    private List<ChatReferenceVO> references = new ArrayList<>();
}
