package com.example.MrPot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TempCandidateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UUID insertIgnoreConflict(TempRow row) {
        String sql = """
            INSERT INTO temp.knowledge_candidate
            (dedupe_key, status, type, session_id, model, top_k, min_score, latency_ms, out_of_scope, error,
             question, answer, key_steps, key_points, evidence, meta)
            VALUES
            (:dedupe_key, :status, :type, :session_id, :model, :top_k, :min_score, :latency_ms, :out_of_scope, :error,
             :question, :answer, CAST(:key_steps AS jsonb), CAST(:key_points AS jsonb), CAST(:evidence AS jsonb), CAST(:meta AS jsonb))
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING id
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("dedupe_key", row.dedupeKey());
        params.put("status", row.status());
        params.put("type", row.type());
        params.put("session_id", row.sessionId());
        params.put("model", row.model());
        params.put("top_k", row.topK());
        params.put("min_score", row.minScore());
        params.put("latency_ms", row.latencyMs());
        params.put("out_of_scope", row.outOfScope());
        params.put("error", row.error());
        params.put("question", row.question());
        params.put("answer", row.answer());
        params.put("key_steps", row.keyStepsJson());
        params.put("key_points", row.keyPointsJson());
        params.put("evidence", row.evidenceJson());
        params.put("meta", row.metaJson());

        return jdbc.query(sql, params, rs -> rs.next() ? (UUID) rs.getObject(1) : null);
    }

    public record TempRow(
            String dedupeKey,
            String status,
            String type,
            String sessionId,
            String model,
            Integer topK,
            Double minScore,
            Integer latencyMs,
            Boolean outOfScope,
            String error,
            String question,
            String answer,
            String keyStepsJson,
            String keyPointsJson,
            String evidenceJson,
            String metaJson
    ) {}
}
