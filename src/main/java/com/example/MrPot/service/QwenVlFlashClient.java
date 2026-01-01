package com.example.MrPot.service;

import com.example.MrPot.service.dto.DocumentUnderstandingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minimal client for calling Qwen3-VL-Flash over the OpenAI-compatible endpoint.
 * Supports either public URLs or Base64 data URLs for OCR / document understanding.
 */
@Service
public class QwenVlFlashClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public QwenVlFlashClient(
            @Value("${qwen.endpoint}") String endpoint,
            @Value("${qwen.api-key}") String apiKey,
            ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }

    @Value("${qwen.model:qwen3-vl-flash}")
    private String model;

    @Value("${qwen.enable-thinking:false}")
    private boolean enableThinking;

    @Value("${qwen.temperature:0.1}")
    private double temperature;

    @Value("${qwen.max-tokens:2048}")
    private int maxTokens;

    /**
     * OCR using public URL (recommended for large images or when you already have a public link).
     */
    public Mono<String> ocrByPublicUrl(String imageUrl, String userPrompt) {
        return postAndExtract(buildRequestBody(imageUrl, normalizePrompt(userPrompt)));
    }

    /**
     * OCR + keyword extraction using public URL (recommended for large images or existing public links).
     */
    public Mono<DocumentUnderstandingResult> understandPublicUrlWithKeywords(String imageUrl, String userPrompt) {
        String prompt = normalizePromptWithKeywords(userPrompt);
        return postAndExtract(buildRequestBody(imageUrl, prompt))
                .map(this::parseDocumentResult);
    }

    /**
     * OCR using Base64 data URL (recommended for small images < 7MB).
     */
    public Mono<String> ocrByBase64(byte[] imageBytes, String mimeType, String userPrompt) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        return postAndExtract(buildRequestBody(dataUrl, normalizePrompt(userPrompt)));
    }

    /**
     * Understand uploaded images (Base64) and extract keywords along with the text content.
     * Returns a structured result so callers don't have to parse model output themselves.
     */
    public Mono<DocumentUnderstandingResult> understandUploadWithKeywords(byte[] imageBytes, String mimeType, String userPrompt) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;
        String prompt = normalizePromptWithKeywords(userPrompt);

        return postAndExtract(buildRequestBody(dataUrl, prompt))
                .map(this::parseDocumentResult);
    }

    private Mono<String> postAndExtract(Map<String, Object> body) {
        return webClient.post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.at("/choices/0/message/content").asText(""))
                .onErrorResume(e -> Mono.error(new RuntimeException("Qwen-VL call failed: " + e.getMessage(), e)));
    }

    private String normalizePrompt(String userPrompt) {
        if (userPrompt != null && !userPrompt.isBlank()) {
            return userPrompt.trim();
        }
        // For document OCR/parsing, Qwen docs recommend prompts like "qwenvl markdown/html"
        // but if you only want plain text, ask for plain text explicitly.
        return "Please perform OCR / document understanding on the image. Return only the key content as plain text, preserving original paragraphs and line breaks, with no commentary or formatting beyond the source text.";
    }

    private String normalizePromptWithKeywords(String userPrompt) {
        String basePrompt = normalizePrompt(userPrompt);
        return basePrompt + "\nAlso extract 3-8 concise keywords. Respond with JSON only: {\"text\":\"...\",\"keywords\":[\"k1\",...]}.";
    }

    private Map<String, Object> buildRequestBody(String imageUrl, String prompt) {
        return Map.of(
                "model", model,
                "temperature", temperature,
                "max_tokens", maxTokens,
                // Non-standard param supported by Qwen3-VL hybrid thinking models
                "enable_thinking", enableThinking,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)),
                                Map.of("type", "text", "text", prompt)
                        )
                ))
        );
    }

    private DocumentUnderstandingResult parseDocumentResult(String content) {
        try {
            return objectMapper.readValue(content, DocumentUnderstandingResult.class);
        } catch (IOException ignore) {
            // Model may return plain text; fall back to heuristic keyword extraction.
            return DocumentUnderstandingResult.builder()
                    .text(content)
                    .keywords(extractKeywords(content))
                    .build();
        }
    }

    private List<String> extractKeywords(String content) {
        Set<String> unique = new LinkedHashSet<>();
        for (String token : content.split("\\W+")) {
            String normalized = token.trim();
            if (normalized.length() >= 2) {
                unique.add(normalized);
            }
            if (unique.size() >= 8) {
                break;
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        return unique.stream().limit(8).collect(Collectors.toList());
    }
}
