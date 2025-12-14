package com.example.MrPot.model;

import com.example.MrPot.model.KbDocument;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "rag_run_docs",
        schema = "public",
        uniqueConstraints = @UniqueConstraint(name = "uq_rag_run_docs_run_rank", columnNames = {"run_id", "rank"})
)
public class RagRunDocEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RagRunEntity run;

    @Column(name = "kb_document_id", nullable = false)
    private Long kbDocumentId;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "score", nullable = false)
    private double score;
}

