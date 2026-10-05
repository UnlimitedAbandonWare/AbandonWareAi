package com.example.lms.dto;

import com.example.lms.trace.SafeRedactor;

import java.util.List;
import java.util.Map;

/**
 * SSE event payload.
 *
 * <p>Event types are intentionally separated by signal ownership: status drives
 * the status line, evidence drives the evidence rail, trace drives operator
 * diagnostics, and scoreDelta drives normalized drawdown cards. Each typed
 * signal carries only the unit/range that its UI owner renders; raw queries,
 * snippets, keys, owner tokens, Authorization headers, and environment values
 * must stay out of this DTO.</p>
 */
public record ChatStreamEvent(
        String type,
        String data,
        String html,
        String modelUsed,
        Boolean ragUsed,
        Long sessionId,
        String answerMode,
        Long traceTurnId,
        LearningContextMetadata learningContext,
        List<RagEvidenceMetadata> evidence,
        StatusSignal statusSignal,
        TraceSignal traceSignal,
        ScoreDeltaSignal scoreDelta,
        PipelineSnapshot pipelineSnapshot,
        DebugFxSignal debugFxSignal,
        List<TransformerBlockSignal> transformerBlocks,
        SelectionEntropySignal selectionEntropySignal,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ChatResponseDto.GenerationTermination generationTermination,
        @com.fasterxml.jackson.annotation.JsonUnwrapped GenerationObservation observation,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String evidenceHint
) {
        public ChatStreamEvent(String type, String data, String html, String modelUsed, Boolean ragUsed,
                Long sessionId, String answerMode, Long traceTurnId, LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence, StatusSignal statusSignal, TraceSignal traceSignal,
                ScoreDeltaSignal scoreDelta, PipelineSnapshot pipelineSnapshot, DebugFxSignal debugFxSignal,
                List<TransformerBlockSignal> transformerBlocks, SelectionEntropySignal selectionEntropySignal,
                ChatResponseDto.GenerationTermination generationTermination, GenerationObservation observation) {
                this(type, data, html, modelUsed, ragUsed, sessionId, answerMode, traceTurnId, learningContext,
                        evidence, statusSignal, traceSignal, scoreDelta, pipelineSnapshot, debugFxSignal,
                        transformerBlocks, selectionEntropySignal, generationTermination, observation, null);
        }
        public ChatStreamEvent(String type, String data, String html, String modelUsed, Boolean ragUsed,
                Long sessionId, String answerMode, Long traceTurnId, LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence, StatusSignal statusSignal, TraceSignal traceSignal,
                ScoreDeltaSignal scoreDelta, PipelineSnapshot pipelineSnapshot, DebugFxSignal debugFxSignal,
                List<TransformerBlockSignal> transformerBlocks, SelectionEntropySignal selectionEntropySignal,
                ChatResponseDto.GenerationTermination generationTermination) {
                this(type, data, html, modelUsed, ragUsed, sessionId, answerMode, traceTurnId, learningContext,
                        evidence, statusSignal, traceSignal, scoreDelta, pipelineSnapshot, debugFxSignal,
                        transformerBlocks, selectionEntropySignal, generationTermination, null);
        }
        public ChatStreamEvent withObservation(Map<String, Object> trace) {
                return new ChatStreamEvent(type, data, html, modelUsed, ragUsed, sessionId, answerMode, traceTurnId,
                        learningContext, evidence, statusSignal, traceSignal, scoreDelta, pipelineSnapshot,
                        debugFxSignal, transformerBlocks, selectionEntropySignal, generationTermination,
                        GenerationObservation.from(trace), ChatResponseDto.evidenceHintFrom(trace));
        }
        public ChatStreamEvent(String type, String data, String html, String modelUsed, Boolean ragUsed,
                Long sessionId, String answerMode, Long traceTurnId, LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence, StatusSignal statusSignal, TraceSignal traceSignal,
                ScoreDeltaSignal scoreDelta, PipelineSnapshot pipelineSnapshot, DebugFxSignal debugFxSignal,
                List<TransformerBlockSignal> transformerBlocks, SelectionEntropySignal selectionEntropySignal) {
                this(type, data, html, modelUsed, ragUsed, sessionId, answerMode, traceTurnId, learningContext,
                        evidence, statusSignal, traceSignal, scoreDelta, pipelineSnapshot, debugFxSignal,
                        transformerBlocks, selectionEntropySignal, null);
        }

        public static ChatStreamEvent terminal(ChatResponseDto response) {
                return new ChatStreamEvent("final", response.getContent(), null, response.getModelUsed(),
                        response.isRagUsed(), response.getSessionId(), null, null,
                        response.getLearningContext(), response.getEvidence(), null, null, null, null,
                        null, List.of(), null, response.getGenerationTermination(), response.getObservation(),
                        response.getEvidenceHint());
        }
        public ChatStreamEvent(
                String type,
                String data,
                String html,
                String modelUsed,
                Boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId,
                LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence,
                StatusSignal statusSignal,
                TraceSignal traceSignal,
                ScoreDeltaSignal scoreDelta,
                PipelineSnapshot pipelineSnapshot,
                DebugFxSignal debugFxSignal,
                List<TransformerBlockSignal> transformerBlocks) {
                this(type, data, html, modelUsed, ragUsed, sessionId, answerMode, traceTurnId,
                        learningContext, evidence, statusSignal, traceSignal, scoreDelta,
                        pipelineSnapshot, debugFxSignal, transformerBlocks, null);
        }

        public ChatStreamEvent {
                evidenceHint = "근거 없음".equals(evidenceHint) ? "근거 없음" : null;
                learningContext = learningContext == null ? LearningContextMetadata.empty() : learningContext;
                evidence = evidence == null ? List.of() : List.copyOf(evidence);
                transformerBlocks = transformerBlocks == null ? List.of() : List.copyOf(transformerBlocks);
        }

        public static ChatStreamEvent status(String msg) {
                return new ChatStreamEvent("status", readableStreamText(msg), null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null, List.of());
        }

        public static ChatStreamEvent status(StatusSignal signal) {
                String msg = signal == null ? null : signal.message();
                return new ChatStreamEvent("status", msg, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), signal, null, null, null, null, List.of());
        }

        public static ChatStreamEvent trace(String html) {
                return trace(html, null);
        }

        public static ChatStreamEvent trace(String html, TraceSignal signal) {
                return trace(html, signal, null);
        }

        public static ChatStreamEvent trace(String html, TraceSignal signal, PipelineSnapshot pipelineSnapshot) {
                return new ChatStreamEvent("trace", null, html, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, signal, null, pipelineSnapshot, null, List.of());
        }

        public static ChatStreamEvent token(String chunk) {
                return new ChatStreamEvent("token", chunk, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null, List.of());
        }

        public static ChatStreamEvent evidence(List<RagEvidenceMetadata> evidence) {
                return new ChatStreamEvent("evidence", null, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), evidence == null ? List.of() : List.copyOf(evidence),
                        null, null, null, null, null, List.of());
        }

        public static ChatStreamEvent scoreDelta(ScoreDeltaSignal signal) {
                return new ChatStreamEvent("scoreDelta", null, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, signal, null, null, List.of());
        }

        public static ChatStreamEvent debugFx(DebugFxSignal signal) {
                return new ChatStreamEvent("debug_fx", null, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, signal, List.of());
        }

        public static ChatStreamEvent transformer(List<TransformerBlockSignal> blocks) {
                return new ChatStreamEvent("transformer", null, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null,
                        blocks == null ? List.of() : List.copyOf(blocks));
        }

        public static ChatStreamEvent selectionEntropy(SelectionEntropySignal signal) {
                return new ChatStreamEvent("selection_entropy", null, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null,
                        List.of(), signal);
        }

        /**
         * Emit an early event carrying the resolved session id.
         */
        public static ChatStreamEvent sessionReady(Long sessionId) {
                return sessionReady(sessionId, null);
        }

        /**
         * Emit the resolved session together with an opaque exact-run capability.
         * Existing clients safely ignore the additive data field.
         */
        public static ChatStreamEvent sessionReady(Long sessionId, String runToken) {
                return sessionReady(sessionId, runToken, null);
        }

        public static ChatStreamEvent sessionReady(Long sessionId, String runToken, TraceSignal traceSignal) {
                return new ChatStreamEvent("session", runToken, null, null, null, sessionId, null, null,
                        LearningContextMetadata.empty(), List.of(), null, traceSignal, null, null, null, List.of());
        }

        public static ChatStreamEvent done(String modelUsed, boolean ragUsed, Long sessionId) {
                return done(modelUsed, ragUsed, sessionId, null);
        }

        public static ChatStreamEvent done(String modelUsed, boolean ragUsed, Long sessionId, String answerMode) {
                return done(modelUsed, ragUsed, sessionId, answerMode, null);
        }

        public static ChatStreamEvent done(
                String modelUsed,
                boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId) {
                return done(modelUsed, ragUsed, sessionId, answerMode, traceTurnId, LearningContextMetadata.empty());
        }

        public static ChatStreamEvent done(
                String modelUsed,
                boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId,
                LearningContextMetadata learningContext) {
                return done(modelUsed, ragUsed, sessionId, answerMode, traceTurnId, learningContext, List.of());
        }

        public static ChatStreamEvent done(
                String modelUsed,
                boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId,
                LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence) {
                return done(modelUsed, ragUsed, sessionId, answerMode, traceTurnId, learningContext, evidence, null);
        }

        public static ChatStreamEvent done(
                String modelUsed,
                boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId,
                LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence,
                PipelineSnapshot pipelineSnapshot) {
                return new ChatStreamEvent("final", null, null, modelUsed, ragUsed, sessionId, answerMode,
                        traceTurnId, learningContext == null ? LearningContextMetadata.empty() : learningContext,
                        evidence == null ? List.of() : List.copyOf(evidence), null, null, null, pipelineSnapshot, null, List.of());
        }

        public static ChatStreamEvent doneWithAnswer(
                String answer,
                String modelUsed,
                boolean ragUsed,
                Long sessionId,
                String answerMode,
                Long traceTurnId,
                LearningContextMetadata learningContext,
                List<RagEvidenceMetadata> evidence,
                PipelineSnapshot pipelineSnapshot) {
                return new ChatStreamEvent("final", answer, null, modelUsed, ragUsed, sessionId, answerMode,
                        traceTurnId, learningContext == null ? LearningContextMetadata.empty() : learningContext,
                        evidence == null ? List.of() : List.copyOf(evidence), null, null, null, pipelineSnapshot, null, List.of());
        }

        public static ChatStreamEvent error(String msg) {
                return new ChatStreamEvent("error", msg, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null, List.of());
        }

        public static ChatStreamEvent thought(String msg) {
                return new ChatStreamEvent("thought", readableStreamText(msg), null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null, List.of());
        }

        public static ChatStreamEvent understanding(String json) {
                return new ChatStreamEvent("understanding", json, null, null, null, null, null, null,
                        LearningContextMetadata.empty(), List.of(), null, null, null, null, null, List.of());
        }

        /**
         * Status signal for loader/stream-card rendering.
         *
         * <p>Units: remainingMs and tookMs are milliseconds. Ranges: time values
         * are non-negative. Security: labels are redacted and truncated. UI owner:
         * loader text plus the stream status card.</p>
         */
        public record StatusSignal(
                String phase,
                String code,
                String message,
                Long remainingMs,
                Long tookMs,
                Boolean cancelled
        ) {
                public StatusSignal {
                        phase = cleanSignal(phase);
                        code = cleanSignal(code);
                        message = cleanSignal(message);
                        remainingMs = nonNegative(remainingMs);
                        tookMs = nonNegative(tookMs);
                }

                public static StatusSignal of(String phase, String code, String message,
                                              Long remainingMs, Long tookMs, Boolean cancelled) {
                        return new StatusSignal(phase, code, message, remainingMs, tookMs, cancelled);
                }
        }

        /**
         * Typed, allowlisted projection of request-scoped selection entropy.
         * The replay reference is a validated 12-hex fingerprint, never the seed.
         */
        public record SelectionEntropySignal(
                String schema,
                String mode,
                String algorithmVersion,
                Boolean replayAccepted,
                String coherenceStatus,
                String replayReference,
                String decisionDigest,
                Integer decisionCount,
                Integer drawCount,
                Integer stableTieBreakCount,
                Integer candidateDriftCount,
                Integer routerDrawCount,
                Integer strategyDrawCount,
                Integer ensembleDrawCount,
                Boolean completionOrderDeterministic,
                String reasonCode
        ) {
        }

        /**
         * Trace signal for operator diagnostics.
         *
         * <p>Units: counts are events per request. Ranges: counts are
         * non-negative. Security: identifiers are hashes and labels are
         * redacted/truncated; trace HTML is emitted only by the controller's
         * debug/exposeTrace gate. UI owner: trace panel, trace card, and
         * orchestration badges.</p>
         */
        public record TraceSignal(
                String traceIdHash,
                String requestIdHash,
                String sessionIdHash,
                Integer eventCount,
                String failureClass,
                String reasonCode,
                Map<String, Integer> stageCounts
        ) {
                public TraceSignal {
                        traceIdHash = cleanSignal(traceIdHash);
                        requestIdHash = cleanSignal(requestIdHash);
                        sessionIdHash = cleanSignal(sessionIdHash);
                        failureClass = cleanSignal(failureClass);
                        reasonCode = cleanSignal(reasonCode);
                        eventCount = eventCount == null ? null : Math.max(0, eventCount);
                        stageCounts = stageCounts == null ? Map.of() : Map.copyOf(stageCounts);
                }
        }

        /**
         * Score drawdown signal for orchestration score cards.
         *
         * <p>Units: ratios. Ranges: scoreDelta, dropRatio, maxDrawdown,
         * expectedDelta, and rawScoreDelta are clamped to [0,1]. Security:
         * stage/guard/clamp labels are redacted/truncated. UI owner: the
         * scoreDelta/dropRatio/maxDrawdown/expectedDelta cards.</p>
         */
        public record ScoreDeltaSignal(
                Double scoreDelta,
                Double dropRatio,
                Double maxDrawdown,
                Double expectedDelta,
                Double rawScoreDelta,
                String clampName,
                String stage,
                String guard,
                Long eventId
        ) {
                public ScoreDeltaSignal {
                        scoreDelta = clamp01(scoreDelta);
                        dropRatio = clamp01(dropRatio);
                        maxDrawdown = clamp01(maxDrawdown);
                        expectedDelta = clamp01(expectedDelta);
                        rawScoreDelta = clamp01(rawScoreDelta);
                        clampName = cleanSignal(clampName);
                        stage = cleanSignal(stage);
                        guard = cleanSignal(guard);
                        eventId = nonNegative(eventId);
                }
        }

        /**
         * Lightweight UI effect signal for diagnostics. Values are labels and counts
         * only; raw prompts, snippets, queries, tokens, and HTML must not be placed here.
         */
        public record DebugFxSignal(
                String phase,
                String code,
                String effect,
                String message,
                Long eventId,
                Map<String, String> labels
        ) {
                public DebugFxSignal {
                        phase = cleanSignal(phase);
                        code = cleanSignal(code);
                        effect = cleanSignal(effect);
                        message = cleanSignal(message);
                        eventId = nonNegative(eventId);
                        labels = cleanSignalMap(labels);
                }
        }

        /**
         * UI-owned transformer block status. Values are short labels, counts, and
         * redacted reason codes only; no raw prompt, query, snippet, key, token,
         * or header data may be copied into this surface.
         */
        public record TransformerBlockSignal(
                String id,
                String label,
                String phase,
                String status,
                String reason,
                Integer order,
                Long tookMs
        ) {
                public TransformerBlockSignal {
                        id = cleanSignal(id);
                        label = cleanSignal(label);
                        phase = cleanSignal(phase);
                        status = cleanSignal(status);
                        reason = cleanReason(reason);
                        order = nonNegative(order);
                        tookMs = nonNegative(tookMs);
                }
        }

        /**
         * Redacted orchestration snapshot shared by trace/final events.
         *
         * <p>Security: counts, labels, and already-redacted reason codes only.
         * Raw queries, snippets, prompts, keys, tokens, and headers must not be
         * copied into this surface.</p>
         */
        public record PipelineSnapshot(
                String planId,
                String route,
                String answerMode,
                Long traceTurnId,
                Integer webCount,
                Integer vectorCount,
                Integer finalContextCount,
                Double citationCoverage,
                Double finalSigmoid,
                String failureClass,
                String disabledReason,
                String finalContextCountSource,
                Boolean planWhenPresent,
                String planWhen,
                String planWhenPost,
                Boolean planLateActivation,
                List<PlanStageSnapshot> planStages,
                AgentWebSearchSnapshot agentWebSearch,
                ContextUsageSnapshot contextUsage,
                ExecutionModeSnapshot executionMode
        ) {
                public PipelineSnapshot(String planId, String route, String answerMode,
                                        Long traceTurnId, Integer webCount, Integer vectorCount,
                                        Integer finalContextCount, Double citationCoverage,
                                        Double finalSigmoid, String failureClass, String disabledReason,
                                        String finalContextCountSource, Boolean planWhenPresent,
                                        String planWhen, String planWhenPost, Boolean planLateActivation,
                                        List<PlanStageSnapshot> planStages, AgentWebSearchSnapshot agentWebSearch,
                                        ContextUsageSnapshot contextUsage) {
                        this(planId, route, answerMode, traceTurnId, webCount, vectorCount,
                                finalContextCount, citationCoverage, finalSigmoid, failureClass, disabledReason,
                                finalContextCountSource, planWhenPresent, planWhen, planWhenPost,
                                planLateActivation, planStages, agentWebSearch, contextUsage, null);
                }
                public PipelineSnapshot(String planId, String route, String answerMode,
                                        Long traceTurnId, Integer webCount, Integer vectorCount,
                                        Integer finalContextCount, Double citationCoverage,
                                        Double finalSigmoid, String failureClass, String disabledReason,
                                        String finalContextCountSource, Boolean planWhenPresent,
                                        String planWhen, String planWhenPost, Boolean planLateActivation,
                                        List<PlanStageSnapshot> planStages, AgentWebSearchSnapshot agentWebSearch) {
                        this(planId, route, answerMode, traceTurnId, webCount, vectorCount,
                                finalContextCount, citationCoverage, finalSigmoid, failureClass,
                                disabledReason, finalContextCountSource, planWhenPresent, planWhen,
                                planWhenPost, planLateActivation, planStages, agentWebSearch, null);
                }
                public PipelineSnapshot(String planId, String route, String answerMode,
                                        Long traceTurnId, Integer webCount, Integer vectorCount,
                                        Integer finalContextCount, Double citationCoverage,
                                        Double finalSigmoid, String failureClass, String disabledReason,
                                        String finalContextCountSource, Boolean planWhenPresent,
                                        String planWhen, String planWhenPost, Boolean planLateActivation,
                                        List<PlanStageSnapshot> planStages) {
                        this(planId, route, answerMode, traceTurnId, webCount, vectorCount,
                                finalContextCount, citationCoverage, finalSigmoid, failureClass,
                                disabledReason, finalContextCountSource, planWhenPresent, planWhen,
                                planWhenPost, planLateActivation, planStages, null);
                }

                public PipelineSnapshot(String planId, String route, String answerMode,
                                        Long traceTurnId, Integer webCount, Integer vectorCount,
                                        Integer finalContextCount, Double citationCoverage,
                                        Double finalSigmoid, String failureClass, String disabledReason) {
                        this(planId, route, answerMode, traceTurnId, webCount, vectorCount,
                                finalContextCount, citationCoverage, finalSigmoid, failureClass,
                                disabledReason, null, null, null, null, null, null);
                }

                public PipelineSnapshot {
                        planId = cleanSignal(planId);
                        route = cleanSignal(route);
                        answerMode = cleanSignal(answerMode);
                        traceTurnId = nonNegative(traceTurnId);
                        webCount = nonNegative(webCount);
                        vectorCount = nonNegative(vectorCount);
                        finalContextCount = nonNegative(finalContextCount);
                        citationCoverage = clamp01(citationCoverage);
                        finalSigmoid = clamp01(finalSigmoid);
                        failureClass = cleanSignal(failureClass);
                        disabledReason = cleanReason(disabledReason);
                        finalContextCountSource = "reported".equals(finalContextCountSource)
                                || "web_vector_estimate".equals(finalContextCountSource)
                                ? finalContextCountSource : null;
                        planWhen = cleanPlanWhen(planWhen);
                        planWhenPost = cleanPlanWhen(planWhenPost);
                        planStages = planStages == null ? null : List.copyOf(planStages);
                }
        }

        public record ExecutionModeSnapshot(String requested, String effective, String reason,
                                            Integer queryCount, Integer httpAttempts, Boolean expanded) {
                public ExecutionModeSnapshot {
                        requested = java.util.Set.of("AUTO", "STRIKE", "SELF_ASK").contains(requested == null ? "" : requested) ? requested : null;
                        effective = java.util.Set.of("AUTO", "STRIKE", "SELF_ASK").contains(effective == null ? "" : effective) ? effective : null;
                        reason = java.util.Set.of("base-retrieval", "user-strike", "user-self-ask", "evidence-gap",
                                "safety-gate", "search-off", "deadline-or-cancel", "evidence-sufficient",
                                "simple-query", "global-disabled", "cheap-search-mode", "search-budget")
                                .contains(reason == null ? "" : reason) ? reason : null;
                        queryCount = queryCount != null && queryCount >= 0 && queryCount <= 3 ? queryCount : null;
                        httpAttempts = httpAttempts != null && httpAttempts >= 0 && httpAttempts <= 6 ? httpAttempts : null;
                }
        }

        /** Numeric request-local projection; memory text and model metadata remain private. */
        public record ContextUsageSnapshot(Integer inputTokens, Integer contextLimitTokens,
                                           String countMethod, String limitSource,
                                           Integer memoryBeforeChars, Integer memoryAfterChars,
                                           Boolean memoryCompressionActivated, String memoryCompressionReason,
                                           Boolean memoryIncluded) {
                public ContextUsageSnapshot {
                        inputTokens = inputTokens != null && inputTokens >= 0 ? inputTokens : null;
                        countMethod = inputTokens != null && "char_estimate".equals(countMethod) ? countMethod : null;
                        contextLimitTokens = contextLimitTokens != null && contextLimitTokens > 0
                                && "model_spec_snapshot".equals(limitSource) ? contextLimitTokens : null;
                        limitSource = contextLimitTokens != null ? "model_spec_snapshot" : null;
                        memoryBeforeChars = memoryBeforeChars != null && memoryBeforeChars >= 0 ? memoryBeforeChars : null;
                        memoryAfterChars = memoryAfterChars != null && memoryAfterChars >= 0 ? memoryAfterChars : null;
                        memoryCompressionReason = java.util.Set.of("disabled", "empty_input", "below_threshold",
                                "all_lines_dropped", "exception_original_returned", "overflow",
                                "history_context_contamination", "overflow_and_contamination")
                                .contains(memoryCompressionReason == null ? "" : memoryCompressionReason)
                                ? memoryCompressionReason : null;
                }
        }

        /** Request-local outcome only; provider text and evidence never enter this projection. */
        public record AgentWebSearchSnapshot(String status, String reasonCode, Integer returnedCount) {
                public AgentWebSearchSnapshot {
                        status = "OK".equals(status) || "FAIL_SOFT".equals(status) || "SKIPPED".equals(status)
                                ? status : null;
                        reasonCode = "OK".equals(status) ? null : cleanReason(reasonCode);
                        returnedCount = nonNegative(returnedCount);
                }
        }

        public record PlanStageSnapshot(String stage, String status, Integer count,
                                        Long durationMs, String reason) {
                public PlanStageSnapshot {
                        stage = cleanSignal(stage);
                        status = cleanSignal(status);
                        count = nonNegative(count);
                        durationMs = nonNegative(durationMs);
                        reason = cleanReason(reason);
                }
        }

        private static String cleanPlanWhen(String value) {
                return "true".equals(value) || "false".equals(value) || "unknown".equals(value)
                        ? value : null;
        }

        private static Integer nonNegative(Integer value) {
                return value == null ? null : Math.max(0, value);
        }

        private static Long nonNegative(Long value) {
                return value == null ? null : Math.max(0L, value);
        }

        private static Double clamp01(Double value) {
                if (value == null || !Double.isFinite(value)) {
                        return null;
                }
                return Math.max(0.0d, Math.min(1.0d, value));
        }

        private static String cleanSignal(String value) {
                String s = clean(value);
                if (s == null) {
                        return null;
                }
                s = s.replaceAll("(?i)(authorization|ownerToken|api[-_]?key|client[-_]?secret|secret|token)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=<redacted>");
                s = s.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer <redacted>");
                s = s.replaceAll("(?i)\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}\\b", "<redacted>");
                return s.length() <= 240 ? s : s.substring(0, 240);
        }

        private static String readableStreamText(String value) {
                String s = cleanSignal(value);
                if (s == null) {
                        return null;
                }
                if (s.contains("* ... *&#47;") || s.contains("\uFFFD")) {
                        return "debug stream update";
                }
                return s;
        }

        private static String cleanReason(String value) {
                String s = clean(value);
                if (s == null) {
                        return null;
                }
                return SafeRedactor.traceLabelOrFallback(s, null);
        }

        private static Map<String, String> cleanSignalMap(Map<String, String> value) {
                if (value == null || value.isEmpty()) {
                        return Map.of();
                }
                java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
                value.forEach((k, v) -> {
                        String key = cleanSignal(k);
                        if (key != null && out.size() < 32) {
                                String safeValue = cleanSignal(v);
                                out.put(key, safeValue == null ? "" : safeValue);
                        }
                });
                return Map.copyOf(out);
        }

        private static String clean(String value) {
                if (value == null) {
                        return null;
                }
                String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
                return s.isEmpty() ? null : s;
        }
}
