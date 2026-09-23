package ai.abandonware.nova.orch.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import com.example.lms.search.TraceStore;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

class NovaAopFailSoftRedactionContractTest {

    @Test
    void guardDebugTraceOrchestrationReasonUsesTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("m.put(\"orch.reason\", orchReason);"));
        assertFalse(source.contains(".append(\" reason=\").append(orchReason);"));
        assertFalse(source.contains("m.put(\"orch.reason\", SafeRedactor.safeMessage(orchReason, 120));"));
        assertFalse(source.contains(".append(\" reason=\").append(SafeRedactor.safeMessage(orchReason, 120));"));
        assertTrue(source.contains("import com.example.lms.trace.SafeRedactor;"));
        assertTrue(source.contains(
                "m.put(\"orch.reason\", SafeRedactor.traceLabelOrFallback(orchReason, \"unknown\"));"));
        assertTrue(source.contains(
                ".append(\" reason=\").append(SafeRedactor.traceLabelOrFallback(orchReason, \"unknown\"));"));
    }

    @Test
    void guardDebugTraceAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java"), StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Guard debug trace aspect needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void guardDebugTraceQueryAndThrowableDiagnosticsAreRedacted() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("m.put(\"query\", queryClip);"));
        assertFalse(source.contains("sb.append(\" query=\\\"\").append(queryClip).append(\"\\\"\");"));
        assertFalse(source.contains(".append(err.getClass().getSimpleName()).append(\":\").append(safe(err.getMessage()))"));
        assertTrue(source.contains("m.put(\"queryHash\", SafeRedactor.hashValue(queryClip));"));
        assertTrue(source.contains("m.put(\"queryLength\", queryClip == null ? 0 : queryClip.length());"));
        assertTrue(source.contains("sb.append(\" queryHash=\").append(SafeRedactor.hashValue(queryClip))"));
        assertTrue(source.contains(".append(SafeRedactor.safeMessage(err.getMessage(), 120));"));
    }

    @Test
    void guardDebugTraceAuxLastDiagnosticsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("String auxDownLast = safe(String.valueOf(TraceStore.get(\"aux.down.last\")));"));
        assertFalse(source.contains("String auxBlockedLast = safe(String.valueOf(TraceStore.get(\"aux.blocked.last\")));"));
        assertFalse(source.contains(
                "String auxDownLast = SafeRedactor.safeMessage(String.valueOf(TraceStore.get(\"aux.down.last\")), 120);"));
        assertFalse(source.contains(
                "String auxBlockedLast = SafeRedactor.safeMessage(String.valueOf(TraceStore.get(\"aux.blocked.last\")), 120);"));
        assertTrue(source.contains(
                "String auxDownLast = SafeRedactor.traceLabelOrFallback(TraceStore.get(\"aux.down.last\"), \"\");"));
        assertTrue(source.contains(
                "String auxBlockedLast = SafeRedactor.traceLabelOrFallback(TraceStore.get(\"aux.blocked.last\"), \"\");"));
    }

    @Test
    void guardDebugTraceSuppressedFallbacksCarryRedactedErrorBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"snapshot.before\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"snapshot.after\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"snapshot.asMap\", t);"));
        assertTrue(source.contains("[nova][guard-debug-trace] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void uawIdlePipelineCanonicalQueryUsesDiagnosticValue() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"uaw.pipeline.canonicalQuery\", q);"));
        assertTrue(source.contains(
                "TraceStore.put(\"uaw.pipeline.canonicalQuery\", SafeRedactor.diagnosticValue(\"uaw.pipeline.canonicalQuery\", q, 240));"));
    }

    @Test
    void uawIdlePipelineSeedTagUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"uaw.pipeline.seedTag\", seed.tag());"));
        assertTrue(source.contains(
                "TraceStore.put(\"uaw.pipeline.seedTag\", SafeRedactor.traceLabelOrFallback(seed.tag(), \"seed\"));"));
    }

    @Test
    void uawPipelineAblationBridgeTraceHintsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawPipelineAblationBridge.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("planHint = \" (planId=\" + pid + \",scenario=\" + scen + \",variant=\" + var + \")\";"));
        assertFalse(source.contains("clip(String.valueOf(last), 120)"));
        assertFalse(source.contains("clip(String.valueOf(reason), 80)"));
        assertTrue(source.contains("import com.example.lms.trace.SafeRedactor;"));
        assertTrue(source.contains(
                "planHint = \" (planId=\" + safeTraceText(pid, 80) + \",scenario=\" + safeTraceText(scen, 80) + \",variant=\" + safeTraceText(var, 80) + \")\";"));
        assertTrue(source.contains("\" last=\" + safeTraceText(last, 120)"));
        assertTrue(source.contains("\" reason=\" + safeTraceText(reason, 80)"));
    }

    @Test
    void nightmareBreakerWebStateSignalUsesOnlyFixedReasonLabels() {
        TraceStore.clear();
        NightmareBreaker breaker = openHybridBreaker();
        TraceStore.clear();
        NightmareBreaker.StateSignal signal = new NightmareBreaker.StateSignal(
                breaker,
                SafeRedactor.hashValue("websearch:hybrid"),
                true,
                NightmareBreaker.SignalType.ADMISSION_BLOCKED,
                NightmareBreaker.BreakerMode.OPEN,
                NightmareBreaker.FailureKind.RATE_LIMIT,
                1L,
                2L);

        new NightmareBreakerWebRateLimitPropagatorAspect().onStateSignal(signal);

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("ADMISSION_BLOCKED"), trace);
        assertTrue(trace.contains("STATE_SIGNAL"), trace);
    }

    @Test
    void nightmareBreakerWebStateSignalIgnoresNonTransitionSignals() {
        TraceStore.clear();
        NightmareBreaker.StateSignal signal = new NightmareBreaker.StateSignal(
                new NightmareBreaker(new NightmareBreakerProperties()),
                SafeRedactor.hashValue("websearch:hybrid"),
                true,
                NightmareBreaker.SignalType.CLOSED,
                NightmareBreaker.BreakerMode.CLOSED,
                null,
                0L,
                0L);

        new NightmareBreakerWebRateLimitPropagatorAspect().onStateSignal(signal);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("orch.webRateLimited"), trace);
        assertFalse(trace.contains("orch.webPartialDown"), trace);
    }

    @Test
    void nightmareBreakerWebStateSignalDoesNotStoreRawKey() {
        TraceStore.clear();
        NightmareBreaker breaker = openHybridBreaker();
        TraceStore.clear();
        String rawKey = "websearch:custom ownerToken=private-web-key";
        NightmareBreaker.StateSignal signal = new NightmareBreaker.StateSignal(
                breaker,
                SafeRedactor.hashValue(rawKey),
                true,
                NightmareBreaker.SignalType.OPENED,
                NightmareBreaker.BreakerMode.OPEN,
                NightmareBreaker.FailureKind.RATE_LIMIT,
                1L,
                2L);

        new NightmareBreakerWebRateLimitPropagatorAspect().onStateSignal(signal);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains("private-web-key"), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void nightmareBreakerPropagatorAcceptsOnlyHashDiagnosticKeys() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/NightmareBreakerWebRateLimitPropagatorAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("value.matches(\"hash:[0-9a-f]{12}\")"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"orch.webRateLimited.key\", key)"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"orch.webPartialDown.key\", key)"));
    }

    @Test
    void nightmareBreakerPropagatorFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/NightmareBreakerWebRateLimitPropagatorAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("String.valueOf(e)"));
        assertTrue(source.contains("propagate(stateSignal) failed (ignored): errorHash={} errorLength={}"));
        assertTrue(source.contains("propagate(isOpen) failed (ignored): errorHash={} errorLength={}"));
        assertFalse(source.contains("propagate(isOpenOrHalfOpen) failed"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(source.contains("messageLength(e)"));
    }

    @Test
    void nightmareBreakerPropagatorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/NightmareBreakerWebRateLimitPropagatorAspect.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "breaker propagation fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSuppressed(\"guardContext\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"providerDownProbe\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"isDownProbe\", ignore);"));
        assertFalse(source.contains("aroundRecordRateLimit"));
        assertFalse(source.contains("aroundIsOpenOrHalfOpen"));
        assertTrue(source.contains("@EventListener"));
        assertTrue(source.contains("reason.name()"));
    }

    private static NightmareBreaker openHybridBreaker() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setRateLimitThreshold(1);
        NightmareBreaker breaker = new NightmareBreaker(props);
        breaker.signalRateLimit(NightmareKeys.WEBSEARCH_HYBRID, "contract", "RATE_LIMIT", 1_000L);
        assertTrue(breaker.isOpen(NightmareKeys.WEBSEARCH_HYBRID));
        return breaker;
    }

    @Test
    void webFailSoftSearchAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "web fail-soft search blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void naverDomainProfileHatchAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/NaverDomainProfileHatchAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Naver domain-profile hatch restore path needs fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverDomainProfileHatch.guardContext\", ignore);"));
    }

    @Test
    void webProviderStructuredEventsDoNotStoreRawQueryPreview() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\"q\", clip(q, 64)"));
        assertTrue(source.contains("\"qHash\", com.example.lms.trace.SafeRedactor.hashValue(q)"));
        assertTrue(source.contains("\"qLen\", q == null ? 0 : q.length()"));
    }

    @Test
    void webProviderStructuredFailureEventsUseStableLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\"err\", t.getClass().getSimpleName()"),
                "provider event telemetry must not expose Java exception class names");
        assertTrue(source.contains("\"err\", \"web_provider_failed\""));
    }

    @Test
    void webProviderStructuredFallbackCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.nextSeq\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.appendEvent\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.breakerState\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.safeOutCount\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.safeStatus\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.safeInt\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.safeHttpStatus\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webProviderStructured.extractHttpStatus\", ignore);"));
    }

    @Test
    void webProviderStructuredStatusParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(s);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "WebProvider structured status parser should be locatable");
        int nextMethod = source.indexOf("\n    private static ", parser + parserCall.length());
        String window = source.substring(parser, nextMethod < 0 ? source.length() : nextMethod);

        assertFalse(window.contains("catch (Throwable"),
                "provider status parser fallback must not swallow Throwable");
        assertFalse(window.contains("catch (Exception"),
                "provider status parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "provider status parser fallback should catch only NumberFormatException");
    }

    @Test
    void novaAopFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/NaverInterruptHygieneAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/NightmareBreakerWebRateLimitPropagatorAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/KnowledgeBasePersistenceAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()")
                            || line.contains(".toString()")
                            || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }
    }

    @Test
    void queryFailSoftAspectsHaveExplicitOrder() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/QueryTransformerAnchorTailAspect.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            assertTrue(code.contains("import org.springframework.core.Ordered;"),
                    source + " should import Ordered for deterministic AOP ordering");
            assertTrue(code.contains("import org.springframework.core.annotation.Order;"),
                    source + " should import Order for deterministic AOP ordering");
            assertTrue(code.contains("@Order(Ordered.HIGHEST_PRECEDENCE +"),
                    source + " should declare an explicit AOP order");
        }
    }

    @Test
    void uawIdlePipelineThrowableSummaryUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"[UAWPipeline] pipeline failed; falling back to ask(stripped)\", t);"));
        assertTrue(source.contains("SafeRedactor.safeMessage(msg, 180)"));
    }

    @Test
    void uawStrictFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertFalse(source.contains("summarize(rootCause(t))"));
        assertTrue(source.contains("rootHash={} rootLength={}"));
        assertTrue(source.contains("errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf("));
    }

    @Test
    void uawStrictRequestAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW strict request aspect needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void uawAndKnowledgeNumericParsersOnlyCatchNumberFormatException() throws Exception {
        assertParserCatchNarrowed(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"),
                "double parsed = Double.parseDouble(raw.trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"),
                "double parsed = Double.parseDouble(raw.trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/ai/abandonware/nova/orch/aop/KnowledgeBasePersistenceAspect.java"),
                "double parsed = Double.parseDouble(String.valueOf(o));");
    }

    @Test
    void knowledgeBasePersistenceSafeDoubleDropsNonFiniteConfidence() throws Exception {
        Method method = KnowledgeBasePersistenceAspect.class.getDeclaredMethod("safeDouble", Object.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, Double.POSITIVE_INFINITY));
        assertNull(method.invoke(null, "1.0e309"));
    }

    @Test
    void knowledgeBasePersistenceLogsDomainAndEntityFingerprints() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/KnowledgeBasePersistenceAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("created domain='{}' entity='{}'"));
        assertFalse(source.contains("updated domain='{}' entity='{}'"));
        assertTrue(source.contains("domainHash={} domainLength={} entityHash={} entityLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(domain)"));
        assertTrue(source.contains("SafeRedactor.hashValue(entityName)"));
    }

    @Test
    void knowledgeBasePersistenceFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/KnowledgeBasePersistenceAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("String.valueOf(e)"));
        assertTrue(source.contains("overlay persist failed; returning original result. errorHash={} errorLength={}"));
        assertTrue(source.contains("JSON parse failed; will try fallback args only. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(source.contains("messageLength(e)"));
    }

    @Test
    void knowledgeBasePersistenceFallbackCatchesUseSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/KnowledgeBasePersistenceAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"knowledgeBasePersistence.uniqueConstraintRetry\", dive);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"knowledgeBasePersistence.safeDouble\", e);"));
    }

    private static void assertParserCatchNarrowed(Path path, String parserCall) throws Exception {
        String source = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable in " + path + ": " + parserCall);
        int nextMethod = source.indexOf("\n    private static ", parser + parserCall.length());
        String window = source.substring(parser, nextMethod < 0 ? source.length() : nextMethod);

        assertFalse(window.contains("catch (Exception"),
                () -> "numeric parser fallback must not hide non-parse failures in " + path);
        assertTrue(window.contains("catch (NumberFormatException"),
                () -> "numeric parser fallback should catch only NumberFormatException in " + path);
    }

    @Test
    void rollingSummaryAndRagCompressionFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String rollingHistory = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/RollingSummaryHistoryAspect.java"),
                StandardCharsets.UTF_8);
        String chunkRolling = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java"),
                StandardCharsets.UTF_8);
        String ragCompression = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(rollingHistory, chunkRolling, ragCompression)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        }

        assertTrue(rollingHistory.contains("RSUM prepend skipped (sessionHash={} errorHash={} errorLength={})"));
        assertTrue(rollingHistory.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        assertTrue(chunkRolling.contains("RSUM update skipped (sessionHash={} errorHash={} errorLength={})"));
        assertTrue(chunkRolling.contains("RSUM init skipped (sessionHash={} errorHash={} errorLength={})"));
        assertTrue(chunkRolling.contains("distill failed (sessionHash={} errorHash={} errorLength={})"));
        assertTrue(chunkRolling.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        assertTrue(ragCompression.contains("activation failed; returning original docs. errorHash={} errorLength={}"));
        assertTrue(ragCompression.contains("narrow failed; returning original docs. errorHash={} errorLength={}"));
        assertTrue(ragCompression.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void zero100SessionRegistryTouchFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertFalse(source.contains("registry.touch failed: {}"));
        assertTrue(source.contains("registry.touch failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }
}
