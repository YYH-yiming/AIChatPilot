package com.yyh.knowledge.service;

import com.yyh.knowledge.entity.KnowledgeChunk;

import java.util.List;

public interface ChunkService {
    List<KnowledgeChunk> splitAndSave(Long kbId, Long docId, String text);
}
