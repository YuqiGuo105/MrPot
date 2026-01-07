package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class CodeSearchTools {

    private final Path root;

    public CodeSearchTools(@Value("${mrpot.code.root:}") String rootPath) {
        this.root = (rootPath == null || rootPath.isBlank()) ? null : Path.of(rootPath);
    }

    public record CodeSearchResult(String status, List<String> snippets) {}

    @Tool(name = "code_search", description = "Search local codebase for relevant snippets.")
    public CodeSearchResult search(String query, Integer limit) {
        if (root == null) {
            return new CodeSearchResult("code_root_not_configured", List.of());
        }
        if (query == null || query.isBlank()) {
            return new CodeSearchResult("empty_query", List.of());
        }

        int max = limit == null ? 5 : Math.max(1, limit);
        List<String> results = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".md") || path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> {
                        if (results.size() >= max) return;
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            String lower = content.toLowerCase(Locale.ROOT);
                            int idx = lower.indexOf(needle);
                            if (idx >= 0) {
                                int start = Math.max(0, idx - 80);
                                int end = Math.min(content.length(), idx + 200);
                                String snippet = path + ":\n" + content.substring(start, end);
                                results.add(snippet);
                            }
                        } catch (IOException ignored) {
                            // ignore unreadable files
                        }
                    });
        } catch (IOException ex) {
            return new CodeSearchResult("search_failed: " + ex.getMessage(), results);
        }

        return new CodeSearchResult("ok", results);
    }
}
