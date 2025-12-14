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

    // If top QA doc is strong enough, we can directly output its answer without calling LLM
    private static final double MIN_SCORE_FOR_DIRECT_QA = 0.55;

    // Fixed fallback reply when the question is beyond the knowledge base
    private static final String OUT_OF_SCOPE_REPLY = "I can only answer Yuqi's related stuff.";

    // Short instruction for all LLM calls
    private static final String RICH_TEXT_INSTRUCTION =
            "Be brief. Prefer a single plain sentence. " +
                    "Only if structure helps, return a small HTML fragment (no <html>/<body>).";

    /**
     * ✅ Softer system prompt:
     * - Use History/Context as evidence
     * - Allow small inference/paraphrase if evidence supports it
     * - Treat Q/A blocks as authoritative
     * - No new facts beyond evidence
     * - Fallback only when evidence is missing/unrelated
     */
    private static final String SYSTEM_BASE =
            "You are Mr Pot, Yuqi's assistant. " +
                    "Use the provided History and Context as evidence. " +
                    "You may make small, reasonable inferences and paraphrases when the evidence clearly supports it " +
                    "(e.g., resolve pronouns, minor wording differences). " +
                    "If Context contains a Q/A block (e.g., '【问题】...【回答】...'), treat the Answer as authoritative and reuse it " +
                    "for semantically equivalent or closely related questions. " +
                    "Do NOT invent new facts beyond the evidence. " +
                    "If the evidence is missing or unrelated, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". " +
                    "Reply in the user's language. ";

    /**
     * Non-streaming RAG answer:
     * - Retrieve related documents
     * - If out-of-scope, return a fixed fallback reply
     * - ✅ If top doc is chat_qa and score is high, directly output its answer (no LLM call)
     * - Otherwise, call LLM once
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

        // ✅ Direct QA fast-path (better latency + prevents unnecessary fallback)
        Optional<String> directQa = tryDirectQaAnswer(retrieval, Math.max(MIN_SCORE_FOR_DIRECT_QA, minScore));
        if (directQa.isPresent()) {
            String answer = directQa.get();
            chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());

            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    "DIRECT_QA (no LLM call)", answer, latencyMs, false, null, retrieval);

            return new RagAnswer(answer, retrieval.documents());
        }

        ChatClient chatClient = resolveClient(model);
        var history = chatMemoryService.loadHistory(session.id());
        String historyText = chatMemoryService.renderHistory(history);

        String prompt = buildPrompt(request.question(), retrieval, historyText);

        // Tool profile resolved for potential tool usage (kept for future function-calling)
        ToolProfile profile = request.resolveToolProfile(ToolProfile.BASIC_CHAT);
        toolRegistry.getFunctionBeanNamesForProfile(profile);

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
     * - ✅ If direct QA is available, stream that once (no LLM call)
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

        // ✅ Direct QA fast-path in streaming mode
        Optional<String> directQa = tryDirectQaAnswer(retrieval, Math.max(MIN_SCORE_FOR_DIRECT_QA, minScore));
        if (directQa.isPresent()) {
            String answer = directQa.get();
            chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());

            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    "DIRECT_QA (no LLM call)", answer, latencyMs, false, null, retrieval);

            return Flux.just(answer);
        }

        ChatClient chatClient = resolveClient(model);
        var history = chatMemoryService.loadHistory(session.id());
        String historyText = chatMemoryService.renderHistory(history);
        String prompt = buildPrompt(request.question(), retrieval, historyText);

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        String systemPrompt = SYSTEM_BASE + RICH_TEXT_INSTRUCTION;

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
     * Streaming answer WITH logic chain metadata, optimized for lower latency.
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

                            // ✅ Direct QA fast-path: emit one delta and skip LLM stream
                            Optional<String> directQa = tryDirectQaAnswer(retrieval, Math.max(MIN_SCORE_FOR_DIRECT_QA, minScore));
                            if (directQa.isPresent()) {
                                outOfScopeRef.set(false);
                                promptRef.set("DIRECT_QA (no LLM call)");
                                String ans = directQa.get();
                                aggregate.get().append(ans);

                                return Flux.just(new ThinkingEvent(
                                        "answer_delta",
                                        "Direct QA",
                                        ans
                                ));
                            }

                            outOfScopeRef.set(false);

                            String historyText = chatMemoryService.renderHistory(history);
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
     * ✅ Prompt now encourages "supported inference" + reusing QA answers.
     */
    private String buildPrompt(String question, RagRetrievalResult retrieval, String historyText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Context:\n").append(retrieval.context()).append("\n\n");
        sb.append("History:\n").append(historyText).append("\n\n");
        sb.append("Question:\n").append(question).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- Use Context/History as evidence. You may infer if evidence clearly supports it.\n");
        sb.append("- If Context contains a Q/A block, you can output the Q/A Answer directly (or minimal paraphrase).\n");
        sb.append("- If evidence is missing/unrelated, reply exactly: ").append(OUT_OF_SCOPE_REPLY).append("\n");
        return sb.toString();
    }

    private List<Map<String, Object>> summarizeHistory(List<RedisChatMemoryService.StoredMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        int maxMessages = 6;
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
     * ✅ Directly answer from a strong chat_qa document.
     * This prevents the LLM from refusing when the QA answer is present but not "numeric/direct".
     */
    private Optional<String> tryDirectQaAnswer(RagRetrievalResult retrieval, double minScoreForDirect) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
            return Optional.empty();
        }

        ScoredDocument best = retrieval.documents().stream()
                .max((a, b) -> Double.compare(a.score(), b.score()))
                .orElse(null);

        if (best == null || best.document() == null) {
            return Optional.empty();
        }

        if (best.score() < minScoreForDirect) {
            return Optional.empty();
        }

        String docType = best.document().getDocType();
        String content = best.document().getContent();

        // Only do this for QA-like docs (or when markers exist)
        boolean looksQa = (docType != null && docType.equalsIgnoreCase("chat_qa"))
                || (content != null && content.contains("【回答】"));

        if (!looksQa || content == null || content.isBlank()) {
            return Optional.empty();
        }

        Optional<String> extracted = extractQaAnswer(content);
        if (extracted.isEmpty()) {
            return Optional.empty();
        }

        String ans = extracted.get().trim();
        if (ans.isBlank()) {
            return Optional.empty();
        }

        // Keep it short to avoid leaking huge blobs
        if (ans.length() > 400) {
            ans = ans.substring(0, 400) + "...";
        }

        return Optional.of(ans);
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

        // Chinese marker
        Pattern zh = Pattern.compile("【回答】\\s*([\\s\\S]*?)(?:\\n\\s*【|\\z)");
        Matcher m1 = zh.matcher(content);
        if (m1.find()) {
            String ans = m1.group(1);
            if (ans != null && !ans.trim().isBlank()) {
                return Optional.of(ans.trim());
            }
        }

        // English marker
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
