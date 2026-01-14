package com.example.MrPot.service;

import com.example.MrPot.model.KnowledgeCandidateEntity;
import com.example.MrPot.repository.KnowledgeCandidateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CandidateIngestionJpaService {

    private final KnowledgeCandidateRepository repo;

    @Transactional
    public UUID insertIgnoreConflict(
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
            JsonNode keySteps,
            JsonNode keyPoints,
            JsonNode evidence,
            JsonNode meta
    ) {
        try {
            KnowledgeCandidateEntity e = KnowledgeCandidateEntity.builder()
                    .dedupeKey(dedupeKey)
                    .status(status)
                    .type(type)
                    .sessionId(sessionId)
                    .model(model)
                    .topK(topK)
                    .minScore(minScore)
                    .latencyMs(latencyMs)
                    .outOfScope(outOfScope)
                    .error(error)
                    .question(question)
                    .answer(answer)
                    .keySteps(keySteps)
                    .keyPoints(keyPoints)
                    .evidence(evidence)
                    .meta(meta)
                    .build();

            repo.saveAndFlush(e);
            return e.getId();
        } catch (DataIntegrityViolationException dup) {
            return repo.findByDedupeKey(dedupeKey)
                    .map(KnowledgeCandidateEntity::getId)
                    .orElse(null);
        }
    }
}

