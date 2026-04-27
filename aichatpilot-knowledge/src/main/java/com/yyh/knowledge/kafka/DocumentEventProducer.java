package com.yyh.knowledge.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "knowledge.async.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${knowledge.async.document-upload-topic}")
    private String topicName;

    public void sendDocumentUploadEvent(Long docId, Long kbId) {
        DocumentUploadEvent event = new DocumentUploadEvent();
        event.setDocId(docId);
        event.setKbId(kbId);
        try {
            kafkaTemplate.send(topicName, String.valueOf(docId), objectMapper.writeValueAsString(event))
                    .get(10, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文档事件序列化失败: " + ex.getMessage());
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Kafka文档事件发送失败: " + ex.getMessage());
        }
    }
}
