package com.example.MrPot.service;

import com.example.MrPot.model.RagAnswer;
import com.example.MrPot.model.RagAnswerRequest;
import com.example.MrPot.model.RagQueryRequest;
import com.example.MrPot.model.RagRetrievalResult;
import com.example.MrPot.model.ScoredDocument;
import com.example.MrPot.model.ThinkingEvent;
import com.example.MrPot.tools.ToolProfile;
import com.example.MrPot.tools.ToolRegistry;
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

    // Short instruction for all LLM calls
    private static final String RICH_TEXT_INSTRUCTION =
            "Keep it short (1-2 sentences). Prefer plain text. " +
                    "You may add a light playful tone if appropriate. " +
                    "Only if structure helps, return a small HTML fragment (no <html>/<body>).";

    /**
     * ✅ Softer + creative-allowed system prompt:
     * - Use History/Context as evidence
     * - Allow paraphrase + light creative wording
     * - Treat QA blocks as strong evidence
     * - No new facts beyond evidence
     * - Fallback only when evidence missing/unrelated
     */
    private static final String SYSTEM_BASE =
            "You are Mr Pot, Yuqi's assistant. " +
                    "Use the provided History and Context as evidence. " +
                    "You may paraphrase, reframe, and be slightly playful/creative in wording, " +
                    "but you MUST NOT add new factual claims that are not supported by the evidence. " +
                    "If Context includes Q/A blocks (e.g., '【问题】...【回答】...'), treat the Answer as strong evidence and base your reply on it " +
                    "(you may polish or lightly rewrite). " +
                    "If the question asks for a number but evidence only supports a status/statement, answer with the supported status/statement instead of refusing. " +
                    "If the evidence is missing or unrelated, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". " +
                    "Reply in the user's language. ";

    /**
     * Non-streaming RAG answer:
     * - Retrieve related documents
     * - If out-of-scope, return a fixed fallback reply
     * - Otherwise, build prompt with QA candidates + context + history, call LLM once
     * - Persist turn into Redis chat memory
     * - Minimal DB logging for analysis (question/prompt/answer + scored docs)
     */
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
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    "OUT_OF_SCOPE (no LLM call)", OUT_OF_SCOPE_REPLY, latencyMs, true, null, retrieval);

            return new RagAnswer(OUT_OF_SCOPE_REPLY, retrieval.documents());
        }

        ChatClient chatClient = resolveClient(model);
        var history = chatMemoryService.loadHistory(session.id());
        String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);

        // Tool profile resolved for potential tool usage (kept for future function-calling)
        ToolProfile profile = request.resolveToolProfile(ToolProfile.BASIC_CHAT);
        toolRegistry.getFunctionBeanNamesForProfile(profile);

        String prompt = buildPrompt(request.question(), retrieval, historyText);
        String systemPrompt = SYSTEM_BASE + RICH_TEXT_INSTRUCTION;

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

    /**
     * Streaming answer (plain text chunks, no logic chain metadata).
     * - If out-of-scope, stream a single fallback reply
     * - Otherwise, stream LLM deltas and persist the full answer at the end
     * - Minimal DB logging for analysis at stream end (best-effort)
     */
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
        String systemPrompt = SYSTEM_BASE + RICH_TEXT_INSTRUCTION;

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

    /**
     * Streaming answer WITH logic chain metadata.
     *
     * Stages:
     *  - "start"
     *  - "redis"
     *  - "rag"
     *  - "answer_delta"
     *  - "answer_final"
     */
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

                            String systemPrompt = SYSTEM_BASE + RICH_TEXT_INSTRUCTION;

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

    /**
     * ✅ Prompt includes QA candidates as "strong evidence" and asks the LLM to polish them.
     */
    private String buildPrompt(String question, RagRetrievalResult retrieval, String historyText) {
        String contextText = truncate(Optional.ofNullable(retrieval.context()).orElse(""), MAX_CONTEXT_CHARS);

        List<QaCandidate> qaCandidates = extractQaCandidates(retrieval);

        StringBuilder sb = new StringBuilder();

        if (!qaCandidates.isEmpty()) {
            sb.append("QA Candidates (strong evidence extracted from KB):\n");
            for (int i = 0; i < qaCandidates.size(); i++) {
                QaCandidate c = qaCandidates.get(i);
                sb.append("- ")
                        .append("#").append(i + 1)
                        .append(" (score=").append(round3(c.score)).append(", id=").append(c.id).append(", type=").append(c.type).append(")\n")
                        .append("  Answer: ").append(c.answer).append("\n");
            }
            sb.append("\n");
            sb.append("Guidance:\n");
            sb.append("- If a QA Candidate clearly matches the question, base your reply on its Answer.\n");
            sb.append("- You may polish/rewrite and be slightly playful, but do not add new facts.\n");
            sb.append("- If question asks for a count but evidence supports only a status/statement, answer with the supported status/statement.\n\n");
        }

        sb.append("Context:\n").append(contextText).append("\n\n");
        sb.append("History:\n").append(historyText).append("\n\n");
        sb.append("Question:\n").append(question).append("\n\n");
        sb.append("Output rules:\n");
        sb.append("- Use evidence above. No new factual claims.\n");
        sb.append("- If evidence is missing/unrelated, reply exactly: ").append(OUT_OF_SCOPE_REPLY).append("\n");

        return sb.toString();
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

    /**
     * Extract QA candidates from retrieved documents (top by score).
     * We DON'T return them directly; we feed them to the LLM for polishing.
     */
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

            boolean looksQa = "chat_qa".equalsIgnoreCase(type) || content.contains("【回答】") || content.toLowerCase().contains("answer:");
            if (!looksQa) {
                continue;
            }

            Optional<String> ans = extractQaAnswer(content);
            if (ans.isEmpty()) {
                continue;
            }

            out.add(new QaCandidate(
                    String.valueOf(doc.getId()),
                    type,
                    sd.score(),
                    truncate(ans.get().trim(), 400)
            ));

            if (out.size() >= MAX_QA_CANDIDATES) {
                break;
            }
        }

        return out;
    }

    /**
     * Extract answer from common QA formats:
     * - Chinese: 【回答】 ... (until next 【...】 or end)
     * - English: Answer: ... / A: ...
     */
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
