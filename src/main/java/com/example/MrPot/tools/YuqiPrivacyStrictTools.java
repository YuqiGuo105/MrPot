package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class YuqiPrivacyStrictTools {

    private static final Pattern YUQI_PATTERN = Pattern.compile(
            "(yuqi|\\u90ed\\u5b87\\u7426|\\u4e8e\\u742a|\\u90ed\\u745c\\u7426)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTACT_HINT = Pattern.compile(
            "(phone|email|mail|address|location|contact|wechat|whatsapp|telegram|linkedin|github|twitter|\\u7535\\u8bdd|\\u624b\\u673a|\\u90ae\\u7bb1|\\u5730\\u5740|\\u4f4d\\u7f6e|\\u8054\\u7cfb|\\u5fae\\u4fe1)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PRIVATE_HINT = Pattern.compile(
            "(private|personal|family|parents|spouse|wife|husband|girlfriend|boyfriend|salary|income|bank|id|passport|ssn|health|medical|\\u79c1\\u4eba|\\u9690\\u79c1|\\u5bb6\\u4eba|\\u7236\\u6bcd|\\u914d\\u5076|\\u5de5\\u8d44|\\u6536\\u5165|\\u8eab\\u4efd\\u8bc1|\\u62a4\\u7167|\\u5065\\u5eb7|\\u75c5\\u5386)",
            Pattern.CASE_INSENSITIVE
    );

    public record PrivacyStrictResult(boolean outOfScope, String reason, List<String> signals) {
        public static PrivacyStrictResult allowDefault() {
            return new PrivacyStrictResult(false, "no_yuqi_privacy_risk", List.of());
        }
    }

    @Tool(name = "yuqi_privacy_strict", description = "Flag Yuqi privacy-sensitive questions as out of scope during deep thinking.")
    public PrivacyStrictResult check(String question) {
        if (question == null || question.isBlank()) {
            return new PrivacyStrictResult(false, "empty_question", List.of());
        }

        String q = question.trim();
        if (!YUQI_PATTERN.matcher(q).find()) {
            return PrivacyStrictResult.allowDefault();
        }

        String lower = q.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();
        if (CONTACT_HINT.matcher(lower).find()) {
            signals.add("contact_request");
        }
        if (PRIVATE_HINT.matcher(lower).find()) {
            signals.add("private_detail");
        }

        if (signals.isEmpty()) {
            return PrivacyStrictResult.allowDefault();
        }

        return new PrivacyStrictResult(true, "yuqi_privacy_strict_out_of_scope", signals);
    }
}
