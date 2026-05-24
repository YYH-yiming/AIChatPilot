package com.yyh.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chat.llm")
public class ChatLlmProperties {

    private String provider = "ark";
    private String apiUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
    private String apiKey;
    private String model;
    private boolean openaiCompatible = true;
    private double temperature = 0.2;
    private int maxTokens = 1024;
}
