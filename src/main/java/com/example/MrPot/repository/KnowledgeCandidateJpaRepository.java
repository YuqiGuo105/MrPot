package com.example.MrPot.repository;

import com.example.MrPot.model.KnowledgeCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeCandidateJpaRepository extends JpaRepository<KnowledgeCandidateEntity, UUID> {

    Optional<KnowledgeCandidateEntity> findByDedupeKey(String dedupeKey);

    @Query("select c.id from KnowledgeCandidateEntity c where c.dedupeKey = :dedupeKey")
    Optional<UUID> findIdByDedupeKey(String dedupeKey);
}
