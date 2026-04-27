package com.yyh.knowledge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.knowledge.service.impl.DocumentAsyncProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "knowledge.async.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentEventConsumer {

    private final ObjectMapper objectMapper;
    private final DocumentAsyncProcessService documentAsyncProcessService;

    @KafkaListener(topics = "${knowledge.async.document-upload-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onDocumentUpload(String payload) {
        try {
            DocumentUploadEvent event = objectMapper.readValue(payload, DocumentUploadEvent.class);
            documentAsyncProcessService.processUploadedDocument(event.getKbId(), event.getDocId());
        } catch (Exception ex) {
            log.error("消费文档异步处理消息失败: payload={}, message={}", payload, ex.getMessage(), ex);
        }
    }
}
