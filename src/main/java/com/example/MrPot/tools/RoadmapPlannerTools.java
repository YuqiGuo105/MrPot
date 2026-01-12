package com.example.MrPot.tools;

import com.example.MrPot.model.RagAnswerRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RoadmapPlannerTools {

    private static final Pattern YUQI_PATTERN = Pattern.compile("(yuqi|\\u90ed\\u5b87\\u7426|\\u4e8e\\u742a|\\u90ed\\u745c\\u7426)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMON_SENSE_HINT = Pattern.compile("(what|why|how|explain|define|\\u4ec0\\u4e48|\\u89e3\\u91ca|\\u4e3a\\u4ec0\\u4e48|\\u600e\\u4e48)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_HINT = Pattern.compile("(code|bug|stacktrace|traceback|exception|api|endpoint|class|method|java|spring|yaml|config|\\u4ee3\\u7801)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLEX_HINT = Pattern.compile("(\\band\\b|\\bor\\b|\\bvs\\b|\\bversus\\b|\\bcompare\\b|,|;|\\n|/)", Pattern.CASE_INSENSITIVE);

    public record RoadmapPlan(
            List<String> steps,
            List<String> skips,
            List<String> rationale,
            boolean useKb,
            boolean useFiles,
            boolean useEntityResolve,
            boolean useCompress,
            boolean useVerify,
            boolean useConflictDetect,
            boolean useCodeSearch,
            boolean useQuestionDecompose,
            boolean useEvidenceGap,
            boolean useAnswerOutline,
            boolean useAssumptionCheck,
            boolean useActionPlan
    ) {}

    @Tool(name = "roadmap_plan", description = "Plan which tools to use and which to skip for deep thinking.")
    public RoadmapPlan plan(String question, RagAnswerRequest.ScopeMode scopeMode, boolean deepThinking, boolean hasFiles) {
        String q = question == null ? "" : question.trim();
        boolean mentionsYuqi = YUQI_PATTERN.matcher(q).find();
        boolean isCommonSense = COMMON_SENSE_HINT.matcher(q).find() && !mentionsYuqi;
        boolean isCode = CODE_HINT.matcher(q).find();

        List<String> steps = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        List<String> rationale = new ArrayList<>();

        boolean useKb = !isCommonSense;
        boolean useFiles = hasFiles && !isCommonSense;
        boolean useEntityResolve = deepThinking && useKb;
        boolean useCompress = deepThinking && (useKb || useFiles);
        boolean useVerify = deepThinking;
        boolean useConflictDetect = deepThinking && useKb && useFiles;
        boolean useCodeSearch = deepThinking && isCode;
        boolean useQuestionDecompose = deepThinking && isComplexQuestion(q);
        boolean useEvidenceGap = deepThinking;
        boolean useAnswerOutline = deepThinking;
        boolean useAssumptionCheck = deepThinking;
        boolean useActionPlan = deepThinking;

        steps.add("scope_guard");
        if (useQuestionDecompose) steps.add("question_decompose");
        if (useKb) steps.add("kb_search");
        if (useFiles) steps.add("file_fetch");
        if (useEntityResolve) steps.add("entity_resolve");
        steps.add("privacy_sanitize");
        if (useCompress) steps.add("context_compress");
        if (useCodeSearch) steps.add("code_search");
        if (useEvidenceGap) steps.add("evidence_gap");
        if (useAnswerOutline) steps.add("answer_outline");
        if (useAssumptionCheck) steps.add("assumption_check");
        if (useActionPlan) steps.add("action_plan");
        if (useConflictDetect) steps.add("conflict_detect");
        if (useVerify) steps.add("answer_verify");

        if (!useQuestionDecompose) {
            skips.add("question_decompose");
            rationale.add("question short/simple -> skip decomposition");
        }
        if (!useKb) {
            skips.add("kb_search");
            rationale.add("common_sense_question -> skip KB");
        }
        if (!useFiles && hasFiles) {
            skips.add("file_fetch");
            rationale.add("common_sense_question -> skip files");
        }
        if (!useEntityResolve) {
            skips.add("entity_resolve");
            rationale.add("entity resolve only when KB retrieval is needed");
        }
        if (!useCompress) {
            skips.add("context_compress");
            rationale.add("no evidence to compress");
        }
        if (!useCodeSearch) {
            skips.add("code_search");
            rationale.add("question not code-related");
        }
        if (!useEvidenceGap) {
            skips.add("evidence_gap");
            rationale.add("gap check only for deep thinking mode");
        }
        if (!useAnswerOutline) {
            skips.add("answer_outline");
            rationale.add("outline only for deep thinking mode");
        }
        if (!useConflictDetect) {
            skips.add("conflict_detect");
            rationale.add("conflict detection requires both KB and file evidence");
        }
        if (!useAssumptionCheck) {
            skips.add("assumption_check");
            rationale.add("assumption check only for deep thinking mode");
        }
        if (!useActionPlan) {
            skips.add("action_plan");
            rationale.add("action plan only for deep thinking mode");
        }

        if (scopeMode == RagAnswerRequest.ScopeMode.YUQI_ONLY && !mentionsYuqi) {
            rationale.add("YUQI_ONLY mode -> out of scope unless explicitly about Yuqi");
        }

        return new RoadmapPlan(
                steps,
                skips,
                rationale,
                useKb,
                useFiles,
                useEntityResolve,
                useCompress,
                useVerify,
                useConflictDetect,
                useCodeSearch,
                useQuestionDecompose,
                useEvidenceGap,
                useAnswerOutline,
                useAssumptionCheck,
                useActionPlan
        );
    }

    private boolean isComplexQuestion(String question) {
        if (question == null) return false;
        String q = question.trim();
        if (q.length() >= 160) return true;
        if (COMPLEX_HINT.matcher(q).find()) return true;
        int questionMarks = 0;
        for (char ch : q.toCharArray()) {
            if (ch == '?' || ch == '？') questionMarks++;
            if (questionMarks >= 2) return true;
        }
        return false;
    }
}
