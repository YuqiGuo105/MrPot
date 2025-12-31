package com.example.MrPot.model;

import java.util.List;
import java.util.Map;

public record AttachmentContext(
        List<ExtractedFile> files,
        String combinedText
) {
    public static AttachmentContext empty() {
        return new AttachmentContext(List.of(), "");
    }

    public List<Map<String, Object>> fetchPayload() {
        return files.stream().map(f -> Map.<String, Object>of(
                "url", f.uri().toString(),
                "filename", f.filename(),
                "mime", f.mimeType(),
                "sizeBytes", f.sizeBytes()
        )).toList();
    }

    public List<Map<String, Object>> extractPayload(int previewChars) {
        return files.stream().map(f -> Map.<String, Object>of(
                "url", f.uri().toString(),
                "preview", f.preview(previewChars),
                "chars", f.extractedText() == null ? 0 : f.extractedText().length(),
                "error", f.error() == null ? "" : f.error()
        )).toList();
    }
}
