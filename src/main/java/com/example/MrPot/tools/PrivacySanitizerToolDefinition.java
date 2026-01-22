package com.example.MrPot.tools;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PrivacySanitizerToolDefinition implements AiToolDefinition {

    public static final String NAME = "privacy_sanitize";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Redact emails, phone numbers, and tokens from evidence before reasoning.";
    }

    @Override
    public Set<ToolProfile> profiles() {
        return Set.of(ToolProfile.FULL, ToolProfile.ADMIN);
    }
}
