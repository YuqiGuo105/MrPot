package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class AssumptionCheckTools {

    private static final Pattern RESOURCE_HINT = Pattern.compile("(budget|cost|time|timeline|resource|\\u6210\\u672c|\\u65f6\\u95f4|\\u9884\\u7b97)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_HINT = Pattern.compile("(data|metric|log|evidence|\\u6570\\u636e|\\u6307\\u6807|\\u65e5\\u5fd7|\\u8bc1\\u636e)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENV_HINT = Pattern.compile("(prod|production|environment|runtime|\\u73af\\u5883|\\u751f\\u4ea7)", Pattern.CASE_INSENSITIVE);

    public record AssumptionResult(List<String> assumptions, String riskLevel) {}

    @Tool(name = "assumption_check", description = "Surface implicit assumptions that might impact the answer.")
    public AssumptionResult check(String question, String evidenceSummary) {
        String q = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        String evidence = evidenceSummary == null ? "" : evidenceSummary.trim();
        List<String> assumptions = new ArrayList<>();

        if (evidence.isBlank()) {
            assumptions.add("Assuming the answer can be given without concrete evidence.");
        }
        if (RESOURCE_HINT.matcher(q).find()) {
            assumptions.add("Assuming acceptable budget/timeline constraints are known.");
        }
        if (DATA_HINT.matcher(q).find()) {
            assumptions.add("Assuming the required data or logs are available.");
        }
        if (ENV_HINT.matcher(q).find()) {
            assumptions.add("Assuming the target environment matches the expected runtime.");
        }

        String riskLevel = assumptions.size() >= 3 ? "high" : assumptions.isEmpty() ? "low" : "medium";
        return new AssumptionResult(assumptions, riskLevel);
    }
}
