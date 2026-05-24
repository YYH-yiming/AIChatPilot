package com.yyh.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "analytics.events")
public class AnalyticsEventProperties {

    private boolean enabled = true;
    private String agentTraceTopic = "analytics.agent.trace";
}
