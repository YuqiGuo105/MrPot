package com.example.MrPot.service;

import com.example.MrPot.model.RagAnswer;
import com.example.MrPot.model.RagAnswerRequest;
import com.example.MrPot.model.RagQueryRequest;
import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.ScoredDocument;
import com.example.MrPot.model.ThinkingEvent;
import com.example.MrPot.tools.ToolProfile;
import com.example.MrPot.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final RagRetrievalService ragRetrievalService;
    private final RedisChatMemoryService chatMemoryService;
    private final Map<String, ChatClient> chatClients;
    private final ToolRegistry toolRegistry;

    // --- Minimal analytics logger (2-table design) ---
    private final RagRunLogger runLogger;

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    // Minimum similarity score required to consider a question "in scope" of the KB
    private static final double MIN_KB_SCORE_FOR_ANSWER = 0.15;

    // Fixed fallback reply when the question is beyond the knowledge base
    private static final String OUT_OF_SCOPE_REPLY = "I can only answer Yuqi's related stuff.";

    // Prompt size controls (reduce token usage and latency)
    private static final int MAX_HISTORY_CHARS = 2500;
    private static final int MAX_CONTEXT_CHARS = 7000;

    // Extract up to N QA candidates to guide the LLM
    private static final int MAX_QA_CANDIDATES = 3;

    // Keep only a few latest log rows in prompt (token saver)
    private static final int MAX_LOG_ROWS_IN_PROMPT = 8;

    private static final ObjectMapper OM = new ObjectMapper();

    // Keep style minimal to reduce tokens
    private static final String RESPONSE_STYLE =
            "Be concise. Plain text. Bullets only if helpful. Raw https:// links only.";

    /**
     * Low-token, accuracy-first system prompt:
     * - Evidence grounded for Yuqi-specific/private facts
     * - Allow friendly human-like tone
     * - Allow normal answers for general how-to/coding/capability questions
     * - Treat Q/A blocks as strong evidence
     * - Strict fallback string when needed
     */
    private static final String SYSTEM_BASE =
            "You are Mr Pot, Yuqi's assistant. " +
                    "Use History/Context as evidence; do not add unsupported facts. " +
                    "You may sound friendly, slightly playful, and human-like. " +
                    "If Context has Q/A blocks (【问题】/【回答】), treat the Answer as strong evidence; you may polish. " +
                    "If asked for a number but evidence only supports a status/statement, answer that supported status/statement. " +
                    "For general how-to/coding/capability questions: answer normally. " +
                    "If Yuqi-specific/private facts lack evidence, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". " +
                    "Reply in the user's language. ";

    public RagAnswer answer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();

        RagRetrievalResult retrieval = ragRetrievalService.retrieve(toQuery(request));

        if (isOutOfScope(retrieval)) {
            chatMemoryService.appendTurn(session.id(), request.question(), OUT_OF_SCOPE_REPLY, session.temporary());

            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(
                    session.id(), request.question(), model, topK, minScore,
                    "OUT_OF_SCOPE (no LLM call)", OUT_OF_SCOPE_REPLY, latencyMs,
                    true, null, retrieval
            );

            return new RagAnswer(OUT_OF_SCOPE_REPLY, retrieval.documents());
        }

        ChatClient chatClient = resolveClient(model);

        var history = chatMemoryService.loadHistory(session.id());
        String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);

        // Resolve tool profile for future function-calling (kept)
        ToolProfile profile = request.resolveToolProfile(ToolProfile.BASIC_CHAT);
        toolRegistry.getFunctionBeanNamesForProfile(profile);

        String prompt = buildPrompt(request.question(), retrieval, historyText);
        String systemPrompt = SYSTEM_BASE + RESPONSE_STYLE;

        String answer;
        String error = null;
        try {
            var response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(prompt)
                    .call();
            answer = response.content();
        } catch (Exception ex) {
            answer = "";
            error = ex.toString();
            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    prompt, answer, latencyMs, false, error, retrieval);
            throw ex;
        }

        chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());

        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        safeLogOnce(session.id(), request.question(), model, topK, minScore,
                prompt, answer, latencyMs, false, null, retrieval);

        return new RagAnswer(answer, retrieval.documents());
    }

    public Flux<String> streamAnswer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();

        RagRetrievalResult retrieval = ragRetrievalService.retrieve(toQuery(request));

        if (isOutOfScope(retrieval)) {
            chatMemoryService.appendTurn(session.id(), request.question(), OUT_OF_SCOPE_REPLY, session.temporary());

            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    "OUT_OF_SCOPE (no LLM call)", OUT_OF_SCOPE_REPLY, latencyMs, true, null, retrieval);

            return Flux.just(OUT_OF_SCOPE_REPLY);
        }

        ChatClient chatClient = resolveClient(model);

        var history = chatMemoryService.loadHistory(session.id());
        String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);

        String prompt = buildPrompt(request.question(), retrieval, historyText);
        String systemPrompt = SYSTEM_BASE + RESPONSE_STYLE;

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(prompt)
                .stream()
                .content()
                .doOnNext(delta -> aggregate.get().append(delta))
                .doOnError(ex -> errorRef.set(ex.toString()))
                .doFinally(signalType -> {
                    String finalAnswer = aggregate.get().toString();
                    chatMemoryService.appendTurn(session.id(), request.question(), finalAnswer, session.temporary());

                    Mono.fromRunnable(() -> {
                                int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
                                safeLogOnce(session.id(), request.question(), model, topK, minScore,
                                        prompt, finalAnswer, latencyMs, false, errorRef.get(), retrieval);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                });
    }

    public Flux<ThinkingEvent> streamAnswerWithLogic(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();
        ChatClient chatClient = resolveClient(model);

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());

        AtomicReference<String> promptRef = new AtomicReference<>(null);
        AtomicReference<Boolean> outOfScopeRef = new AtomicReference<>(false);
        AtomicReference<RagRetrievalResult> retrievalRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        Mono<List<RedisChatMemoryService.StoredMessage>> historyMono =
                Mono.fromCallable(() -> chatMemoryService.loadHistory(session.id()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        Mono<RagRetrievalResult> retrievalMono =
                Mono.fromCallable(() -> ragRetrievalService.retrieve(toQuery(request)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        Flux<ThinkingEvent> startStep = Flux.just(
                new ThinkingEvent("start", "Init", Map.of("ts", System.currentTimeMillis()))
        );

        Flux<ThinkingEvent> redisStep = historyMono.flatMapMany(history ->
                Flux.just(new ThinkingEvent("redis", "History", summarizeHistory(history)))
        );

        Flux<ThinkingEvent> ragStep = retrievalMono.flatMapMany(retrieval ->
                Flux.just(new ThinkingEvent("rag", "Retrieval", summarizeRetrieval(retrieval)))
        );

        Flux<ThinkingEvent> answerDeltaStep =
                Mono.zip(historyMono, retrievalMono)
                        .flatMapMany(tuple -> {
                            var history = tuple.getT1();
                            var retrieval = tuple.getT2();

                            retrievalRef.set(retrieval);

                            if (isOutOfScope(retrieval)) {
                                outOfScopeRef.set(true);
                                promptRef.set("OUT_OF_SCOPE (no LLM call)");
                                aggregate.get().append(OUT_OF_SCOPE_REPLY);

                                return Flux.just(new ThinkingEvent(
                                        "answer_delta",
                                        "Out-of-scope",
                                        OUT_OF_SCOPE_REPLY
                                ));
                            }

                            outOfScopeRef.set(false);

                            String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);
                            String prompt = buildPrompt(request.question(), retrieval, historyText);
                            promptRef.set(prompt);

                            String systemPrompt = SYSTEM_BASE + RESPONSE_STYLE;

                            return chatClient.prompt()
                                    .system(systemPrompt)
                                    .user(prompt)
                                    .stream()
                                    .content()
                                    .map(delta -> {
                                        aggregate.get().append(delta);
                                        return new ThinkingEvent("answer_delta", "Generating", delta);
                                    });
                        })
                        .doOnError(ex -> errorRef.set(ex.toString()))
                        .doFinally(signalType -> {
                            String finalAnswer = aggregate.get().toString();
                            chatMemoryService.appendTurn(session.id(), request.question(), finalAnswer, session.temporary());

                            Mono.fromRunnable(() -> {
                                        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
                                        safeLogOnce(
                                                session.id(),
                                                request.question(),
                                                model,
                                                topK,
                                                minScore,
                                                promptRef.get(),
                                                finalAnswer,
                                                latencyMs,
                                                Boolean.TRUE.equals(outOfScopeRef.get()),
                                                errorRef.get(),
                                                retrievalRef.get()
                                        );
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                        });

        Flux<ThinkingEvent> finalStep = Flux.defer(() ->
                Flux.just(new ThinkingEvent("answer_final", "Done", aggregate.get().toString()))
        );

        return Flux.concat(startStep, redisStep, ragStep, answerDeltaStep, finalStep);
    }

    private RagQueryRequest toQuery(RagAnswerRequest request) {
        return new RagQueryRequest(
                request.question(),
                request.resolveTopK(DEFAULT_TOP_K),
                request.resolveMinScore(DEFAULT_MIN_SCORE)
        );
    }

    private ChatClient resolveClient(String model) {
        String key = Optional.ofNullable(model)
                .map(String::toLowerCase)
                .orElse(RagAnswerRequest.DEFAULT_MODEL);

        if (!chatClients.isEmpty()) {
            if (chatClients.containsKey(key + "ChatClient")) {
                return chatClients.get(key + "ChatClient");
            }
            if (chatClients.containsKey(key)) {
                return chatClients.get(key);
            }
        }

        ChatClient fallback = chatClients.get(RagAnswerRequest.DEFAULT_MODEL + "ChatClient");
        if (fallback != null) {
            return fallback;
        }

        return chatClients.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChatClient beans are available"));
    }

    private String buildPrompt(String question, RagRetrievalResult retrieval, String historyText) {
        String rawContext = Optional.ofNullable(retrieval.context()).orElse("");
        String contextText = compactLogContext(rawContext, MAX_CONTEXT_CHARS);

        List<QaCandidate> qaCandidates = extractQaCandidates(retrieval);

        StringBuilder sb = new StringBuilder();

        // Keep QA hints short (token saver)
        if (!qaCandidates.isEmpty()) {
            sb.append("QA Hints:\n");
            for (int i = 0; i < qaCandidates.size(); i++) {
                QaCandidate c = qaCandidates.get(i);
                sb.append("- #").append(i + 1)
                        .append(" (score=").append(round3(c.score))
                        .append(", id=").append(c.id)
                        .append(", type=").append(c.type)
                        .append(") ")
                        .append(truncate(c.answer, 260))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("Context:\n").append(contextText).append("\n\n");
        sb.append("History:\n").append(historyText).append("\n\n");
        sb.append("Q:\n").append(question).append("\n");

        return sb.toString();
    }

    // Compress log-like structured context into a few rows to reduce tokens
    private String compactLogContext(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw.trim();
        if (!(s.startsWith("[") || s.startsWith("{"))) {
            return truncate(s, maxChars);
        }

        try {
            JsonNode root = OM.readTree(s);

            List<JsonNode> rows = new ArrayList<>();
            if (root.isArray()) root.forEach(rows::add);
            else if (root.isObject()) rows.add(root);
            else return truncate(s, maxChars);

            record Row(String id, long createdAt, String q, String a) {}

            List<Row> out = new ArrayList<>();
            for (JsonNode n : rows) {
                if (!n.isObject()) continue;

                String q = n.path("question").asText("");
                String a = n.path("answer").asText("");
                if (a != null && a.trim().equals(OUT_OF_SCOPE_REPLY)) continue; // ignore stored fallback

                long t = n.path("createdAt").asLong(0L);
                String id = n.path("id").asText("");

                if ((q == null || q.isBlank()) && (a == null || a.isBlank())) continue;
                out.add(new Row(id, t, q, a));
            }

            // Newest first
            out.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));

            StringBuilder sb = new StringBuilder();
            sb.append("Log rows:\n");
            int limit = Math.min(MAX_LOG_ROWS_IN_PROMPT, out.size());
            for (int i = 0; i < limit; i++) {
                Row r = out.get(i);
                sb.append("- (id=").append(r.id()).append(", t=").append(r.createdAt()).append(") ")
                        .append("Q: ").append(truncate(r.q(), 160)).append(" ")
                        .append("A: ").append(truncate(r.a(), 220))
                        .append("\n");
            }

            return truncate(sb.toString(), maxChars);
        } catch (Exception ignore) {
            return truncate(s, maxChars);
        }
    }

    private List<Map<String, Object>> summarizeHistory(List<RedisChatMemoryService.StoredMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        int maxMessages = 6; // last 3 turns (user+assistant)
        int startIdx = Math.max(0, history.size() - maxMessages);

        return history.subList(startIdx, history.size()).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.role(),
                        "content", m.content()
                ))
                .toList();
    }

    private List<Map<String, Object>> summarizeRetrieval(RagRetrievalResult retrieval) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
            return List.of();
        }

        return retrieval.documents().stream()
                .map(sd -> {
                    var doc = sd.document();
                    String content = doc.getContent();
                    String preview;
                    if (content == null) {
                        preview = "";
                    } else if (content.length() > 200) {
                        preview = content.substring(0, 200) + "...";
                    } else {
                        preview = content;
                    }

                    return Map.<String, Object>of(
                            "id", doc.getId(),
                            "type", doc.getDocType(),
                            "score", sd.score(),
                            "preview", preview
                    );
                })
                .toList();
    }

    private boolean isOutOfScope(RagRetrievalResult retrieval) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
            return true;
        }

        double topScore = retrieval.documents().stream()
                .mapToDouble(ScoredDocument::score)
                .max()
                .orElse(0.0);

        return topScore < MIN_KB_SCORE_FOR_ANSWER;
    }

    private List<QaCandidate> extractQaCandidates(RagRetrievalResult retrieval) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
            return List.of();
        }

        List<ScoredDocument> sorted = retrieval.documents().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .toList();

        List<QaCandidate> out = new ArrayList<>();
        for (ScoredDocument sd : sorted) {
            if (sd == null || sd.document() == null) {
                continue;
            }

            var doc = sd.document();
            String type = Optional.ofNullable(doc.getDocType()).orElse("");
            String content = Optional.ofNullable(doc.getContent()).orElse("");

            boolean looksQa =
                    "chat_qa".equalsIgnoreCase(type)
                            || content.contains("【回答】")
                            || content.toLowerCase().contains("answer:");

            if (!looksQa) {
                continue;
            }

            Optional<String> ans = extractQaAnswer(content);
            if (ans.isEmpty()) {
                continue;
            }

            String a = ans.get().trim();
            if (a.equals(OUT_OF_SCOPE_REPLY)) {
                continue; // do not treat fallback as evidence
            }

            out.add(new QaCandidate(
                    String.valueOf(doc.getId()),
                    type,
                    sd.score(),
                    truncate(a, 400)
            ));

            if (out.size() >= MAX_QA_CANDIDATES) {
                break;
            }
        }

        return out;
    }

    private Optional<String> extractQaAnswer(String content) {
        if (content == null) {
            return Optional.empty();
        }

        // Chinese marker: 【回答】 ... (until next 【...】 or end)
        Pattern zh = Pattern.compile("【回答】\\s*([\\s\\S]*?)(?:\\n\\s*【|\\z)");
        Matcher m1 = zh.matcher(content);
        if (m1.find()) {
            String ans = m1.group(1);
            if (ans != null && !ans.trim().isBlank()) {
                return Optional.of(ans.trim());
            }
        }

        // English marker: Answer: ... / A: ...
        Pattern en = Pattern.compile("(?i)(?:^|\\n)\\s*(?:answer\\s*:|a\\s*: )\\s*([\\s\\S]*?)(?:\\n\\s*(?:question\\s*:|q\\s*:|context\\s*:)|\\z)");
        Matcher m2 = en.matcher(content);
        if (m2.find()) {
            String ans = m2.group(1);
            if (ans != null && !ans.trim().isBlank()) {
                return Optional.of(ans.trim());
            }
        }

        return Optional.empty();
    }

    private String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "...";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record QaCandidate(String id, String type, double score, String answer) {}

    private void safeLogOnce(
            String sessionId,
            String question,
            String model,
            int topK,
            double minScore,
            String promptText,
            String answerText,
            Integer latencyMs,
            boolean outOfScope,
            String error,
            RagRetrievalResult retrieval
    ) {
        try {
            runLogger.logOnce(
                    sessionId,
                    question,
                    model,
                    topK,
                    minScore,
                    promptText,
                    answerText,
                    latencyMs,
                    outOfScope,
                    error,
                    retrieval
            );
        } catch (Exception ignored) {
            // Logging must never break answering
        }
    }
}
