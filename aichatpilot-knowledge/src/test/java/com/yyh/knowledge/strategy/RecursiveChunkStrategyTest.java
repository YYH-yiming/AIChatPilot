package com.yyh.knowledge.strategy;

import com.yyh.knowledge.parser.ParsedDocument;
import com.yyh.knowledge.parser.StructuredDocumentParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 真递归子块切分自测（env 门控、不自动跑）：解析样例 → toMarkdown → 递归切分，
 * 打印块数 / 尺寸分布(min/max/avg) / 预览，肉眼验证「不切碎句子、尺寸均匀、相邻有重叠」。
 *
 * <pre>
 *   $env:CHUNK_TEST="1"; $env:PARSE_TEST_FILE="E:\路径\样例.pdf"; $env:CHUNK_SIZE="300"; $env:CHUNK_OVERLAP="50"
 *   mvn -pl aichatpilot-knowledge test -Dtest=RecursiveChunkStrategyTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHUNK_TEST", matches = "1")
class RecursiveChunkStrategyTest {

    @Test
    void splitAndPrint() throws Exception {
        String path = System.getenv("PARSE_TEST_FILE");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "设 PARSE_TEST_FILE 指向样例文件");
        int size = Integer.parseInt(System.getenv().getOrDefault("CHUNK_SIZE", "300"));
        int overlap = Integer.parseInt(System.getenv().getOrDefault("CHUNK_OVERLAP", "50"));

        Path p = Path.of(path);
        ParsedDocument doc = new StructuredDocumentParser().parse(p.getFileName().toString(), Files.readAllBytes(p));
        String text = doc.toMarkdown();

        List<String> chunks = new RecursiveChunkStrategy().split(text, size, overlap);
        System.out.println("=== recursive size=" + size + " overlap=" + overlap
                + " chunks=" + chunks.size() + " textLen=" + text.length() + " ===");
        int min = Integer.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            min = Math.min(min, c.length());
            max = Math.max(max, c.length());
            sum += c.length();
            String preview = (c.length() > 120 ? c.substring(0, 120) + "…" : c).replace("\n", "\\n");
            System.out.println("[" + i + " len=" + c.length() + "] " + preview);
        }
        if (!chunks.isEmpty()) {
            System.out.println("min=" + min + " max=" + max + " avg=" + (sum / chunks.size()));
        }
        org.junit.jupiter.api.Assertions.assertFalse(chunks.isEmpty(), "应切出至少一个块");
    }
}
