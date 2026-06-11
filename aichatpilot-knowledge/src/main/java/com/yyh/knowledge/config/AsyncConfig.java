package com.yyh.knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

import java.util.concurrent.Executor;

/**
 * 知识库流式问答（/ask/stream）的工作线程池。
 *
 * <p>B7 并发悬崖修复：旧 core=4 / max=16 / queue=50 同犯 JDK「队列未满不扩容」的病，有效并发恒=4
 * （仅真流式路径触发，否则会成为 chat 修好后的下一道墙）。改为 <b>core=max=N（默认 8）+ 小有界队列（20）</b>，
 * 有效并发真正=N。全程 env 可调可回退：{@code KNOWLEDGE_STREAM_POOL_SIZE} / {@code KNOWLEDGE_STREAM_QUEUE_CAPACITY}。
 * 队列满走默认 {@code AbortPolicy}，由提交点 {@code KnowledgeBaseController.askStream} 捕获后回吐 error 事件。
 *
 * <p>用 {@link DelegatingSecurityContextExecutor} 把请求线程的 SecurityContext（网关透传的
 * userId/tenantId）透传到工作线程，否则 requireKnowledgeBase 的租户校验取不到身份。
 */
@Configuration
public class AsyncConfig {

    @Bean("knowledgeStreamExecutor")
    public Executor knowledgeStreamExecutor(
            @Value("${knowledge.stream.pool-size:8}") int poolSize,
            @Value("${knowledge.stream.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("kb-stream-");
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
