package com.yyh.agent.agent;

import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.dto.KnowledgeReference;
import com.yyh.agent.tool.KnowledgeTool;
import com.yyh.agent.trace.AgentTraceService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class FaqAgent extends BaseAgent {

    private final KnowledgeTool knowledgeTool;
    private final AgentProperties agentProperties;

    public FaqAgent(AgentTraceService agentTraceService,
                    KnowledgeTool knowledgeTool,
                    AgentProperties agentProperties) {
        super(agentTraceService);
        this.knowledgeTool = knowledgeTool;
        this.agentProperties = agentProperties;
    }

    @Override
    protected AgentResponse doExecute(AgentRequest request, AgentExecutionContext context) {
        Long kbId = request.getKbId() != null ? request.getKbId() : agentProperties.getKnowledge().getFaqKbId();
        context.addTool("knowledge.ask(kbId=" + kbId + ")");
        KnowledgeAskClientResponse askResponse = knowledgeTool.askKnowledge(
                request.getQuery(),
                kbId,
                agentProperties.getKnowledge().getAskTopK()
        );
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
