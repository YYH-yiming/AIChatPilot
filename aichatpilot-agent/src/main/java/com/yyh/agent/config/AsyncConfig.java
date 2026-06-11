package com.yyh.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

import java.util.concurrent.Executor;

/**
 * Agent 流式问答（/api/agent/chat/stream）工作线程池。
 *
 * <p>与 knowledge 同款：core=max=N（默认 8）+ 小有界队列，有效并发=N；env 可调可回退。
 * 用 {@link DelegatingSecurityContextExecutor} 把请求线程的 SecurityContext（网关透传的 userId/tenantId）
 * 透传到工作线程，否则下游 knowledge 调用取不到身份。
 */
@Configuration
public class AsyncConfig {

    @Bean("agentStreamExecutor")
    public Executor agentStreamExecutor(
            @Value("${agent.stream.pool-size:8}") int poolSize,
            @Value("${agent.stream.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-stream-");
        int n = Math.max(1, poolSize);
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return new DelegatingSecurityContextExecutor(executor);
    }
}
