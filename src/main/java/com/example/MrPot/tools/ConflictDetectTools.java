package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConflictDetectTools {

    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    public record ConflictResult(boolean conflict, List<String> issues) {}

    @Tool(name = "conflict_detect", description = "Detect obvious conflicts between KB and file evidence.")
    public ConflictResult detect(String kbEvidence, String fileEvidence) {
        if (kbEvidence == null || kbEvidence.isBlank() || fileEvidence == null || fileEvidence.isBlank()) {
            return new ConflictResult(false, List.of());
        }

        List<String> kbNumbers = extractNumbers(kbEvidence);
        List<String> fileNumbers = extractNumbers(fileEvidence);
        List<String> issues = new ArrayList<>();

        for (String num : kbNumbers) {
            if (fileNumbers.contains(num)) continue;
            if (!fileNumbers.isEmpty()) {
                issues.add("Potential mismatch number: KB has " + num + " while file has " + fileNumbers.get(0));
                break;
            }
        }

        return new ConflictResult(!issues.isEmpty(), issues);
    }

    private List<String> extractNumbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }
}
