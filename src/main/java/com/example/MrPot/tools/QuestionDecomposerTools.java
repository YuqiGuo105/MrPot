package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class QuestionDecomposerTools {

    @Tool(name = "question_decompose", description = "Split a complex question into smaller sub-questions.")
    public List<String> decompose(String question) {
        if (question == null || question.isBlank()) return List.of();
        String[] parts = question.split("[\\n;；。.!?？！]+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        return out;
    }
}
