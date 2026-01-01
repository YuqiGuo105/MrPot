package com.example.MrPot.service;

import com.example.MrPot.model.ExtractedFile;
import com.example.MrPot.service.dto.DocumentUnderstandingResult;
import com.example.MrPot.service.dto.FileContentData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RemoteOcrFileEnricher {

    private final QwenVlFlashClient qwenVlFlashClient;

    public List<FileContentData> enrich(List<String> urls, List<FileContentData> baseFiles, Duration timeout) {
        List<FileContentData> base = safeList(baseFiles);
        if (urls == null || urls.isEmpty()) return base;

        Map<String, FileContentData> byUrl = new LinkedHashMap<>();
        for (FileContentData f : base) {
            if (f == null || f.getFile() == null || f.getFile().uri() == null) continue;
            byUrl.put(f.getFile().uri().toString(), f);
        }

        List<FileContentData> merged = new ArrayList<>(base);
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            try {
                DocumentUnderstandingResult result = qwenVlFlashClient
                        .understandPublicUrlWithKeywords(url, null)
                        .block(timeout);
                if (result == null) continue;

                String text = Optional.ofNullable(result.getText()).orElse("");
                List<String> kws = safeList(result.getKeywords());
                if (text.isBlank() && kws.isEmpty()) continue;

                FileContentData existing = byUrl.get(url);
                if (existing != null) {
                    String combinedText = combineText(existing.getText(), text);
                    List<String> combinedKeywords = mergeKeywords(existing.getKeywords(), kws);
                    FileContentData updated = FileContentData.builder()
                            .file(existing.getFile())
                            .text(combinedText)
                            .keywords(combinedKeywords)
                            .build();
                    merged.remove(existing);
                    merged.add(updated);
                    byUrl.put(url, updated);
                } else {
                    ExtractedFile synthetic = new ExtractedFile(
                            URI.create(url),
                            "remote-file",
                            "image/remote-url",
                            0L,
                            text,
                            null
                    );
                    FileContentData added = FileContentData.builder()
                            .file(synthetic)
                            .text(text)
                            .keywords(kws)
                            .build();
                    merged.add(added);
                    byUrl.put(url, added);
                }
            } catch (Exception ignore) {
                // Skip failures silently to avoid blocking the main answer flow.
            }
        }

        return merged;
    }

    private static List<String> mergeKeywords(List<String> existing, List<String> extra) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String k : safeList(existing)) {
            if (k != null && !k.isBlank()) merged.add(k);
        }
        for (String k : safeList(extra)) {
            if (k != null && !k.isBlank()) merged.add(k);
        }
        return List.copyOf(merged);
    }

    private static String combineText(String original, String addition) {
        String a = Optional.ofNullable(original).orElse("").trim();
        String b = Optional.ofNullable(addition).orElse("").trim();
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        if (a.contains(b)) return a;
        if (b.contains(a)) return b;
        return a + "\n" + b;
    }

    private static <T> List<T> safeList(List<T> list) {
        if (list == null) return List.of();
        return list.stream().filter(Objects::nonNull).toList();
    }
}

