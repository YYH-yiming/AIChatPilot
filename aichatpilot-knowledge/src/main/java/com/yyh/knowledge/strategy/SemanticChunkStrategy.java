package com.yyh.knowledge.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SemanticChunkStrategy implements ChunkStrategy {

    private final RecursiveChunkStrategy recursiveChunkStrategy;

    @Override
    public String strategyName() {
        return "semantic";
    }

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        return recursiveChunkStrategy.split(text, chunkSize, overlap);
    }
}
