package com.example.MrPot.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "rag_runs", schema = "public")
public class RagRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Lob
    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "top_k", nullable = false)
    private int topK;

    @Column(name = "min_score", nullable = false)
    private double minScore;

    @Lob
    @Column(name = "prompt_text")
    private String promptText;

    @Lob
    @Column(name = "answer_text")
    private String answerText;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "out_of_scope", nullable = false)
    private boolean outOfScope;

    @Lob
    @Column(name = "error")
    private String error;

    @Column(name = "prompt_sha256", length = 64)
    private String promptSha256;

    @Column(name = "answer_sha256", length = 64)
    private String answerSha256;
}
