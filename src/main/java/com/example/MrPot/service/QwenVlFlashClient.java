package com.example.MrPot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QwenVlFlashClient {

    private final String baseUrl;

    // spring.ai.dashscope.api-key
    private final String apiKey;

    // spring.ai.dashscope.chat.options.model (fallback: qwen-vl-plus)
    private final String defaultModel;

    // allow overriding by env if you want
    private final Duration timeout;

    private final WebClient client;

    public QwenVlFlashClient(
            @Value("${spring.ai.dashscope.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-vl-plus}") String defaultModel,
            @Value("${mrpot.qwen.timeout-seconds:60}") long timeoutSeconds
    ) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));

        this.client = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * messages: OpenAI-compatible:
     * [{role:"user", content:[{type:"image_url",image_url:{url:"https://..." or "data:image/..."}},{type:"text",text:"..."}]}]
     *
     * extraBody: {temperature, top_p, max_tokens, ...} (optional)
     */
    public String chatCompletions(String model,
                                  List<Map<String, Object>> messages,
                                  Map<String, Object> extraBody) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing DashScope API key: set spring.ai.dashscope.api-key (or env DASHSCOPE_API_KEY if you map it)");
        }

        String useModel = (model == null || model.isBlank()) ? defaultModel : model;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("messages", messages);
        body.put("stream", false);

        // allow runtime overrides (keep small set to avoid 400)
        if (extraBody != null && !extraBody.isEmpty()) {
            copyIfPresent(extraBody, body, "temperature");
            copyIfPresent(extraBody, body, "top_p");
            copyIfPresent(extraBody, body, "max_tokens");
            copyIfPresent(extraBody, body, "presence_penalty");
            copyIfPresent(extraBody, body, "frequency_penalty");
            copyIfPresent(extraBody, body, "stop");
        }

        JsonNode resp = client.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class).flatMap(msg ->
                                Mono.error(new RuntimeException("DashScope error: " + msg))))
                .bodyToMono(JsonNode.class)
                .block(timeout);

        if (resp == null) return "";
        return resp.path("choices").path(0).path("message").path("content").asText("");
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String k) {
        if (from.containsKey(k) && from.get(k) != null) {
            to.put(k, from.get(k));
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
