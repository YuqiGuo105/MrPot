package com.example.MrPot.repository;

import com.example.MrPot.model.KnowledgeCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeCandidateRepository extends JpaRepository<KnowledgeCandidateEntity, UUID> {
    Optional<KnowledgeCandidateEntity> findByDedupeKey(String dedupeKey);
}
