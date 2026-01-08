package com.example.MrPot.tools;

import com.example.MrPot.model.RagQueryRequest;
import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.ScoredDocument;
import com.example.MrPot.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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

    @Tool(name = "kb_search_multi", description = "Search the knowledge base with multiple queries and merge results.")
    public RagRetrievalResult searchMulti(List<String> queries, Integer topK, Double minScore) {
        if (queries == null || queries.isEmpty()) {
            return new RagRetrievalResult("", List.of(), "");
        }
        int resolvedTopK = topK == null ? DEFAULT_TOP_K : topK;
        double resolvedMinScore = minScore == null ? DEFAULT_MIN_SCORE : minScore;

        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<ScoredDocument> merged = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        String question = queries.get(0);

        for (String q : queries) {
            if (q == null || q.isBlank()) continue;
            RagRetrievalResult result = ragRetrievalService.retrieve(new RagQueryRequest(q, resolvedTopK, resolvedMinScore));
            if (result == null || result.documents() == null) continue;
            for (ScoredDocument doc : result.documents()) {
                if (doc == null || doc.document() == null || doc.document().getId() == null) continue;
                if (seen.add(doc.document().getId())) {
                    merged.add(doc);
                }
            }
            if (result.context() != null && !result.context().isBlank()) {
                ctx.append(result.context()).append("\n");
            }
        }

        return new RagRetrievalResult(question, merged, ctx.toString());
    }
}
