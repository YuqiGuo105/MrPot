package com.example.MrPot.tools;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class YuqiPrivacyStrictToolDefinition implements AiToolDefinition {

    public static final String NAME = "yuqi_privacy_strict";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Mark Yuqi privacy-sensitive requests as out of scope during deep thinking.";
    }

    @Override
    public Set<ToolProfile> profiles() {
        return Set.of(ToolProfile.FULL, ToolProfile.ADMIN);
    }
}
