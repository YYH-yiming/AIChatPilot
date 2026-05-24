package com.yyh.agent.agent;

import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.dto.KnowledgeReference;
import com.yyh.agent.service.KnowledgeBaseSelector;
import com.yyh.agent.trace.AgentTraceService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class FaqAgent extends BaseAgent {

    private final KnowledgeBaseSelector knowledgeBaseSelector;
    private final AgentProperties agentProperties;

    public FaqAgent(AgentTraceService agentTraceService,
                    KnowledgeBaseSelector knowledgeBaseSelector,
                    AgentProperties agentProperties) {
        super(agentTraceService);
        this.knowledgeBaseSelector = knowledgeBaseSelector;
        this.agentProperties = agentProperties;
    }

    @Override
    protected AgentResponse doExecute(AgentRequest request, AgentExecutionContext context) {
        KnowledgeBaseSelector.SelectionResult selectionResult = knowledgeBaseSelector.askAcrossKnowledgeBases(
                request,
                "faq",
                agentProperties.getKnowledge().getFaqKbId(),
                agentProperties.getKnowledge().getAskTopK()
        );
        Long kbId = selectionResult.kbId();
        KnowledgeAskClientResponse askResponse = selectionResult.response();
        context.addTool("knowledge.ask(kbId=" + kbId + ", strategy=" + selectionResult.strategy() + ")");
        context.addToolResult("knowledgeAsk", askResponse.getReferenceCount());

        AgentResponse response = new AgentResponse();
        response.setAnswer(askResponse.getAnswer());
        response.setKbId(kbId);
        response.setReferences(toReferences(askResponse.getReferences()));
        return response;
    }

    @Override
    protected String agentName() {
        return "faq";
    }

    private List<KnowledgeReference> toReferences(List<KnowledgeSearchHitClientResponse> references) {
        if (references == null) {
            return Collections.emptyList();
        }
        return references.stream().map(item -> {
            KnowledgeReference reference = new KnowledgeReference();
            reference.setChunkId(item.getChunkId());
            reference.setDocId(item.getDocId());
            reference.setKbId(item.getKbId());
            reference.setChunkIndex(item.getChunkIndex());
            reference.setTokenCount(item.getTokenCount());
            reference.setContent(item.getContent());
            reference.setScore(item.getScore());
            reference.setDenseScore(item.getDenseScore());
            reference.setSparseScore(item.getSparseScore());
            reference.setSource(item.getSource());
            return reference;
        }).toList();
    }
}
