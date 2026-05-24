package com.yyh.agent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "agent.llm")
public class AgentLlmProperties {
    private String provider = "ark";
    private String apiUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
    private String apiKey;
    private String model;
    private boolean openaiCompatible = true;
    private double temperature = 0.2;
    private int maxTokens = 1024;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    private List<Provider> fallbacks = new ArrayList<>();

    public List<Provider> orderedProviders() {
        List<Provider> providers = new ArrayList<>();
        Provider primary = new Provider();
        primary.setName(provider);
        primary.setApiUrl(apiUrl);
        primary.setApiKey(apiKey);
        primary.setModel(model);
        primary.setOpenaiCompatible(openaiCompatible);
        primary.setTemperature(temperature);
        primary.setMaxTokens(maxTokens);
        primary.setConnectTimeoutMs(connectTimeoutMs);
        primary.setReadTimeoutMs(readTimeoutMs);
        providers.add(primary);

        if (fallbacks != null) {
            for (Provider fallback : fallbacks) {
                if (fallback == null || !fallback.isEnabled()) {
                    continue;
                }
                providers.add(fallback.withDefaults(primary));
            }
        }
        return providers;
    }

    @Data
    public static class Provider {
        private boolean enabled = true;
        private String name;
        private String apiUrl;
        private String apiKey;
        private String model;
        private Boolean openaiCompatible;
        private Double temperature;
        private Integer maxTokens;
        private Integer connectTimeoutMs;
        private Integer readTimeoutMs;

        public Provider withDefaults(Provider defaults) {
            Provider merged = new Provider();
            merged.setEnabled(enabled);
            merged.setName(name != null ? name : defaults.getName());
            merged.setApiUrl(apiUrl != null ? apiUrl : defaults.getApiUrl());
            merged.setApiKey(apiKey != null ? apiKey : defaults.getApiKey());
            merged.setModel(model != null ? model : defaults.getModel());
            merged.setOpenaiCompatible(openaiCompatible != null ? openaiCompatible : defaults.getOpenaiCompatible());
            merged.setTemperature(temperature != null ? temperature : defaults.getTemperature());
            merged.setMaxTokens(maxTokens != null ? maxTokens : defaults.getMaxTokens());
            merged.setConnectTimeoutMs(connectTimeoutMs != null ? connectTimeoutMs : defaults.getConnectTimeoutMs());
            merged.setReadTimeoutMs(readTimeoutMs != null ? readTimeoutMs : defaults.getReadTimeoutMs());
            return merged;
        }

        public boolean isOpenaiCompatible() {
            return openaiCompatible == null || openaiCompatible;
        }

        public String displayName() {
            return (name == null || name.isBlank() ? "llm" : name) + "/" + (model == null ? "" : model);
        }
    }
}
