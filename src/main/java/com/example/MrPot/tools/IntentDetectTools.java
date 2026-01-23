package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class IntentDetectTools {

    private static final Pattern DEBUG_HINT = Pattern.compile("(debug|error|issue|fix|\\u9519\\u8bef|\\u95ee\\u9898|\\u4fee\\u590d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRATEGY_HINT = Pattern.compile("(strategy|plan|roadmap|\\u7b56\\u7565|\\u89c4\\u5212)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEARN_HINT = Pattern.compile("(learn|study|practice|\\u5b66\\u4e60|\\u7ec3\\u4e60)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOWTO_HINT = Pattern.compile("(how to|guide|tutorial|\\u600e\\u4e48|\\u5982\\u4f55|\\u6559\\u7a0b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARE_HINT = Pattern.compile("(compare|vs\\.?|difference|\\u5bf9\\u6bd4|\\u533a\\u522b)", Pattern.CASE_INSENSITIVE);

    public record IntentResult(String intent, List<String> signals) {}

    @Tool(name = "intent_detect", description = "Detect the user's high-level intent to guide response tone and structure.")
    public IntentResult detect(String question) {
        String q = question == null ? "" : question.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();

        if (DEBUG_HINT.matcher(lower).find()) {
            signals.add("debug");
            return new IntentResult("debug", signals);
        }
        if (STRATEGY_HINT.matcher(lower).find()) {
            signals.add("strategy");
            return new IntentResult("plan", signals);
        }
        if (LEARN_HINT.matcher(lower).find()) {
            signals.add("learn");
            return new IntentResult("learn", signals);
        }
        if (HOWTO_HINT.matcher(lower).find()) {
            signals.add("howto");
            return new IntentResult("howto", signals);
        }
        if (COMPARE_HINT.matcher(lower).find()) {
            signals.add("compare");
            return new IntentResult("compare", signals);
        }

        if (!q.isBlank()) {
            signals.add("general");
        }

        return new IntentResult("general", signals);
    }
}
