package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ActionPlanTools {

    private static final Pattern DEBUG_HINT = Pattern.compile("(debug|error|issue|fix|\\u9519\\u8bef|\\u95ee\\u9898|\\u4fee\\u590d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRATEGY_HINT = Pattern.compile("(strategy|plan|roadmap|\\u7b56\\u7565|\\u89c4\\u5212)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEARN_HINT = Pattern.compile("(learn|study|practice|\\u5b66\\u4e60|\\u7ec3\\u4e60)", Pattern.CASE_INSENSITIVE);

    public record ActionPlanResult(List<String> steps, String style) {}

    @Tool(name = "action_plan", description = "Draft concise next-step actions aligned to the question.")
    public ActionPlanResult plan(String question, List<String> keyInfo) {
        String q = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        List<String> steps = new ArrayList<>();
        String style = "bullets";

        if (DEBUG_HINT.matcher(q).find()) {
            style = "steps";
            steps.add("Reproduce the issue with minimal input.");
            steps.add("Inspect logs/metrics around the failure.");
            steps.add("Isolate the root cause and propose a fix.");
            steps.add("Validate the fix with tests or checks.");
        } else if (STRATEGY_HINT.matcher(q).find()) {
            steps.add("Clarify objectives and success metrics.");
            steps.add("Identify constraints and resources.");
            steps.add("Prioritize initiatives and milestones.");
            steps.add("Define owners and timeline.");
        } else if (LEARN_HINT.matcher(q).find()) {
            steps.add("Set a learning goal and scope.");
            steps.add("Pick curated resources or examples.");
            steps.add("Practice with small exercises.");
            steps.add("Review and iterate based on feedback.");
        } else {
            steps.add("Summarize the goal.");
            steps.add("List key inputs or evidence needed.");
            steps.add("Outline the primary response steps.");
            steps.add("Confirm next actions or open questions.");
        }

        if (keyInfo != null && !keyInfo.isEmpty()) {
            steps.add(0, "Review key facts and constraints.");
        }

        return new ActionPlanResult(steps, style);
    }
}
