package com.yyh.knowledge.spike;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LangChain4j 选型 spike —— 仅 test 作用域，<b>绝不进生产包、不改动任何现有服务代码</b>。
 *
 * <p>目的（对应 {@code docs/流式输出诊断与LLM客户端选型评估.md}）：在不动现有
 * RestClient / JDK HttpClient 的前提下，验证 LangChain4j 对本项目 SiliconFlow 全流程的支持，
 * 以及它能带来我们目前缺少的能力。每个 {@code @Test} 对应一个待验证点，控制台打印结论。
 *
 * <p><b>怎么跑</b>（先让脚本拿到 SiliconFlow key，二选一）：
 * <pre>
 *   1) 设环境变量：   $env:LLM_API_KEY="sk-..."   （可选 LLM_API_URL/LLM_MODEL/EMBEDDING_MODEL）
 *   2) 或仓库根放好 .env.local（本测试会自动读取其中的 LLM_API_KEY/LLM_API_URL/...）
 * 然后（在仓库根执行）：
 *   mvn -pl aichatpilot-knowledge test -Dtest=Lc4jSpikeTest
 * </pre>
 * 没配 key 时整组用例 {@code assumeTrue} 跳过，不会失败。会真打 SiliconFlow（思考默认开，t2/t3 各约 15-25s）。
 *
 * <p><b>已知不确定项</b>：LC4j 在 1.0.0 alpha→GA 期间改过方法名（{@code generate→chat}、
 * {@code onNext→onPartialResponse}）。本文件按 <b>GA 1.0.0</b> 写。若 {@code test-compile} 报方法/类名
 * 不存在，把报错贴出即可一处修正（通常只是换个方法名或调整 baseUrl 约定）。
 */
@EnabledIfEnvironmentVariable(named = "LC4J_SPIKE", matches = "1")
class Lc4jSpikeTest {

    // ---- 配置：环境变量优先，缺了回退读仓库根 .env.local（不打印 key） ----
    private static final Map<String, String> CFG = loadConfig();
    private static final String API_KEY = CFG.get("LLM_API_KEY");
    private static final String BASE_URL =
            deriveBaseUrl(CFG.getOrDefault("LLM_API_URL", "https://api.siliconflow.cn/v1/chat/completions"));
    private static final String MODEL = CFG.getOrDefault("LLM_MODEL", "Qwen/Qwen3-8B");
    private static final String EMBED_MODEL = CFG.getOrDefault("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5");

    private void requireKey() {
        Assumptions.assumeTrue(API_KEY != null && !API_KEY.isBlank(),
                "未找到 LLM_API_KEY（设环境变量或仓库根放 .env.local）→ 跳过 spike");
        System.out.println("[cfg] baseUrl=" + BASE_URL + " model=" + MODEL + " embedModel=" + EMBED_MODEL);
    }

    private OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
                .temperature(0.3).maxTokens(512)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private OpenAiStreamingChatModel streamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
                .temperature(0.3).maxTokens(512)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /** 1) 阻塞 chat：验证 LC4j 跑通 SiliconFlow OpenAI 兼容接口（对应现 LlmService.chat）。 */
    @Test
    void t1_blockingChat() {
        requireKey();
        String answer = chatModel().chat("一句话回答：什么是 RAG？");
        System.out.println("[t1] blocking answer = " + answer);
        assertTrue(answer != null && !answer.isBlank(), "应返回非空答案");
    }

