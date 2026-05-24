package com.yyh.knowledge.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.knowledge.config.KnowledgeReliabilityProperties;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaqCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeReliabilityProperties properties;

    public KnowledgeAskResponse get(Long kbId, String query, Integer topK) {
        if (!properties.getFaqCache().isEnabled() || kbId == null || !StringUtils.hasText(query)) {
            return null;
        }
        String cacheKey = buildCacheKey(kbId, query, topK);
        String json = redisTemplate.opsForValue().get(cacheKey);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, KnowledgeAskResponse.class);
        } catch (Exception ex) {
            log.warn("FAQ缓存反序列化失败，已清理坏数据: key={}, message={}", cacheKey, ex.getMessage());
            redisTemplate.delete(cacheKey);
            return null;
        }
    }

    public void put(KnowledgeAskResponse response) {
        if (!properties.getFaqCache().isEnabled() || response == null
                || response.getKbId() == null || !StringUtils.hasText(response.getQuery())) {
            return;
        }
        if (!properties.getFaqCache().isCacheEmptyAnswer() && Boolean.FALSE.equals(response.getGrounded())) {
            return;
        }
        String cacheKey = buildCacheKey(response.getKbId(), response.getQuery(), response.getTopK());
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response),
                    Duration.ofMinutes(Math.max(properties.getFaqCache().getTtlMinutes(), 1)));
        } catch (Exception ex) {
            log.warn("FAQ缓存写入失败，已忽略: key={}, message={}", cacheKey, ex.getMessage());
        }
    }

    public void evictKnowledgeBaseCache(Long kbId) {
        if (!properties.getFaqCache().isEnabled() || kbId == null) {
            return;
        }
        String pattern = properties.getFaqCache().getKeyPrefix() + ":" + kbId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
        log.info("已清理知识库FAQ缓存: kbId={}, count={}", kbId, keys.size());
    }

    private String buildCacheKey(Long kbId, String query, Integer topK) {
        String normalized = query.trim().replaceAll("\\s+", " ").toLowerCase();
        String hash = DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
        return properties.getFaqCache().getKeyPrefix() + ":" + kbId + ":" + (topK == null ? 0 : topK) + ":" + hash;
    }
}
