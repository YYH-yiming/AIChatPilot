package com.yyh.knowledge.parser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P1 结构化解析自测（env 门控、不自动跑）：解析一个样例文件，打印块结构 + 渲染 Markdown，肉眼验证「保结构、不错位、不丢信息」。
 *
 * <pre>
 *   $env:PARSE_TEST="1"; $env:PARSE_TEST_FILE="E:\路径\样例.pdf"
 *   mvn -pl aichatpilot-knowledge test -Dtest=StructuredDocumentParserTest
 * </pre>
 * 换 .md / .csv / .xlsx / .pdf 各跑一遍，看：标题层级对不对、表格 cell 不串行、PDF 多栏阅读顺序不乱。
 */
@EnabledIfEnvironmentVariable(named = "PARSE_TEST", matches = "1")
class StructuredDocumentParserTest {

    @Test
    void parseAndPrint() throws Exception {
        String path = System.getenv("PARSE_TEST_FILE");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "设 PARSE_TEST_FILE 指向样例文件");

        Path p = Path.of(path);
        byte[] bytes = Files.readAllBytes(p);
        ParsedDocument doc = new StructuredDocumentParser().parse(p.getFileName().toString(), bytes);

        System.out.println("=== docType=" + doc.getDocType() + "  blocks=" + doc.getBlocks().size() + " ===");
        for (DocBlock b : doc.getBlocks()) {
            String t = b.getText();
            String preview = (t.length() > 160 ? t.substring(0, 160) + "…" : t).replace("\n", "\\n");
            System.out.println("[" + b.getType() + " L" + b.getLevel() + " path=" + b.getHeadingPath() + "] " + preview);
        }
        System.out.println("\n=== toMarkdown() ===\n" + doc.toMarkdown());

        org.junit.jupiter.api.Assertions.assertFalse(doc.getBlocks().isEmpty(), "应解析出至少一个块");
    }
}