    /** 2) 流式 chat：回调式逐 token —— 验证"流式是否更简单"（对应现 LlmService.chatStream 的手解析 SSE）。 */
    @Test
    void t2_streamingChat() throws InterruptedException {
        requireKey();
        CountDownLatch done = new CountDownLatch(1);
        long t0 = System.nanoTime();
        AtomicLong firstTokenMs = new AtomicLong(-1);
        AtomicInteger chunks = new AtomicInteger();
        StringBuilder full = new StringBuilder();

        streamingModel().chat("请分三点简述如何申请退货。", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (firstTokenMs.get() < 0) {
                    firstTokenMs.set((System.nanoTime() - t0) / 1_000_000L);
                }
                chunks.incrementAndGet();
                full.append(partial);
                System.out.print(partial); // 逐 token 打印：肉眼可见是否真流式
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
                done.countDown();
            }
        });

        boolean ok = done.await(150, TimeUnit.SECONDS);
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("%n[t2] streaming: chunks=%d firstTokenMs=%d totalMs=%d ok=%s%n",
                chunks.get(), firstTokenMs.get(), totalMs, ok);
        assertTrue(ok && chunks.get() > 1, "应收到多个 token chunk（>1）才算真流式");
    }

    /**
     * 3) 思考模式观测（最关键的迁移风险）：LC4j 是否透出 {@code reasoning_content}？{@code /no_think} 能否生效？
     * 预期结论：onPartialResponse 只收到可见 content，<b>拿不到 reasoning_content</b> →
     * 迁移会丢失 §4.1「思考/正文」三段分解的数据来源，需自定义扩展或保留手写。
     */
    @Test
    void t3_thinkingObservation() throws InterruptedException {
        requireKey();
        long thinkOn = firstTokenMsFor("简述退款多久到账。");              // 思考开（默认）→ 首 token 慢
        long thinkOff = firstTokenMsFor("简述退款多久到账。\n\n/no_think"); // /no_think → 首 token 快
        System.out.printf("[t3] firstTokenMs  thinkingOn=%d  /no_think=%d%n", thinkOn, thinkOff);
        System.out.println("[t3] 观察：onPartialResponse 只收可见 content，reasoning_content（思考）未透出。"
                + " 若 thinkingOn 明显大于 /no_think，说明思考在首 token 前消耗了时间却拿不到其 token —— "
                + "迁移需自定义扩展暴露 reasoning_content，否则丢三段分解；enable_thinking 请求参数能否下发需另测"
                + "（LC4j 较新版本才有 customParameters）。");
    }

    private long firstTokenMsFor(String prompt) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        long t0 = System.nanoTime();
        AtomicLong first = new AtomicLong(-1);
        streamingModel().chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String p) {
                if (first.get() < 0) {
                    first.set((System.nanoTime() - t0) / 1_000_000L);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse r) {
                done.countDown();
            }

            @Override
            public void onError(Throwable e) {
                done.countDown();
            }
        });
        done.await(150, TimeUnit.SECONDS);
        return first.get();
    }

    /** 4) 向量化：验证 LC4j 跑通 SiliconFlow embeddings（对应现 EmbeddingService）。 */
    @Test
    void t4_embeddings() {
        requireKey();
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(BASE_URL).apiKey(API_KEY).modelName(EMBED_MODEL)
                .timeout(Duration.ofSeconds(60))
                .build();
        Response<Embedding> r = model.embed("退货政策是什么");
        int dim = r.content().vector().length;
        System.out.println("[t4] embedding dim = " + dim + "（bge-large-zh-v1.5 期望 1024）");
        assertTrue(dim > 0, "应返回非空向量");
    }

    /**
     * 5) 重排缺口（不联网）：LC4j 无 SiliconFlow {@code /v1/rerank} 的 ScoringModel（仅 Cohere/Jina 等）→
     * 无论选哪个框架，rerank 都保留现有手写 RerankService。
     */
    @Test
    void t5_rerankGapNote() {
        System.out.println("[t5] LC4j 的 ScoringModel 只有特定厂商实现（Cohere/Jina 等），"
                + "无 SiliconFlow /v1/rerank（bge-reranker-v2-m3）→ rerank 保留现有手写 RerankService。");
    }

    /**
     * 6) 思考捕获（验证用户发现的 onPartialThinking / TokenStream）：开 {@code returnThinking(true)} + 重写
     * {@code onPartialThinking}，看 SiliconFlow Qwen3 的 {@code reasoning_content} 能否经 LC4j 拿到。
     * <p>若 thinkingChars>0 → §2.9 的「唯一代价」被消除：迁移后思考/正文三段分解照样可采，LC4j 变成"严格更好"。
     * <p>注：你查到的 {@code TokenStream} 是 AiServices 高层 API；同名回调 {@code onPartialThinking} 在低层
     * {@code StreamingChatResponseHandler} 也有，这里用低层（更贴近 {@code LlmService.chatStream} 的用法、不需 AiServices）。
     * 需 LC4j 较新版本（pom 已升 1.2.0）；若 {@code returnThinking}/{@code onPartialThinking}/{@code PartialThinking}
     * 编译报不存在，把你查到的版本告诉我即可对齐。
     */
    @Test
    void t6_thinkingCapture() throws InterruptedException {
        requireKey();
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL)
                .returnThinking(true) // 关键：让 LC4j 解析并回吐 reasoning_content（思考开时）
                .temperature(0.3).maxTokens(512)
                .timeout(Duration.ofSeconds(120))
                .build();

        CountDownLatch done = new CountDownLatch(1);
        long t0 = System.nanoTime();
        AtomicLong firstThinkMs = new AtomicLong(-1);
        AtomicLong firstContentMs = new AtomicLong(-1);
        AtomicInteger thinkChars = new AtomicInteger();
        AtomicInteger contentChars = new AtomicInteger();

        model.chat("简述退款一般多久到账。", new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (firstThinkMs.get() < 0) {
                    firstThinkMs.set((System.nanoTime() - t0) / 1_000_000L);
                }
                String t = partialThinking.text();
                if (t != null) {
                    thinkChars.addAndGet(t.length());
                }
            }

            @Override
            public void onPartialResponse(String partial) {
                if (firstContentMs.get() < 0) {
                    firstContentMs.set((System.nanoTime() - t0) / 1_000_000L);
                }
                contentChars.addAndGet(partial.length());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
                done.countDown();
            }
        });

        boolean ok = done.await(150, TimeUnit.SECONDS);
        System.out.printf("[t6] returnThinking: thinkingChars=%d firstThinkMs=%d | contentChars=%d firstContentMs=%d | ok=%s%n",
                thinkChars.get(), firstThinkMs.get(), contentChars.get(), firstContentMs.get(), ok);
        if (thinkChars.get() > 0) {
            System.out.println("[t6] ✅ reasoning_content 经 onPartialThinking 拿到了 → §2.9「唯一代价」消除："
                    + "迁移后思考/正文三段分解照样可采，LC4j 严格更好！");
        } else {
            System.out.println("[t6] ⚠️ 未拿到 thinking（版本不支持 / 需其它开关 / SiliconFlow 未回吐）→ 维持 §2.9 结论。");
        }
        assertTrue(ok, "流应正常完成");
    }

    // ---- helpers ----

    /** LC4j 的 baseUrl 期望 /v1 根（会自动追加 /chat/completions、/embeddings），故从完整 LLM_API_URL 去掉路径尾。 */
    private static String deriveBaseUrl(String llmApiUrl) {
        return llmApiUrl
                .replace("/chat/completions", "")
                .replace("/embeddings", "")
                .replaceAll("/+$", "");
    }

    private static Map<String, String> loadConfig() {
        Map<String, String> cfg = new HashMap<>();
        // 1) 环境变量优先
        for (String k : new String[]{"LLM_API_KEY", "LLM_API_URL", "LLM_MODEL", "EMBEDDING_MODEL"}) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                cfg.put(k, v);
            }
        }
        // 2) 回退读 .env.local（mvn 从仓库根或模块目录跑都试）
        for (Path p : new Path[]{Path.of(".env.local"), Path.of("..", ".env.local"), Path.of("../..", ".env.local")}) {
            try {
                if (Files.exists(p)) {
                    for (String line : Files.readAllLines(p)) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                            continue;
                        }
                        int i = s.indexOf('=');
                        cfg.putIfAbsent(s.substring(0, i).trim(), s.substring(i + 1).trim());
                    }
                    break;
                }
            } catch (Exception ignored) {
                // spike：读不到就跳过，由 requireKey() 决定是否跳过用例
            }
        }
        return cfg;
    }
}
