package com.example.MrPot.service;

import com.example.MrPot.model.RagRunEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Async HTTP publisher to log-search service
@Service
public class HttpLogIngestionClient implements LogIngestionClient {

    private static final ObjectMapper OM = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Value("${mrpot.log.ingest.enabled:true}")
    private boolean enabled;

    @Value("${mrpot.log.ingest.url:http://log-search:8081/api/logs/ingest}")
    private String ingestUrl;

    @Override
    public void ingestAsync(RagRunEvent event) {
        if (!enabled || event == null) return;

        try {
            String body = OM.writeValueAsString(event);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ingestUrl))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> null); // never throw
        } catch (Exception ignore) {
            // never throw
        }
    }
}
