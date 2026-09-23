package ai.abandonware.nova.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class NovaRuntimeRedactionContractTest {

    @Test
    void novaRuntimeFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java"),
                Path.of("main/java/ai/abandonware/nova/boot/NovaReactorContextPropagationHook.java"),
                Path.of("main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"),
                Path.of("main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java"),
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsInstaller.java"),
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsRestTemplateInterceptor.java"),
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveRestTemplateTimeoutOverrideInstaller.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/BraveOperationalGateAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/FaultMaskAblationPenaltyAspect.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }
    }

    @Test
    void novaNoiseFilterIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/log/NovaNoiseTurboFilter.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(s);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "Nova noise filter integer parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertTrue(!Pattern.compile("catch \\(Throwable").matcher(window).find(),
                "Nova noise filter parser fallback must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "Nova noise filter parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("recordNoiseFilterFallback(\"safe_int\", parseError)"),
                "Nova noise filter parser fallback should leave a redacted breadcrumb");
    }

    @Test
    void novaNoiseDimensionMismatchSuppressionDoesNotUseSilentCatch() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/log/NovaNoiseTurboFilter.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertTrue(!source.contains("catch (Throwable ignore)"),
                "Nova noise filter suppression should not hide unexpected parameter parsing failures");
        assertTrue(source.contains("recordNoiseFilterFallback(\"dimension_mismatch_params\", parseError)"),
                "Nova noise filter suppression should leave a redacted breadcrumb for unexpected parameter parsing failures");
    }

    @Test
    void llmCallTraceAspectDoesNotStoreOrLogRawModelBaseUrlOrArgs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmCallTraceAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("llm.trace.last.modelHash"));
        assertTrue(source.contains("llm.trace.last.baseUrlHost"));
        assertTrue(source.contains("llm.trace.last.argsSummary"));
        assertTrue(source.contains("llm.trace.last.argsHash"));
        assertTrue(source.contains("modelHash"));
        assertTrue(source.contains("baseUrlHost"));
        assertTrue(source.contains("argsSummary"));

        assertTrue(!source.contains("TraceStore.put(\"llm.trace.last.model\", model)"));
        assertTrue(!source.contains("TraceStore.put(\"llm.trace.last.baseUrl\", baseUrl)"));
        assertTrue(!source.contains("TraceStore.put(\"llm.trace.last.args\", argsInfo)"));
        assertTrue(!source.contains("baseUrl={} model={} class={} args={}"));
        assertTrue(!source.contains("baseUrl={} model={} ctx("));
        assertTrue(!source.contains("baseUrl={} model={}\", tag"));
        assertTrue(!source.contains("msg={} args={}"));
        assertTrue(!source.contains("msg={} argsSummary={}"));
        assertTrue(!source.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertTrue(!source.contains("TraceStore.put(\"llm.trace.last.err\", t.getClass().getSimpleName())"));
        assertTrue(source.contains("TraceStore.put(\"llm.trace.last.err\", \"llm_trace_call_failed\")"));
        assertTrue(source.contains("errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(t.getMessage())"));
    }

    @Test
    void openAiResponsesAdapterDoesNotLogOrRenderRawModelName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("status={} model={}"));
        assertTrue(!source.contains("failed: model={} ex={}"));
        assertTrue(!source.contains("\"OpenAiResponsesChatModel(\" + modelName"));
        assertTrue(!source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("modelHash"));
        assertTrue(source.contains("SafeRedactor.hashValue(modelName)"));
        assertTrue(source.contains("OpenAI /responses failed: modelHash={} failureReason={} errorType={}"));
        assertTrue(source.contains("\"responses-route-error\""));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void rateLimitBackoffLogsAndTraceDoNotWriteRawReasonDetails() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinator.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("retryAfterValue='{}'"));
        assertTrue(!source.contains("reason='{}'"));
        assertTrue(!source.contains("detail='{}'"));
        assertTrue(!source.contains(".last.reason\", safe(reason)"));
        assertTrue(source.contains("retryAfterValueHash"));
        assertTrue(source.contains("reasonHash"));
        assertTrue(source.contains("detailHash"));

        String aspect = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java"),
                StandardCharsets.UTF_8);
        assertTrue(!aspect.contains("TraceStore.put(\"web.failsoft.rateLimitBackoff.\" + provider + \".reason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.putIfAbsent(\"orch.webPartialDown.reason.\" + provider, reason);"));
        assertTrue(!aspect.contains("TraceStore.putIfAbsent(\"orch.webPartialDown.firstReason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.put(\"web.await.brave.disabledReason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", \"disabled\");"));
        assertTrue(aspect.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", safeReason);"));
        assertTrue(!aspect.contains("return \"quota_exhausted remainingMs=\" + rem;"));
        assertTrue(!aspect.contains("return \"cooldown remainingMs=\" + rem;"));
        assertTrue(aspect.contains("TraceStore.put(\"web.brave.disabled.remainingMs\", rem);"));
        assertTrue(aspect.contains("TraceStore.put(\"web.brave.cooldown.remainingMs\", rem);"));
        assertTrue(aspect.contains("return \"quota_exhausted\";"));
        assertTrue(aspect.contains("return \"cooldown\";"));
        assertTrue(aspect.contains("SafeRedactor.traceLabel(o)"));

        String brave = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsRestTemplateInterceptor.java"),
                StandardCharsets.UTF_8);
        assertTrue(!brave.contains("TraceStore.putIfAbsent(\"orch.webPartialDown.reason\", reason);"));
        assertTrue(!brave.contains("TraceStore.put(\"brave.ratelimit.cooldownReason\", reason);"));
        assertTrue(!brave.contains("TraceStore.put(\"brave.ratelimit.quotaLatch.cleared.reason\", why);"));
        assertTrue(!brave.contains("TraceStore.put(\"web.await.brave.disabledReason\", reason);"));
        assertTrue(!brave.contains("\"quota_exhausted remainingMs=\" + remainingMs"));
        assertTrue(!brave.contains("kind + \" remainingMs=\" + remainingMs"));
        assertTrue(brave.contains("TraceStore.put(\"web.await.brave.disabledReason\", disabledReason);"));
        assertTrue(!brave.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", \"disabled\");"));
        assertTrue(brave.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", disabledReason);"));
        assertTrue(brave.contains("TraceStore.put(\"web.brave.disabled.remainingMs\", remainingMs);"));
        assertTrue(brave.contains("SafeRedactor.traceLabel(value)"));
    }

    @Test
    void webFailSoftReasonsUseSafeMessagesOrLabels() throws Exception {
        String aspect = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(aspect.contains("TraceStore.put(\"web.failsoft.guardContext.failed\", true);"));
        assertTrue(aspect.contains("TraceStore.put(\"web.failsoft.guardContext.stage\", \"GuardContextHolder.get\");"));
        assertTrue(aspect.contains("TraceStore.put(\"web.failsoft.guardContext.errorType\","));
        assertTrue(!aspect.contains("TraceStore.put(\"web.failsoft.starvationFallback.skipReason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.put(\"web.failsoft.rateLimitBackoff.cooldownTradeoff.reason\", reason);"));
        assertTrue(!aspect.contains("TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", dr);"));
        assertTrue(!aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.naver.reason\", stringOrNull(nd.reason()));"));
        assertTrue(!aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.brave.reason\", stringOrNull(bd.reason()));"));
        assertTrue(!aspect.contains("j.put(\"web.failsoft.rateLimitBackoff.naver.reason\", safeTrim(reason, 64));"));
        assertTrue(!aspect.contains("j.put(\"web.failsoft.rateLimitBackoff.brave.reason\", safeTrim(reason, 64));"));
        assertTrue(!aspect.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.skipReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(!aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.cooldownTradeoff.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(!aspect.contains(
                "TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", SafeRedactor.safeMessage(dr, 120));"));
        assertTrue(aspect.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.skipReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.cooldownTradeoff.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(aspect.contains(
                "TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", SafeRedactor.traceLabelOrFallback(dr, \"unknown\"));"));
        assertTrue(aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.naver.reason\", SafeRedactor.traceLabel(stringOrNull(nd.reason())));"));
        assertTrue(aspect.contains(
                "TraceStore.put(\"web.failsoft.rateLimitBackoff.brave.reason\", SafeRedactor.traceLabel(stringOrNull(bd.reason())));"));
        assertTrue(aspect.contains("j.put(\"web.failsoft.rateLimitBackoff.naver.reason\", SafeRedactor.traceLabel(reason));"));
        assertTrue(aspect.contains("j.put(\"web.failsoft.rateLimitBackoff.brave.reason\", SafeRedactor.traceLabel(reason));"));
    }

    @Test
    void braveOperationalGateKeepsDisabledReasonAndTimingSeparate() throws Exception {
        String gate = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/BraveOperationalGateAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(!gate.contains("\"cooldown remainingMs=\" + rem"));
        assertTrue(!gate.contains("\"quota_exhausted remainingMs=\" + remainingMs"));
        assertTrue(gate.contains("TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", \"cooldown\");"));
        assertTrue(gate.contains("TraceStore.put(\"web.brave.cooldown.remainingMs\", rem);"));
        assertTrue(gate.contains("TraceStore.putIfAbsent(\"web.await.brave.disabledReason\", \"quota_exhausted\");"));
        assertTrue(gate.contains("TraceStore.put(\"web.brave.disabled.remainingMs\", remainingMs);"));
        assertTrue(!gate.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", \"disabled\");"));
        assertTrue(gate.contains("TraceStore.putIfAbsent(\"web.brave.skipped.reason\", \"quota_exhausted\");"));
        assertTrue(!gate.contains("brave.setOperationallyDisabled(\"quota_exhausted untilEpochMs=\" + untilEpochMs);"));
        assertTrue(gate.contains("brave.setOperationallyDisabled(\"quota_exhausted\");"));
    }

    @Test
    void braveOperationalGateFailureLogUsesHashAndLengthOnly() throws Exception {
        String gate = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/BraveOperationalGateAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(!gate.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(!gate.contains("String.valueOf(t)"));
        assertTrue(gate.contains("apply failed (ignored): errorHash={} errorLength={}"));
        assertTrue(gate.contains("SafeRedactor.hashValue(messageOf(t))"));
        assertTrue(gate.contains("messageLength(t)"));
    }

    @Test
    void braveRestTemplateSupportFailureLogsUseHashAndLengthOnly() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsInstaller.java"),
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsRestTemplateInterceptor.java"),
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveRestTemplateTimeoutOverrideInstaller.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);

            assertTrue(!code.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"), source.toString());
            assertTrue(!code.contains("String.valueOf(t)"), source.toString());
            assertTrue(code.contains("errorHash={} errorLength={}"), source.toString());
            assertTrue(code.contains("SafeRedactor.hashValue(messageOf(t))"), source.toString());
            assertTrue(code.contains("messageLength(t)"), source.toString());
        }
    }

    @Test
    void reactorHookResetFailureLogsUseHashAndLengthOnly() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java"),
                Path.of("main/java/ai/abandonware/nova/boot/NovaReactorContextPropagationHook.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);

            assertTrue(!code.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"), source.toString());
            assertTrue(!code.contains("String.valueOf(t)"), source.toString());
            assertTrue(code.contains("errorHash={} errorLength={}"), source.toString());
            assertTrue(code.contains("SafeRedactor.hashValue(messageOf(t))")
                    || code.contains("com.example.lms.trace.SafeRedactor.hashValue(messageOf(t))"), source.toString());
            assertTrue(code.contains("messageLength(t)"), source.toString());
        }
    }

    @Test
    void reactorContextPropagationHookDoesNotUseSilentIgnoreCatches() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/NovaReactorContextPropagationHook.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("catch (Throwable ignore)"),
                "Reactor context hook should name suppressed failures and keep redacted breadcrumbs");
        assertTrue(source.contains("reactor.contextPropagation.suppressed.errorType"));
    }

    @Test
    void llmRouterBanditFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String code = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java"),
                StandardCharsets.UTF_8);

        assertTrue(!code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(!code.contains("String.valueOf(ex)"));
        assertTrue(code.contains("pickAuto fail-soft: errorHash={} errorLength={}"));
        assertTrue(code.contains("com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex))"));
        assertTrue(code.contains("messageLength(ex)"));
    }

    @Test
    void degradedStoragePeekMetadataDoesNotExposeRawLocalPaths() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("meta.put(\"path\", p.toString());"));
        assertTrue(source.contains("meta.put(\"pathHash\", SafeRedactor.hashValue(p.toString()));"));
        assertTrue(source.contains("meta.put(\"fileName\", name);"));
    }

    @Test
    void degradedStorageDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "degraded storage fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"constructor.createDirectoryBase\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"constructor.createJsonlParent\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"peekDirectory.scan\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"writeEnvelopeFile.directory\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"writeEnvelopeFile.write\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"readEnvelopeFile.parse\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"claimDirectory.scan\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"readJsonl\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"writeJsonl\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"appendJsonl\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"parseEnvelope.legacyEvent\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"safeMtime\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"safeSize\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"safeDelete\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"safeMove.atomic\", e);"));
        assertTrue(source.contains("DegradedStorageTraceSuppressions.trace(\"safeMove.fallback\", ignored);"));
    }

    @Test
    void mlaOtelBridgeFailSoftPathsHaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("catch (Throwable ignore)"),
                "OTel bridge emit failures need fixed-stage breadcrumbs instead of silent swallow");
        assertTrue(!source.contains("catch (NumberFormatException ignore)"),
                "OTel bridge parse failures need fixed-stage breadcrumbs instead of silent swallow");
        assertTrue(source.contains("traceSuppressed(\"emit\", e);"));
        assertTrue(source.contains("traceSuppressed(\"longValue\", e);"));
        assertTrue(source.contains("traceSuppressed(\"doubleValue\", e);"));
        assertTrue(source.contains("TraceStore.put(\"mla.otel.suppressed.stage\", stage);"));
        assertTrue(!source.contains("error.getMessage()"));
    }
}
