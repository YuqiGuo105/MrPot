package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class AnswerOutlineTools {

    private static final Pattern COMPARE_HINT = Pattern.compile("(compare|difference|vs\\.?|versus|\\u6bd4\\u8f83|\\u533a\\u522b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOW_HINT = Pattern.compile("(how|steps|procedure|\\u5982\\u4f55|\\u6b65\\u9aa4)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHY_HINT = Pattern.compile("(why|reason|cause|\\u4e3a\\u4ec0\\u4e48|\\u539f\\u56e0)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_HINT = Pattern.compile("(list|examples|kinds|types|\\u5217\\u51fa|\\u4f8b\\u5b50|\\u7c7b\\u522b)", Pattern.CASE_INSENSITIVE);

    public record OutlineResult(List<String> sections, String style) {}

    @Tool(name = "answer_outline", description = "Draft an answer outline based on question intent and key info.")
    public OutlineResult outline(String question, List<String> keyInfo) {
        String q = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        List<String> sections = new ArrayList<>();
        String style = "bullets";

        if (COMPARE_HINT.matcher(q).find()) {
            sections.add("Items being compared");
            sections.add("Comparison criteria");
            sections.add("Pros/cons or differences");
            sections.add("Recommendation");
        } else if (HOW_HINT.matcher(q).find()) {
            style = "steps";
            sections.add("Goal");
            sections.add("Prerequisites");
            sections.add("Step-by-step approach");
            sections.add("Validation/checks");
        } else if (WHY_HINT.matcher(q).find()) {
            sections.add("Short answer");
            sections.add("Explanation");
            sections.add("Supporting evidence");
        } else if (LIST_HINT.matcher(q).find()) {
            sections.add("Overview");
            sections.add("Key items");
            sections.add("Notes or caveats");
        } else {
            sections.add("Answer");
            sections.add("Details");
            sections.add("Next steps");
        }

        if (keyInfo != null && !keyInfo.isEmpty()) {
            sections.add(0, "Key facts");
        }

        return new OutlineResult(sections, style);
    }
}
