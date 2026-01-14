package com.example.MrPot.service;

import com.example.MrPot.model.KnowledgeCandidateEntity;
import com.example.MrPot.repository.KnowledgeCandidateJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TempCandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(TempCandidateRepository.class);

    private final KnowledgeCandidateJpaRepository repo;
    private final ObjectMapper objectMapper;

    /**
     * 语义：如果 dedupe_key 不存在 -> 插入并返回新 id
     *      如果 dedupe_key 已存在 -> 不插入，返回已有 id（尽量返回；极端并发下可能返回 null）
     */
    @Transactional
    public UUID insertIgnoreConflict(TempRow row) {
        // 1) 先查（最便宜）
        UUID existing = repo.findIdByDedupeKey(row.dedupeKey()).orElse(null);
        if (existing != null) return existing;

        // 2) 尝试插入
        try {
            KnowledgeCandidateEntity entity = toEntity(row);
            return repo.saveAndFlush(entity).getId();
        } catch (DataIntegrityViolationException dup) {
            // 3) 并发场景：你刚准备插入，别人已插入 -> 再查一次拿 id
            UUID id = repo.findIdByDedupeKey(row.dedupeKey()).orElse(null);
            log.debug("insertIgnoreConflict hit duplicate, dedupeKey={}, id={}", row.dedupeKey(), id);
            return id;
        }
    }

    private KnowledgeCandidateEntity toEntity(TempRow r) {
        return KnowledgeCandidateEntity.builder()
                .dedupeKey(r.dedupeKey())
                .status(nz(r.status(), "NEW"))
                .type(nz(r.type(), "QA"))
                .sessionId(r.sessionId())
                .model(r.model())
                .topK(r.topK())
                .minScore(r.minScore())
                .latencyMs(Math.toIntExact(r.latencyMs()))
                .outOfScope(r.outOfScope())
                .error(r.error())
                .question(r.question())
                .answer(r.answer())
                .keySteps(parseJson(r.keyStepsJson()))
                .keyPoints(parseJson(r.keyPointsJson()))
                .evidence(parseJson(r.evidenceJson()))
                .meta(parseJson(r.metaJson()))
                .build();
    }

    private String nz(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // 你也可以选择 fallback：meta 里记录 raw 字符串；这里先简单丢弃避免写坏数据
            log.warn("Invalid JSON, ignored. json={}", json);
            return null;
        }
    }

    /**
     * 你在 RagAnswerService 里 new TempCandidateRepository.TempRow(...) 的参数顺序
     * 建议保持一致（下面就是一份“按你截图那行”设计的顺序）。
     */
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
