package com.yyh.analytics.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic analyticsChatSessionTopic(AnalyticsProperties properties) {
        return new NewTopic(
                properties.getEvents().getChatSessionTopic(),
                properties.getEvents().getTopicPartitions(),
                properties.getEvents().getTopicReplicas()
        );
    }

    @Bean
    public NewTopic analyticsChatMessageTopic(AnalyticsProperties properties) {
        return new NewTopic(
                properties.getEvents().getChatMessageTopic(),
                properties.getEvents().getTopicPartitions(),
                properties.getEvents().getTopicReplicas()
        );
    }

    @Bean
    public NewTopic analyticsAgentTraceTopic(AnalyticsProperties properties) {
        return new NewTopic(
                properties.getEvents().getAgentTraceTopic(),
                properties.getEvents().getTopicPartitions(),
                properties.getEvents().getTopicReplicas()
        );
    }
}
