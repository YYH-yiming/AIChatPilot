package com.yyh.knowledge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RerankService service = new RerankService();

    private KnowledgeSearchHitVO hit(long id, String content) {
        KnowledgeSearchHitVO vo = new KnowledgeSearchHitVO();
        vo.setChunkId(id);
        vo.setContent(content);
        return vo;
    }

    @Test
    void reorderShouldFollowResultIndexAndSetScore() throws Exception {
        List<KnowledgeSearchHitVO> candidates = List.of(hit(1, "a"), hit(2, "b"), hit(3, "c"));
        JsonNode resp = mapper.readTree(
                "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.5}]}");

        List<KnowledgeSearchHitVO> out = service.reorder(candidates, resp);

        assertEquals(2, out.size());
        assertEquals(3L, out.get(0).getChunkId());
        assertEquals(1L, out.get(1).getChunkId());
        assertEquals(0.9, out.get(0).getScore(), 1e-9);
    }

    @Test
    void reorderShouldIgnoreEmptyOrOutOfRange() throws Exception {
        List<KnowledgeSearchHitVO> candidates = List.of(hit(1, "a"));
        assertTrue(service.reorder(candidates, mapper.readTree("{}")).isEmpty());
        assertTrue(service.reorder(candidates, mapper.readTree("{\"results\":[{\"index\":5}]}")).isEmpty());
        assertTrue(service.reorder(candidates, null).isEmpty());
    }

    @Test
    void rerankShouldDegradeToHeadWhenNotConfigured() {
        // 未配置 api-url/api-key 时降级为候选原序前 topK
        List<KnowledgeSearchHitVO> candidates = List.of(hit(1, "a"), hit(2, "b"), hit(3, "c"));

        List<KnowledgeSearchHitVO> out = service.rerank("q", candidates, 2);

        assertEquals(2, out.size());
        assertEquals(1L, out.get(0).getChunkId());
        assertEquals(2L, out.get(1).getChunkId());
    }
}
