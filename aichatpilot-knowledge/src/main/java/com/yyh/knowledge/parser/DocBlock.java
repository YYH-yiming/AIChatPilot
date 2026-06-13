package com.yyh.knowledge.parser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化解析的最小块单元：标题/段落/表格/列表。
 * <p>表格块的 {@code text} 存 Markdown 表格文本（保列序/行序）；{@code headingPath} 存祖先标题面包屑（如「一级 &gt; 二级」），
 * 供下游父子切分给子块附上层级上下文、提升召回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocBlock {

    public enum Type { HEADING, PARAGRAPH, TABLE, LIST }

    private Type type;
    private int level;          // 标题层级 1-6；非标题为 0
    private String text;        // 段落/标题文本；表格为 Markdown 表
    private String headingPath; // 祖先标题面包屑（可空）

    public static DocBlock heading(int level, String text, String headingPath) {
        return new DocBlock(Type.HEADING, level, text, headingPath);
    }

    public static DocBlock paragraph(String text, String headingPath) {
        return new DocBlock(Type.PARAGRAPH, 0, text, headingPath);
    }

    public static DocBlock table(String markdown, String headingPath) {
        return new DocBlock(Type.TABLE, 0, markdown, headingPath);
    }
}
