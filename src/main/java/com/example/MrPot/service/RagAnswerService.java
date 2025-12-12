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

@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final RagRetrievalService ragRetrievalService;
    private final RedisChatMemoryService chatMemoryService;
    private final Map<String, ChatClient> chatClients;
    private final ToolRegistry toolRegistry;

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    // Minimum similarity score required to consider a question "in scope" of the KB
    private static final double MIN_KB_SCORE_FOR_ANSWER = 0.15;

    // Fixed fallback reply when the question is beyond the knowledge base
    private static final String OUT_OF_SCOPE_REPLY = "I can only answer Yuqi's related stuff.";

    // Short instruction for all LLM calls
    private static final String RICH_TEXT_INSTRUCTION =
            "Be brief. Prefer a single plain sentence. " +
                    "Only if structure helps, return a small HTML fragment (no <html>/<body>).";

    // Shared base system prompt
    private static final String SYSTEM_BASE =
            "You are Mr Pot, Yuqi's assistant. Use only the given history and context. " +
                    "If they do not contain the answer, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". ";

    /**
     * Non-streaming RAG answer:
     * - Retrieve related documents
     * - If out-of-scope, return a fixed fallback reply
     * - Otherwise, build prompt with history + context, call LLM once
     * - Persist turn into Redis chat memory
     */
    public RagAnswer answer(RagAnswerRequest request) {
        // 1) Retrieve RAG results first
        RagRetrievalResult retrieval = ragRetrievalService.retrieve(toQuery(request));
        RagAnswerRequest.ResolvedSession session = request.resolveSession();

        // 2) If beyond the KB (low score / no docs), short-circuit with fixed reply
        if (isOutOfScope(retrieval)) {
            chatMemoryService.appendTurn(
                    session.id(),
                    request.question(),
                    OUT_OF_SCOPE_REPLY,
                    session.temporary()
            );
            return new RagAnswer(OUT_OF_SCOPE_REPLY, retrieval.documents());
        }

        // 3) Otherwise, call LLM
        ChatClient chatClient = resolveClient(request.resolveModel());
        var history = chatMemoryService.loadHistory(session.id());
        String prompt = buildPrompt(
                request.question(),
                retrieval,
                chatMemoryService.renderHistory(history)
        );

        // Tool profile resolved for potential tool usage
        ToolProfile profile = request.resolveToolProfile(ToolProfile.BASIC_CHAT);
        List<String> toolBeanNames = toolRegistry.getFunctionBeanNamesForProfile(profile);
        // TODO: wire toolBeanNames into ChatClient function calling if needed

        var response = chatClient.prompt()
                .system(SYSTEM_BASE + RICH_TEXT_INSTRUCTION)
                .user(prompt)
                .call();

        String answer = response.content();
        chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());
        return new RagAnswer(answer, retrieval.documents());
    }

    /**
     * Streaming answer (plain text chunks, no logic chain metadata).
     * - If out-of-scope, stream a single fallback reply
     * - Otherwise, stream LLM deltas and persist the full answer at the end
     */
    public Flux<String> streamAnswer(RagAnswerRequest request) {
        RagRetrievalResult retrieval = ragRetrievalService.retrieve(toQuery(request));
        RagAnswerRequest.ResolvedSession session = request.resolveSession();

        // If beyond KB, just stream the fallback reply once
        if (isOutOfScope(retrieval)) {
            chatMemoryService.appendTurn(
                    session.id(),
                    request.question(),
                    OUT_OF_SCOPE_REPLY,
                    session.temporary()
            );
            return Flux.just(OUT_OF_SCOPE_REPLY);
        }

        ChatClient chatClient = resolveClient(request.resolveModel());
        var history = chatMemoryService.loadHistory(session.id());
        String prompt = buildPrompt(
                request.question(),
                retrieval,
                chatMemoryService.renderHistory(history)
        );

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());

        return chatClient.prompt()
                .system(SYSTEM_BASE + RICH_TEXT_INSTRUCTION)
                .user(prompt)
                .stream()
                .content()
                // Collect all deltas so we can persist the full answer at the end
                .doOnNext(delta -> aggregate.get().append(delta))
                .doFinally(signalType -> chatMemoryService.appendTurn(
                        session.id(),
                        request.question(),
                        aggregate.get().toString(),
                        session.temporary()
                ));
    }

    /**
     * Streaming answer WITH logic chain metadata, optimized for lower latency.
     *
     * Stages:
     *  - "start": request accepted, pipeline initialized
     *  - "redis": loaded previous conversation from Redis
     *  - "rag": searched knowledge base for related documents
     *  - "answer_delta": LLM token stream (or single fallback delta when out-of-scope)
     *  - "answer_final": final aggregated answer
     */
    public Flux<ThinkingEvent> streamAnswerWithLogic(RagAnswerRequest request) {
        // Resolve session and client up front (cheap operations)
        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        ChatClient chatClient = resolveClient(request.resolveModel());

        // Per-subscription buffer for the aggregated answer text
        AtomicReference<StringBuilder> aggregate =
                new AtomicReference<>(new StringBuilder());

        // Async Redis history load on boundedElastic
        Mono<List<RedisChatMemoryService.StoredMessage>> historyMono =
                Mono.fromCallable(() -> chatMemoryService.loadHistory(session.id()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        // Async RAG retrieval on boundedElastic (embedding + DB are blocking)
        Mono<RagRetrievalResult> retrievalMono =
                Mono.fromCallable(() -> ragRetrievalService.retrieve(toQuery(request)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        // Stage 0: "start"
        Flux<ThinkingEvent> startStep = Flux.just(
                new ThinkingEvent(
                        "start",
                        "Init",
                        Map.of("ts", System.currentTimeMillis())
                )
        );

        // Stage 1: "redis"
        Flux<ThinkingEvent> redisStep = historyMono.flatMapMany(history ->
                Flux.just(
                        new ThinkingEvent(
                                "redis",
                                "History",
                                summarizeHistory(history)
                        )
                )
        );

        // Stage 2: "rag"
        Flux<ThinkingEvent> ragStep = retrievalMono.flatMapMany(retrieval ->
                Flux.just(
                        new ThinkingEvent(
                                "rag",
                                "Retrieval",
                                summarizeRetrieval(retrieval)
                        )
                )
        );

        // Stage 3: "answer_delta"
        Flux<ThinkingEvent> answerDeltaStep =
                Mono.zip(historyMono, retrievalMono)
                        .flatMapMany(tuple -> {
                            var history = tuple.getT1();
                            var retrieval = tuple.getT2();

                            // If beyond KB: no LLM call, emit a single fallback delta
                            if (isOutOfScope(retrieval)) {
                                aggregate.get().append(OUT_OF_SCOPE_REPLY);
                                return Flux.just(
                                        new ThinkingEvent(
                                                "answer_delta",
                                                "Out-of-scope",
                                                OUT_OF_SCOPE_REPLY
                                        )
                                );
                            }

                            // Otherwise, call LLM
                            String historyText = chatMemoryService.renderHistory(history);
                            String prompt = buildPrompt(
                                    request.question(),
                                    retrieval,
                                    historyText
                            );

                            return chatClient.prompt()
                                    .system(SYSTEM_BASE + RICH_TEXT_INSTRUCTION)
                                    .user(prompt)
                                    .stream()
                                    .content()
                                    .map(delta -> {
                                        // Aggregate all deltas into a single final answer
                                        aggregate.get().append(delta);
                                        return new ThinkingEvent(
                                                "answer_delta",
                                                "Generating",
                                                delta
                                        );
                                    });
                        })
                        .doFinally(signalType -> {
                            // Persist the full answer in Redis chat memory once streaming finishes
                            chatMemoryService.appendTurn(
                                    session.id(),
                                    request.question(),
                                    aggregate.get().toString(),
                                    session.temporary()
                            );
                        });

        // Stage 4: "answer_final"
        Flux<ThinkingEvent> finalStep = Flux.defer(() ->
                Flux.just(
                        new ThinkingEvent(
                                "answer_final",
                                "Done",
                                aggregate.get().toString()
                        )
                )
        );

        // Final order:
        //  start → redis → rag → answer_delta* → answer_final
        return Flux.concat(startStep, redisStep, ragStep, answerDeltaStep, finalStep);
    }

    /**
     * Convert a high-level RAG answer request into a retrieval-only query.
     */
    private RagQueryRequest toQuery(RagAnswerRequest request) {
        return new RagQueryRequest(
                request.question(),
                request.resolveTopK(DEFAULT_TOP_K),
                request.resolveMinScore(DEFAULT_MIN_SCORE)
        );
    }

    /**
     * Resolve ChatClient bean based on requested model identifier.
     */
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
     * Build the combined prompt:
     *  - conversation history
     *  - retrieved KB context
     *  - user question
     */
    private String buildPrompt(String question, RagRetrievalResult retrieval, String historyText) {
        StringBuilder sb = new StringBuilder();
        sb.append("History:\n").append(historyText).append("\n\n");
        sb.append("Context:\n").append(retrieval.context()).append("\n\n");
        sb.append("Q:\n").append(question).append("\n");
        sb.append("Answer using only History and Context.");
        return sb.toString();
    }

    /**
     * Summarize chat history for UI / debug payload.
     */
    private List<Map<String, Object>> summarizeHistory(List<RedisChatMemoryService.StoredMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        int maxMessages = 6; // e.g. last 3 turns (user+assistant)
        int startIdx = Math.max(0, history.size() - maxMessages);

        return history.subList(startIdx, history.size()).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.role(),
                        "content", m.content()
                ))
                .toList();
    }

    /**
     * Summarize retrieval result for UI / debug payload.
     */
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

    /**
     * Determine whether the retrieval result is "out of scope" for the KB.
     */
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
}
