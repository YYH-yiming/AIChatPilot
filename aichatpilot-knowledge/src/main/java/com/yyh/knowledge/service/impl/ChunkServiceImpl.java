package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.service.ChunkService;
import com.yyh.knowledge.strategy.ChunkStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements ChunkService {

    private final List<ChunkStrategy> chunkStrategies;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final ObjectMapper objectMapper;

    @Value("${knowledge.chunk.strategy:fixed}")
    private String strategyName;

    @Value("${knowledge.chunk.size:1000}")
    private int chunkSize;

    @Value("${knowledge.chunk.overlap:100}")
    private int overlap;

    @Override
    @Transactional
    public List<KnowledgeChunk> splitAndSave(Long kbId, Long docId, String text) {
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
        ChunkStrategy strategy = resolveStrategy();
        List<String> chunks = strategy.split(text, chunkSize, overlap);
        return java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    KnowledgeChunk chunk = new KnowledgeChunk();
                    chunk.setKbId(kbId);
                    chunk.setDocId(docId);
                    chunk.setChunkIndex(index);
                    chunk.setContent(chunks.get(index));
                    chunk.setTokenCount(chunks.get(index).length());
                    chunk.setMetadata(toMetadata(index, strategy.strategyName()));
                    knowledgeChunkMapper.insert(chunk);
                    chunk.setVectorId(String.valueOf(chunk.getId()));
                    knowledgeChunkMapper.updateById(chunk);
                    return chunk;
                })
                .toList();
    }

    private ChunkStrategy resolveStrategy() {
        return chunkStrategies.stream()
                .filter(candidate -> candidate.strategyName().equalsIgnoreCase(strategyName))
                .findFirst()
                .orElseGet(() -> chunkStrategies.stream()
                        .filter(candidate -> "fixed".equalsIgnoreCase(candidate.strategyName()))
                        .findFirst()
                        .orElseThrow());
    }

    private String toMetadata(int index, String strategy) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunkIndex", index);
            metadata.put("strategy", strategy);
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return "{\"chunkIndex\":" + index + "}";
        }
    }
}
