package com.example.MrPot.tools;

import com.example.MrPot.model.RagAnswerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ScopeGuardTools {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SYSTEM_PROMPT = "You are a scope guard for Mr Pot (Yuqi's assistant)";

    private final Map<String, ChatClient> chatClients;

    public record ScopeGuardResult(boolean scoped, String reason, String rewriteHint) {
        public static ScopeGuardResult scopedDefault() {
            return new ScopeGuardResult(true, "assume in scope", "");
        }
    }

    @Tool(name = "scope_guard", description = "Check whether the question is in-scope for Yuqi-related answers.")
    public ScopeGuardResult guard(String question) {
        if (question == null || question.isBlank()) {
            return new ScopeGuardResult(false, "empty question", "");
        }

        String ask = "Question: " + question + "\n" +
                "Return JSON with keys scoped (true/false), reason, rewrite_hint (optional narrower query).";
        try {
            String content = resolveClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(ask)
                    .call()
                    .content();

            ScopeGuardResult parsed = tryParse(content);
            if (parsed != null) return parsed;

            return fallbackParse(content);
        } catch (Exception ex) {
            return new ScopeGuardResult(true, "guard_failed: " + safeMsg(ex), "");
        }
    }

    private ScopeGuardResult tryParse(String content) {
        try {
            JsonNode root = OM.readTree(content);
            boolean scoped = root.path("scoped").asBoolean(true);
            String reason = root.path("reason").asText("");
            String rewriteHint = root.path("rewrite_hint").asText("");
            if (rewriteHint.isBlank()) {
                rewriteHint = root.path("rewriteHint").asText("");
            }
            return new ScopeGuardResult(scoped, reason, rewriteHint);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ScopeGuardResult fallbackParse(String content) {
        String lower = Optional.ofNullable(content).orElse("").toLowerCase(Locale.ROOT);
        boolean scoped = !(lower.contains("not") && lower.contains("yuqi"));
        return new ScopeGuardResult(scoped, content, "");
    }

    private ChatClient resolveClient() {
        if (chatClients.containsKey(RagAnswerRequest.DEFAULT_MODEL + "ChatClient")) {
            return chatClients.get(RagAnswerRequest.DEFAULT_MODEL + "ChatClient");
        }
        if (chatClients.containsKey(RagAnswerRequest.DEFAULT_MODEL)) {
            return chatClients.get(RagAnswerRequest.DEFAULT_MODEL);
        }
        return chatClients.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChatClient beans are available"));
    }

    private static String safeMsg(Throwable ex) {
        if (ex == null) return "";
        String m = String.valueOf(ex.getMessage());
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 300) m = m.substring(0, 300) + "...";
        return m;
    }
}
