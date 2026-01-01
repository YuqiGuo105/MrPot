package com.example.MrPot.service;

import com.example.MrPot.model.DocumentUnderstandingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final ObjectMapper OM = new ObjectMapper();

    private final QwenVlFlashClient qwenVlClient;
    private final UrlFileDownloader downloader;

    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration DL_TIMEOUT = Duration.ofSeconds(25);

    private static final int MAX_PDF_PAGES = 3;
    private static final int PDF_DPI = 150;

    public DocumentUnderstandingResult understandFileUrlWithQwenVl(String fileUrl) {
        String mime = downloader.headContentType(fileUrl, HEAD_TIMEOUT).block();
        mime = normalizeMime(mime);

        try {
            if (isImage(mime, fileUrl)) {
                // Prefer passing URL directly; if provider can't fetch, fallback to data: URI (download bytes).
                DocumentUnderstandingResult r1 = callQwenWithParts(List.of(
                        imageUrlPart(fileUrl),
                        textPart(buildExtractionPrompt("image"))
                ));
                if (!isEmptyKeyInfo(r1)) return r1;

                UrlFileDownloader.DownloadedFile f = downloader.download(fileUrl, DL_TIMEOUT).block();
                if (f == null || f.bytes() == null || f.bytes().length == 0) return fallbackResult("download_failed: image");

                String imgMime = normalizeMime(f.contentType());
                if (imgMime == null || imgMime.isBlank() || !imgMime.startsWith("image/")) imgMime = guessImageMimeFromUrl(fileUrl);

                String dataUri = toDataUri(imgMime, f.bytes());
                return callQwenWithParts(List.of(
                        imageUrlPart(dataUri),
                        textPart(buildExtractionPrompt("image"))
                ));
            }

            if (isPdf(mime, fileUrl)) {
                UrlFileDownloader.DownloadedFile f = downloader.download(fileUrl, DL_TIMEOUT).block();
                if (f == null || f.bytes() == null || f.bytes().length == 0) return fallbackResult("download_failed: pdf");

                List<String> pageDataUris = renderPdfFirstPagesToPngDataUri(f.bytes(), MAX_PDF_PAGES, PDF_DPI);
                if (pageDataUris.isEmpty()) return fallbackResult("pdf_render_failed");

                List<Map<String, Object>> parts = new ArrayList<>();
                for (String du : pageDataUris) parts.add(imageUrlPart(du));
                parts.add(textPart(buildExtractionPrompt("pdf")));
                return callQwenWithParts(parts);
            }

            if (isText(mime, fileUrl)) {
                UrlFileDownloader.DownloadedFile f = downloader.download(fileUrl, DL_TIMEOUT).block();
                if (f == null || f.bytes() == null || f.bytes().length == 0) return fallbackResult("download_failed: text");

                String text = new String(f.bytes(), StandardCharsets.UTF_8);
                return callQwenWithParts(List.of(
                        textPart(buildExtractionPrompt("text") + "\nFILE_TEXT:\n" + truncate(text, 12000))
                ));
            }

            String note = "unsupported_type: " + (mime == null ? "unknown" : mime);
            return callQwenWithParts(List.of(
                    textPart(buildExtractionPrompt("binary") + "\n" + note + "\nurl=" + fileUrl)
            ));

        } catch (Exception e) {
            return fallbackResult("extract_failed: " + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private DocumentUnderstandingResult callQwenWithParts(List<Map<String, Object>> contentParts) {
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", contentParts);

        // Align with DashScope UI-ish defaults
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("temperature", 0.7);
        extra.put("top_p", 0.8);
        extra.put("max_tokens", 256);

        String raw = qwenVlClient.chatCompletions(null, List.of(userMsg), extra);
        return parseUnderstandingOrFallback(raw);
    }

    // ---------------- Prompt (short) ----------------

    private String buildExtractionPrompt(String kind) {
        return """
Extract key info from %s.
JSON only: {"text":"","keywords":[],"queries":[]}  text max 60 chars; keywords max 10; queries max 1. Do not echo limits.
""".formatted(kind);
    }

    // ---------------- Parse (never empty) ----------------

    private DocumentUnderstandingResult parseUnderstandingOrFallback(String llmRaw) {
        String cleaned = cleanupModelOutput(llmRaw);

        DocumentUnderstandingResult r = tryParseJson(cleaned);
        if (r == null) {
            String json = extractFirstJsonObject(cleaned);
            r = tryParseJson(json);
        }

        if (r == null) {
            return fallbackResult(cleaned == null ? "" : cleaned.trim());
        }

        if (r.getText() == null || r.getText().trim().isBlank()) {
            r.setText(cleaned == null ? "" : cleaned.trim());
        }
        if (r.getKeywords() == null) r.setKeywords(List.of());
        if (r.getQueries() == null) r.setQueries(List.of());
        return r;
    }

    private boolean isEmptyKeyInfo(DocumentUnderstandingResult r) {
        if (r == null) return true;
        String t = r.getText() == null ? "" : r.getText().trim();
        return t.isBlank()
                && (r.getKeywords() == null || r.getKeywords().isEmpty())
                && (r.getQueries() == null || r.getQueries().isEmpty());
    }

    private DocumentUnderstandingResult fallbackResult(String text) {
        return DocumentUnderstandingResult.builder()
                .text(text == null ? "" : text)
                .keywords(List.of())
                .queries(List.of())
                .build();
    }

    private String cleanupModelOutput(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = t.replaceAll("^```(?:json)?\\s*", "");
        t = t.replaceAll("\\s*```\\s*$", "");
        return t.trim();
    }

    private DocumentUnderstandingResult tryParseJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("{")) return null;
        try {
            return OM.readValue(t, DocumentUnderstandingResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFirstJsonObject(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1).trim();
        return null;
    }

    // ---------------- PDF -> images (PDFBox 3.x) ----------------

    private List<String> renderPdfFirstPagesToPngDataUri(byte[] pdfBytes, int maxPages, int dpi) {
        if (pdfBytes == null || pdfBytes.length == 0) return List.of();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(maxPages, doc.getNumberOfPages());

            List<String> out = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", bos);
                byte[] png = bos.toByteArray();

                out.add(toDataUri("image/png", png));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------- Qwen message parts ----------------

    private Map<String, Object> textPart(String text) {
        return Map.of("type", "text", "text", text);
    }

    private Map<String, Object> imageUrlPart(String urlOrDataUri) {
        return Map.of("type", "image_url", "image_url", Map.of("url", urlOrDataUri));
    }

    // ---------------- Helpers ----------------

    private static String toDataUri(String mime, byte[] bytes) {
        String b64 = Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
        return "data:" + (mime == null || mime.isBlank() ? "application/octet-stream" : mime) + ";base64," + b64;
    }

    private static boolean isImage(String mime, String url) {
        if (mime != null && mime.startsWith("image/")) return true;
        String u = (url == null) ? "" : url.toLowerCase(Locale.ROOT);
        return u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".webp");
    }

    private static boolean isPdf(String mime, String url) {
        if ("application/pdf".equalsIgnoreCase(mime)) return true;
        String u = (url == null) ? "" : url.toLowerCase(Locale.ROOT);
        return u.endsWith(".pdf");
    }

    private static boolean isText(String mime, String url) {
        if (mime != null && mime.startsWith("text/")) return true;
        String u = (url == null) ? "" : url.toLowerCase(Locale.ROOT);
        return u.endsWith(".txt") || u.endsWith(".md") || u.endsWith(".html") || u.endsWith(".htm") || u.endsWith(".json");
    }

    private static String normalizeMime(String ct) {
        if (ct == null) return null;
        String t = ct.trim();
        if (t.isBlank()) return null;
        int semi = t.indexOf(';');
        return (semi > 0 ? t.substring(0, semi) : t).trim();
    }

    private static String guessImageMimeFromUrl(String url) {
        String u = (url == null) ? "" : url.toLowerCase(Locale.ROOT);
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...(truncated)";
    }
}
