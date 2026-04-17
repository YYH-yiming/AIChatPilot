package com.yyh.knowledge.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FixedLengthChunkStrategy implements ChunkStrategy {

    @Override
    public String strategyName() {
        return "fixed";
    }

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int safeChunkSize = Math.max(chunkSize, 1);
        int safeOverlap = Math.max(Math.min(overlap, safeChunkSize - 1), 0);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + safeChunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            start = end - safeOverlap;
        }
        return chunks;
    }
}
