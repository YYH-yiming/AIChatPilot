package com.yyh.analytics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

    private final Cache cache = new Cache();
    private final Events events = new Events();
    private final Query query = new Query();

    @Data
    public static class Cache {
        private int ttlMinutes = 5;
    }

    @Data
    public static class Events {
        private boolean enabled = true;
        private String chatSessionTopic = "analytics.chat.session";
        private String chatMessageTopic = "analytics.chat.message";
        private String agentTraceTopic = "analytics.agent.trace";
        private int topicPartitions = 1;
        private short topicReplicas = 1;
    }

    @Data
    public static class Query {
        private int defaultDays = 7;
        private int maxDays = 30;
    }
}
