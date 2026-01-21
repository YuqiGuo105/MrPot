package com.example.MrPot.service;

import com.example.MrPot.tools.*;

import java.util.List;

public record ToolRunSummary(
        List<String> keyInfo,
        EvidenceGapTools.EvidenceGapResult evidenceGap,
        AnswerOutlineTools.OutlineResult answerOutline,
        AssumptionCheckTools.AssumptionResult assumptionResult,
        ActionPlanTools.ActionPlanResult actionPlan,
        ScopeGuardTools.ScopeGuardResult scopeGuard,
        List<String> entityTerms,
        IntentDetectTools.IntentResult intentResult,
        KeywordExtractTools.KeywordResult keywordResult,
        TrackCorrectTools.TrackResult trackCorrectResult
) {}
