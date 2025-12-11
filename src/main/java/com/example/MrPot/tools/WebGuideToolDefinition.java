package com.example.MrPot.tools;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Metadata for WebGuide tool.
 * Real logic lives in WebGuideToolFunction or another service.
 */
@Component
public class WebGuideToolDefinition implements AiToolDefinition {

    public static final String NAME = "webGuideTool";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Web guide tool. Use this when the user asks for greeting, onboarding,
                or a website guide. It helps guide the user step by step.
                """;
    }

    @Override
    public Set<ToolProfile> profiles() {
        // Enabled in basic chat and full mode
        return Set.of(ToolProfile.BASIC_CHAT, ToolProfile.FULL);
    }
}
