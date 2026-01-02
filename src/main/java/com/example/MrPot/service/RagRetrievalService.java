package com.example.MrPot.service;

import com.example.MrPot.model.RagQueryRequest;
import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.ScoredDocument;
import com.example.MrPot.repository.KbDocumentVectorRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * RAG retrieval-only service:
 * - Takes user input (question)
 * - Generates embedding
 * - Queries the vector store
 * - Applies basic filtering (with dynamic score adaptation)
 * - Builds LLM-ready context text
 *
 * This service does NOT call any chat/LLM APIs.
 * It is intended to be reused by various "tool services"
 * that need knowledge from the internal KB.
 */
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    /** Default topK when client does not specify. */
    private static final int DEFAULT_TOP_K = 3;

    /**
     * Default minimum score requested by API users.
     * This is an input "preference", not a hard cutoff anymore,
     * because we apply dynamic adaptation based on actual scores.
     */
    private static final double DEFAULT_MIN_SCORE = 0.60;

    /**
     * Margin from the top score used for dynamic thresholding.
     * Example: if topScore = 0.82 and margin = 0.10, dynamic threshold ~0.72.
     */
    private static final double TOP_SCORE_MARGIN = 0.10;

    /**
     * Absolute lower bound when all scores are low.
     * We do not drop below this to avoid extremely noisy results.
     */
    private static final double ABSOLUTE_FLOOR_SCORE = 0.25;

    private final @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel;
    private final KbDocumentVectorRepository kbRepository;

    /**
     * Core retrieval method:
     * 1. Extract question
     * 2. Embed question
     * 3. Query vector store
     * 4. Compute dynamic min score
     * 5. Filter by dynamic min score
     * 6. Build LLM context
     *
     * @param request RAG query request from client
     * @return retrieval result including:
     *         - original question
     *         - filtered documents
     *         - formatted context string for LLM
     */
    public RagRetrievalResult retrieve(RagQueryRequest request) {
        // Defensive: if the question is missing/blank (e.g., file-only uploads),
        // skip embedding and return an empty retrieval result so callers can
        // decide whether to expand the query using extracted file keywords.
        if (request == null || request.question() == null || request.question().isBlank()) {
            log.debug("RAG retrieval: empty question provided; skipping vector search");
            return new RagRetrievalResult("", List.of(), "");
        }

        // 1. Get user question
        String question = request.question();

        if (question == null || question.isEmpty()) {
            log.debug("Input empty");
            return new RagRetrievalResult(question, List.of(), "");
        }

        // 2. Generate query embedding from the question
        float[] queryEmbedding = embeddingModel.embed(question);

        // 3. Resolve retrieval parameters
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double requestedMinScore = request.resolveMinScore(DEFAULT_MIN_SCORE);

        // 4. Query vector store for topK nearest documents
        List<ScoredDocument> retrieved = kbRepository.findNearest(queryEmbedding, topK);

        if (retrieved == null || retrieved.isEmpty()) {
            log.debug("RAG retrieval: no documents found for question='{}'", question);
            return new RagRetrievalResult(question, List.of(), "(no results)");
        }

        // Ensure we know the top score (assumes descending order; otherwise compute max)
        double topScore = retrieved.stream()
                .mapToDouble(ScoredDocument::score)
                .max()
                .orElse(0.0);

        // 5. Compute dynamic minimum score
        double effectiveMinScore = computeDynamicMinScore(requestedMinScore, topScore);

        // 6. Filter by similarity score using the dynamic threshold
        List<ScoredDocument> filtered = retrieved.stream()
                .filter(doc -> doc.score() >= effectiveMinScore)
                .toList();

        // 7. Safety net: if everything was filtered out but we did get results,
        //    keep at least the single best document.
        if (filtered.isEmpty() && !retrieved.isEmpty()) {
            log.debug(
                    "RAG retrieval: all docs filtered out (topScore={}, effectiveMinScore={}). " +
                            "Keeping best document as fallback.",
                    topScore, effectiveMinScore
            );
            filtered = List.of(retrieved.get(0));
        }

        // 8. Build textual context for LLM consumption
        String context = buildContext(filtered);

        return new RagRetrievalResult(
                question,
                filtered,
                context
        );
    }

    /**
     * Compute a dynamic minimum score based on:
     *  - user/default requestedMinScore
     *  - actual topScore of the retrieved documents
     *
     * Logic:
     *  - If topScore >= requestedMinScore:
     *      use the tighter of (topScore - margin) and requestedMinScore,
     *      to keep only high-quality matches close to the best one.
     *  - If topScore < requestedMinScore (all scores "low", e.g. around 0.4):
     *      relax the threshold but still keep a floor, based on:
     *         max(ABSOLUTE_FLOOR_SCORE, topScore - margin)
     */
    private double computeDynamicMinScore(double requestedMinScore, double topScore) {
        double dynamicMinScore;

        if (topScore >= requestedMinScore) {
            // High-confidence scenario: scores are decent.
            // We enforce both:
            //  - not too far from the top (topScore - margin)
            //  - not below the requestedMinScore
            double fromTop = topScore - TOP_SCORE_MARGIN;
            dynamicMinScore = Math.max(requestedMinScore, fromTop);
        } else {
            // Low-confidence scenario: topScore is below requestedMinScore (e.g. ~0.4).
            // We relax the threshold but do not go below ABSOLUTE_FLOOR_SCORE.
            double relaxedFromTop = topScore - TOP_SCORE_MARGIN;
            dynamicMinScore = Math.max(ABSOLUTE_FLOOR_SCORE, relaxedFromTop);
        }

        // Ensure we never exceed topScore (corner case when margin is very small)
        if (dynamicMinScore > topScore) {
            dynamicMinScore = topScore;
        }

        return dynamicMinScore;
    }

    /**
     * Convert a list of scored documents into a single context string
     * that can be injected into an LLM system prompt.
     *
     * Example format:
     *   【docId=..., type=..., score=0.873】
     *   document content...
     *
     *   【docId=..., type=..., score=0.751】
     *   document content...
     *
     * @param docs scored documents from vector store
     * @return concatenated context string
     */
    public String buildContext(List<ScoredDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return "(no results)";
        }

        return docs.stream()
                .map(d -> {
                    var doc = d.document();
                    return "【docId=" + doc.getId()
                            + ", type=" + doc.getDocType()
                            + ", score=" + String.format(Locale.US, "%.3f", d.score())
                            + "】\n"
                            + doc.getContent();
                })
                .collect(Collectors.joining("\n\n"));
    }
}
