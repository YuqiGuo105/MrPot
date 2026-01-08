package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TrackCorrectTools {

    public record TrackResult(boolean onTrack, String hint, String status) {}

    @Tool(name = "track_correct", description = "Check whether the reasoning flow is on track and suggest recovery hints.")
    public TrackResult ensure(String question, String roadmapSummary, String statusHint) {
        String q = question == null ? "" : question.trim();
        String status = statusHint == null ? "" : statusHint.trim().toLowerCase(Locale.ROOT);

        if (q.isBlank()) {
            return new TrackResult(false, "Ask the user to provide a concrete question.", "empty_question");
        }
        if (status.contains("no_evidence")) {
            return new TrackResult(false, "Ask for more context or files to ground the answer.", "no_evidence");
        }
        if (status.contains("error")) {
            return new TrackResult(false, "Retry the step or fall back to a simpler response.", "error_detected");
        }
        if (roadmapSummary == null || roadmapSummary.isBlank()) {
            return new TrackResult(false, "Clarify the goal and pick a minimal set of steps.", "missing_roadmap");
        }

        return new TrackResult(true, "Proceed with the planned steps.", "ok");
    }
}
