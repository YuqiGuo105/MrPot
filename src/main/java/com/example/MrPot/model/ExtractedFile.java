package com.example.MrPot.model;

import java.net.URI;
import java.util.Collections;
import java.util.List;

public record ExtractedFile(
        URI uri,
        String filename,
        String mimeType,
        long sizeBytes,
        String extractedText,
        String error,
        String keyInfoJson,
        List<String> entities
) {
    public String preview(int maxChars) {
        if (extractedText == null) return "";
        return extractedText.length() <= maxChars
                ? extractedText
                : extractedText.substring(0, maxChars) + "...";
    }

    public ExtractedFile withKeyInfo(String keyInfoJson, List<String> entities) {
        List<String> safeEntities = entities == null ? List.of() : List.copyOf(entities);
        return new ExtractedFile(
                uri,
                filename,
                mimeType,
                sizeBytes,
                extractedText,
                error,
                keyInfoJson,
                safeEntities
        );
    }

    public List<String> entitiesOrEmpty() {
        if (entities == null) return List.of();
        return Collections.unmodifiableList(entities);
    }
}
