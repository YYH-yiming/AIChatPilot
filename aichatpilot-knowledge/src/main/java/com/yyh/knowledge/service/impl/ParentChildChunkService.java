package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.parser.DocBlock;
import com.yyh.knowledge.parser.ParsedDocument;
import com.yyh.knowledge.strategy.ChunkStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子切分（small-to-big）：把结构化 {@link ParsedDocument} 切成「父块（粗、上下文全）+ 子块（细、检索准）」。
 * <p>父块：按结构 block 顺序打包到 ~parentSize（表格块自成一父、不与正文混；不跨父拆 block）。
 * 子块：在每个父块内用配置的 {@link ChunkStrategy}（recursive/semantic）切到 ~childSize，并前缀父块标题面包屑提升召回。
 * <p>父/子均入 MySQL：父块 {@code role=parent, parentId=null}（不索引、命中后返回）、
 * 子块 {@code role=child, parentId=父id}（只它进 Milvus/ES）。
 * <p>纯计算 {@link #plan} 与持久化分离，便于自测。P2.3：仅产出+入库，接管线在 P2.4（flag 控制）。
 */
@Service
@RequiredArgsConstructor
public class ParentChildChunkService {

    private final List<ChunkStrategy> chunkStrategies;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final ObjectMapper objectMapper;

    /** 子块切分策略（复用全局参数）。 */
    @Value("${knowledge.chunk.strategy:recursive}")
    private String childStrategyName;

    @Value("${knowledge.chunk.parent-child.parent-size:1500}")
    private int parentSize;

    @Value("${knowledge.chunk.parent-child.child-size:300}")
    private int childSize;

    @Value("${knowledge.chunk.parent-child.child-overlap:50}")
    private int childOverlap;

    /** 子块标题前缀模式：full=完整面包屑 / leaf=末级标题 / none=不加。默认 leaf：保留小节上下文又不让同父子块共享长前缀稀释向量区分度。 */
    @Value("${knowledge.chunk.parent-child.child-heading-mode:leaf}")
    private String childHeadingMode;

    /**
     * 切分并入库，返回【子块】列表（供索引；父块只入库不返回索引）。先删旧 chunk，行为对齐 ChunkServiceImpl。
     */
    @Transactional
    public List<KnowledgeChunk> splitAndSave(Long kbId, Long docId, ParsedDocument doc) {
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
        ChunkStrategy childStrategy = resolveStrategy(childStrategyName);
        List<ParentPlan> plans = plan(doc, childStrategy, parentSize, childSize, childOverlap, childHeadingMode);

        List<KnowledgeChunk> children = new ArrayList<>();
        int parentIndex = 0;
        int childIndex = 0;
        for (ParentPlan pp : plans) {
            KnowledgeChunk parent = persistParent(kbId, docId, parentIndex++, pp);
            for (String childContent : pp.children()) {
                children.add(persistChild(kbId, docId, childIndex++, parent.getId(),
                        pp.headingPath(), childContent, childStrategy.strategyName()));
            }
        }
        return children;
    }

    // ---------------------------------------------------------------------
    // 纯计算：结构化文档 → 父块(含子块文本)。无 DB/Spring 依赖，供持久化与自测复用。
    // ---------------------------------------------------------------------
    public static List<ParentPlan> plan(ParsedDocument doc, ChunkStrategy childStrategy,
                                        int parentSize, int childSize, int childOverlap, String headingMode) {
        List<ParentPlan> plans = new ArrayList<>();
        for (Parent parent : buildParents(doc, Math.max(parentSize, 1))) {
            List<String> children = new ArrayList<>();
            for (String c : childStrategy.split(parent.text(), childSize, childOverlap)) {
                children.add(prefixHeading(parent.headingPath(), c, headingMode));
            }
            if (children.isEmpty()) {
                children.add(parent.text()); // 父块本身已够小：至少留一个子块，保证可检索
            }
            plans.add(new ParentPlan(parent.text(), parent.headingPath(), children));
        }
        return plans;
    }

    /** 按结构 block 打包父块：表格自成一父、不跨父拆 block、累计到 parentSize 收口。 */
    private static List<Parent> buildParents(ParsedDocument doc, int parentSize) {
        List<Parent> parents = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String headingPath = null;
        for (DocBlock b : doc.getBlocks()) {
            String text = render(b);
            if (text.isBlank()) {
                continue;
            }
            boolean isTable = b.getType() == DocBlock.Type.TABLE;
            if (buf.length() > 0 && (isTable || buf.length() + text.length() > parentSize)) {
                parents.add(new Parent(buf.toString().strip(), headingPath));
                buf.setLength(0);
                headingPath = null;
            }
            if (headingPath == null) {
                headingPath = effectiveHeadingPath(b);
            }
            if (buf.length() > 0) {
                buf.append("\n");
            }
            buf.append(text);
            if (isTable) { // 表格块单独收口，避免后续正文黏进表父
                parents.add(new Parent(buf.toString().strip(), headingPath));
                buf.setLength(0);
                headingPath = null;
            }
        }
        if (buf.length() > 0) {
            parents.add(new Parent(buf.toString().strip(), headingPath));
        }
        return parents;
    }

    private static String render(DocBlock b) {
        if (b.getType() == DocBlock.Type.HEADING) {
            int level = Math.max(1, Math.min(b.getLevel(), 6));
            return "#".repeat(level) + " " + safe(b.getText());
        }
        return safe(b.getText());
    }

    private static String effectiveHeadingPath(DocBlock b) {
        if (b.getHeadingPath() != null && !b.getHeadingPath().isBlank()) {
            return b.getHeadingPath();
        }
        if (b.getType() == DocBlock.Type.HEADING) {
            return b.getText();
        }
        return null;
    }

    /** 子块前缀标题（让子块自带层级上下文，提升召回）：full=完整面包屑 / leaf=末级标题 / none=不加。 */
    private static String prefixHeading(String headingPath, String childText, String mode) {
        if (headingPath == null || headingPath.isBlank() || "none".equalsIgnoreCase(mode)) {
            return childText;
        }
        String prefix = "full".equalsIgnoreCase(mode) ? headingPath : leafHeading(headingPath);
        return prefix + "\n" + childText;
    }

    /** 取面包屑末级标题（最后一个 " > " 之后）。 */
    private static String leafHeading(String headingPath) {
        int i = headingPath.lastIndexOf(" > ");
        return i >= 0 ? headingPath.substring(i + 3) : headingPath;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------------
    // 持久化
    // ---------------------------------------------------------------------
    private KnowledgeChunk persistParent(Long kbId, Long docId, int index, ParentPlan pp) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setKbId(kbId);
        c.setDocId(docId);
        c.setChunkIndex(index);
        c.setContent(pp.text());
        c.setTokenCount(pp.text().length());
        c.setChunkRole("parent");
        c.setMetadata(metadata("parent", null, pp.headingPath(), null));
        knowledgeChunkMapper.insert(c);
        return c;
    }

    private KnowledgeChunk persistChild(Long kbId, Long docId, int index, Long parentId,
                                        String headingPath, String content, String strategy) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setKbId(kbId);
        c.setDocId(docId);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setTokenCount(content.length());
        c.setChunkRole("child");
        c.setParentId(parentId);
        c.setMetadata(metadata("child", parentId, headingPath, strategy));
        knowledgeChunkMapper.insert(c);
        c.setVectorId(String.valueOf(c.getId()));
        knowledgeChunkMapper.updateById(c);
        return c;
    }

    private ChunkStrategy resolveStrategy(String name) {
        return chunkStrategies.stream()
                .filter(s -> s.strategyName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> chunkStrategies.stream()
                        .filter(s -> "recursive".equalsIgnoreCase(s.strategyName()))
                        .findFirst()
                        .orElse(chunkStrategies.get(0)));
    }

    private String metadata(String role, Long parentId, String headingPath, String strategy) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            if (parentId != null) {
                m.put("parentId", parentId);
            }
            if (headingPath != null) {
                m.put("headingPath", headingPath);
            }
            if (strategy != null) {
                m.put("strategy", strategy);
            }
            return objectMapper.writeValueAsString(m);
        } catch (Exception ex) {
            return "{\"role\":\"" + role + "\"}";
        }
    }

    /** 父块计划：父块文本 + 标题面包屑 + 其子块文本列表（已含标题前缀）。 */
    public record ParentPlan(String text, String headingPath, List<String> children) {
    }

    private record Parent(String text, String headingPath) {
    }
}
