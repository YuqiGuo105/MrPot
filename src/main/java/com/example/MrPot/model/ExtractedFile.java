package com.example.MrPot.model;

import java.net.URI;

public record ExtractedFile(
        URI uri,
        String filename,
        String mimeType,
        long sizeBytes,
        String extractedText,
        String error
) {
    public String preview(int maxChars) {
        if (extractedText == null) return "";
        return extractedText.length() <= maxChars
                ? extractedText
                : extractedText.substring(0, maxChars) + "...";
    }
}
