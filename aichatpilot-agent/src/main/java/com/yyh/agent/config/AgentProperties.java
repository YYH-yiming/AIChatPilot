package com.yyh.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private final Knowledge knowledge = new Knowledge();
    private final Memory memory = new Memory();
    private final Router router = new Router();

    @Data
    public static class Knowledge {
        private String serviceUrl = "http://localhost:8082";
        private Long faqKbId = 1L;
        private Long policyKbId = 2L;
        private Integer askTopK = 5;
        private Integer searchTopK = 5;
    }

    @Data
    public static class Memory {
        private boolean enabled = true;
        private int maxMessages = 8;
        private long ttlHours = 12;
    }

    @Data
    public static class Router {
        private double confidenceThreshold = 0.65;
        private String fallbackIntent = "faq";
    }
}
