package com.example.MrPot.tools;

import com.example.MrPot.model.RagQueryRequest;
import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KbTools {

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    private final RagRetrievalService ragRetrievalService;

    @Tool(name = "kb_search", description = "Search internal knowledge base for documents relevant to the query.")
    public RagRetrievalResult search(String query, Integer topK, Double minScore) {
        int resolvedTopK = topK == null ? DEFAULT_TOP_K : topK;
        double resolvedMinScore = minScore == null ? DEFAULT_MIN_SCORE : minScore;
        return ragRetrievalService.retrieve(new RagQueryRequest(query, resolvedTopK, resolvedMinScore));
    }
}
