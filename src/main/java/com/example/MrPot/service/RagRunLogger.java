package com.example.MrPot.service;


import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.RagRunDocEntity;
import com.example.MrPot.model.RagRunEntity;
import com.example.MrPot.model.ScoredDocument;
import com.example.MrPot.repository.RagRunDocRepository;
import com.example.MrPot.repository.RagRunRepository;
import com.example.MrPot.util.Sha256;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagRunLogger {

    private final RagRunRepository runRepo;
    private final RagRunDocRepository docRepo;

    @Transactional
    public Long logOnce(
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
        // 1) Insert run row
        RagRunEntity run = RagRunEntity.builder()
                .sessionId(sessionId)
                .question(question)
                .model(model)
                .topK(topK)
                .minScore(minScore)
                .promptText(promptText)
                .answerText(answerText)
                .latencyMs(latencyMs)
                .outOfScope(outOfScope)
                .error(error)
                .promptSha256(Sha256.hex(promptText))
                .answerSha256(Sha256.hex(answerText))
                .build();

        run = runRepo.save(run);

        // 2) Insert scored docs (supporting documents)
        if (retrieval != null && retrieval.documents() != null && !retrieval.documents().isEmpty()) {
            List<RagRunDocEntity> rows = new ArrayList<>();
            int rank = 1;
            for (ScoredDocument sd : retrieval.documents()) {
                Long kbId = (sd.document() == null ? null : sd.document().getId());
                if (kbId == null) {
                    // Skip rows without a KB id to avoid DB constraint issues
                    continue;
                }

                rows.add(RagRunDocEntity.builder()
                        .run(run)
                        .kbDocumentId(kbId)
                        .rank(rank++)
                        .score(sd.score())
                        .build());
            }
            docRepo.saveAll(rows);
        }

        return run.getId();
    }

    @Transactional
    public Long startStreamRun(
            String sessionId,
            String question,
            String model,
            int topK,
            double minScore,
            String promptText,
            boolean outOfScope,
            RagRetrievalResult retrieval
    ) {
        // Insert run first (answer comes later)
        RagRunEntity run = RagRunEntity.builder()
                .sessionId(sessionId)
                .question(question)
                .model(model)
                .topK(topK)
                .minScore(minScore)
                .promptText(promptText)
                .outOfScope(outOfScope)
                .promptSha256(Sha256.hex(promptText))
                .build();

        run = runRepo.save(run);

        // You can save docs now or at finish; saving now is simplest and safe
        if (retrieval != null && retrieval.documents() != null && !retrieval.documents().isEmpty()) {
            List<RagRunDocEntity> rows = new ArrayList<>();
            int rank = 1;
            for (ScoredDocument sd : retrieval.documents()) {
                Long kbId = (sd.document() == null ? null : sd.document().getId());
                if (kbId == null) {
                    // Skip rows without a KB id to avoid DB constraint issues
                    continue;
                }

                rows.add(RagRunDocEntity.builder()
                        .run(run)
                        .kbDocumentId(kbId)
                        .rank(rank++)
                        .score(sd.score())
                        .build());
            }
            docRepo.saveAll(rows);
        }

        return run.getId();
    }

    @Transactional
    public void finishStreamRun(Long runId, String answerText, Integer latencyMs, String error) {
        RagRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalStateException("run not found: " + runId));

        // Update only key fields
        run.setAnswerText(answerText);
        run.setLatencyMs(latencyMs);
        run.setError(error);
        run.setAnswerSha256(Sha256.hex(answerText));

        runRepo.save(run);
    }
}

