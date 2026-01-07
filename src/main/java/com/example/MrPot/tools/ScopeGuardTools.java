package com.example.MrPot.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ScopeGuardTools {

    private static final Pattern YUQI_PATTERN = Pattern.compile("(yuqi|\\u90ed\\u5b87\\u7426|\\u4e8e\\u742a|\\u90ed\\u745c\\u7426)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PII_PATTERN = Pattern.compile("(email|e-mail|mailbox|\\bphone\\b|\\bmobile\\b|\\baddress\\b|\\bwechat\\b|\\btelegram\\b|\\bwhatsapp\\b|\\bqq\\b|\\bwechat\\b|\\bwx\\b|\\b\\d{3,4}[-\\s]?\\d{4,}\\b|\\b\\d{5,}\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_HINT_PATTERN = Pattern.compile("(\\u90ae\\u7bb1|\\u7535\\u8bdd|\\u624b\\u673a|\\u4f4f\\u5740|\\u8054\\u7cfb\\u65b9\\u5f0f|\\u8eab\\u4efd\\u8bc1|\\u94f6\\u884c\\u5361|\\u9690\\u79c1)", Pattern.CASE_INSENSITIVE);

    public record ScopeGuardResult(boolean allowed, String reason, String rewriteHint, String policy) {
        public static ScopeGuardResult allowedDefault(String policy) {
            return new ScopeGuardResult(true, "assume allowed", "", policy);
        }
    }

    @Tool(name = "scope_guard", description = "Check whether the question is allowed under the selected scope mode.")
    public ScopeGuardResult guard(String question, String scopeMode) {
        String safeMode = scopeMode == null ? "PRIVACY_SAFE" : scopeMode.trim().toUpperCase(Locale.ROOT);
        if (question == null || question.isBlank()) {
            return new ScopeGuardResult(false, "empty question", "Provide a clear question.", safeMode);
        }

        String normalized = question.trim();
        boolean mentionsYuqi = YUQI_PATTERN.matcher(normalized).find();
        boolean privacyRisk = PII_PATTERN.matcher(normalized).find() || PRIVATE_HINT_PATTERN.matcher(normalized).find();

        if ("YUQI_ONLY".equals(safeMode)) {
            if (!mentionsYuqi) {
                return new ScopeGuardResult(false, "out_of_scope: yuqi_only", "Ask about Yuqi's work, projects, or blog.", safeMode);
            }
            if (privacyRisk) {
                return new ScopeGuardResult(false, "privacy_blocked", "Ask about Yuqi's public work instead.", safeMode);
            }
            return new ScopeGuardResult(true, "yuqi_related", "", safeMode);
        }

        if (privacyRisk) {
            return new ScopeGuardResult(false, "privacy_blocked", "Avoid personal contact details. Ask about public work instead.", safeMode);
        }

        return new ScopeGuardResult(true, mentionsYuqi ? "yuqi_related" : "general_allowed", "", safeMode);
    }
}
