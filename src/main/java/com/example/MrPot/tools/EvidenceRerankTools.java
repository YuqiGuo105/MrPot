package com.example.MrPot.tools;

import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.ScoredDocument;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class EvidenceRerankTools {

    @Tool(name = "evidence_rerank", description = "Rerank retrieved documents using keyword overlap.")
    public RagRetrievalResult rerank(RagRetrievalResult retrieval, List<String> terms, Integer maxDocs) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
            return retrieval;
        }
        int limit = maxDocs == null ? retrieval.documents().size() : Math.max(1, maxDocs);
        List<String> safeTerms = terms == null ? List.of() : terms;

        List<ScoredDocument> reranked = new ArrayList<>(retrieval.documents());
        reranked.sort(Comparator.comparingDouble((ScoredDocument doc) ->
                overlapScore(doc, safeTerms)).reversed().thenComparingDouble(ScoredDocument::score).reversed());

        List<ScoredDocument> limited = reranked.stream().limit(limit).collect(Collectors.toList());
        return new RagRetrievalResult(retrieval.question(), limited, retrieval.context());
    }

    private double overlapScore(ScoredDocument doc, List<String> terms) {
        if (doc == null || doc.document() == null || terms == null || terms.isEmpty()) return 0.0;
        String text = (doc.document().getContent() == null ? "" : doc.document().getContent()).toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            if (text.contains(term.toLowerCase(Locale.ROOT))) score += 1.0;
        }
        return score;
    }
}
