package com.example.MrPot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ElasticCandidateIndexer {

    private static final Logger log = LoggerFactory.getLogger(ElasticCandidateIndexer.class);

    private final WebClient elasticWebClient;

    @Value("${app.candidate.es-index:mrpot_candidates}")
    private String indexName;

    @Value("${app.candidate.es-sync:false}")
    private boolean esSync; // debug 时可以设 true

    public void upsert(String docId, Map<String, Object> doc) {
        Mono<String> mono = elasticWebClient.put()
                .uri("/{index}/_doc/{id}", indexName, docId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(doc)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(6))
                .doOnSuccess(resp -> log.info("ES indexed: index={} id={}", indexName, docId))
                .doOnError(e -> log.warn("ES index failed: index={} id={} err={}", indexName, docId, e.toString()));

        if (esSync) {
            // 调试：同步 block，方便你在 debug 里看到确实写成功/失败
            mono.onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(6));
        } else {
            // 生产：异步
            mono.subscribeOn(Schedulers.boundedElastic()).subscribe();
        }
    }
}
