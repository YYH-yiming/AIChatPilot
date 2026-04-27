package com.yyh.knowledge.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "knowledge.async.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    @Bean
    public NewTopic knowledgeDocumentUploadTopic(
            @Value("${knowledge.async.document-upload-topic}") String topicName,
            @Value("${knowledge.async.topic-partitions:1}") int partitions,
            @Value("${knowledge.async.topic-replicas:1}") short replicas) {
        return new NewTopic(topicName, partitions, replicas);
    }
}
