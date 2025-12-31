package com.example.MrPot.model;

import java.net.URI;

public record DownloadedFile(
        URI uri,
        String filename,
        String mimeType,
        long sizeBytes,
        byte[] bytes
) {
}
