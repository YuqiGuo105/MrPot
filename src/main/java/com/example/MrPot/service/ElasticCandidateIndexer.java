package com.example.MrPot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ElasticCandidateIndexer {

    private final WebClient elasticWebClient;

    @Value("${app.candidate.es-index:mrpot_candidates}")
    private String indexName;

    public void upsertAsync(String docId, Map<String, Object> doc) {
        elasticWebClient.put()
                .uri("/{index}/_doc/{id}", indexName, docId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(doc)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> {
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
