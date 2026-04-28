package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;

public interface RagService {
    KnowledgeAskResponse ask(Long kbId, KnowledgeAskRequest request);
}
