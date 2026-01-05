package com.example.MrPot.tools;

import com.example.MrPot.model.DocumentUnderstandingResult;
import com.example.MrPot.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FileTools {

    private final AttachmentService attachmentService;

    @Tool(name = "file_understand_url", description = "Understand a remote file (image/pdf/text) and return key text, keywords, queries, and error if any.")
    public FileUnderstanding understandUrl(String url) {
        if (url == null || url.isBlank()) {
            return new FileUnderstanding("", List.of(), List.of(), "empty_url");
        }
        try {
            DocumentUnderstandingResult result = attachmentService.understandFileUrlWithQwenVl(url);
            String text = result == null ? "" : safe(result.getText());
            List<String> keywords = result == null ? List.of() : safeList(result.getKeywords());
            List<String> queries = result == null ? List.of() : safeList(result.getQueries());
            return new FileUnderstanding(text, keywords, queries, "");
        } catch (Exception ex) {
            return new FileUnderstanding("", List.of(), List.of(), "extract_failed: " + ex.getClass().getSimpleName() + ": " + safeMsg(ex));
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static List<String> safeList(List<String> list) {
        if (list == null) return List.of();
        return list.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String safeMsg(Throwable ex) {
        String m = ex == null ? "" : String.valueOf(ex.getMessage());
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 400) m = m.substring(0, 400) + "...";
        return m;
    }

    public record FileUnderstanding(String text, List<String> keywords, List<String> queries, String error) {}
}
