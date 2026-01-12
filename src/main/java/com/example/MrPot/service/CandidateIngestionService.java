package com.example.MrPot.service;

import com.example.MrPot.service.KeyInfoExtractor.KeyInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CandidateIngestionService {

    private final TempCandidateRepository tempRepo;
    private final ElasticCandidateIndexer esIndexer;
    private final ObjectMapper objectMapper;

    @Value("${app.candidate.store-to-es:true}")
    private boolean storeToEs;

    public void ingest(
            String sessionId,
            String model,
            Integer topK,
            Double minScore,
            Integer latencyMs,
            Boolean outOfScope,
            String error,
            String question,
            String answer,
            Object retrievalDocs
    ) {
        String dedupeKey = sha256Base64(safe(sessionId) + "|" + safe(question) + "|" + safe(model) + "|" + safe(error));

        KeyInfo keyInfo = KeyInfoExtractor.extract(answer);
        String keyStepsJson = toJson(keyInfo.steps());
        String keyPointsJson = toJson(keyInfo.points());
        String evidenceJson = toJson(Map.of("retrieval_docs", retrievalDocs));

        String canonText = "Q: " + safe(question) + "\nA: " + safe(answer);
        String metaJson = toJson(Map.of(
                "canon_text", canonText,
                "ts", Instant.now().toString()
        ));

        UUID candidateId = tempRepo.insertIgnoreConflict(new TempCandidateRepository.TempRow(
                dedupeKey,
                "NEW",
                "QA",
                sessionId,
                model,
                topK,
                minScore,
                latencyMs,
                outOfScope,
                error,
                question,
                answer,
                keyStepsJson,
                keyPointsJson,
                evidenceJson,
                metaJson
        ));

        if (storeToEs) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("@timestamp", Instant.now().toString());
            doc.put("candidate_id", candidateId == null ? null : candidateId.toString());
            doc.put("dedupe_key", dedupeKey);
            doc.put("status", "NEW");
            doc.put("type", "QA");

            doc.put("session_id", sessionId);
            doc.put("model", model);
            doc.put("top_k", topK);
            doc.put("min_score", minScore);
            doc.put("latency_ms", latencyMs);
            doc.put("out_of_scope", outOfScope);

            doc.put("question", question);
            doc.put("answer", answer);
            doc.put("canon_text", canonText);

            doc.put("key_steps", keyInfo.steps());
            doc.put("key_points", keyInfo.points());

            esIndexer.upsertAsync(dedupeKey, doc);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Base64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
