package com.yyh.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * 聊天 SSE 工作线程池。
     *
     * <p>B7 并发悬崖修复：JDK {@code ThreadPoolExecutor} 语义是「先填 core，再填队列，队列满才扩到 max」，
     * 旧配置 core=2 / max=8 / queue=100 在 c≤8 压测下队列永远填不满，线程数恒停在 core=2，
     * <b>有效并发=2 而非 max=8</b>（阶段 A 已用 {@code ttft_start_ms} 阶梯坐实）。改为
     * <b>core=max=N（默认 8）+ 小有界队列（20）</b>，前 N 个任务各占一线程立即并发，有效并发真正=N。
     *
     * <p>全程 env 可调可瞬时回退：{@code CHAT_SSE_POOL_SIZE} / {@code CHAT_SSE_QUEUE_CAPACITY}
     * （{@code CHAT_SSE_POOL_SIZE=2} 即回到旧悬崖行为）。队列满走默认 {@code AbortPolicy}，由提交点
     * {@code ChatStreamService.streamMessage} 捕获 {@code RejectedExecutionException} 后回吐 error 事件，
     * 避免无限堆积/OOM。{@code allowCoreThreadTimeOut(true)} + keepAlive 60s 让空闲线程可回收。
     *
     * <p>仍用 {@link DelegatingSecurityContextExecutor} 把请求线程的 SecurityContext（网关透传的
     * userId/tenantId）透传到工作线程，否则 SecurityUtils 取不到身份，requireOwnedSession 会误判“会话不存在”。
     */
    @Bean("chatTaskExecutor")
    public Executor chatTaskExecutor(
            @Value("${chat.sse.pool-size:8}") int poolSize,
            @Value("${chat.sse.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-sse-");
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
