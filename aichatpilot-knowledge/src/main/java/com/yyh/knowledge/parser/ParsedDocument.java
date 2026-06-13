package com.yyh.knowledge.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化解析产物：有序的块列表（保留文档阅读顺序），替代旧的「一坨纯文本」。
 * 供 P2 父子切分按结构切；P1 阶段先用 {@link #toMarkdown()} 看输出 / 兼容旧的平铺切分。
 */
public class ParsedDocument {

    private final DocumentType docType;
    private final List<DocBlock> blocks = new ArrayList<>();

    public ParsedDocument(DocumentType docType) {
        this.docType = docType;
    }

    public DocumentType getDocType() {
        return docType;
    }

    public List<DocBlock> getBlocks() {
        return blocks;
    }

    public void add(DocBlock block) {
        if (block != null && block.getText() != null && !block.getText().isBlank()) {
            blocks.add(block);
        }
    }

    /** 渲染成 Markdown：标题加 `#`、表格原样、段落空行分隔。供下游/自测查看与（过渡期）平铺切分。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (DocBlock b : blocks) {
            if (b.getType() == DocBlock.Type.HEADING) {
                int level = Math.max(1, Math.min(b.getLevel(), 6));
                sb.append("#".repeat(level)).append(' ').append(b.getText()).append("\n\n");
            } else {
                sb.append(b.getText()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /** 纯文本（去结构标记），用于过渡期喂给现有平铺切分。 */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        for (DocBlock b : blocks) {
            sb.append(b.getText()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
