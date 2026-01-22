package com.example.MrPot.tools;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class KbSearchToolDefinition implements AiToolDefinition {

    public static final String NAME = "kb_search";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the internal knowledge base for Yuqi-related evidence and references.";
    }

    @Override
    public Set<ToolProfile> profiles() {
        return Set.of(ToolProfile.FULL, ToolProfile.ADMIN);
    }
}
