package com.example.MrPot.service;

import com.example.MrPot.model.*;
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

import java.time.Duration;
import java.util.*;
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
    private final LogIngestionClient logIngestionClient;

    // --- URL file fetch + extract (Tika / vision) ---
    private final AttachmentService attachmentService;

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    // Minimum similarity score required to consider a question "in scope" of the KB
    private static final double MIN_KB_SCORE_FOR_ANSWER = 0.15;

    // Internal canonical fallback marker (for logs/filters only; NOT required to be user-facing)
    private static final String OUT_OF_SCOPE_REPLY = "I can only answer Yuqi's related stuff.";

    // Prompt size controls (reduce token usage and latency)
    private static final int MAX_HISTORY_CHARS = 2500;
    private static final int MAX_CONTEXT_CHARS = 7000;

    // Extract up to N QA candidates to guide the LLM
    private static final int MAX_QA_CANDIDATES = 3;

    // Keep only a few latest log rows in prompt (token saver)
    private static final int MAX_LOG_ROWS_IN_PROMPT = 8;

    // URL attachment constraints
    private static final int MAX_FILE_URLS = 2;
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(30);

    // File-to-prompt budget (token saver)
    private static final int MAX_FILE_CONTEXT_CHARS = 3500;
    private static final int FILE_CHUNK_SIZE = 900;
    private static final int FILE_CHUNK_OVERLAP = 120;
    private static final int MAX_FILE_CHUNKS_IN_PROMPT = 2;

    // --- File keywords -> retrieval expansion budget ---
    private static final int MAX_FILE_QUERY_KEYWORDS = 2;
    private static final int MAX_FILE_QUERY_CHARS = 480;

    // --- Heuristic: only refine retrieval if initial is weak ---
    private static final double REFINE_TOP_SCORE_THRESHOLD = 0.25;

    // --- QA hint match thresholds (avoid blind copying) ---
    private static final int QA_MATCH_HIGH = 6;
    private static final int QA_MATCH_MED = 3;

    // --- Frontend progress payload limits ---
    private static final int PROGRESS_EXCERPT_CHARS = 900;
    private static final int PROGRESS_KEYWORDS = 8;

    private static final ObjectMapper OM = new ObjectMapper();

    private record FileInsights(List<String> queryKeywords, List<String> progressKeywords, String promptContext, String promptExcerpt) {
    }

    /**
     * Low-token, accuracy-first system prompt (fixes “everything out-of-scope”):
     * - Yuqi-specific/private facts MUST be grounded in CTX/FILE/HIS
     * - General knowledge/how-to/coding/science/common-sense: answer normally (even if CTX is empty/irrelevant)
     * - Treat Q/A blocks as strong evidence
     * - Strict fallback string ONLY for Yuqi-specific/private questions when evidence is missing
     */
    private static final String SYSTEM_PROMPT =
            "You are Mr Pot, Yuqi's assistant and a general-purpose helpful AI. " +
                    "Reply in the user's language. Be friendly, slightly playful, and human-like (no insults; no made-up facts). " +
                    "Scope: if the question is about Yuqi (his blog/projects/work/background/private facts) => Yuqi-mode; otherwise General-mode. " +
                    "Output: Prefer plain text for short/simple replies. Use WYSIWYG HTML only when needed for structure (multiple paragraphs/lists/tables) or notation (formulas). " +
                    "WYSIWYG rules: return a single HTML fragment (no Markdown, no outer <html>/<body>). Use <p>, <br>, <ul><li>, <strong>/<em>, <code>, <pre><code>, and <sup>/<sub> (e.g., E = mc<sup>2</sup>). " +
                    "Safety: never include <script>/<style>/<iframe> or inline event handlers. " +
                    "Yuqi-mode: use only evidence from CTX/FILE/HIS; never invent. If CTX has Q/A blocks (【问题】/【回答】), treat 【回答】 as strong evidence and you may polish. " +
                    "If asked for a number but evidence only supports a status/statement, answer the supported status/statement. " +
                    "If a Yuqi-mode question lacks evidence, reply exactly: \"" + OUT_OF_SCOPE_REPLY + "\". " +
                    "General-mode: for common sense/general knowledge/how-to/coding/science, answer normally even if CTX/FILE/HIS are empty or irrelevant.";

    public RagAnswer answer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();

        // 1) Retrieval by question
        RagRetrievalResult retrieval = ragRetrievalService.retrieve(
                new RagQueryRequest(request.question(), topK, minScore)
        );

        // 2) Fetch + extract URL attachments (blocking path)
        List<String> urls = request.resolveFileUrls(MAX_FILE_URLS);
        AttachmentContext attach = AttachmentContext.empty();

        if (!urls.isEmpty()) {
            String visionModel = Optional.ofNullable(request.resolveVisionModelOrNull())
                    .filter(s -> !s.isBlank())
                    .orElse(RagAnswerRequest.DEFAULT_MODEL);

            ChatClient visionClient = resolveClient(visionModel);

            attach = attachmentService.fetchAndExtract(urls, visionClient)
                    .timeout(ATTACH_TIMEOUT)
                    .onErrorReturn(AttachmentContext.empty())
                    .blockOptional()
                    .orElse(AttachmentContext.empty());
        }

        // 3) Build file context for prompt (budgeted)
        List<ExtractedFile> files = (attach == null) ? List.of() : safeList(attach.files());
        FileInsights fileInsights = summarizeFileInsights(request.question(), files);
        String fileText = fileInsights.promptContext();

        // 4) Use file keywords to refine retrieval (only when needed)
        List<String> fileKeywords = fileInsights.queryKeywords();
        if (shouldRefineRetrieval(retrieval, fileKeywords)) {
            String expandedQuery = buildExpandedRetrievalQuery(request.question(), fileKeywords);
            if (!expandedQuery.equals(request.question())) {
                retrieval = ragRetrievalService.retrieve(new RagQueryRequest(expandedQuery, topK, minScore));
            }
        }

        boolean outOfScopeKb = isOutOfScope(retrieval);
        boolean hasAnyRef = hasAnyReference(retrieval, fileText);
        boolean noEvidence = !hasAnyRef;

        ChatClient chatClient = resolveClient(model);

        var history = chatMemoryService.loadHistory(session.id());
        String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);

        ToolProfile profile = request.resolveToolProfile(ToolProfile.BASIC_CHAT);
        toolRegistry.getFunctionBeanNamesForProfile(profile);

        String prompt = buildPrompt(request.question(), retrieval, historyText, fileText, noEvidence, outOfScopeKb);

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
                    prompt, answer, latencyMs, noEvidence, error, retrieval);
            throw ex;
        }

        chatMemoryService.appendTurn(session.id(), request.question(), answer, session.temporary());

        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        safeLogOnce(session.id(), request.question(), model, topK, minScore,
                prompt, answer, latencyMs, noEvidence, null, retrieval);

        return new RagAnswer(answer, retrieval == null ? List.of() : retrieval.documents());
    }

    public Flux<String> streamAnswer(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();

        return prepareContextReactive(request, topK, minScore)
                .flatMapMany(ctx -> {
                    ChatClient chatClient = resolveClient(model);

                    var history = chatMemoryService.loadHistory(session.id());
                    String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);

                    boolean noEvidence = !ctx.hasAnyRef;
                    String prompt = buildPrompt(
                            request.question(),
                            ctx.retrieval,
                            historyText,
                            ctx.fileText,
                            noEvidence,
                            ctx.outOfScopeKb
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

    public Flux<ThinkingEvent> streamAnswerWithLogic(RagAnswerRequest request) {
        long t0 = System.nanoTime();

        RagAnswerRequest.ResolvedSession session = request.resolveSession();
        int topK = request.resolveTopK(DEFAULT_TOP_K);
        double minScore = request.resolveMinScore(DEFAULT_MIN_SCORE);
        String model = request.resolveModel();
        ChatClient chatClient = resolveClient(model);

        AtomicReference<StringBuilder> aggregate = new AtomicReference<>(new StringBuilder());

        AtomicReference<String> promptRef = new AtomicReference<>(null);
        AtomicReference<Boolean> noEvidenceRef = new AtomicReference<>(false);
        AtomicReference<RagRetrievalResult> retrievalRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        Mono<List<RedisChatMemoryService.StoredMessage>> historyMono =
                Mono.fromCallable(() -> chatMemoryService.loadHistory(session.id()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        // --- URL attachments (reactive) ---
        List<String> urls = request.resolveFileUrls(MAX_FILE_URLS);
        Mono<AttachmentContext> attachMono;
        if (urls.isEmpty()) {
            attachMono = Mono.just(AttachmentContext.empty());
        } else {
            String visionModel = Optional.ofNullable(request.resolveVisionModelOrNull())
                    .filter(s -> !s.isBlank())
                    .orElse(RagAnswerRequest.DEFAULT_MODEL);

            ChatClient visionClient = resolveClient(visionModel);

            attachMono = attachmentService.fetchAndExtract(urls, visionClient)
                    .timeout(ATTACH_TIMEOUT)
                    .onErrorReturn(AttachmentContext.empty())
                    .subscribeOn(Schedulers.boundedElastic())
                    .cache();
        }

        // --- Retrieval (2-phase with refinement based on file keywords) ---
        Mono<RagRetrievalResult> retrievalMono =
                Mono.fromCallable(() -> ragRetrievalService.retrieve(new RagQueryRequest(request.question(), topK, minScore)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        Mono<RagRetrievalResult> retrievalFinalMono =
                Mono.zip(retrievalMono, attachMono)
                        .flatMap(tuple -> {
                            RagRetrievalResult r0 = tuple.getT1();
                            AttachmentContext ac = tuple.getT2();

                            List<ExtractedFile> files = (ac == null) ? List.of() : safeList(ac.files());
                            List<String> fileKeywords = extractFileKeywords(files, MAX_FILE_QUERY_KEYWORDS);

                            if (!shouldRefineRetrieval(r0, fileKeywords)) return Mono.just(r0);

                            String expandedQuery = buildExpandedRetrievalQuery(request.question(), fileKeywords);
                            if (expandedQuery.equals(request.question())) return Mono.just(r0);

                            return Mono.fromCallable(() -> ragRetrievalService.retrieve(new RagQueryRequest(expandedQuery, topK, minScore)))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .onErrorReturn(r0);
                        })
                        .cache();

        Flux<ThinkingEvent> startStep = Flux.just(
                new ThinkingEvent("start", "Init", Map.of("ts", System.currentTimeMillis()))
        );

        // Emit URL list BEFORE subscribing to attachMono (frontend progress)
        Flux<ThinkingEvent> fileFetchStartStep = Flux.defer(() -> {
            if (urls.isEmpty()) return Flux.empty();
            return Flux.just(new ThinkingEvent(
                    "file_fetch_start",
                    "Fetching files",
                    Map.of("count", urls.size(), "urls", urls)
            ));
        });

        Flux<ThinkingEvent> fileFetchStep = attachMono.flatMapMany(ctx -> {
            if (urls.isEmpty()) return Flux.empty();
            List<ExtractedFile> fs = (ctx == null) ? List.of() : safeList(ctx.files());
            if (fs.isEmpty()) return Flux.just(new ThinkingEvent("file_fetch", "Fetched", Map.of("count", 0)));
            return Flux.just(new ThinkingEvent("file_fetch", "Fetched", summarizeFilesFetched(fs)));
        });

        Flux<ThinkingEvent> fileExtractStep = attachMono.flatMapMany(ctx -> {
            if (urls.isEmpty()) return Flux.empty();
            List<ExtractedFile> fs = (ctx == null) ? List.of() : safeList(ctx.files());
            if (fs.isEmpty()) return Flux.just(new ThinkingEvent("file_extract", "Extracted", Map.of("count", 0)));

            // Emit keywords + small relevant excerpt for frontend progress display
            FileInsights fileInsights = summarizeFileInsights(request.question(), fs);
            List<String> kws = fileInsights.progressKeywords();
            String excerpts = fileInsights.promptExcerpt();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", summarizeFilesExtracted(fs));
            payload.put("keywords", kws);
            payload.put("excerpts", excerpts);

            return Flux.just(new ThinkingEvent("file_extract", "Extracted files' content", payload));
        });

        Flux<ThinkingEvent> redisStep = historyMono.flatMapMany(history ->
                Flux.just(new ThinkingEvent("redis", "History", summarizeHistory(history)))
        );

        Flux<ThinkingEvent> ragStep = retrievalFinalMono.flatMapMany(retrieval ->
                Flux.just(new ThinkingEvent("rag", "Retrieval", summarizeRetrieval(retrieval)))
        );

        Flux<ThinkingEvent> answerDeltaStep =
                Mono.zip(historyMono, retrievalFinalMono, attachMono)
                        .flatMapMany(tuple -> {
                            var history = tuple.getT1();
                            var retrieval = tuple.getT2();
                            var attach = tuple.getT3();

                            retrievalRef.set(retrieval);

                            List<ExtractedFile> files = (attach == null) ? List.of() : safeList(attach.files());
                            FileInsights fileInsightsLocal = summarizeFileInsights(request.question(), files);
                            String fileText = fileInsightsLocal.promptContext();

                            boolean outOfScopeKb = isOutOfScope(retrieval);
                            boolean hasAnyRef = hasAnyReference(retrieval, fileText);
                            boolean noEvidence = !hasAnyRef;
                            noEvidenceRef.set(noEvidence);

                            String historyText = truncate(chatMemoryService.renderHistory(history), MAX_HISTORY_CHARS);
                            String prompt = buildPrompt(request.question(), retrieval, historyText, fileText, noEvidence, outOfScopeKb);
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

        // Order guaranteed: urls -> fetch -> extract -> redis/rag -> answer
        return Flux.concat(
                startStep,
                fileFetchStartStep,
                fileFetchStep,
                fileExtractStep,
                redisStep,
                ragStep,
                answerDeltaStep,
                finalStep
        );
    }

    // --------------------------
    // Reactive preparation helper
    // --------------------------

    private Mono<PreparedContext> prepareContextReactive(RagAnswerRequest request, int topK, double minScore) {
        List<String> urls = request.resolveFileUrls(MAX_FILE_URLS);

        Mono<AttachmentContext> attachMono;
        if (urls.isEmpty()) {
            attachMono = Mono.just(AttachmentContext.empty());
        } else {
            String visionModel = Optional.ofNullable(request.resolveVisionModelOrNull())
                    .filter(s -> !s.isBlank())
                    .orElse(RagAnswerRequest.DEFAULT_MODEL);

            ChatClient visionClient = resolveClient(visionModel);

            attachMono = attachmentService.fetchAndExtract(urls, visionClient)
                    .timeout(ATTACH_TIMEOUT)
                    .onErrorReturn(AttachmentContext.empty())
                    .subscribeOn(Schedulers.boundedElastic())
                    .cache();
        }

        Mono<RagRetrievalResult> retrievalMono =
                Mono.fromCallable(() -> ragRetrievalService.retrieve(new RagQueryRequest(request.question(), topK, minScore)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .cache();

        Mono<RagRetrievalResult> retrievalFinalMono =
                Mono.zip(retrievalMono, attachMono)
                        .flatMap(tuple -> {
                            RagRetrievalResult r0 = tuple.getT1();
                            AttachmentContext ac = tuple.getT2();

                            List<ExtractedFile> files = (ac == null) ? List.of() : safeList(ac.files());
                            List<String> fileKeywords = extractFileKeywords(files, MAX_FILE_QUERY_KEYWORDS);

                            if (!shouldRefineRetrieval(r0, fileKeywords)) return Mono.just(r0);

                            String expandedQuery = buildExpandedRetrievalQuery(request.question(), fileKeywords);
                            if (expandedQuery.equals(request.question())) return Mono.just(r0);

                            return Mono.fromCallable(() -> ragRetrievalService.retrieve(new RagQueryRequest(expandedQuery, topK, minScore)))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .onErrorReturn(r0);
                        })
                        .cache();

        return Mono.zip(retrievalFinalMono, attachMono)
                .map(tuple -> {
                    RagRetrievalResult retrieval = tuple.getT1();
                    AttachmentContext ac = tuple.getT2();
                    List<ExtractedFile> files = (ac == null) ? List.of() : safeList(ac.files());

                    FileInsights fileInsights = summarizeFileInsights(request.question(), files);
                    String fileText = fileInsights.promptContext();
                    boolean outOfScopeKb = isOutOfScope(retrieval);
                    boolean hasAnyRef = hasAnyReference(retrieval, fileText);

                    return new PreparedContext(retrieval, fileText, outOfScopeKb, hasAnyRef);
                });
    }

    private record PreparedContext(RagRetrievalResult retrieval, String fileText, boolean outOfScopeKb, boolean hasAnyRef) { }

    // --------------------------
    // Retrieval expansion
    // --------------------------

    private static boolean shouldRefineRetrieval(RagRetrievalResult retrieval, List<String> fileKeywords) {
        if (fileKeywords == null || fileKeywords.isEmpty()) return false;
        if (retrieval == null || retrieval.documents() == null || retrieval.documents().isEmpty()) return true;

        double top = retrieval.documents().stream().mapToDouble(ScoredDocument::score).max().orElse(0.0);
        return top < REFINE_TOP_SCORE_THRESHOLD;
    }

    private static String buildExpandedRetrievalQuery(String question, List<String> fileKeywords) {
        String q = (question == null) ? "" : question.trim();
        if (q.isBlank()) q = "";

        if (fileKeywords == null || fileKeywords.isEmpty()) return q;

        String joined = String.join(", ", fileKeywords);
        if (joined.length() > MAX_FILE_QUERY_CHARS) {
            joined = joined.substring(0, MAX_FILE_QUERY_CHARS) + "...";
        }

        return q + "\nFile keywords: " + joined;
    }

    private FileInsights summarizeFileInsights(String question, List<ExtractedFile> files) {
        List<ExtractedFile> safeFiles = safeList(files);
        List<String> queryKeywords = extractFileKeywords(safeFiles, MAX_FILE_QUERY_KEYWORDS);
        List<String> progressKeywords = extractFileKeywords(safeFiles, PROGRESS_KEYWORDS);
        String fileContext = buildFileContext(question, safeFiles, queryKeywords);
        String excerpt = truncate(fileContext, PROGRESS_EXCERPT_CHARS);

        return new FileInsights(queryKeywords, progressKeywords, fileContext, excerpt);
    }

    /**
     * Language-agnostic keyword extraction:
     * - Unicode tokens: letters/digits across any language/script
     * - If tokenization yields too few tokens (typical for no-space scripts), add char-bigrams from long runs
     * - No script-specific hard-coding
     */
    private static List<String> extractFileKeywords(List<ExtractedFile> files, int maxK) {
        if (files == null || files.isEmpty() || maxK <= 0) return List.of();

        // Any letters/digits (all scripts). No language/script hard-coding.
        final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");

        Map<String, Integer> freq = new HashMap<>();
        int totalTokenCount = 0;

        for (ExtractedFile f : files) {
            if (f == null) continue;
            if (f.error() != null && !f.error().isBlank()) continue;

            // 1) Filename tokens (boosted)
            String filename = Optional.ofNullable(f.filename()).orElse("");
            if (!filename.isBlank()) {
                for (String part : filename.split("[^\\p{L}\\p{N}]+")) {
                    String kw = normalizeKeyword(part);
                    if (!isNoiseToken(kw)) bump(freq, kw, 3);
                }
            }

            // 2) Text tokens (budgeted)
            String text = Optional.ofNullable(f.extractedText()).orElse("");
            if (text.isBlank()) continue;

            String scan = text.length() > 5000 ? text.substring(0, 5000) : text;

            Matcher m = TOKEN.matcher(scan);
            while (m.find()) {
                totalTokenCount++;
                String kw = normalizeKeyword(m.group());
                if (isNoiseToken(kw)) continue;
                bump(freq, kw, 1);
            }

            // 3) N-gram fallback (only when tokenization is sparse → often no-space writing)
            // Heuristic is language-agnostic: if we got very few tokens, we add bigrams from long runs.
            if (totalTokenCount < 20) {
                // Re-scan and only bigram "long" matches to avoid noise on typical space-separated languages.
                Matcher m2 = TOKEN.matcher(scan);
                int ngramBudget = 600; // guardrail
                while (m2.find() && ngramBudget > 0) {
                    String run = m2.group();
                    if (run == null) continue;
                    run = run.trim();
                    if (run.length() < 8) continue; // only long runs (common in no-space text)
                    if (run.length() > 80) run = run.substring(0, 80); // clamp

                    String normRun = normalizeKeyword(run);
                    if (!isNoiseToken(normRun) && normRun.length() <= 16) {
                        bump(freq, normRun, 1); // keep short-ish run as a whole too
                    }

                    for (int i = 0; i + 2 <= normRun.length() && ngramBudget > 0; i++) {
                        String bg = normRun.substring(i, i + 2);
                        if (!isNoiseToken(bg)) bump(freq, bg, 1);
                        ngramBudget--;
                    }
                }
            }
        }

        if (freq.isEmpty()) return List.of();

        return freq.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    if (c != 0) return c;
                    c = Integer.compare(b.getKey().length(), a.getKey().length());
                    if (c != 0) return c;
                    return a.getKey().compareTo(b.getKey());
                })
                .map(Map.Entry::getKey)
                .limit(maxK)
                .toList();
    }

    private static void bump(Map<String, Integer> freq, String kw, int delta) {
        if (kw == null || kw.isBlank()) return;
        freq.merge(kw, delta, Integer::sum);
    }

    private static String normalizeKeyword(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * Generic noise filter (NOT language-specific stopwords).
     * Conservative: removes obvious boilerplate only.
     */
    private static boolean isNoiseToken(String w) {
        if (w == null) return true;
        String s = w.trim();
        if (s.isEmpty()) return true;

        if (s.length() < 2) return true;
        if (s.length() > 40) return true;

        boolean allDigits = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return true;

        String lower = s.toLowerCase(Locale.ROOT);
        return Set.of(
                "http", "https", "www",
                "com", "org", "net",
                "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
                "png", "jpg", "jpeg", "webp",
                "file", "files", "image", "images"
        ).contains(lower);
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean hasAnyReference(RagRetrievalResult retrieval, String fileText) {
        if (fileText != null && !fileText.isBlank()) return true;
        if (retrieval == null) return false;

        if (retrieval.documents() != null && !retrieval.documents().isEmpty()) return true;

        String ctx = Optional.ofNullable(retrieval.context()).orElse("");
        return !ctx.isBlank();
    }

    // --------------------------
    // Prompt + file context
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

        String rawContext = (retrieval == null) ? "" : Optional.ofNullable(retrieval.context()).orElse("");
        String contextText = compactLogContext(rawContext, MAX_CONTEXT_CHARS);

        List<QaCandidate> qaCandidates = extractQaCandidates(retrieval, question);

        StringBuilder sb = new StringBuilder();

        sb.append("Meta: noEvidence=").append(noEvidence)
                .append(", kbWeak=").append(outOfScopeKb)
                .append("\n\n");

        String fileSection = truncate(Optional.ofNullable(fileText).orElse(""), MAX_FILE_CONTEXT_CHARS);
        if (!fileSection.isBlank()) {
            sb.append("File:\n").append(fileSection).append("\n\n");
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

        sb.append("Context:\n").append(contextText).append("\n\n");
        sb.append("History:\n").append(historyText).append("\n\n");
        sb.append("Q:\n").append(question).append("\n");

        return sb.toString();
    }

    private static String matchLabel(int score) {
        if (score >= QA_MATCH_HIGH) return "HIGH";
        if (score >= QA_MATCH_MED) return "MED";
        return "LOW";
    }

    private String buildFileContext(String question, List<ExtractedFile> files, List<String> fileKeywordsForScoring) {
        if (files == null || files.isEmpty()) return "";

        List<String> questionKeywords = extractKeywords(question);
        if (questionKeywords.isEmpty()) questionKeywords = List.of();

        List<String> scoringKeywords = new ArrayList<>(questionKeywords);
        if (fileKeywordsForScoring != null) {
            for (String kw : fileKeywordsForScoring) {
                if (kw == null || kw.isBlank()) continue;
                if (!scoringKeywords.contains(kw)) {
                    scoringKeywords.add(kw);
                }
            }
        }

        record Chunk(String fileName, String mime, String text, int score) {}
        List<Chunk> chunks = new ArrayList<>();

        for (ExtractedFile f : files) {
            if (f == null) continue;
            if (f.error() != null && !f.error().isBlank()) continue;

            String text = Optional.ofNullable(f.extractedText()).orElse("").trim();
            if (text.isBlank()) continue;

            List<String> pieceList = chunk(text, FILE_CHUNK_SIZE, FILE_CHUNK_OVERLAP);
            for (String piece : pieceList) {
                int score = scoreChunk(piece, scoringKeywords);
                chunks.add(new Chunk(
                        safe(f.filename(), "file"),
                        safe(f.mimeType(), "application/octet-stream"),
                        piece,
                        score
                ));
            }
        }

        if (chunks.isEmpty()) return "";

        chunks.sort((a, b) -> Integer.compare(b.score(), a.score()));

        StringBuilder sb = new StringBuilder();
        if (fileKeywordsForScoring != null && !fileKeywordsForScoring.isEmpty()) {
            sb.append("File keywords: ")
                    .append(String.join(", ", fileKeywordsForScoring))
                    .append("\n\n");
        }
        sb.append("Relevant excerpts:\n");

        int used = 0;
        int taken = 0;
        for (Chunk c : chunks) {
            if (taken >= MAX_FILE_CHUNKS_IN_PROMPT) break;

            String header = "- (" + c.fileName() + ", " + c.mime() + ")\n";
            String body = c.text();

            int addLen = header.length() + body.length() + 2;
            if (used + addLen > MAX_FILE_CONTEXT_CHARS) {
                int remaining = MAX_FILE_CONTEXT_CHARS - used - header.length() - 5;
                if (remaining <= 80) break;
                body = body.substring(0, Math.min(body.length(), remaining)) + "...";
                addLen = header.length() + body.length() + 2;
            }

            sb.append(header).append(body).append("\n\n");
            used += addLen;
            taken++;

            if (used >= MAX_FILE_CONTEXT_CHARS) break;
        }

        return truncate(sb.toString(), MAX_FILE_CONTEXT_CHARS);
    }

    private static String safe(String s, String d) {
        return (s == null || s.isBlank()) ? d : s;
    }

    private static List<String> chunk(String text, int size, int overlap) {
        String s = text == null ? "" : text;
        if (s.isBlank()) return List.of();
        int step = Math.max(1, size - overlap);

        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += step) {
            int end = Math.min(s.length(), i + size);
            String part = s.substring(i, end).trim();
            if (!part.isBlank()) out.add(part);
            if (end >= s.length()) break;
        }
        return out;
    }

    private static int scoreChunk(String chunk, List<String> keywords) {
        if (chunk == null || chunk.isBlank() || keywords == null || keywords.isEmpty()) return 0;

        String lower = chunk.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String k = kw.toLowerCase(Locale.ROOT);

            int idx = 0;
            while ((idx = lower.indexOf(k, idx)) >= 0) {
                score += 2;
                idx += k.length();
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

    private List<Map<String, Object>> summarizeFilesFetched(List<ExtractedFile> files) {
        return files.stream().map(f -> Map.<String, Object>of(
                "url", String.valueOf(f.uri()),
                "name", safe(f.filename(), "file"),
                "mime", safe(f.mimeType(), "application/octet-stream"),
                "bytes", f.sizeBytes()
        )).toList();
    }

    private List<Map<String, Object>> summarizeFilesExtracted(List<ExtractedFile> files) {
        return files.stream().map(f -> Map.<String, Object>of(
                "name", safe(f.filename(), "file"),
                "mime", safe(f.mimeType(), "application/octet-stream"),
                "preview", truncate(Optional.ofNullable(f.extractedText()).orElse(""), 400),
                "error", Optional.ofNullable(f.error()).orElse("")
        )).toList();
    }

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

    private List<Map<String, Object>> summarizeHistory(List<RedisChatMemoryService.StoredMessage> history) {
        if (history == null || history.isEmpty()) return List.of();

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
                            || content.toLowerCase().contains("answer:");

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

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record QaCandidate(String id, String type, double score, int matchScore, String question, String answer) {}

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
