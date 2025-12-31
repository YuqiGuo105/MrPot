package com.example.MrPot.model;

import java.util.List;

public record RagRunEvent(
        long ts,
        String sessionId,
        String model,
        int topK,
        double minScore,
        boolean outOfScope,
        Integer latencyMs,
        String error,

        // searchable text
        String question,
        String answer,
        String prompt,

        // retrieval hints
        Double topScore,
        List<DocHit> hits
) {
    public record DocHit(String id, String type, double score, String preview) {}

    // Build a compact event (avoid huge payload)
    public static RagRunEvent from(
            String sessionId,
            String question,
            String model,
            int topK,
            double minScore,
            String promptText,
            String answerText,
            Integer latencyMs,
            boolean outOfScope,
            String error,
            RagRetrievalResult retrieval
    ) {
        double tscore = 0.0;
        if (retrieval != null && retrieval.documents() != null) {
            tscore = retrieval.documents().stream().mapToDouble(ScoredDocument::score).max().orElse(0.0);
        }

        List<DocHit> hits = List.of();
        if (retrieval != null && retrieval.documents() != null) {
            hits = retrieval.documents().stream()
                    .limit(3)
                    .map(sd -> {
                        var d = sd.document();
                        String content = (d == null || d.getContent() == null) ? "" : d.getContent();
                        String preview = content.length() > 220 ? content.substring(0, 220) + "..." : content;
                        return new DocHit(String.valueOf(d.getId()), d.getDocType(), sd.score(), preview);
                    })
                    .toList();
        }

        return new RagRunEvent(
                System.currentTimeMillis(),
                sessionId,
                model,
                topK,
                minScore,
                outOfScope,
                latencyMs,
                error,
                question,
                answerText,
                truncate(promptText, 6000),
                tscore,
                hits
        );
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...";
    }
}

