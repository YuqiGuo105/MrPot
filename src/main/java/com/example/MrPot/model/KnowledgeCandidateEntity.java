package com.example.MrPot.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        schema="public",
        name = "knowledge_candidate",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_kc_dedupe_key", columnNames = "dedupe_key")
        }
)
public class KnowledgeCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dedupe_key", nullable = false, updatable = false, length = 128)
    private String dedupeKey;

    @Column(nullable = false, length = 32)
    private String status;   // e.g. NEW / APPROVED / REJECTED

    @Column(nullable = false, length = 32)
    private String type;     // e.g. QA / DOC / NOTE

    @Column(name = "session_id")
    private String sessionId;

    private String model;

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "min_score")
    private Double minScore;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "out_of_scope")
    private Boolean outOfScope;

    @Column(columnDefinition = "text")
    private String error;

    @Column(columnDefinition = "text")
    private String question;

    @Column(columnDefinition = "text")
    private String answer;

    // --- jsonb fields ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_steps", columnDefinition = "jsonb")
    private JsonNode keySteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points", columnDefinition = "jsonb")
    private JsonNode keyPoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode meta;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

