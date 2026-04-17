package com.yyh.knowledge.strategy;

import java.util.List;

public interface ChunkStrategy {
    String strategyName();
    List<String> split(String text, int chunkSize, int overlap);
}
