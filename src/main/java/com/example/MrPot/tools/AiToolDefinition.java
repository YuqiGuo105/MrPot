package com.example.MrPot.tools;

import java.util.Set;
import java.util.function.Function;

/**
 * Common metadata for an AI tool.
 * This does NOT require the tool to be the Function itself.
 * You can keep your domain logic separate from the function adapter.
 */
public interface AiToolDefinition {
    /**
     * Unique tool name registered as bean name and used in ChatClient.toolNames(...)
     */
    String name();

    /**
     * Natural language description visible to the LLM.
     */
    String description();

    /**
     * Tool profiles in which this tool should be enabled.
     */
    Set<ToolProfile> profiles();

    /**
     * Underlying Spring AI Function bean name (usually same as name()).
     */
    default String functionBeanName() {
        // In most cases we keep tool name == function bean name
        return name();
    }
}
