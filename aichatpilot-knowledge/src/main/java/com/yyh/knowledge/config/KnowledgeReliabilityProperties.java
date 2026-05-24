package com.yyh.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeReliabilityProperties {

    private final FaqCache faqCache = new FaqCache();
    private final UploadLock uploadLock = new UploadLock();

    @Data
    public static class FaqCache {
        private boolean enabled = true;
        private long ttlMinutes = 30;
        private boolean cacheEmptyAnswer = true;
        private String keyPrefix = "knowledge:faq-cache";
    }

    @Data
    public static class UploadLock {
        private boolean enabled = true;
        private long uploadLeaseSeconds = 60;
        private long processLeaseSeconds = 600;
        private String keyPrefix = "knowledge:upload-lock";
    }
}
