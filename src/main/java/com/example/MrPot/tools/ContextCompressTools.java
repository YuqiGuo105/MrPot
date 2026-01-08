package com.example.MrPot.tools;

import com.example.MrPot.model.RagAnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ContextCompressTools {

    private static final int MAX_EVIDENCE = 5000;
    private static final int MAX_BULLETS = 8;
    private static final String SYSTEM_PROMPT = "Compress evidence into concise, grounded bullet claims with provenance.";

    private final Map<String, ChatClient> chatClients;

    public record ContextCompressResult(String summary) {}

    @Tool(name = "context_compress", description = "Compress evidence into concise bullet claims (include doc ids when available).")
    public ContextCompressResult compress(String question, String retrievalContext, String fileText) {
        String safeRetrieval = truncate(retrievalContext, MAX_EVIDENCE);
        String safeFileText = truncate(fileText, MAX_EVIDENCE);

        String ask = "Question: " + question + "\n" +
                "Evidence from KB:\n" + safeRetrieval + "\n" +
                (safeFileText.isBlank() ? "" : ("Evidence from files:\n" + safeFileText + "\n")) +
                "Return up to " + MAX_BULLETS + " bullet claims. Format: '- [doc:ID] claim'. If no id, omit bracket.";

        try {
            String content = resolveClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(ask)
                    .call()
                    .content();
            return new ContextCompressResult(content);
        } catch (Exception ex) {
            return new ContextCompressResult("context_compress_failed: " + safeMsg(ex));
        }
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
