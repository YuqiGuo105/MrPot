package com.example.MrPot.model;

import com.example.MrPot.tools.ToolProfile;

import java.util.*;

/**
 * Request payload for generating an answer with RAG context.
 *
 * @param question     user question
 * @param sessionId    chat session id for memory separation
 * @param deepThinking whether to enable enhanced reasoning and helper steps
 * @param scopeMode    scope mode for privacy/scope checks
 * @param topK         optional override for retrieval topK
 * @param minScore     optional override for retrieval minScore
 * @param model        optional model name hint (e.g. "deepseek", "openai")
 * @param toolProfile  optional tool profile (e.g. "BASIC_CHAT", "ADMIN", "FULL")
 * @param fileUrls     optional external file URLs for attachment
 * @param visionModel  optional vision model key
 */
public record RagAnswerRequest(
        String question,
        String sessionId,
        Boolean deepThinking,
        ScopeMode scopeMode,
        Integer topK,
        Double minScore,
        String model,
        String toolProfile,
        List<String> fileUrls,
        String visionModel
) {
    private static final Set<String> models = Set.of("deepseek", "gemini", "openai");
    public static final String DEFAULT_MODEL = "deepseek";
    public static final ScopeMode DEFAULT_SCOPE_MODE = ScopeMode.PRIVACY_SAFE;

    public enum ScopeMode {
        PRIVACY_SAFE,
        YUQI_ONLY
    }

    public int resolveTopK(int defaultValue) {
        return topK == null || topK <= 0 ? defaultValue : topK;
    }

    public double resolveMinScore(double defaultValue) {
        return minScore == null ? defaultValue : minScore;
    }

    public boolean resolveDeepThinking(boolean defaultValue) {
        return deepThinking != null && deepThinking;
    }

    public ScopeMode resolveScopeMode() {
        return scopeMode == null ? DEFAULT_SCOPE_MODE : scopeMode;
    }

    public String resolveModel() {
        return (model == null || model.isBlank()
        || !models.contains(model)) ? DEFAULT_MODEL : model;
    }

    public List<String> resolveFileUrls(int maxFiles) {
        if (this.fileUrls == null) return List.of();
        int limit = Math.min(Math.max(0, maxFiles), 3);
        return this.fileUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    public String resolveVisionModelOrNull() {
        String key = normalizeModelKey(visionModel);
        return (key == null || !models.contains(key)) ? DEFAULT_MODEL : key;
    }

    private static String normalizeModelKey(String s) {
        if (s == null) return null;
        String key = s.trim().toLowerCase(Locale.ROOT);
        return key.isBlank() ? null : key;
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
