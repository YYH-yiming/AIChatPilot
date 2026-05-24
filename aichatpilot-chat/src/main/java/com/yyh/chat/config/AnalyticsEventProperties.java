package com.yyh.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "analytics.events")
public class AnalyticsEventProperties {

    private boolean enabled = true;
    private String chatSessionTopic = "analytics.chat.session";
    private String chatMessageTopic = "analytics.chat.message";
}
