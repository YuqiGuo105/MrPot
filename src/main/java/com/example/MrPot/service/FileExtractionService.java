package com.example.MrPot.service;


import com.example.MrPot.model.DownloadedFile;
import com.example.MrPot.model.ExtractedFile;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class FileExtractionService {

    private static final int MAX_EXTRACT_CHARS = 10_000;

    private final TikaUniversalExtractor tika;

    public FileExtractionService(TikaUniversalExtractor tika) {
        this.tika = tika;
    }

    public Mono<ExtractedFile> extract(DownloadedFile f, ChatClient visionClientOrNull) {
        return Mono.fromCallable(() -> {
            try {
                String mime = normalizeMime(f.mimeType());

                String text;
                if (isImage(mime)) {
                    // English comments: Use vision model for images (OCR + understanding).
                    text = (visionClientOrNull == null) ? "" : extractImageViaVision(visionClientOrNull, mime, f.bytes());
                } else if (isPlainText(mime)) {
                    // English comments: Fast path for plain text.
                    text = new String(f.bytes(), StandardCharsets.UTF_8);
                } else {
                    // English comments: Universal extraction for pdf/docx/xlsx/ppt/html/etc.
                    text = tika.extract(f.bytes(), f.filename(), mime).text();
                }

                text = clamp(text, MAX_EXTRACT_CHARS);
                return new ExtractedFile(f.uri(), f.filename(), f.mimeType(), f.sizeBytes(), text, null);

            } catch (Exception ex) {
                return new ExtractedFile(f.uri(), f.filename(), f.mimeType(), f.sizeBytes(), "", ex.toString());
            }
        });
    }

    private static String extractImageViaVision(ChatClient visionClient, String mime, byte[] bytes) {
        MimeType mt = MimeTypeUtils.parseMimeType(mime);

        // English comments: Keep instruction short to reduce tokens.
        String instruction =
                "Extract readable text from the image. " +
                        "If no text, describe key objects briefly. " +
                        "Plain text only.";

        return visionClient.prompt()
                .user(u -> u.text(instruction).media(mt, new ByteArrayResource(bytes)))
                .call()
                .content();
    }

    private static boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    private static boolean isPlainText(String mime) {
        if (mime == null) return false;
        return mime.startsWith("text/")
                || mime.equals("application/json")
                || mime.equals("application/xml")
                || mime.endsWith("+json")
                || mime.endsWith("+xml");
    }

    private static String normalizeMime(String mime) {
        if (mime == null) return "application/octet-stream";
        String m = mime.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        return semi > 0 ? m.substring(0, semi).trim() : m.trim();
    }

    private static String clamp(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...";
    }
}

