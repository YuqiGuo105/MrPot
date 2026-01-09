package com.example.MrPot.service;

import com.example.MrPot.model.*;
import com.example.MrPot.tools.ActionPlanTools;
import com.example.MrPot.tools.AnswerOutlineTools;
import com.example.MrPot.tools.AssumptionCheckTools;
import com.example.MrPot.tools.CodeSearchTools;
import com.example.MrPot.tools.ConflictDetectTools;
import com.example.MrPot.tools.ContextCompressTools;
import com.example.MrPot.tools.EntityResolveTools;
import com.example.MrPot.tools.EvidenceGapTools;
import com.example.MrPot.tools.EvidenceRerankTools;
import com.example.MrPot.tools.FileTools;
import com.example.MrPot.tools.KbTools;
import com.example.MrPot.tools.MemoryTools;
import com.example.MrPot.tools.PrivacySanitizerTools;
import com.example.MrPot.tools.QuestionDecomposerTools;
import com.example.MrPot.tools.RoadmapPlannerTools;
import com.example.MrPot.tools.ScopeGuardTools;
import com.example.MrPot.tools.TrackCorrectTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);

    private final KbTools kbTools;
    private final MemoryTools memoryTools;
    private final FileTools fileTools;
    private final ScopeGuardTools scopeGuardTools;
    private final EntityResolveTools entityResolveTools;
    private final ContextCompressTools contextCompressTools;
    private final RoadmapPlannerTools roadmapPlannerTools;
    private final PrivacySanitizerTools privacySanitizerTools;
    private final QuestionDecomposerTools questionDecomposerTools;
    private final EvidenceRerankTools evidenceRerankTools;
    private final ConflictDetectTools conflictDetectTools;
    private final CodeSearchTools codeSearchTools;
    private final TrackCorrectTools trackCorrectTools;
    private final EvidenceGapTools evidenceGapTools;
    private final AnswerOutlineTools answerOutlineTools;
    private final AssumptionCheckTools assumptionCheckTools;
    private final ActionPlanTools actionPlanTools;
    private final RedisChatMemoryService chatMemoryService;
    private final Map<String, ChatClient> chatClients;

    // --- Minimal analytics logger (2-table design) ---
    private final RagRunLogger runLogger;
    private final LogIngestionClient logIngestionClient;

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    // KB threshold: below => treat as weak/out-of-scope KB
    private static final double MIN_KB_SCORE_FOR_ANSWER = 0.15;

    private static final String OUT_OF_SCOPE_REPLY = "I can only answer Yuqi's related stuff.";

    // Prompt budgets
    private static final int MAX_HISTORY_CHARS = 2500;
    private static final int MAX_HISTORY_TURNS = 12;
    private static final int MAX_CONTEXT_CHARS = 7000;
    private static final int MAX_LOG_ROWS_IN_PROMPT = 8;

    // Attachments
    private static final int MAX_FILE_URLS = 2;
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(30);

    // File insights budgets
    private static final int MAX_FILE_CONTEXT_CHARS = 3500;
    private static final int MAX_FILE_TERM_COUNT = 6;     // queries+keywords for retrieval expansion
    private static final int PROGRESS_EXCERPT_CHARS = 900;
    private static final int PROGRESS_TERMS = 8;

    // Retrieval refinement
    private static final double REFINE_TOP_SCORE_THRESHOLD = 0.25;
    private static final int MAX_FILE_QUERY_CHARS = 480;

    // QA hints
    private static final int MAX_QA_CANDIDATES = 3;
    private static final int QA_MATCH_HIGH = 6;
    private static final int QA_MATCH_MED = 3;

    private static final ObjectMapper OM = new ObjectMapper();

    private record FileItem(
            String url,
            String name,
            String mime,
            String keyText,
            List<String> keywords,
            List<String> queries,
            String error
    ) {}

    private record FileInsights(
            List<String> retrievalTerms,
            List<String> progressTerms,
            String promptContext,
            String promptExcerpt
    ) {}

    private record PreparedContext(
            RagRetrievalResult retrieval,
            String fileText,
            boolean outOfScopeKb,
            boolean hasAnyRef,
            ScopeGuardTools.ScopeGuardResult scopeGuardResult,
            List<String> entityTerms,
            String compressedContext,
            SanitizedEvidence sanitizedEvidence,
            List<String> keyInfo,
            EvidenceGapTools.EvidenceGapResult evidenceGap,
            AnswerOutlineTools.OutlineResult answerOutline,
            AssumptionCheckTools.AssumptionResult assumptionResult,
            ActionPlanTools.ActionPlanResult actionPlan
    ) { }

    private record SanitizedEvidence(
            String context,
            String fileText,
            Map<String, Integer> hits
    ) {
        String combined() {
            StringBuilder sb = new StringBuilder();
            if (context != null && !context.isBlank()) sb.append(context);
            if (fileText != null && !fileText.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(fileText);
            }
            return sb.toString();
        }
    }

    private static final String SYSTEM_PROMPT =
            "You are Mr Pot, Yuqi's assistant and a general-purpose helpful AI. " +
                    "Reply in the user's language. Be friendly, slightly playful, and human-like (no insults; no made-up facts). " +
                    "Scope: if the question is about Yuqi (his blog/projects/work/background/private facts) => Yuqi-mode; otherwise General-mode. " +
                    "Output: Prefer plain text for short/simple replies. Use WYSIWYG HTML only when needed for structure (multiple paragraphs/lists/tables) or notation (formulas). " +
                    "WYSIWYG rules: return a single HTML fragment (no Markdown, no outer <html>/<body>). Use <p>, <br>, <ul><li>, <strong>/<em>, <code>, <pre><code>, and <sup>/<sub>. " +
                    "Safety: never include <script>/<style>/<iframe> or inline event handlers. " +
                    "Yuqi-mode: use only evidence from CTX/FILE/HIS; never invent. If CTX has Q/A blocks (【问题】/【回答】), treat 【回答】 as strong evidence and you may polish. " +
                    "If asked for a number but evidence only supports a status/statement, answer the supported status/statement. " +
                    "If a Yuqi-mode question lacks evidence, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". " +
                    "General-mode: for common sense/general knowledge/how-to/coding/science, answer normally even if CTX/FILE/HIS are empty or irrelevant.";

    private static final int MAX_KEY_INFO = 6;
    private static final int MAX_EVIDENCE_PREVIEW_CHARS = 1000;

    // --------------------------
    // Blocking answer
    // --------------------------
    public RagAnswer answer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();
        boolean deepThinking = request.resolveDeepThinking(false);
        RagAnswerRequest.ScopeMode scopeMode = request.resolveScopeMode();

        PreparedContext ctx = prepareContextMono(request, topK, minScore, deepThinking)
                .blockOptional()
                .orElse(new PreparedContext(
                        new RagRetrievalResult("", List.of(), ""),
                        "",
                        true,
                        false,
                        ScopeGuardTools.ScopeGuardResult.scopedDefault(),
                        List.of(),
                        "",
                        new SanitizedEvidence("", "", Map.of()),
                        List.of(),
                        new EvidenceGapTools.EvidenceGapResult(List.of(), List.of(), "skipped"),
                        new AnswerOutlineTools.OutlineResult(List.of(), "bullets"),
                        new AssumptionCheckTools.AssumptionResult(List.of(), "low"),
                        new ActionPlanTools.ActionPlanResult(List.of(), "bullets")
                ));

        ChatClient chatClient = resolveClient(model);

        String historyText = truncate(memoryTools.recent(session.id(), MAX_HISTORY_TURNS), MAX_HISTORY_CHARS);

        boolean noEvidence = !ctx.hasAnyRef;
        String prompt = buildPrompt(
                request.question(),
                ctx.retrieval,
                historyText,
                ctx.fileText,
                noEvidence,
                ctx.outOfScopeKb,
                deepThinking,
                scopeMode,
                ctx.scopeGuardResult,
                ctx.entityTerms,
                ctx.compressedContext,
                ctx.keyInfo,
                ctx.evidenceGap,
                ctx.answerOutline,
                ctx.assumptionResult,
                ctx.actionPlan
        );

        String answer;
        String error = null;
        try {
            var response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call();
            answer = response.content();
        } catch (Exception ex) {
            answer = "";
            error = ex.toString();
            int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            safeLogOnce(session.id(), request.question(), model, topK, minScore,
                    prompt, answer, latencyMs, noEvidence, error, ctx.retrieval);
            throw ex;
        }

        chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());

        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        safeLogOnce(session.id(), request.question(), model, topK, minScore,
                prompt, answer, latencyMs, noEvidence, null, ctx.retrieval);

        return new RagAnswer(answer, ctx.retrieval == null ? List.of() : ctx.retrieval.documents());
    }

    // --------------------------
    // Streaming answer (no logic events)
    // --------------------------
    public Flux<String> streamAnswer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();
        boolean deepThinking = request.resolveDeepThinking(false);
        RagAnswerRequest.ScopeMode scopeMode = request.resolveScopeMode();

        return prepareContextMono(request, topK, minScore, deepThinking)
                .flatMapMany(ctx -> {
                    ChatClient chatClient = resolveClient(model);

                    String historyText = truncate(memoryTools.recent(session.id(), MAX_HISTORY_TURNS), MAX_HISTORY_CHARS);

                    boolean noEvidence = !ctx.hasAnyRef;
                    String prompt = buildPrompt(
                            request.question(),
                            ctx.retrieval,
                            historyText,
                                    ctx.fileText,
                                    noEvidence,
                                    ctx.outOfScopeKb,
                                    deepThinking,
                                    scopeMode,
                                    ctx.scopeGuardResult,
                                    ctx.entityTerms,
                                    ctx.compressedContext,
                                    ctx.keyInfo,
                                    ctx.evidenceGap,
                                    ctx.answerOutline,
                                    ctx.assumptionResult,
                                    ctx.actionPlan
                            );

                    AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());
                    AtomicReference<String> errorRef = new AtomicReference<>(null);

                    return chatClient.prompt()
                            .system(SYSTEM_PROMPT)
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
                                                    prompt, finalAnswer, latencyMs, noEvidence, errorRef.get(), ctx.retrieval);
                                        })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe();
                            });
                });
    }

    // --------------------------
    // Streaming with logic events
    // --------------------------
    public Flux<ThinkingEvent> streamAnswerWithLogic(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();
        boolean deepThinking = request.resolveDeepThinking(false);
        RagAnswerRequest.ScopeMode scopeMode = request.resolveScopeMode();
        ChatClient chatClient = resolveClient(model);

        List<String> urls = request.resolveFileUrls(MAX_FILE_URLS);

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());
        AtomicReference<String> promptRef = new AtomicReference<>(null);
        AtomicReference<Boolean> noEvidenceRef = new AtomicReference<>(false);
        AtomicReference<RagRetrievalResult> retrievalRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        Mono<String> historyMono =
                Mono.fromCallable(() -> memoryTools.recent(session.id(), MAX_HISTORY_TURNS))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        Mono<RoadmapPlannerTools.RoadmapPlan> roadmapPlanMono = deepThinking
                ? Mono.fromCallable(() -> roadmapPlannerTools.plan(request.question(), scopeMode, true, !urls.isEmpty()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache()
                : Mono.just(defaultRoadmapPlan(!urls.isEmpty()));

        Mono<List<String>> decomposeMono = roadmapPlanMono.flatMap(plan -> {
            if (!deepThinking || !plan.useQuestionDecompose()) {
                return Mono.just(List.<String>of());
            }
            return Mono.fromCallable(() -> questionDecomposerTools.decompose(request.question()))
                    .subscribeOn(Schedulers.boundedElastic());
        }).cache();

        Mono<CodeSearchTools.CodeSearchResult> codeSearchMono = roadmapPlanMono.flatMap(plan -> {
            if (!deepThinking || !plan.useCodeSearch()) {
                return Mono.just(new CodeSearchTools.CodeSearchResult("skipped", List.of()));
            }
            return Mono.fromCallable(() -> codeSearchTools.search(request.question(), 4))
                    .subscribeOn(Schedulers.boundedElastic());
        }).cache();

        Mono<List<FileItem>> filesMono = roadmapPlanMono.flatMap(plan ->
                plan.useFiles() ? extractFilesMono(urls) : Mono.just(List.<FileItem>of())
        ).cache();
        Mono<FileInsights> fileInsightsMono = filesMono.map(this::summarizeFileInsights).cache();
        Mono<String> fileTextMono = fileInsightsMono.map(FileInsights::promptContext).defaultIfEmpty("").cache();

        Mono<ScopeGuardTools.ScopeGuardResult> scopeGuardMono = deepThinking
                ? Mono.fromCallable(() -> scopeGuardTools.guard(request.question()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache()
                : Mono.just(ScopeGuardTools.ScopeGuardResult.scopedDefault());

        Mono<EntityResolveTools.EntityResolveResult> entityResolveMono = Mono.zip(roadmapPlanMono, fileTextMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    String text = tuple.getT2();
                    if (!deepThinking || !plan.useEntityResolve()) {
                        return Mono.just(new EntityResolveTools.EntityResolveResult(List.of(), ""));
                    }
                    return Mono.fromCallable(() -> entityResolveTools.resolve(request.question(), text, PROGRESS_TERMS))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<RagRetrievalResult> retrieval0Mono = Mono.zip(roadmapPlanMono, decomposeMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> decomposed = tuple.getT2();
                    if (!plan.useKb()) {
                        return Mono.just(new RagRetrievalResult(request.question(), List.of(), ""));
                    }
                    List<String> queries = (decomposed == null || decomposed.isEmpty())
                            ? List.of(request.question())
                            : decomposed;
                    if (plan.useQuestionDecompose() && queries.size() > 1) {
                        return Mono.fromCallable(() -> kbTools.searchMulti(queries, topK, minScore))
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    return Mono.fromCallable(() -> kbTools.search(request.question(), topK, minScore))
                            .subscribeOn(Schedulers.boundedElastic());
                }).cache();

        Mono<RagRetrievalResult> retrievalFinalMono = refineRetrievalMono(
                request.question(), topK, minScore, filesMono, entityResolveMono, retrieval0Mono);

        Mono<List<KbDocument>> kbDocsMono = buildKbDocumentsMono(retrievalFinalMono);

        Mono<SanitizedEvidence> sanitizeMono = buildSanitizedEvidenceMono(retrievalFinalMono, fileTextMono);

        Mono<String> compressedMono = buildCompressedMono(request, deepThinking, roadmapPlanMono, retrievalFinalMono, sanitizeMono);

        Mono<List<String>> keyInfoMono = sanitizeMono.map(sanitized ->
                        extractKeyInfo(sanitized.combined(), MAX_KEY_INFO))
                .defaultIfEmpty(List.of())
                .cache();

        Mono<EvidenceGapTools.EvidenceGapResult> gapMono = Mono.zip(roadmapPlanMono, sanitizeMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT2();
                    List<String> keyInfo = tuple.getT3();
                    if (!deepThinking || !plan.useEvidenceGap()) {
                        return Mono.just(new EvidenceGapTools.EvidenceGapResult(List.of(), List.of(), "skipped"));
                    }
                    return Mono.fromCallable(() -> evidenceGapTools.detect(request.question(), sanitized.combined(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<AnswerOutlineTools.OutlineResult> outlineMono = Mono.zip(roadmapPlanMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> keyInfo = tuple.getT2();
                    if (!deepThinking || !plan.useAnswerOutline()) {
                        return Mono.just(new AnswerOutlineTools.OutlineResult(List.of(), "bullets"));
                    }
                    return Mono.fromCallable(() -> answerOutlineTools.outline(request.question(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<AssumptionCheckTools.AssumptionResult> assumptionMono = Mono.zip(roadmapPlanMono, sanitizeMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT2();
                    if (!deepThinking || !plan.useAssumptionCheck()) {
                        return Mono.just(new AssumptionCheckTools.AssumptionResult(List.of(), "low"));
                    }
                    return Mono.fromCallable(() -> assumptionCheckTools.check(request.question(), sanitized.combined()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<ActionPlanTools.ActionPlanResult> actionPlanMono = Mono.zip(roadmapPlanMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> keyInfo = tuple.getT2();
                    if (!deepThinking || !plan.useActionPlan()) {
                        return Mono.just(new ActionPlanTools.ActionPlanResult(List.of(), "bullets"));
                    }
                    return Mono.fromCallable(() -> actionPlanTools.plan(request.question(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<PreparedContext> preparedContextMono = assemblePreparedContext(
                deepThinking,
                scopeMode,
                retrievalFinalMono,
                fileTextMono,
                scopeGuardMono,
                entityResolveMono,
                compressedMono,
                sanitizeMono,
                keyInfoMono,
                gapMono,
                outlineMono,
                assumptionMono,
                actionPlanMono
        ).cache();

        Mono<TrackCorrectTools.TrackResult> trackCorrectMono = Mono.zip(roadmapPlanMono, preparedContextMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    PreparedContext ctx = tuple.getT2();
                    boolean outOfScope = ctx.scopeGuardResult != null && !ctx.scopeGuardResult.scoped();
                    String status = outOfScope ? "out_of_scope" : (ctx.hasAnyRef ? "" : "no_evidence");
                    String roadmapSummary = String.join(" -> ", plan.steps());
                    return Mono.fromCallable(() -> trackCorrectTools.ensure(request.question(), roadmapSummary, status))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Flux<ThinkingEvent> startStep = Flux.just(
                new ThinkingEvent("start", "Init", Map.of("ts", System.currentTimeMillis()))
        );

        Flux<ThinkingEvent> roadmapStep = deepThinking ? roadmapPlanMono.flatMapMany(plan ->
                Flux.just(new ThinkingEvent(
                        "roadmap",
                        "Roadmap plan",
                        Map.of(
                                "steps", plan.steps(),
                                "skips", plan.skips(),
                                "rationale", plan.rationale()
                        )
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> deepThinkStep = Flux.defer(() -> {
            if (!deepThinking) return Flux.empty();
            return Flux.just(new ThinkingEvent(
                    "deep_mode",
                    "Thinking",
                    Map.of("enabled", true)
            ));
        });

        Flux<ThinkingEvent> questionDecomposeStep = deepThinking
                ? Mono.zip(roadmapPlanMono, decomposeMono).flatMapMany(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> decomposed = tuple.getT2();
                    if (!plan.useQuestionDecompose() || decomposed == null || decomposed.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.just(new ThinkingEvent(
                            "question_decompose",
                            "Question decomposition",
                            Map.of("subQuestions", decomposed)
                    ));
                })
                : Flux.empty();

        Flux<ThinkingEvent> fileFetchStartStep = roadmapPlanMono.flatMapMany(plan -> {
            if (urls.isEmpty() || !plan.useFiles()) return Flux.empty();
            return Flux.just(new ThinkingEvent(
                    "file_fetch_start",
                    "Fetching files",
                    Map.of("count", urls.size(), "urls", urls)
            ));
        });

        Flux<ThinkingEvent> fileFetchStep = Mono.zip(roadmapPlanMono, filesMono).flatMapMany(tuple -> {
            RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
            List<FileItem> files = tuple.getT2();
            if (urls.isEmpty() || !plan.useFiles()) return Flux.empty();
            return Flux.just(new ThinkingEvent("file_fetch", "Fetched", Map.of("files", summarizeFilesFetched(files))));
        });

        Flux<ThinkingEvent> fileExtractStep = Mono.zip(roadmapPlanMono, filesMono).flatMapMany(tuple -> {
            RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
            List<FileItem> files = tuple.getT2();
            if (urls.isEmpty() || !plan.useFiles()) return Flux.empty();

            FileInsights ins = summarizeFileInsights(files);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", summarizeFilesExtracted(files));
            payload.put("keywords", ins.progressTerms());
            payload.put("excerpts", ins.promptExcerpt());

            return Flux.just(new ThinkingEvent("file_extract", "Extracted files' key info", payload));
        });

        Flux<ThinkingEvent> scopeGuardStep = deepThinking ? scopeGuardMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "scope_guard",
                        "Scope guard",
                        Map.of(
                                "scoped", result.scoped(),
                                "reason", result.reason(),
                                "rewriteHint", result.rewriteHint()
                        )
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> trackCorrectStep = deepThinking ? trackCorrectMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "track_correct",
                        "Track correction",
                        Map.of(
                                "onTrack", result.onTrack(),
                                "status", result.status(),
                                "hint", result.hint()
                        )
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> entityResolveStep = deepThinking
                ? Mono.zip(roadmapPlanMono, entityResolveMono).flatMapMany(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    EntityResolveTools.EntityResolveResult result = tuple.getT2();
                    if (!plan.useEntityResolve()) return Flux.empty();
                    return Flux.just(new ThinkingEvent(
                            "entity_resolve",
                            "Entity resolution",
                            Map.of("terms", result.terms())
                    ));
                })
                : Flux.empty();

        Flux<ThinkingEvent> codeSearchStep = deepThinking ? codeSearchMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "code_search",
                        "Code search",
                        Map.of("status", result.status(), "snippets", result.snippets())
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> redisStep = historyMono.flatMapMany(history ->
                Flux.just(new ThinkingEvent("redis", "History", summarizeHistory(history)))
        );

        Flux<ThinkingEvent> ragStep = Mono.zip(roadmapPlanMono, retrievalFinalMono).flatMapMany(tuple -> {
            RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
            RagRetrievalResult retrieval = tuple.getT2();
            if (!plan.useKb()) return Flux.empty();
            return Flux.just(new ThinkingEvent("rag", "Retrieval", summarizeRetrieval(retrieval)));
        });

        Flux<ThinkingEvent> kbDocsStep = deepThinking ? kbDocsMono.flatMapMany(docs ->
                Flux.just(new ThinkingEvent(
                        "kb_docs",
                        "KB documents",
                        Map.of(
                                "count", docs.size(),
                                "ids", docs.stream().map(KbDocument::getId).filter(Objects::nonNull).toList()
                        )
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> contextCompressStep = deepThinking
                ? Mono.zip(roadmapPlanMono, compressedMono).flatMapMany(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    String summary = tuple.getT2();
                    if (!plan.useCompress()) return Flux.empty();
                    return Flux.just(new ThinkingEvent(
                            "context_compress",
                            "Compressed context",
                            Map.of("preview", truncate(summary, PROGRESS_EXCERPT_CHARS))
                    ));
                })
                : Flux.empty();

        Flux<ThinkingEvent> privacyStep = deepThinking ? sanitizeMono.flatMapMany(sanitized ->
                Flux.just(new ThinkingEvent(
                        "privacy_sanitize",
                        "Privacy sanitize",
                        Map.of("hits", sanitized.hits())
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> evidenceStep = deepThinking ? Mono.zip(sanitizeMono, codeSearchMono).flatMapMany(tuple -> {
            SanitizedEvidence sanitized = tuple.getT1();
            CodeSearchTools.CodeSearchResult codeSearch = tuple.getT2();
            StringBuilder sb = new StringBuilder(sanitized.combined());
            if (codeSearch != null && codeSearch.snippets() != null && !codeSearch.snippets().isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append("CODE_SNIPPETS:\n");
                for (String snippet : codeSearch.snippets()) {
                    sb.append(snippet).append("\n");
                }
            }
            return Flux.just(new ThinkingEvent(
                    "evidence",
                    "Sanitized evidence",
                    Map.of("preview", truncate(sb.toString(), MAX_EVIDENCE_PREVIEW_CHARS))
            ));
        }) : Flux.empty();

        Flux<ThinkingEvent> keyInfoStep = deepThinking ? keyInfoMono.flatMapMany(keyInfo ->
                Flux.just(new ThinkingEvent("key_info", "Key info", Map.of("items", keyInfo)))
        ) : Flux.empty();

        Flux<ThinkingEvent> evidenceGapStep = deepThinking ? gapMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "evidence_gap",
                        "Evidence gap check",
                        Map.of(
                                "status", result.status(),
                                "missingFacts", result.missingFacts(),
                                "followUps", result.followUps()
                        )
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> answerOutlineStep = deepThinking ? outlineMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "answer_outline",
                        "Answer outline",
                        Map.of("style", result.style(), "sections", result.sections())
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> assumptionCheckStep = deepThinking ? assumptionMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "assumption_check",
                        "Assumption check",
                        Map.of("risk", result.riskLevel(), "assumptions", result.assumptions())
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> actionPlanStep = deepThinking ? actionPlanMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "action_plan",
                        "Action plan",
                        Map.of("style", result.style(), "steps", result.steps())
                ))
        ) : Flux.empty();

        Mono<ConflictDetectTools.ConflictResult> conflictMono = Mono.zip(roadmapPlanMono, sanitizeMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT2();
                    if (!deepThinking || !plan.useConflictDetect()) {
                        return Mono.just(new ConflictDetectTools.ConflictResult(false, List.of()));
                    }
                    return Mono.fromCallable(() -> conflictDetectTools.detect(sanitized.context(), sanitized.fileText()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Flux<ThinkingEvent> conflictStep = deepThinking ? conflictMono.flatMapMany(result ->
                Flux.just(new ThinkingEvent(
                        "conflict_detect",
                        "Conflict detect",
                        Map.of("conflict", result.conflict(), "issues", result.issues())
                ))
        ) : Flux.empty();

        Flux<ThinkingEvent> answerDeltaStep =
                Mono.zip(historyMono, preparedContextMono)
                        .flatMapMany(tuple -> {
                            var history = tuple.getT1();
                            PreparedContext ctx = tuple.getT2();

                            retrievalRef.set(ctx.retrieval);

                            boolean noEvidence = !ctx.hasAnyRef;
                            noEvidenceRef.set(noEvidence);

                            String historyText = truncate(history, MAX_HISTORY_CHARS);
                            String prompt = buildPrompt(
                                    request.question(),
                                    ctx.retrieval,
                                    historyText,
                                    ctx.fileText,
                                    noEvidence,
                                    ctx.outOfScopeKb,
                                    deepThinking,
                                    scopeMode,
                                    ctx.scopeGuardResult,
                                    ctx.entityTerms,
                                    ctx.compressedContext,
                                    ctx.keyInfo,
                                    ctx.evidenceGap,
                                    ctx.answerOutline,
                                    ctx.assumptionResult,
                                    ctx.actionPlan
                            );
                            promptRef.set(prompt);

                            return chatClient.prompt()
                                    .system(SYSTEM_PROMPT)
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
                                                Boolean.TRUE.equals(noEvidenceRef.get()),
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

        Flux<ThinkingEvent> answerVerifyStep = deepThinking
                ? conflictMono.flatMapMany(result -> {
                    List<String> issues = new ArrayList<>();
                    if (Boolean.TRUE.equals(noEvidenceRef.get())) {
                        issues.add("no_evidence");
                    }
                    if (result != null && result.conflict()) {
                        issues.addAll(result.issues());
                    }
                    return Flux.just(new ThinkingEvent(
                            "answer_verify",
                            "Answer verification",
                            Map.of(
                                    "supportScore", verifySupportScore(noEvidenceRef.get()),
                                    "issues", issues
                            )
                    ));
                })
                : Flux.empty();

        return Flux.concat(
                startStep,
                roadmapStep,
                deepThinkStep,
                questionDecomposeStep,
                fileFetchStartStep,
                fileFetchStep,
                fileExtractStep,
                scopeGuardStep,
                trackCorrectStep,
                entityResolveStep,
                codeSearchStep,
                privacyStep,
                evidenceStep,
                redisStep,
                ragStep,
                kbDocsStep,
                contextCompressStep,
                keyInfoStep,
                evidenceGapStep,
                answerOutlineStep,
                assumptionCheckStep,
                actionPlanStep,
                conflictStep,
                answerDeltaStep,
                answerVerifyStep,
                finalStep
        );
    }

    // --------------------------
    // Context preparation (shared)
    // --------------------------
    private Mono<PreparedContext> prepareContextMono(RagAnswerRequest request, int topK, double minScore, boolean deepThinking) {
        List<String> urls = request.resolveFileUrls(MAX_FILE_URLS);
        RagAnswerRequest.ScopeMode scopeMode = request.resolveScopeMode();

        Mono<RoadmapPlannerTools.RoadmapPlan> roadmapPlanMono = deepThinking
                ? Mono.fromCallable(() -> roadmapPlannerTools.plan(request.question(), scopeMode, true, !urls.isEmpty()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache()
                : Mono.just(defaultRoadmapPlan(!urls.isEmpty()));

        Mono<List<String>> decomposeMono = roadmapPlanMono.flatMap(plan -> {
            if (!deepThinking || !plan.useQuestionDecompose()) {
                return Mono.just(List.<String>of());
            }
            return Mono.fromCallable(() -> questionDecomposerTools.decompose(request.question()))
                    .subscribeOn(Schedulers.boundedElastic());
        }).cache();

        Mono<List<FileItem>> filesMono = roadmapPlanMono.flatMap(plan ->
                plan.useFiles() ? extractFilesMono(urls) : Mono.just(List.<FileItem>of())
        ).cache();
        Mono<FileInsights> fileInsightsMono = filesMono.map(this::summarizeFileInsights).cache();
        Mono<String> fileTextMono = fileInsightsMono.map(FileInsights::promptContext).defaultIfEmpty("").cache();

        Mono<ScopeGuardTools.ScopeGuardResult> scopeGuardMono = deepThinking
                ? Mono.fromCallable(() -> scopeGuardTools.guard(request.question()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache()
                : Mono.just(ScopeGuardTools.ScopeGuardResult.scopedDefault());

        Mono<EntityResolveTools.EntityResolveResult> entityResolveMono = Mono.zip(roadmapPlanMono, fileTextMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    String text = tuple.getT2();
                    if (!deepThinking || !plan.useEntityResolve()) {
                        return Mono.just(new EntityResolveTools.EntityResolveResult(List.of(), ""));
                    }
                    return Mono.fromCallable(() -> entityResolveTools.resolve(request.question(), text, PROGRESS_TERMS))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<RagRetrievalResult> retrieval0Mono = Mono.zip(roadmapPlanMono, decomposeMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> decomposed = tuple.getT2();
                    if (!plan.useKb()) {
                        return Mono.just(new RagRetrievalResult(request.question(), List.of(), ""));
                    }
                    List<String> queries = (decomposed == null || decomposed.isEmpty())
                            ? List.of(request.question())
                            : decomposed;
                    if (plan.useQuestionDecompose() && queries.size() > 1) {
                        return Mono.fromCallable(() -> kbTools.searchMulti(queries, topK, minScore))
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    return Mono.fromCallable(() -> kbTools.search(request.question(), topK, minScore))
                            .subscribeOn(Schedulers.boundedElastic());
                }).cache();

        Mono<RagRetrievalResult> retrievalFinalMono = refineRetrievalMono(
                request.question(), topK, minScore, filesMono, entityResolveMono, retrieval0Mono
        );

        Mono<SanitizedEvidence> sanitizeMono = buildSanitizedEvidenceMono(retrievalFinalMono, fileTextMono);

        Mono<String> compressedMono = buildCompressedMono(request, deepThinking, roadmapPlanMono, retrievalFinalMono, sanitizeMono);

        Mono<List<String>> keyInfoMono = sanitizeMono.map(sanitized ->
                        extractKeyInfo(sanitized.combined(), MAX_KEY_INFO))
                .defaultIfEmpty(List.of())
                .cache();

        Mono<EvidenceGapTools.EvidenceGapResult> gapMono = Mono.zip(roadmapPlanMono, sanitizeMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT2();
                    List<String> keyInfo = tuple.getT3();
                    if (!deepThinking || !plan.useEvidenceGap()) {
                        return Mono.just(new EvidenceGapTools.EvidenceGapResult(List.of(), List.of(), "skipped"));
                    }
                    return Mono.fromCallable(() -> evidenceGapTools.detect(request.question(), sanitized.combined(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<AnswerOutlineTools.OutlineResult> outlineMono = Mono.zip(roadmapPlanMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> keyInfo = tuple.getT2();
                    if (!deepThinking || !plan.useAnswerOutline()) {
                        return Mono.just(new AnswerOutlineTools.OutlineResult(List.of(), "bullets"));
                    }
                    return Mono.fromCallable(() -> answerOutlineTools.outline(request.question(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<AssumptionCheckTools.AssumptionResult> assumptionMono = Mono.zip(roadmapPlanMono, sanitizeMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT2();
                    if (!deepThinking || !plan.useAssumptionCheck()) {
                        return Mono.just(new AssumptionCheckTools.AssumptionResult(List.of(), "low"));
                    }
                    return Mono.fromCallable(() -> assumptionCheckTools.check(request.question(), sanitized.combined()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        Mono<ActionPlanTools.ActionPlanResult> actionPlanMono = Mono.zip(roadmapPlanMono, keyInfoMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    List<String> keyInfo = tuple.getT2();
                    if (!deepThinking || !plan.useActionPlan()) {
                        return Mono.just(new ActionPlanTools.ActionPlanResult(List.of(), "bullets"));
                    }
                    return Mono.fromCallable(() -> actionPlanTools.plan(request.question(), keyInfo))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();

        return assemblePreparedContext(
                deepThinking,
                scopeMode,
                retrievalFinalMono,
                fileTextMono,
                scopeGuardMono,
                entityResolveMono,
                compressedMono,
                sanitizeMono,
                keyInfoMono,
                gapMono,
                outlineMono,
                assumptionMono,
                actionPlanMono
        );
    }

    // --------------------------
    // Files: unified extraction via FileTools
    // --------------------------
    private Mono<List<FileItem>> extractFilesMono(List<String> urls) {
        if (urls == null || urls.isEmpty()) return Mono.just(List.of());

        List<String> safeUrls = urls.stream().filter(Objects::nonNull).toList();

        return Flux.fromIterable(safeUrls)
                .take(MAX_FILE_URLS)
                .flatMap(url ->
                                Mono.fromCallable(() -> extractOneFileBlocking(url))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .timeout(ATTACH_TIMEOUT)
                                        .onErrorResume(ex -> Mono.just(new FileItem(
                                                url,
                                                filenameFromUrl(url),
                                                guessMimeFromUrl(url),
                                                "",
                                                List.of(),
                                                List.of(),
                                                "extract_failed: " + ex.getClass().getSimpleName() + ": " + safeMsg(ex)
                                        ))),
                        2
                )
                .collectList();
    }

    private FileItem extractOneFileBlocking(String url) {
        String name = filenameFromUrl(url);
        String mime = guessMimeFromUrl(url);

        try {
            FileTools.FileUnderstanding understanding = fileTools.understandUrl(url);

            String keyText = safeText(understanding.text());

            if (keyText.isBlank() && understanding.keywords().isEmpty() && understanding.queries().isEmpty()) {
                return new FileItem(
                        url, name, mime,
                        "",
                        List.of(),
                        List.of(),
                        understanding.error() == null ? "extract_empty_result" : understanding.error()
                );
            }

            return new FileItem(
                    url,
                    name,
                    mime,
                    keyText,
                    uniqLimit(understanding.keywords(), 30),
                    uniqLimit(understanding.queries(), 30),
                    understanding.error()
            );
        } catch (Exception ex) {
            String err = "extract_failed: " + ex.getClass().getSimpleName() + ": " + safeMsg(ex);
            log.warn("[FileExtract] url={} name={} mime={} err={}", url, name, mime, err);
            return new FileItem(
                    url, name, mime,
                    "",
                    List.of(),
                    List.of(),
                    err
            );
        }
    }

    private static String safeMsg(Throwable ex) {
        String m = ex == null ? "" : String.valueOf(ex.getMessage());
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 500) m = m.substring(0, 500) + "...";
        return m;
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static <T> List<T> uniqLimit(List<T> list, int limit) {
        if (list == null) return List.of();
        LinkedHashSet<T> set = new LinkedHashSet<>(list);
        return set.stream().limit(limit).toList();
    }

    private Mono<RagRetrievalResult> refineRetrievalMono(String question,
                                                         int topK,
                                                         double minScore,
                                                         Mono<List<FileItem>> filesMono,
                                                         Mono<EntityResolveTools.EntityResolveResult> entityResolveMono,
                                                         Mono<RagRetrievalResult> retrieval0Mono) {
        return Mono.zip(retrieval0Mono, filesMono, entityResolveMono)
                .flatMap(tuple -> {
                    RagRetrievalResult r0 = tuple.getT1();
                    List<FileItem> files = tuple.getT2();
                    EntityResolveTools.EntityResolveResult entity = tuple.getT3();

                    LinkedHashSet<String> termSet = new LinkedHashSet<>(collectRetrievalTerms(files, MAX_FILE_TERM_COUNT));
                    if (entity != null && entity.terms() != null) {
                        termSet.addAll(entity.terms());
                    }

                    List<String> terms = termSet.stream()
                            .limit(MAX_FILE_TERM_COUNT + PROGRESS_TERMS)
                            .toList();

                    if (!shouldRefineRetrieval(r0, terms)) return Mono.just(r0);

                    String expandedQuery = buildExpandedRetrievalQuery(question, terms);
                    if (expandedQuery.equals(question)) return Mono.just(r0);

                    return Mono.fromCallable(() -> kbTools.search(expandedQuery, topK, minScore))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(result -> evidenceRerankTools.rerank(result, terms, topK))
                            .onErrorReturn(r0);
                })
                .cache();
    }

    private Mono<SanitizedEvidence> buildSanitizedEvidenceMono(Mono<RagRetrievalResult> retrievalMono,
                                                               Mono<String> fileTextMono) {
        return Mono.zip(retrievalMono, fileTextMono)
                .map(tuple -> {
                    String context = Optional.ofNullable(tuple.getT1().context()).orElse("");
                    String fileText = Optional.ofNullable(tuple.getT2()).orElse("");
                    PrivacySanitizerTools.SanitizedResult sanitizedContext = privacySanitizerTools.sanitize(context);
                    PrivacySanitizerTools.SanitizedResult sanitizedFile = privacySanitizerTools.sanitize(fileText);
                    Map<String, Integer> hits = mergeHits(sanitizedContext.hits(), sanitizedFile.hits());
                    return new SanitizedEvidence(sanitizedContext.sanitized(), sanitizedFile.sanitized(), hits);
                })
                .cache();
    }

    private Mono<List<KbDocument>> buildKbDocumentsMono(Mono<RagRetrievalResult> retrievalMono) {
        return retrievalMono
                .map(retrieval -> {
                    if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) {
                        return List.<KbDocument>of();
                    }
                    return retrieval.documents().stream()
                            .map(ScoredDocument::document)
                            .filter(Objects::nonNull)
                            .toList();
                })
                .defaultIfEmpty(List.<KbDocument>of())
                .cache();
    }

    private Mono<String> buildCompressedMono(RagAnswerRequest request,
                                             boolean deepThinking,
                                             Mono<RoadmapPlannerTools.RoadmapPlan> roadmapPlanMono,
                                             Mono<RagRetrievalResult> retrievalMono,
                                             Mono<SanitizedEvidence> sanitizedMono) {
        if (!deepThinking) return Mono.just("");

        return Mono.zip(roadmapPlanMono, retrievalMono, sanitizedMono)
                .flatMap(tuple -> {
                    RoadmapPlannerTools.RoadmapPlan plan = tuple.getT1();
                    SanitizedEvidence sanitized = tuple.getT3();
                    if (!plan.useCompress()) return Mono.just("");
                    return Mono.fromCallable(() -> contextCompressTools.compress(
                                    request.question(),
                                    Optional.ofNullable(sanitized.context()).orElse(""),
                                    Optional.ofNullable(sanitized.fileText()).orElse("")
                            ).summary())
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .cache();
    }

    private Mono<PreparedContext> assemblePreparedContext(boolean deepThinking,
                                                          RagAnswerRequest.ScopeMode scopeMode,
                                                          Mono<RagRetrievalResult> retrievalMono,
                                                          Mono<String> fileTextMono,
                                                          Mono<ScopeGuardTools.ScopeGuardResult> scopeGuardMono,
                                                          Mono<EntityResolveTools.EntityResolveResult> entityResolveMono,
                                                          Mono<String> compressedMono,
                                                          Mono<SanitizedEvidence> sanitizedMono,
                                                          Mono<List<String>> keyInfoMono,
                                                          Mono<EvidenceGapTools.EvidenceGapResult> gapMono,
                                                          Mono<AnswerOutlineTools.OutlineResult> outlineMono,
                                                          Mono<AssumptionCheckTools.AssumptionResult> assumptionMono,
                                                          Mono<ActionPlanTools.ActionPlanResult> actionPlanMono) {
        return Mono.zip(
                        List.of(
                                retrievalMono,
                                fileTextMono,
                                scopeGuardMono,
                                entityResolveMono,
                                compressedMono,
                                sanitizedMono,
                                keyInfoMono,
                                gapMono,
                                outlineMono,
                                assumptionMono,
                                actionPlanMono
                        ),
                        tuple -> {
                            RagRetrievalResult retrieval = (RagRetrievalResult) tuple[0];
                            String fileText = (String) tuple[1];
                            ScopeGuardTools.ScopeGuardResult guard = (ScopeGuardTools.ScopeGuardResult) tuple[2];
                            EntityResolveTools.EntityResolveResult entity = (EntityResolveTools.EntityResolveResult) tuple[3];
                            String compressed = (String) tuple[4];
                            SanitizedEvidence sanitized = (SanitizedEvidence) tuple[5];
                            @SuppressWarnings("unchecked")
                            List<String> keyInfo = (List<String>) tuple[6];
                            EvidenceGapTools.EvidenceGapResult gapResult = (EvidenceGapTools.EvidenceGapResult) tuple[7];
                            AnswerOutlineTools.OutlineResult outlineResult = (AnswerOutlineTools.OutlineResult) tuple[8];
                            AssumptionCheckTools.AssumptionResult assumptionResult = (AssumptionCheckTools.AssumptionResult) tuple[9];
                            ActionPlanTools.ActionPlanResult actionPlanResult = (ActionPlanTools.ActionPlanResult) tuple[10];

                            boolean outOfScopeKb = isOutOfScope(retrieval);
                            boolean hasAnyRef = hasAnyReference(retrieval, fileText);

                            if (deepThinking) {
                                if (guard != null && !guard.scoped()) {
                                    outOfScopeKb = true;
                                    hasAnyRef = false;
                                }
                                if (!compressed.isBlank()) {
                                    hasAnyRef = true;
                                }
                            }

                            if (scopeMode == RagAnswerRequest.ScopeMode.YUQI_ONLY && (guard == null || !guard.scoped())) {
                                outOfScopeKb = true;
                                hasAnyRef = false;
                            }

                            List<String> entityTerms = (entity == null || entity.terms() == null)
                                    ? List.of()
                                    : entity.terms();

                            RagRetrievalResult sanitizedRetrieval = new RagRetrievalResult(
                                    retrieval.question(),
                                    retrieval.documents(),
                                    sanitized.context()
                            );

                            return new PreparedContext(
                                    sanitizedRetrieval,
                                    sanitized.fileText(),
                                    outOfScopeKb,
                                    hasAnyRef,
                                    guard,
                                    entityTerms,
                            compressed,
                            sanitized,
                            keyInfo == null ? List.of() : keyInfo,
                            gapResult == null ? new EvidenceGapTools.EvidenceGapResult(List.of(), List.of(), "skipped") : gapResult,
                            outlineResult == null ? new AnswerOutlineTools.OutlineResult(List.of(), "bullets") : outlineResult,
                            assumptionResult == null ? new AssumptionCheckTools.AssumptionResult(List.of(), "low") : assumptionResult,
                            actionPlanResult == null ? new ActionPlanTools.ActionPlanResult(List.of(), "bullets") : actionPlanResult
                    );
                        }
                );
    }

    private List<String> extractKeyInfo(String evidence, int limit) {
        if (evidence == null || evidence.isBlank() || limit <= 0) return List.of();
        List<String> out = new ArrayList<>();
        String[] lines = evidence.split("\\r?\\n");
        for (String line : lines) {
            if (out.size() >= limit) break;
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        if (!out.isEmpty()) return out;

        String[] sentences = evidence.split("[。.!?]\\s*");
        for (String sentence : sentences) {
            if (out.size() >= limit) break;
            String trimmed = sentence.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        return out;
    }

    private Map<String, Integer> mergeHits(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        if (a != null) {
            for (Map.Entry<String, Integer> entry : a.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (b != null) {
            for (Map.Entry<String, Integer> entry : b.entrySet()) {
                merged.put(entry.getKey(), merged.getOrDefault(entry.getKey(), 0) + entry.getValue());
            }
        }
        return merged;
    }

    private RoadmapPlannerTools.RoadmapPlan defaultRoadmapPlan(boolean hasFiles) {
        List<String> steps = new ArrayList<>();
        steps.add("scope_guard");
        steps.add("kb_search");
        if (hasFiles) steps.add("file_fetch");
        steps.add("privacy_sanitize");
        return new RoadmapPlannerTools.RoadmapPlan(
                steps,
                List.of(),
                List.of(),
                true,
                hasFiles,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private double verifySupportScore(Boolean noEvidence) {
        if (Boolean.TRUE.equals(noEvidence)) return 0.2;
        return 0.7;
    }

    // --------------------------
    // Retrieval refinement
    // --------------------------
    private List<String> collectRetrievalTerms(List<FileItem> files, int maxTerms) {
        if (files == null || maxTerms <= 0) return List.of();

        LinkedHashSet<String> set = new LinkedHashSet<>();

        for (FileItem f : files) {
            if (f == null) continue;

            for (String q : safeList(f.queries())) {
                if (set.size() >= maxTerms) break;
                addTerm(set, q);
            }

            for (String kw : safeList(f.keywords())) {
                if (set.size() >= maxTerms) break;
                addTerm(set, kw);
            }

            if (set.size() >= maxTerms) break;
        }

        return set.stream().toList();
    }

    private boolean shouldRefineRetrieval(RagRetrievalResult original, List<String> terms) {
        if (terms == null || terms.isEmpty()) return false;
        if (original == null || original.documents() == null || original.documents().isEmpty()) return true;

        double topScore = original.documents().stream()
                .mapToDouble(ScoredDocument::score)
                .max()
                .orElse(0.0);

        return topScore < REFINE_TOP_SCORE_THRESHOLD;
    }

    private String buildExpandedRetrievalQuery(String baseQuery, List<String> terms) {
        if (terms == null || terms.isEmpty()) return Optional.ofNullable(baseQuery).orElse("");

        StringBuilder sb = new StringBuilder();
        sb.append(Optional.ofNullable(baseQuery).orElse(""));

        int addedChars = 0;
        for (String t : terms) {
            if (t == null || t.isBlank()) continue;
            if (addedChars >= MAX_FILE_QUERY_CHARS) break;
            sb.append(" | ").append(t);
            addedChars += t.length();
        }

        return sb.toString();
    }

    private void addTerm(LinkedHashSet<String> set, String term) {
        if (term == null) return;
        String t = term.trim();
        if (t.isBlank()) return;
        if (t.length() > 80) t = t.substring(0, 80);
        set.add(t);
    }

    // --------------------------
    // Utilities: URLs
    // --------------------------
    private String filenameFromUrl(String url) {
        try {
            URI u = URI.create(url);
            String path = Optional.ofNullable(u.getPath()).orElse("");
            if (path.isBlank()) return "file";
            String[] parts = path.split("/");
            String last = parts[parts.length - 1];
            return last.isBlank() ? "file" : last;
        } catch (Exception e) {
            return "file";
        }
    }

    private String guessMimeFromUrl(String url) {
        if (url == null) return "application/octet-stream";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".md")) return "text/markdown";
        return "application/octet-stream";
    }

    // --------------------------
    // File summarization and context assembly
    // --------------------------
    private FileInsights summarizeFileInsights(List<FileItem> files) {
        List<FileItem> safeFiles = safeList(files);

        List<String> retrievalTerms = collectRetrievalTerms(safeFiles, MAX_FILE_TERM_COUNT);

        LinkedHashSet<String> progressSet = new LinkedHashSet<>();
        for (FileItem f : safeFiles) {
            for (String q : safeList(f.queries())) {
                if (progressSet.size() >= PROGRESS_TERMS) break;
                addTerm(progressSet, q);
            }
            for (String kw : safeList(f.keywords())) {
                if (progressSet.size() >= PROGRESS_TERMS) break;
                addTerm(progressSet, kw);
            }
            if (progressSet.size() >= PROGRESS_TERMS) break;
        }
        List<String> progressTerms = progressSet.stream().toList();

        String ctx = buildFileContext(safeFiles);
        String excerpt = truncate(ctx, PROGRESS_EXCERPT_CHARS);

        return new FileInsights(retrievalTerms, progressTerms, ctx, excerpt);
    }

    private String buildFileContext(List<FileItem> files) {
        if (files == null || files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("File (key info):\n");

        for (FileItem f : files) {
            if (f == null) continue;

            sb.append("- ").append(safe(f.name(), "file"))
                    .append(" (").append(safe(f.mime(), "application/octet-stream")).append(")\n");

            if (f.error() != null && !f.error().isBlank()) {
                sb.append("  error: ").append(f.error()).append("\n\n");
                continue;
            }

            if (!safeList(f.keywords()).isEmpty()) {
                sb.append("  keywords: ").append(String.join(", ", uniqLimit(f.keywords(), 12))).append("\n");
            }
            if (!safeList(f.queries()).isEmpty()) {
                sb.append("  queries: ").append(String.join(" | ", uniqLimit(f.queries(), 6))).append("\n");
            }
            String txt = safeText(f.keyText());
            if (!txt.isBlank()) {
                sb.append("  text: ").append(truncate(txt, 900)).append("\n");
            }
            sb.append("\n");

            if (sb.length() >= MAX_FILE_CONTEXT_CHARS) break;
        }

        return truncate(sb.toString(), MAX_FILE_CONTEXT_CHARS);
    }

    private static <T> List<T> safeList(List<T> list) {
        if (list == null) return List.of();
        return list.stream().filter(Objects::nonNull).toList();
    }

    private static String safe(String s, String d) {
        return (s == null || s.isBlank()) ? d : s;
    }

    private static boolean hasAnyReference(RagRetrievalResult retrieval, String fileText) {
        if (fileText != null && !fileText.isBlank()) return true;
        if (retrieval == null) return false;
        if (retrieval.documents() != null && !retrieval.documents().isEmpty()) return true;
        String ctx = Optional.ofNullable(retrieval.context()).orElse("");
        return !ctx.isBlank();
    }

    // --------------------------
    // Prompt + QA candidates
    // --------------------------
    private ChatClient resolveClient(String model) {
        String key = Optional.ofNullable(model)
                .map(String::toLowerCase)
                .orElse(RagAnswerRequest.DEFAULT_MODEL);

        if (!chatClients.isEmpty()) {
            if (chatClients.containsKey(key + "ChatClient")) return chatClients.get(key + "ChatClient");
            if (chatClients.containsKey(key)) return chatClients.get(key);
        }

        ChatClient fallback = chatClients.get(RagAnswerRequest.DEFAULT_MODEL + "ChatClient");
        if (fallback != null) return fallback;

        return chatClients.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChatClient beans are available"));
    }

    private String buildPrompt(String question,
                               RagRetrievalResult retrieval,
                               String historyText,
                               String fileText,
                               boolean noEvidence,
                               boolean outOfScopeKb) {
        return buildPrompt(
                question,
                retrieval,
                historyText,
                fileText,
                noEvidence,
                outOfScopeKb,
                false,
                RagAnswerRequest.ScopeMode.PRIVACY_SAFE,
                ScopeGuardTools.ScopeGuardResult.scopedDefault(),
                List.of(),
                "",
                List.of(),
                new EvidenceGapTools.EvidenceGapResult(List.of(), List.of(), "skipped"),
                new AnswerOutlineTools.OutlineResult(List.of(), "bullets"),
                new AssumptionCheckTools.AssumptionResult(List.of(), "low"),
                new ActionPlanTools.ActionPlanResult(List.of(), "bullets")
        );
    }

    private String buildPrompt(String question,
                               RagRetrievalResult retrieval,
                               String historyText,
                               String fileText,
                               boolean noEvidence,
                               boolean outOfScopeKb,
                               boolean deepThinking,
                               RagAnswerRequest.ScopeMode scopeMode,
                               ScopeGuardTools.ScopeGuardResult scopeGuardResult,
                               List<String> entityTerms,
                               String compressedContext,
                               List<String> keyInfo,
                               EvidenceGapTools.EvidenceGapResult evidenceGap,
                               AnswerOutlineTools.OutlineResult answerOutline,
                               AssumptionCheckTools.AssumptionResult assumptionResult,
                               ActionPlanTools.ActionPlanResult actionPlan) {

        String rawContext = (retrieval == null) ? "" : Optional.ofNullable(retrieval.context()).orElse("");
        String contextText = compactLogContext(rawContext, MAX_CONTEXT_CHARS);
        String compressed = truncate(Optional.ofNullable(compressedContext).orElse(""), MAX_CONTEXT_CHARS);
        ScopeGuardTools.ScopeGuardResult guard = scopeGuardResult == null
                ? ScopeGuardTools.ScopeGuardResult.scopedDefault()
                : scopeGuardResult;
        List<String> safeTerms = entityTerms == null ? List.of() : entityTerms;

        List<QaCandidate> qaCandidates = extractQaCandidates(retrieval, question);

        StringBuilder sb = new StringBuilder();

        sb.append("Meta: noEvidence=").append(noEvidence)
                .append(", kbWeak=").append(outOfScopeKb)
                .append(", scopeScoped=").append(guard.scoped())
                .append(", deepThinking=").append(deepThinking)
                .append(", scopeMode=").append(scopeMode)
                .append("\n\n");

        if (guard.reason() != null && !guard.reason().isBlank()) {
            sb.append("Scope note: ").append(guard.reason()).append("\n\n");
        }
        if (guard.rewriteHint() != null && !guard.rewriteHint().isBlank()) {
            sb.append("Rewrite hint: ").append(guard.rewriteHint()).append("\n\n");
        }
        if (!guard.scoped()) {
            if (scopeMode == RagAnswerRequest.ScopeMode.YUQI_ONLY) {
                sb.append("Instruction: reply exactly with ").append(OUT_OF_SCOPE_REPLY).append(".\n\n");
            } else {
                sb.append("Instruction: refuse to provide private contact details; suggest safe public info topics.\n\n");
            }
        }

        String fileSection = truncate(Optional.ofNullable(fileText).orElse(""), MAX_FILE_CONTEXT_CHARS);
        if (!fileSection.isBlank()) {
            sb.append(fileSection).append("\n\n");
        }

        if (!safeTerms.isEmpty()) {
            sb.append("Entity/keyword terms: ")
                    .append(String.join(", ", safeTerms))
                    .append("\n\n");
        }

        if (deepThinking && keyInfo != null && !keyInfo.isEmpty()) {
            sb.append("Key info candidates:\n");
            for (String item : keyInfo) {
                if (item == null || item.isBlank()) continue;
                sb.append("- ").append(item.trim()).append("\n");
            }
            sb.append("\n");
        }

        if (deepThinking && evidenceGap != null) {
            List<String> gaps = evidenceGap.missingFacts();
            List<String> followUps = evidenceGap.followUps();
            if (gaps != null && !gaps.isEmpty()) {
                sb.append("Potential evidence gaps:\n");
                for (String gap : gaps) {
                    if (gap == null || gap.isBlank()) continue;
                    sb.append("- ").append(gap.trim()).append("\n");
                }
                sb.append("\n");
            }
            if (followUps != null && !followUps.isEmpty()) {
                sb.append("Follow-up questions:\n");
                for (String followUp : followUps) {
                    if (followUp == null || followUp.isBlank()) continue;
                    sb.append("- ").append(followUp.trim()).append("\n");
                }
                sb.append("\n");
            }
        }

        if (deepThinking && answerOutline != null && answerOutline.sections() != null && !answerOutline.sections().isEmpty()) {
            sb.append("Answer outline (style=").append(answerOutline.style()).append("):\n");
            for (String section : answerOutline.sections()) {
                if (section == null || section.isBlank()) continue;
                sb.append("- ").append(section.trim()).append("\n");
            }
            sb.append("\n");
        }

        if (deepThinking && assumptionResult != null && assumptionResult.assumptions() != null && !assumptionResult.assumptions().isEmpty()) {
            sb.append("Assumptions (risk=").append(assumptionResult.riskLevel()).append("):\n");
            for (String assumption : assumptionResult.assumptions()) {
                if (assumption == null || assumption.isBlank()) continue;
                sb.append("- ").append(assumption.trim()).append("\n");
            }
            sb.append("\n");
        }

        if (deepThinking && actionPlan != null && actionPlan.steps() != null && !actionPlan.steps().isEmpty()) {
            sb.append("Action plan (style=").append(actionPlan.style()).append("):\n");
            for (String step : actionPlan.steps()) {
                if (step == null || step.isBlank()) continue;
                sb.append("- ").append(step.trim()).append("\n");
            }
            sb.append("\n");
        }

        if (!qaCandidates.isEmpty()) {
            sb.append("QA References (hint only; do not copy blindly):\n");
            for (int i = 0; i < qaCandidates.size(); i++) {
                QaCandidate c = qaCandidates.get(i);
                sb.append("- #").append(i + 1)
                        .append(" (score=").append(round3(c.score))
                        .append(", match=").append(matchLabel(c.matchScore))
                        .append(") ");
                String qPreview = truncate(Optional.ofNullable(c.question).orElse(""), 140);
                String aPreview = truncate(Optional.ofNullable(c.answer).orElse(""), 220);
                if (!qPreview.isBlank()) sb.append("Q: ").append(qPreview).append(" | ");
                sb.append("A: ").append(aPreview).append("\n");
            }
            sb.append("\n");
        }

        if (deepThinking && !compressed.isBlank()) {
            sb.append("CTX_COMPRESSED:\n").append(compressed).append("\n\n");
            if (!contextText.isBlank()) {
                sb.append("CTX_RAW:\n").append(contextText).append("\n\n");
            }
        } else {
            sb.append("CTX:\n").append(contextText).append("\n\n");
        }
        sb.append("HIS:\n").append(historyText).append("\n\n");
        sb.append("Q:\n").append(question).append("\n");

        return sb.toString();
    }

    private static String matchLabel(int score) {
        if (score >= QA_MATCH_HIGH) return "HIGH";
        if (score >= QA_MATCH_MED) return "MED";
        return "LOW";
    }

    private boolean isOutOfScope(RagRetrievalResult retrieval) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) return true;

        double topScore = retrieval.documents().stream()
                .mapToDouble(ScoredDocument::score)
                .max()
                .orElse(0.0);

        return topScore < MIN_KB_SCORE_FOR_ANSWER;
    }

    private List<QaCandidate> extractQaCandidates(RagRetrievalResult retrieval, String userQuestion) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) return List.of();

        List<String> userKeywords = extractKeywords(userQuestion);

        List<ScoredDocument> sorted = retrieval.documents().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .toList();

        List<QaCandidate> out = new ArrayList<>();
        for (ScoredDocument sd : sorted) {
            if (sd == null || sd.document() == null) continue;

            var doc = sd.document();
            String type = Optional.ofNullable(doc.getDocType()).orElse("");
            String content = Optional.ofNullable(doc.getContent()).orElse("");

            boolean looksQa =
                    "chat_qa".equalsIgnoreCase(type)
                            || content.contains("【回答】")
                            || content.toLowerCase(Locale.ROOT).contains("answer:");

            if (!looksQa) continue;

            Optional<String> ansOpt = extractQaAnswer(content);
            if (ansOpt.isEmpty()) continue;

            String a = ansOpt.get().trim();
            if (a.equals(OUT_OF_SCOPE_REPLY)) continue;

            String q = extractQaQuestion(content).orElse("").trim();

            String matchText = !q.isBlank() ? q : a;
            int matchScore = scoreChunk(matchText, userKeywords);

            out.add(new QaCandidate(
                    String.valueOf(doc.getId()),
                    type,
                    sd.score(),
                    matchScore,
                    truncate(q, 260),
                    truncate(a, 400)
            ));

            if (out.size() >= MAX_QA_CANDIDATES) break;
        }

        return out;
    }

    private Optional<String> extractQaAnswer(String content) {
        if (content == null) return Optional.empty();

        Pattern zh = Pattern.compile("【回答】\\s*([\\s\\S]*?)(?:\\n\\s*【|\\z)");
        Matcher m1 = zh.matcher(content);
        if (m1.find()) {
            String ans = m1.group(1);
            if (ans != null && !ans.trim().isBlank()) return Optional.of(ans.trim());
        }

        Pattern en = Pattern.compile("(?i)(?:^|\\n)\\s*(?:answer\\s*:|a\\s*: )\\s*([\\s\\S]*?)(?:\\n\\s*(?:question\\s*:|q\\s*:|context\\s*:)|\\z)");
        Matcher m2 = en.matcher(content);
        if (m2.find()) {
            String ans = m2.group(1);
            if (ans != null && !ans.trim().isBlank()) return Optional.of(ans.trim());
        }

        return Optional.empty();
    }

    private Optional<String> extractQaQuestion(String content) {
        if (content == null) return Optional.empty();

        Pattern zhQ = Pattern.compile("【(?:问题|提问)】\\s*([\\s\\S]*?)(?:\\n\\s*【|\\z)");
        Matcher m1 = zhQ.matcher(content);
        if (m1.find()) {
            String q = m1.group(1);
            if (q != null && !q.trim().isBlank()) return Optional.of(q.trim());
        }

        Pattern enQ = Pattern.compile("(?i)(?:^|\\n)\\s*(?:question\\s*:|q\\s*: )\\s*([\\s\\S]*?)(?:\\n\\s*(?:answer\\s*:|a\\s*:|context\\s*:)|\\z)");
        Matcher m2 = enQ.matcher(content);
        if (m2.find()) {
            String q = m2.group(1);
            if (q != null && !q.trim().isBlank()) return Optional.of(q.trim());
        }

        return Optional.empty();
    }

    private int scoreChunk(String text, List<String> userKeywords) {
        if (text == null || text.isBlank() || userKeywords.isEmpty()) return 0;

        String lower = text.toLowerCase(Locale.ROOT);

        int score = 0;
        for (String k : userKeywords) {
            if (lower.contains(k.toLowerCase(Locale.ROOT))) {
                score += 2;
            } else {
                int idx = lower.indexOf(k.toLowerCase(Locale.ROOT));
                while (idx >= 0) {
                    score++;
                    idx = lower.indexOf(k.toLowerCase(Locale.ROOT), idx + 1);
                }
            }
        }
        return score;
    }

    private static List<String> extractKeywords(String question) {
        if (question == null) return List.of();

        List<String> tokens = new ArrayList<>();

        String[] parts = question.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        for (String p : parts) {
            if (p != null && p.length() >= 3) tokens.add(p);
        }

        Matcher m = Pattern.compile("[\\p{IsHan}]{2,}").matcher(question);
        while (m.find()) tokens.add(m.group());

        LinkedHashSet<String> set = new LinkedHashSet<>(tokens);
        return set.stream().limit(12).toList();
    }

    // --------------------------
    // Progress payload helpers
    // --------------------------
    private List<Map<String, Object>> summarizeFilesFetched(List<FileItem> files) {
        if (files == null) return List.of();
        return files.stream()
                .filter(Objects::nonNull)
                .map(f -> Map.<String, Object>of(
                        "url", safe(f.url(), ""),
                        "name", safe(f.name(), "file"),
                        "mime", safe(f.mime(), "application/octet-stream"),
                        "error", safe(f.error(), "")
                ))
                .toList();
    }

    private List<Map<String, Object>> summarizeFilesExtracted(List<FileItem> files) {
        if (files == null) return List.of();
        return files.stream()
                .filter(Objects::nonNull)
                .map(f -> Map.<String, Object>of(
                        "name", safe(f.name(), "file"),
                        "mime", safe(f.mime(), "application/octet-stream"),
                        "keywords", uniqLimit(f.keywords(), 8),
                        "queries", uniqLimit(f.queries(), 4),
                        "preview", truncate(safeText(f.keyText()), 400),
                        "error", safe(f.error(), "")
                ))
                .toList();
    }

    private List<Map<String, Object>> summarizeHistory(String historyText) {
        if (historyText == null || historyText.isBlank()) return List.of();
        return List.of(Map.of("preview", truncate(historyText, 500)));
    }

    private List<Map<String, Object>> summarizeRetrieval(RagRetrievalResult retrieval) {
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) return List.of();

        return retrieval.documents().stream()
                .map(sd -> {
                    var doc = sd.document();
                    String content = doc.getContent();
                    String preview = (content == null) ? "" : (content.length() > 200 ? content.substring(0, 200) + "..." : content);

                    return Map.<String, Object>of(
                            "id", doc.getId(),
                            "type", doc.getDocType(),
                            "score", sd.score(),
                            "preview", preview
                    );
                })
                .toList();
    }

    // --------------------------
    // Context compaction
    // --------------------------
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

                boolean oos = n.path("outOfScope").asBoolean(false);
                if (oos) continue;

                String q = n.path("question").asText("");
                String a = n.path("answer").asText("");
                if (a != null && a.trim().equals(OUT_OF_SCOPE_REPLY)) continue;

                long t = n.path("createdAt").asLong(0L);
                String id = n.path("id").asText("");

                if ((q == null || q.isBlank()) && (a == null || a.isBlank())) continue;
                out.add(new Row(id, t, q, a));
            }

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

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record QaCandidate(String id, String type, double score, int matchScore, String question, String answer) {}

    // --------------------------
    // Logging
    // --------------------------
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

            RagRunEvent evt = RagRunEvent.from(
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
            logIngestionClient.ingestAsync(evt);
        } catch (Exception ignored) {
            // Logging must never break answering
        }
    }
}
