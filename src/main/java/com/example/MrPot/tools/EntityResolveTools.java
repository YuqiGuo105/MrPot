package com.example.MrPot.tools;

import com.example.MrPot.model.RagAnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EntityResolveTools {

    private static final int MAX_TERMS = 8;
    private static final int MAX_FILE_PREVIEW = 900;
    private static final String SYSTEM_PROMPT = "Extract concise search terms (keywords, entities) that best represent the user's ask.";

    private final Map<String, ChatClient> chatClients;

    public record EntityResolveResult(List<String> terms, String raw) {
    }

    @Tool(name = "entity_resolve", description = "Extract up to 8 key terms from the question (and optional file text) for better recall.")
    public EntityResolveResult resolve(String question, String fileText, Integer maxTerms) {
        int limit = maxTerms == null ? MAX_TERMS : Math.min(Math.max(1, maxTerms), MAX_TERMS);
        String safeFileText = truncate(fileText, MAX_FILE_PREVIEW);

        String ask = "Question: " + question + "\n" +
                (safeFileText.isBlank() ? "" : ("File text:\n" + safeFileText + "\n")) +
                "Return a comma-separated list of at most " + limit + " short terms.";

        try {
            String content = resolveClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(ask)
                    .call()
                    .content();

            return new EntityResolveResult(parseTerms(content, limit), content);
        } catch (Exception ex) {
            return new EntityResolveResult(List.of(), "entity_resolve_failed: " + safeMsg(ex));
        }
    }

    private List<String> parseTerms(String content, int limit) {
        if (content == null || content.isBlank()) return List.of();
        String normalized = content
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('"', ' ');

        String[] parts = normalized.split("[,\n]\s*");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            String term = p.trim();
            if (term.isBlank()) continue;
            out.add(term);
            if (out.size() >= limit) break;
        }
        return new ArrayList<>(out);
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

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "...";
    }

    private static String safeMsg(Throwable ex) {
        if (ex == null) return "";
        String m = String.valueOf(ex.getMessage());
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 300) m = m.substring(0, 300) + "...";
        return m;
    }
}
