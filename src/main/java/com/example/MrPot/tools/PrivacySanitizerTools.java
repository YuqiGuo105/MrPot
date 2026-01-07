package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PrivacySanitizerTools {

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{1,3}[-\\s]?)?(\\d{3,4}[-\\s]?)?\\d{6,12}");
    private static final Pattern TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{20,}\\b");

    public record SanitizedResult(String sanitized, Map<String, Integer> hits) {}

    @Tool(name = "privacy_sanitize", description = "Redact emails, phone numbers, and tokens from evidence.")
    public SanitizedResult sanitize(String text) {
        if (text == null || text.isBlank()) {
            return new SanitizedResult("", Map.of("email", 0, "phone", 0, "token", 0));
        }

        Map<String, Integer> hits = new LinkedHashMap<>();
        hits.put("email", 0);
        hits.put("phone", 0);
        hits.put("token", 0);

        String sanitized = replaceAndCount(text, EMAIL, "<REDACTED_EMAIL>", hits, "email");
        sanitized = replaceAndCount(sanitized, PHONE, "<REDACTED_PHONE>", hits, "phone");
        sanitized = replaceAndCount(sanitized, TOKEN, "<REDACTED_TOKEN>", hits, "token");

        return new SanitizedResult(sanitized, hits);
    }

    private String replaceAndCount(String text, Pattern pattern, String replacement, Map<String, Integer> hits, String key) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        hits.put(key, hits.getOrDefault(key, 0) + count);
        return sb.toString();
    }
}
