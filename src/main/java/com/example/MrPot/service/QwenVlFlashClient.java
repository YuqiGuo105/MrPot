package com.example.MrPot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.*;

@Component
@RequiredArgsConstructor
public class QwenVlFlashClient {

    private final @Qualifier("qwenChatClient") ChatClient qwenChatClient;

    /**
     * OpenAI-compatible input shape:
     * messages: [{role: "system"|"user"|"assistant", content: "..." | [{type:"text",text:"..."},{type:"image_url",image_url:{url:"..."}}]}]
     *
     * extraBody: runtime overrides like temperature/top_p/top_k/max_tokens/stop, etc.
     */
    public String chatCompletions(String model,
                                  List<Map<String, Object>> messages,
                                  Map<String, Object> extraBody) {

        List<Message> springMessages = toSpringMessages(messages);
        ChatOptions options = toChatOptions(model, extraBody);

        return qwenChatClient.prompt()
                .messages(springMessages)
                .options(options)
                .call()
                .content();
    }

    private static List<Message> toSpringMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        List<Message> out = new ArrayList<>();

        for (Map<String, Object> m : messages) {
            if (m == null) continue;

            String role = Objects.toString(m.get("role"), "user").trim();
            Object content = m.get("content");

            // Plain string
            if (content instanceof String s) {
                out.add(toTextMessage(role, s));
                continue;
            }

            // OpenAI multimodal array:
            // [{"type":"text","text":"..."},{"type":"image_url","image_url":{"url":"..."}}]
            if (content instanceof List<?> parts) {
                ParsedParts parsed = parseOpenAiParts(parts);

                if ("system".equals(role)) {
                    out.add(new SystemMessage(parsed.text()));
                } else if ("assistant".equals(role)) {
                    out.add(new AssistantMessage(parsed.text()));
                } else {
                    // user with optional media
                    out.add(UserMessage.builder()
                            .text(parsed.text())
                            .media(parsed.media())
                            .build());
                }
                continue;
            }

            // Fallback
            out.add(toTextMessage(role, String.valueOf(content)));
        }

        return out;
    }

    private static Message toTextMessage(String role, String text) {
        return switch (role) {
            case "system" -> new SystemMessage(text);
            case "assistant" -> new AssistantMessage(text);
            default -> new UserMessage(text);
        };
    }

    private record ParsedParts(String text, List<Media> media) {}

    @SuppressWarnings("unchecked")
    private static ParsedParts parseOpenAiParts(List<?> parts) {
        StringBuilder text = new StringBuilder();
        List<Media> media = new ArrayList<>();

        for (Object p : parts) {
            if (!(p instanceof Map<?, ?> pm)) continue;

            String type = Objects.toString(pm.get("type"), "");
            if ("text".equals(type)) {
                String t = Objects.toString(pm.get("text"), "");
                if (!t.isBlank()) {
                    if (!text.isEmpty()) text.append("\n");
                    text.append(t);
                }
            } else if ("image_url".equals(type)) {
                String url = extractImageUrl(pm.get("image_url"));
                if (url != null && !url.isBlank()) {
                    MimeType mt = guessImageMimeType(url);
                    media.add(new Media(mt, URI.create(url)));
                }
            }
        }

        return new ParsedParts(text.toString(), media);
    }

    private static String extractImageUrl(Object imageUrlObj) {
        if (imageUrlObj == null) return null;

        // sometimes it's directly a string
        if (imageUrlObj instanceof String s) return s;

        // common: {"url":"..."}
        if (imageUrlObj instanceof Map<?, ?> m) {
            Object u = m.get("url");
            return (u == null) ? null : String.valueOf(u);
        }

        return String.valueOf(imageUrlObj);
    }

    private static MimeType guessImageMimeType(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.endsWith(".png")) return Media.Format.IMAGE_PNG;
        if (u.endsWith(".webp")) return Media.Format.IMAGE_WEBP;
        if (u.endsWith(".gif")) return Media.Format.IMAGE_GIF;
        return Media.Format.IMAGE_JPEG;
    }

    /**
     * Portable options so you can set max_tokens even if DashScopeChatOptionsBuilder
     * doesn't have maxTokens(...) in your version.
     */
    private static ChatOptions toChatOptions(String model, Map<String, Object> extraBody) {
        ChatOptions.Builder b = ChatOptions.builder();

        if (model != null && !model.isBlank()) {
            b.model(model);
        }

        if (extraBody == null || extraBody.isEmpty()) {
            return b.build();
        }

        // OpenAI-style keys -> ChatOptions
        if (extraBody.get("temperature") instanceof Number n) b.temperature(n.doubleValue());
        if (extraBody.get("top_p") instanceof Number n) b.topP(n.doubleValue());
        if (extraBody.get("top_k") instanceof Number n) b.topK(n.intValue());
        if (extraBody.get("max_tokens") instanceof Number n) b.maxTokens(n.intValue());

        // Optional extras (if you pass them)
        if (extraBody.get("presence_penalty") instanceof Number n) b.presencePenalty(n.doubleValue());
        if (extraBody.get("frequency_penalty") instanceof Number n) b.frequencyPenalty(n.doubleValue());
        if (extraBody.get("stop") instanceof List<?> stopList) {
            List<String> stops = stopList.stream().map(String::valueOf).toList();
            b.stopSequences(stops);
        }

        return b.build();
    }
}
