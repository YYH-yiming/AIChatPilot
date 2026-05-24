package com.yyh.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private final Context context = new Context();
    private final Knowledge knowledge = new Knowledge();
    private final Agent agent = new Agent();
    private final Memory memory = new Memory();
    private final Sse sse = new Sse();

    @Data
    public static class Context {
        private int windowRounds = 6;
        private boolean rewriteEnabled = true;
    }

    @Data
    public static class Knowledge {
        private String serviceUrl = "http://localhost:8082";
        private int askTopK = 5;
    }

    @Data
    public static class Agent {
        private String serviceUrl = "http://localhost:8083";
    }

    @Data
    public static class Memory {
        private boolean enabled = true;
        private int maxMessages = 40;
        private long ttlHours = 12;
    }

    @Data
    public static class Sse {
        private long timeoutMs = 180000L;
    }
}
