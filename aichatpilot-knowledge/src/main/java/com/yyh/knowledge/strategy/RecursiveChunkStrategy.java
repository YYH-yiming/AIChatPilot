package com.yyh.knowledge.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 真·递归字符切分（仿 LangChain RecursiveCharacterTextSplitter）：
 * 按分隔符优先级「段落 \n\n → 行 \n → 中英句末 。！？.!? → 分号 ；; → 空格 → 字符」逐级递归，
 * 尽量在自然边界切、不切碎句子；同级相邻小片滑动打包到接近 chunkSize，并跨块保留 overlap 重叠。
 * <p>自包含、无依赖，既作平铺策略也作父子切分的子块切分器。
 */
@Component
public class RecursiveChunkStrategy implements ChunkStrategy {

    /** 优先级从粗到细；保留分隔符以免丢句末标点/换行。空串=字符级兜底。 */
    private static final List<String> SEPARATORS =
            List.of("\n\n", "\n", "。", "！", "？", "!", "?", ".", "；", ";", " ", "");

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
        int size = Math.max(chunkSize, 1);
        int ov = Math.max(Math.min(overlap, size - 1), 0);
        for (String c : recurse(text, SEPARATORS, size, ov)) {
            String t = c.strip();
            if (!t.isEmpty()) {
                chunks.add(t);
            }
        }
        return chunks;
    }

    private List<String> recurse(String text, List<String> separators, int size, int overlap) {
        List<String> finalChunks = new ArrayList<>();
        String sep = separators.get(separators.size() - 1);
        List<String> nextSeparators = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String s = separators.get(i);
            if (s.isEmpty()) {
                sep = s;
                break;
            }
            if (text.contains(s)) {
                sep = s;
                nextSeparators = separators.subList(i + 1, separators.size());
                break;
            }
        }
        List<String> good = new ArrayList<>();
        for (String piece : splitKeepingSeparator(text, sep)) {
            if (piece.length() <= size) {
                good.add(piece);
            } else {
                if (!good.isEmpty()) {
                    finalChunks.addAll(mergeSplits(good, size, overlap));
                    good.clear();
                }
                if (nextSeparators.isEmpty()) {
                    finalChunks.add(piece); // 已到字符级仍超长：只能整段保留
                } else {
                    finalChunks.addAll(recurse(piece, nextSeparators, size, overlap));
                }
            }
        }
        if (!good.isEmpty()) {
            finalChunks.addAll(mergeSplits(good, size, overlap));
        }
        return finalChunks;
    }

    /** 按分隔符切但把分隔符保留在每片末尾（避免丢句末标点/换行）；sep 为空串时按字符切。 */
    private List<String> splitKeepingSeparator(String text, String sep) {
        List<String> out = new ArrayList<>();
        if (sep.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                out.add(String.valueOf(text.charAt(i)));
            }
            return out;
        }
        int idx = 0;
        while (idx < text.length()) {
            int next = text.indexOf(sep, idx);
            if (next < 0) {
                out.add(text.substring(idx));
                break;
            }
            out.add(text.substring(idx, next + sep.length()));
            idx = next + sep.length();
        }
        return out;
    }

    /** 同级小片滑动打包到接近 size，发射后从队首回退到 overlap 以内形成重叠。 */
    private List<String> mergeSplits(List<String> splits, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        Deque<String> current = new ArrayDeque<>();
        int total = 0;
        for (String piece : splits) {
            int len = piece.length();
            if (total + len > size && !current.isEmpty()) {
                chunks.add(join(current));
                while (total > overlap && !current.isEmpty()) {
                    total -= current.peekFirst().length();
                    current.pollFirst();
                }
            }
            current.addLast(piece);
            total += len;
        }
        if (!current.isEmpty()) {
            chunks.add(join(current));
        }
        return chunks;
    }

    private String join(Deque<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
        }
        return sb.toString().strip();
    }
}
