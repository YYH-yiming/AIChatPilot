package com.yyh.knowledge.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RecursiveChunkStrategy implements ChunkStrategy {

    private final FixedLengthChunkStrategy fixedLengthChunkStrategy;

    @Override
    public String strategyName() {
        return "recursive";
    }

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String[] paragraphs = text.split("\\R{2,}");
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            String normalized = paragraph.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() > chunkSize) {
                if (!buffer.isEmpty()) {
                    chunks.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                chunks.addAll(fixedLengthChunkStrategy.split(normalized, chunkSize, overlap));
                continue;
            }
            if (buffer.length() + normalized.length() + 1 > chunkSize) {
                chunks.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append(System.lineSeparator()).append(System.lineSeparator());
            }
            buffer.append(normalized);
        }
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
        }
        return chunks;
    }
}
