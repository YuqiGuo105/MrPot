package com.example.MrPot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KeywordExtractTools {

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "with", "by", "from",
            "is", "are", "was", "were", "be", "been", "this", "that", "these", "those", "it",
            "you", "your", "me", "my", "we", "our", "they", "their", "he", "she", "his", "her",
            "how", "what", "why", "when", "where", "which"
    );

    public record KeywordResult(List<String> keywords, String source) {}

    @Tool(name = "keyword_extract", description = "Extract lightweight keyword candidates from question/context for retrieval hints.")
    public KeywordResult extract(String question, List<String> keyInfo, List<String> entityTerms, int limit) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        StringBuilder sourceBuilder = new StringBuilder();

        if (question != null && !question.isBlank()) {
            sourceBuilder.append(question).append(" ");
        }
        if (keyInfo != null && !keyInfo.isEmpty()) {
            for (String item : keyInfo) {
                if (item == null || item.isBlank()) continue;
                sourceBuilder.append(item).append(" ");
            }
        }
        if (entityTerms != null && !entityTerms.isEmpty()) {
            for (String term : entityTerms) {
                if (term == null || term.isBlank()) continue;
                out.add(term.trim());
            }
        }

        String source = sourceBuilder.toString();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() < 2) continue;
            if (STOPWORDS.contains(token)) continue;
            out.add(token);
            if (limit > 0 && out.size() >= limit) break;
        }

        List<String> keywords = new ArrayList<>(out);
        if (limit > 0 && keywords.size() > limit) {
            keywords = keywords.subList(0, limit);
        }

        return new KeywordResult(keywords, source.isBlank() ? "none" : "question+context");
    }
}
