package com.example.MrPot.service;

import com.example.MrPot.service.KeyInfoExtractor.KeyInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(CandidateIngestionService.class);

    private final TempCandidateRepository tempRepo;
    private final ObjectProvider<ElasticCandidateIndexer> esIndexerProvider;
    private final ObjectMapper objectMapper;

    @Value("${app.candidate.store-to-es:true}")
    private boolean storeToEs;

    /**
     * REQUIRES_NEW：避免外层回答流程（可能有事务）回滚导致你这里插入也被回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            Object retrievalDocs,
            ToolRunSummary toolSummary
    ) {
        String dedupeKey = sha256Base64(safe(sessionId) + "|" + safe(question) + "|" + safe(model) + "|" + safe(error));

        KeyInfo keyInfo = KeyInfoExtractor.extract(answer);

        // Map.of 遇到 null 会 NPE，这里用 LinkedHashMap 更稳
        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("retrieval_docs", retrievalDocs);
        if (toolSummary != null) {
            evidenceMap.put("tool_results", buildToolResults(toolSummary));
        }

        String keyStepsJson = toJson(keyInfo.steps());
        String keyPointsJson = toJson(keyInfo.points());
        String evidenceJson  = toJson(evidenceMap);

        String canonText = "Q: " + safe(question) + "\nA: " + safe(answer);
        String metaJson = toJson(Map.of(
                "canon_text", canonText,
                "ts", Instant.now().toString()
        ));

        UUID candidateId = null;
        try {
            log.info("DB insert start: dedupeKey={}", dedupeKey);
            candidateId = tempRepo.insertIgnoreConflict(new TempCandidateRepository.TempRow(
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
            log.info("DB insert done: candidateId={}", candidateId);
        } catch (Exception e) {
            // 关键：如果 DB 这里失败，你至少要知道原因
            log.warn("DB insert failed: dedupeKey={} err={}", dedupeKey, e.toString(), e);
            // DB 失败也可以继续写 ES（便于你在 Kibana 看失败样本）
        }

        ElasticCandidateIndexer esIndexer = esIndexerProvider.getIfAvailable();
        if (storeToEs && esIndexer != null) {
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
            doc.put("error", error);

            doc.put("question", question);
            doc.put("answer", answer);
            doc.put("canon_text", canonText);

            doc.put("key_steps", keyInfo.steps());
            doc.put("key_points", keyInfo.points());

            if (toolSummary != null) {
                doc.put("tool_results", buildToolResults(toolSummary));
            }

            log.info("ES upsert start: id={}", dedupeKey);
            esIndexer.upsert(dedupeKey, doc);
        } else {
            log.warn("ES index skipped: storeToEs={} esIndexerBean={}", storeToEs, (esIndexer != null));
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private Map<String, Object> buildToolResults(ToolRunSummary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (summary == null) return out;

        if (summary.keyInfo() != null && !summary.keyInfo().isEmpty()) {
            out.put("key_info", summary.keyInfo());
        }
        if (summary.entityTerms() != null && !summary.entityTerms().isEmpty()) {
            out.put("entity_terms", summary.entityTerms());
        }
        if (summary.scopeGuard() != null) {
            Map<String, Object> scopeGuard = new LinkedHashMap<>();
            scopeGuard.put("scoped", summary.scopeGuard().scoped());
            if (summary.scopeGuard().reason() != null) {
                scopeGuard.put("reason", summary.scopeGuard().reason());
            }
            if (summary.scopeGuard().rewriteHint() != null) {
                scopeGuard.put("rewriteHint", summary.scopeGuard().rewriteHint());
            }
            out.put("scope_guard", scopeGuard);
        }
        if (summary.privacyStrictResult() != null) {
            Map<String, Object> privacyStrict = new LinkedHashMap<>();
            privacyStrict.put("out_of_scope", summary.privacyStrictResult().outOfScope());
            privacyStrict.put("reason", summary.privacyStrictResult().reason());
            privacyStrict.put("signals", summary.privacyStrictResult().signals());
            out.put("yuqi_privacy_strict", privacyStrict);
        }
        if (summary.intentResult() != null) {
            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("intent", summary.intentResult().intent());
            intent.put("signals", summary.intentResult().signals());
            out.put("intent", intent);
        }
        if (summary.keywordResult() != null) {
            Map<String, Object> keywords = new LinkedHashMap<>();
            keywords.put("items", summary.keywordResult().keywords());
            keywords.put("source", summary.keywordResult().source());
            out.put("keywords", keywords);
        }
        if (summary.evidenceGap() != null) {
            Map<String, Object> evidenceGap = new LinkedHashMap<>();
            evidenceGap.put("status", summary.evidenceGap().status());
            evidenceGap.put("missing_facts", summary.evidenceGap().missingFacts());
            evidenceGap.put("follow_ups", summary.evidenceGap().followUps());
            out.put("evidence_gap", evidenceGap);
        }
        if (summary.answerOutline() != null) {
            Map<String, Object> answerOutline = new LinkedHashMap<>();
            answerOutline.put("style", summary.answerOutline().style());
            answerOutline.put("sections", summary.answerOutline().sections());
            out.put("answer_outline", answerOutline);
        }
        if (summary.assumptionResult() != null) {
            Map<String, Object> assumptionCheck = new LinkedHashMap<>();
            assumptionCheck.put("risk", summary.assumptionResult().riskLevel());
            assumptionCheck.put("assumptions", summary.assumptionResult().assumptions());
            out.put("assumption_check", assumptionCheck);
        }
        if (summary.actionPlan() != null) {
            Map<String, Object> actionPlan = new LinkedHashMap<>();
            actionPlan.put("style", summary.actionPlan().style());
            actionPlan.put("steps", summary.actionPlan().steps());
            out.put("action_plan", actionPlan);
        }
        if (summary.trackCorrectResult() != null) {
            Map<String, Object> trackCorrect = new LinkedHashMap<>();
            trackCorrect.put("on_track", summary.trackCorrectResult().onTrack());
            trackCorrect.put("status", summary.trackCorrectResult().status());
            trackCorrect.put("hint", summary.trackCorrectResult().hint());
            out.put("track_correct", trackCorrect);
        }

        return out;
    }

    private static String safe(String value) { return value == null ? "" : value; }

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
