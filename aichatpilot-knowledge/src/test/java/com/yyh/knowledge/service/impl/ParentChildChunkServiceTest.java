package com.yyh.knowledge.service.impl;

import com.yyh.knowledge.parser.ParsedDocument;
import com.yyh.knowledge.parser.StructuredDocumentParser;
import com.yyh.knowledge.strategy.RecursiveChunkStrategy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 父子切分自测（env 门控、不碰 DB）：解析样例 → {@link ParentChildChunkService#plan}，
 * 打印父块数/尺寸/标题，及每个父块的子块数与预览，肉眼验证「父~parentSize 按结构、子~childSize、表格自成父、子块带标题前缀」。
 *
 * <pre>
 *   $env:PC_TEST="1"; $env:PARSE_TEST_FILE="E:\路径\样例.md"; $env:PC_PARENT_SIZE="1500"; $env:PC_CHILD_SIZE="300"
 *   mvn -pl aichatpilot-knowledge test -Dtest=ParentChildChunkServiceTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "PC_TEST", matches = "1")
class ParentChildChunkServiceTest {

    @Test
    void planAndPrint() throws Exception {
        String path = System.getenv("PARSE_TEST_FILE");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "设 PARSE_TEST_FILE 指向样例文件");
        int parentSize = intEnv("PC_PARENT_SIZE", 1500);
        int childSize = intEnv("PC_CHILD_SIZE", 300);
        int overlap = intEnv("PC_CHILD_OVERLAP", 50);

        Path p = Path.of(path);
        ParsedDocument doc = new StructuredDocumentParser().parse(p.getFileName().toString(), Files.readAllBytes(p));

        String headingMode = System.getenv().getOrDefault("PC_HEADING_MODE", "leaf");
        List<ParentChildChunkService.ParentPlan> plans =
                ParentChildChunkService.plan(doc, new RecursiveChunkStrategy(), parentSize, childSize, overlap, headingMode);

        int totalChildren = plans.stream().mapToInt(pp -> pp.children().size()).sum();
        System.out.println("=== parents=" + plans.size() + " children=" + totalChildren
                + " (parentSize=" + parentSize + " childSize=" + childSize + " heading=" + headingMode + ") ===");
        int pi = 0;
        for (ParentChildChunkService.ParentPlan pp : plans) {
            System.out.println("[P" + pi++ + " len=" + pp.text().length()
                    + " heading=" + pp.headingPath() + " children=" + pp.children().size() + "]");
            int ci = 0;
            for (String c : pp.children()) {
                String preview = (c.length() > 100 ? c.substring(0, 100) + "…" : c).replace("\n", "\\n");
                System.out.println("   c" + ci++ + " len=" + c.length() + ": " + preview);
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(plans.isEmpty(), "应切出至少一个父块");
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v);
    }
}
