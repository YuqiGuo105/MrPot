package com.example.MrPot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyInfoExtractor {

    private static final Pattern NUM_STEP = Pattern.compile("^\\s*(?:步骤\\s*)?(\\d+)[\\.、\\)]\\s*(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.+)$");

    public record KeyInfo(List<String> steps, List<String> points) {}

    public static KeyInfo extract(String answerText) {
        if (answerText == null) return new KeyInfo(List.of(), List.of());

        List<String> steps = new ArrayList<>();
        List<String> points = new ArrayList<>();

        String[] lines = answerText.split("\\r?\\n");
        boolean inStepSection = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.contains("步骤") || line.toLowerCase().contains("steps")) {
                inStepSection = true;
            }

            Matcher m1 = NUM_STEP.matcher(line);
            if (m1.find()) {
                steps.add(m1.group(1) + ". " + m1.group(2).trim());
                continue;
            }

            Matcher m2 = BULLET.matcher(line);
            if (m2.find()) {
                if (inStepSection) steps.add(m2.group(1).trim());
                else points.add(m2.group(1).trim());
            }
        }

        if (steps.isEmpty() && points.isEmpty()) {
            String compact = answerText.replaceAll("\\s+", " ").trim();
            String[] parts = compact.split("[。.!?]\\s*");
            for (int i = 0; i < Math.min(parts.length, 3); i++) {
                String p = parts[i].trim();
                if (!p.isEmpty()) points.add(p);
            }
        }

        steps = steps.stream().limit(12).toList();
        points = points.stream().limit(8).toList();

        return new KeyInfo(steps, points);
    }
}
