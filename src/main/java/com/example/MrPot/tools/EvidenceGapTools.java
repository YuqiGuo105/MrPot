package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class EvidenceGapTools {

    private static final Pattern WHY_HINT = Pattern.compile("(why|reason|cause|\\u4e3a\\u4ec0\\u4e48|\\u539f\\u56e0)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOW_HINT = Pattern.compile("(how|steps|procedure|\\u5982\\u4f55|\\u6b65\\u9aa4)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARE_HINT = Pattern.compile("(compare|difference|vs\\.?|versus|\\u6bd4\\u8f83|\\u533a\\u522b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_HINT = Pattern.compile("(\\d+|when|timeline|date|time|\\u65f6\\u95f4|\\u65e5\\u671f)", Pattern.CASE_INSENSITIVE);

    public record EvidenceGapResult(List<String> missingFacts, List<String> followUps, String status) {}

    @Tool(name = "evidence_gap", description = "Identify missing evidence and clarifying questions for deep thinking.")
    public EvidenceGapResult detect(String question, String evidenceSummary, List<String> keyInfo) {
        String q = question == null ? "" : question.trim();
        String evidence = evidenceSummary == null ? "" : evidenceSummary.trim();
        List<String> missing = new ArrayList<>();
        List<String> followUps = new ArrayList<>();

        if (q.isBlank()) {
            missing.add("Question is missing.");
            followUps.add("Ask the user to provide a concrete question.");
            return new EvidenceGapResult(missing, followUps, "empty_question");
        }

        if (evidence.isBlank()) {
            missing.add("No supporting evidence was provided.");
            followUps.add("Request relevant context, documents, or examples.");
        }

        if (keyInfo == null || keyInfo.isEmpty()) {
            missing.add("No key facts were extracted yet.");
        }

        String lowered = q.toLowerCase(Locale.ROOT);
        if (COMPARE_HINT.matcher(lowered).find()) {
            missing.add("Comparison criteria are not specified.");
            followUps.add("Ask which criteria matter most in the comparison.");
        }
        if (HOW_HINT.matcher(lowered).find()) {
            missing.add("Constraints or environment are unclear.");
            followUps.add("Ask about constraints, target environment, or expected outcome.");
        }
        if (WHY_HINT.matcher(lowered).find()) {
            missing.add("Causal evidence may be required.");
        }
        if (NUMBER_HINT.matcher(lowered).find()) {
            missing.add("Exact timeline or numeric details may be missing.");
        }

        String status = missing.isEmpty() ? "ok" : "needs_info";
        return new EvidenceGapResult(missing, followUps, status);
    }
}
