package com.yyh.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_trace")
public class AgentTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Long messageId;
    private String agentName;
    private Long parentTraceId;
    private String inputText;
    private String outputText;
    private String toolsCalled;
    private String toolResults;
    private Integer tokenUsed;
    private Integer durationMs;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
}
