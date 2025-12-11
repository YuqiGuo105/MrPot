package com.example.MrPot.tools;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for all AI tools.
 * It knows which tools are available and which profile they belong to.
 */
@Component
public class ToolRegistry {

    private final Map<String, AiToolDefinition> toolsByName;
    private final Map<ToolProfile, List<AiToolDefinition>> toolsByProfile;

    public ToolRegistry(List<AiToolDefinition> definitions) {
        // Index by name
        this.toolsByName = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AiToolDefinition::name,
                        d -> d
                ));

        // Index by profile
        Map<ToolProfile, List<AiToolDefinition>> tmp = new EnumMap<>(ToolProfile.class);
        for (AiToolDefinition def : definitions) {
            for (ToolProfile profile : def.profiles()) {
                tmp.computeIfAbsent(profile, p -> new ArrayList<>()).add(def);
            }
        }
        this.toolsByProfile = Collections.unmodifiableMap(tmp);
    }

    /**
     * Get all tool definitions for a specific profile.
     */
    public List<AiToolDefinition> getToolsForProfile(ToolProfile profile) {
        return toolsByProfile.getOrDefault(profile, List.of());
    }

    /**
     * Get all function bean names for a specific profile.
     * Used to call ChatClient.toolNames(...)
     */
    public List<String> getFunctionBeanNamesForProfile(ToolProfile profile) {
        return getToolsForProfile(profile).stream()
                .map(AiToolDefinition::functionBeanName)
                .toList();
    }

    /**
     * Look up a tool definition by its name.
     */
    public Optional<AiToolDefinition> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /**
     * Get all registered tools.
     */
    public Collection<AiToolDefinition> allTools() {
        return toolsByName.values();
    }
}
