package com.yyh.knowledge.lock;

import com.yyh.knowledge.config.KnowledgeReliabilityProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final KnowledgeReliabilityProperties properties;

    public LockHandle tryLock(String namespace, String businessKey, Duration leaseDuration) {
        if (!properties.getUploadLock().isEnabled()) {
            return LockHandle.noop();
        }
        String key = properties.getUploadLock().getKeyPrefix() + ":" + namespace + ":" + businessKey;
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, token, leaseDuration);
        return Boolean.TRUE.equals(locked)
                ? new LockHandle(true, key, token)
                : new LockHandle(false, key, token);
    }

    public void unlock(LockHandle handle) {
        if (handle == null || !handle.isAcquired() || handle.isNoop()) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(handle.getKey()), handle.getToken());
        } catch (Exception ex) {
            log.warn("释放分布式锁失败，已忽略: key={}, message={}", handle.getKey(), ex.getMessage());
        }
    }

    @Getter
    public static final class LockHandle {
        private final boolean acquired;
        private final String key;
        private final String token;
        private final boolean noop;

        private LockHandle(boolean acquired, String key, String token) {
            this(acquired, key, token, false);
        }

        private LockHandle(boolean acquired, String key, String token, boolean noop) {
            this.acquired = acquired;
            this.key = key;
            this.token = token;
            this.noop = noop;
        }

        public static LockHandle noop() {
            return new LockHandle(true, null, null, true);
        }
    }
}
