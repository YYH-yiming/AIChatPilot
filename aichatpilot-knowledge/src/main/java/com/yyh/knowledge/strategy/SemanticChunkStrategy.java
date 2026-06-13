package com.yyh.knowledge.strategy;

import com.yyh.knowledge.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 真·语义切分：按句切分 → 逐句 embedding → 相邻句余弦距离，在距离突增处（&gt; 均值 + k·标准差）作为语义边界断块；
 * 超过 chunkSize 的块回退递归切分避免过大。句子过少或 embedding 不可用时整体回退递归切分（不阻断入库）。
 * <p>代价：每篇按句数调一次 embedBatch；仅在 {@code knowledge.chunk.strategy=semantic} 时启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticChunkStrategy implements ChunkStrategy {

    private final EmbeddingService embeddingService;
    private final RecursiveChunkStrategy recursiveChunkStrategy;

    /** 断点阈值 = 距离均值 + 该倍数 × 标准差；越大切得越粗（块更少更大）。 */
    @Value("${knowledge.chunk.semantic.std-multiplier:1.0}")
    private double stdMultiplier;

    @Override
    public String strategyName() {
        return "semantic";
    }

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(chunkSize, 1);
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 2) {
            return recursiveChunkStrategy.split(text, size, overlap);
        }
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(sentences);
        } catch (Exception ex) {
            log.warn("语义切分 embedding 失败，回退递归切分: {}", ex.getMessage());
            return recursiveChunkStrategy.split(text, size, overlap);
        }
        if (embeddings.size() != sentences.size()) {
            log.warn("语义切分 embedding 数量({})与句子数({})不符，回退递归切分", embeddings.size(), sentences.size());
            return recursiveChunkStrategy.split(text, size, overlap);
        }

        double[] distances = new double[sentences.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = 1.0 - cosine(embeddings.get(i), embeddings.get(i + 1));
        }
        double threshold = mean(distances) + stdMultiplier * std(distances);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            boolean wouldOverflow = current.length() > 0 && current.length() + sentences.get(i).length() > size;
            current.append(sentences.get(i));
            boolean semanticBreak = i < distances.length && distances[i] > threshold;
            if (wouldOverflow || semanticBreak) {
                flush(chunks, current, size, overlap);
            }
        }
        flush(chunks, current, size, overlap);
        return chunks;
    }

    /** 收口当前累积块：超 size 的回退递归切分，否则整块加入。 */
    private void flush(List<String> chunks, StringBuilder current, int size, int overlap) {
        String block = current.toString().strip();
        current.setLength(0);
        if (block.isEmpty()) {
            return;
        }
        if (block.length() > size) {
            chunks.addAll(recursiveChunkStrategy.split(block, size, overlap));
        } else {
            chunks.add(block);
        }
    }

    /** 按中英句末标点/换行切句，保留标点。 */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[。！？!?；;\\n])")) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double mean(double[] xs) {
        if (xs.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }

    private double std(double[] xs) {
        if (xs.length == 0) {
            return 0;
        }
        double m = mean(xs);
        double sq = 0;
        for (double x : xs) {
            sq += (x - m) * (x - m);
        }
        return Math.sqrt(sq / xs.length);
    }
}
