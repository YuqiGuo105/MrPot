package com.example.MrPot.model;

import com.example.MrPot.tools.ToolProfile;

import java.util.HashSet;
import java.util.Set;

/**
 * Request payload for generating an answer with RAG context.
 *
 * @param question   user question
 * @param sessionId  chat session id for memory separation
 * @param topK       optional override for retrieval topK
 * @param minScore   optional override for retrieval minScore
 * @param model      optional model name hint (e.g. "deepseek", "openai")
 * @param toolProfile  optional tool profile (e.g. "BASIC_CHAT", "ADMIN", "FULL")
 */
public record RagAnswerRequest(
        String question,
        String sessionId,
        Integer topK,
        Double minScore,
        String model,
        String toolProfile
) {
    private static final Set<String> models = Set.of("deepseek", "gemini", "openai");
    public static final String DEFAULT_MODEL = "deepseek";

    public int resolveTopK(int defaultValue) {
        return topK == null || topK <= 0 ? defaultValue : topK;
    }

    public double resolveMinScore(double defaultValue) {
        return minScore == null ? defaultValue : minScore;
    }

    public String resolveModel() {
        return (model == null || model.isBlank()
        || !models.contains(model)) ? DEFAULT_MODEL : model;
    }

    public ResolvedSession resolveSession() {
        boolean temporary = sessionId == null || sessionId.isBlank();
        String resolvedId = temporary ? "temp-" + java.util.UUID.randomUUID() : sessionId;
        return new ResolvedSession(resolvedId, temporary);
    }

    /**
     * Resolve the tool profile for this request.
     * If toolProfile is null/blank/invalid, fall back to provided defaultProfile.
     */
    public ToolProfile resolveToolProfile(ToolProfile defaultProfile) {
        if (toolProfile == null || toolProfile.isBlank()) {
            return defaultProfile;
        }
        try {
            // Accept strings like "basic_chat", "BASIC_CHAT", "basic chat" etc.
            String normalized = toolProfile
                    .trim()
                    .replace(' ', '_')
                    .toUpperCase();
            return ToolProfile.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return defaultProfile;
        }
    }

    public record ResolvedSession(String id, boolean temporary) { }
}
