package com.example.MrPot.service;

import com.example.MrPot.model.AttachmentContext;
import com.example.MrPot.model.ExtractedFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class AttachmentService {

    private static final int MAX_FILES = 3;
    private static final int MAX_KEY_INFO_INPUT_CHARS = 4500;
    private static final int MAX_ENTITY_COUNT = 12;
    private static final ObjectMapper OM = new ObjectMapper();

    private final RemoteFileService remote;
    private final FileExtractionService extractor;

    public AttachmentService(RemoteFileService remote, FileExtractionService extractor) {
        this.remote = remote;
        this.extractor = extractor;
    }

    public Mono<AttachmentContext> fetchAndExtract(List<String> urls, ChatClient visionClientOrNull, ChatClient keyInfoClient) {
        if (urls == null || urls.isEmpty()) return Mono.just(AttachmentContext.empty());

        return Flux.fromIterable(urls)
                .filter(u -> u != null && !u.isBlank())
                .take(MAX_FILES)
                .concatMap(url ->
                        remote.download(url)
                                .flatMap(df -> extractor.extract(df, visionClientOrNull))
                                .flatMap(f -> enrichWithKeyInfo(f, keyInfoClient))
                                // English comments: One file failure should not kill the whole batch.
                                .onErrorResume(ex -> Mono.just(failed(url, ex)))
                )
                .collectList()
                // English comments: keep combinedText empty; budget & selection happen in RagAnswerService
                .map(files -> new AttachmentContext(files, ""))
                .onErrorReturn(AttachmentContext.empty());
    }

    // English comments: fallback record for a failed file.
    private static ExtractedFile failed(String url, Throwable ex) {
        // NOTE: adjust constructor params to match your ExtractedFile record fields.
        // This matches your usage: uri(), filename(), mimeType(), sizeBytes(), extractedText(), error()
        return new ExtractedFile(
                URI.create(url),
                "file",
                "application/octet-stream",
                0L,
                "",
                String.valueOf(ex),
                null,
                List.of()
        );
    }

    private Mono<ExtractedFile> enrichWithKeyInfo(ExtractedFile file, ChatClient keyInfoClient) {
        if (file == null) return Mono.just(file);
        if (keyInfoClient == null) return Mono.just(file);
        if (file.error() != null && !file.error().isBlank()) return Mono.just(file);

        String text = Optional.ofNullable(file.extractedText()).orElse("").trim();
        if (text.isBlank()) return Mono.just(file);

        String clipped = text.length() > MAX_KEY_INFO_INPUT_CHARS
                ? text.substring(0, MAX_KEY_INFO_INPUT_CHARS)
                : text;

        String prompt = "Extract key details from the document text. Respond with compact JSON only matching this schema:\n" +
                "{\n" +
                "  \"title\": \"\",\n" +
                "  \"doc_type\": \"\",\n" +
                "  \"summary_bullets\": [\"...\"],\n" +
                "  \"key_facts\": [{\"fact\":\"...\",\"evidence\":\"...\"}],\n" +
                "  \"entities\": [{\"type\":\"person|org|date|amount|other\",\"value\":\"...\"}],\n" +
                "  \"action_items\": [{\"task\":\"...\",\"due_date\":\"\"}]\n" +
                "}\n" +
                "Rules: base everything on the provided text; keep evidence short; output valid JSON only.\n\n" +
                "CONTENT:\n" + clipped;

        return Mono.fromCallable(() -> {
                    String keyInfoJson = keyInfoClient.prompt()
                            .user(prompt)
                            .call()
                            .content();

                    List<String> entities = parseEntityValues(keyInfoJson, MAX_ENTITY_COUNT);
                    return file.withKeyInfo(keyInfoJson, entities);
                })
                .onErrorReturn(file);
    }

    private static List<String> parseEntityValues(String json, int max) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = OM.readTree(json);
            JsonNode entitiesNode = root.path("entities");
            if (!entitiesNode.isArray()) return List.of();

            Set<String> set = new LinkedHashSet<>();
            for (JsonNode n : (ArrayNode) entitiesNode) {
                if (!n.isObject()) continue;
                String v = n.path("value").asText("").trim();
                if (v.isEmpty()) continue;
                set.add(v);
                if (set.size() >= max) break;
            }
            return new ArrayList<>(set);
        } catch (Exception ex) {
            return List.of();
        }
    }
}
