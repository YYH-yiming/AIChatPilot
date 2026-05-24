package com.yyh.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long tenantId;
    private Long userId;
    private String role;
    private String content;
    private String answerSource;
    private String intent;
    private Long kbId;
    private Integer tokenUsed;
    private Long durationMs;
    private String referenceData;
    private LocalDateTime createdAt;
}
