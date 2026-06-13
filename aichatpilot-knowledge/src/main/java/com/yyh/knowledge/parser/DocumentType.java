package com.yyh.knowledge.parser;

import java.util.Locale;

/** 文档类型（按扩展名判定），决定走哪条结构化解析路径。 */
public enum DocumentType {
    MARKDOWN, CSV, EXCEL, PDF, WORD, HTML, PPT, PLAIN, OTHER;

    public static DocumentType fromFilename(String filename) {
        if (filename == null) {
            return OTHER;
        }
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".md") || f.endsWith(".markdown")) {
            return MARKDOWN;
        }
        if (f.endsWith(".csv")) {
            return CSV;
        }
        if (f.endsWith(".xlsx") || f.endsWith(".xls")) {
            return EXCEL;
        }
        if (f.endsWith(".pdf")) {
            return PDF;
        }
        if (f.endsWith(".docx") || f.endsWith(".doc")) {
            return WORD;
        }
        if (f.endsWith(".html") || f.endsWith(".htm")) {
            return HTML;
        }
        if (f.endsWith(".pptx") || f.endsWith(".ppt")) {
            return PPT;
        }
        if (f.endsWith(".txt") || f.endsWith(".json")) {
            return PLAIN;
        }
        return OTHER;
    }
}
