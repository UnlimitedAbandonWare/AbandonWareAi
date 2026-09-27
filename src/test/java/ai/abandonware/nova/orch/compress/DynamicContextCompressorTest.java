package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DynamicContextCompressorTest {

    private DynamicContextCompressor compressor;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.RagCompressorProps cfg = props.getRagCompressor();
        cfg.setMaxContents(4);
        cfg.setMaxCharsPerContent(180);
        cfg.setAnchorWindowChars(80);
        compressor = new DynamicContextCompressor(props);
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void prioritizesAnchorCapsHostDedupesAndKeepsMetadata() {
        List<Content> docs = List.of(
                content("https://same.example/a", "alpha anchor evidence body " + "x".repeat(260)),
                content("https://same.example/b", "second anchor evidence body " + "y".repeat(260)),
                content("https://same.example/c", "third anchor evidence body " + "z".repeat(260)),
                content("https://other.example/d", "no keyword but useful context"),
                content("https://dup.example/e", "alpha anchor evidence body " + "x".repeat(260))
        );

        List<Content> out = compressor.compress("anchor", docs);

        assertEquals(3, out.size(),
                () -> "reason=" + TraceStore.get("compress.reason")
                        + ", failSoft=" + TraceStore.get("compress.failSoft")
                        + ", exception=" + TraceStore.get("compress.exception"));
        assertTrue(out.get(0).textSegment().text().contains("anchor"));
        assertEquals("true", out.get(0).textSegment().metadata().getString("_nova.compressed"));
        assertEquals("https://same.example/a", out.get(0).textSegment().metadata().getString("url"));
        assertFalse(out.get(0).textSegment().metadata().toMap().containsKey("_nova.anchor"));
        assertNotNull(out.get(0).textSegment().metadata().getString("_nova.anchorHash"));
        assertNotNull(out.get(0).textSegment().metadata().toMap().get("_nova.anchorLen"));
        assertFalse(out.get(0).textSegment().metadata().toMap().containsKey("_nova.origText"));
        assertNotNull(out.get(0).textSegment().metadata().getString("_nova.origHash"));
        assertEquals(docs.get(0).textSegment().text().length(),
                out.get(0).textSegment().metadata().toMap().get("_nova.origLen"));
        assertEquals(5, TraceStore.get("compress.input.count"));
        assertEquals(3, TraceStore.get("compress.output.count"));
        assertEquals(false, TraceStore.get("compress.failSoft"));
        assertEquals(1, TraceStore.get("overdrive.stagesApplied"));
        assertEquals(3, TraceStore.get("overdrive.finalCandidateCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.exactPhraseProbeUsed"));
    }

    @Test
    void returnsOriginalWhenAllCandidatesAreInvalid() {
        List<Content> docs = new java.util.ArrayList<>();
        docs.add(null);

        List<Content> out = compressor.compress("anchor", docs);

        assertSame(docs, out);
        assertEquals(true, TraceStore.get("compress.failSoft"));
        assertEquals("empty_output_original_returned", TraceStore.get("compress.reason"));
    }

    @Test
    void traceCompressionStoresReasonAsSafeLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"compress.reason\", reason);"));
        assertTrue(source.contains("TraceStore.put(\"compress.reason\", safeLabel(reason));"));
    }

    @Test
    void promptComposerTraceStoresReasonsAsSafeLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"prompt.context.composer.reason\", decision.reason());"));
        assertFalse(source.contains("failure.put(\"reasonCode\", decision.reason());"));
        assertFalse(source.contains("control.put(\"reasonCode\", decision.reason());"));
        assertTrue(source.contains("out.put(\"reason\", safeLabel(reason));"));
        assertTrue(source.contains("TraceStore.put(\"prompt.context.composer.reason\", safeLabel(decision.reason()));"));
        assertTrue(source.contains("failure.put(\"reasonCode\", safeLabel(decision.reason()));"));
        assertTrue(source.contains("control.put(\"reasonCode\", safeLabel(decision.reason()));"));
    }

    @Test
    void compressorFailSoftCatchBlocksEmitHashAndLengthDebugLogs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(source.contains("log.debug(\"[DynamicContextCompressor] composeForPrompt fail-soft errorHash={} errorLength={}\""));
        assertTrue(source.contains("log.debug(\"[DynamicContextCompressor] compressMemoryForPrompt fail-soft errorHash={} errorLength={}\""));
        assertTrue(source.contains("log.debug(\"[DynamicContextCompressor] compress fail-soft errorHash={} errorLength={}\""));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void compressionNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String compressorSource = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String anchorProbeSource = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/AnchorProbeHandler.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertParserCatchNarrowed(compressorSource, "Double.parseDouble(String.valueOf(raw).trim())");
        assertParserCatchNarrowed(compressorSource, "return Double.parseDouble(s);");
        assertParserCatchNarrowed(anchorProbeSource, "Double.parseDouble(String.valueOf(raw).trim())");
    }

    @Test
    void numericFallbackTraceUsesStableInvalidNumberLabel() throws Exception {
        Method traceSkipped = DynamicContextCompressor.class.getDeclaredMethod(
                "traceSkipped", String.class, Throwable.class);
        traceSkipped.setAccessible(true);

        traceSkipped.invoke(null, "dynamicCompressor.toDouble",
                new NumberFormatException("private-score-token"));

        assertEquals(Boolean.TRUE, TraceStore.get("compress.suppressed.dynamicCompressor.toDouble"));
        assertEquals("invalid_number",
                TraceStore.get("compress.suppressed.dynamicCompressor.toDouble.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("private-score-token"), trace);
    }

    @Test
    void compressorSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        Method traceSkipped = DynamicContextCompressor.class.getDeclaredMethod(
                "traceSkipped", String.class, Throwable.class);
        traceSkipped.setAccessible(true);
        String secret = com.example.lms.test.SecretFixtures.openAiKey();

        traceSkipped.invoke(null, "dynamicCompressor.toDouble " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("compress.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("compress.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("compress.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("compress.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
    }

    @Test
    void anchorProbeNumericFallbackTraceUsesStableInvalidNumberLabel() throws Exception {
        Method traceSkipped = AnchorProbeHandler.class.getDeclaredMethod(
                "traceSkipped", String.class, Throwable.class);
        traceSkipped.setAccessible(true);

        traceSkipped.invoke(null, "anchorProbe.matrixTileKey",
                new NumberFormatException("private-tile-token"));

        assertEquals(Boolean.TRUE,
                TraceStore.get("prompt.context.composer.anchorProbe.suppressed.anchorProbe.matrixTileKey"));
        assertEquals("invalid_number",
                TraceStore.get("prompt.context.composer.anchorProbe.suppressed.anchorProbe.matrixTileKey.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("private-tile-token"), trace);
    }

    @Test
    void anchorProbeSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        Method traceSkipped = AnchorProbeHandler.class.getDeclaredMethod(
                "traceSkipped", String.class, Throwable.class);
        traceSkipped.setAccessible(true);
        String secret = com.example.lms.test.SecretFixtures.openAiKey();

        traceSkipped.invoke(null, "anchorProbe.matrixTileKey " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("prompt.context.composer.anchorProbe.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE,
                TraceStore.get("prompt.context.composer.anchorProbe.suppressed." + safeStage));
        assertEquals("IllegalStateException",
                TraceStore.get("prompt.context.composer.anchorProbe.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("prompt.context.composer.anchorProbe.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
    }

    @Test
    void safeLabelHashesFreeTextReasonsAndPreservesReasonCodes() throws Exception {
        Method safeLabel = DynamicContextCompressor.class.getDeclaredMethod("safeLabel", Object.class);
        safeLabel.setAccessible(true);

        String freeText = String.valueOf(safeLabel.invoke(null, "student private composer reason"));
        String reasonCode = String.valueOf(safeLabel.invoke(null, "all_prompt_ineligible"));

        assertTrue(freeText.startsWith("hash:"), freeText);
        assertFalse(freeText.contains("student"), freeText);
        assertEquals("all_prompt_ineligible", reasonCode);
    }

    @Test
    void starvationFlagAcceptsCanonicalStarvationFallbackUsedAlias() throws Exception {
        Method starvationFlag = DynamicContextCompressor.class.getDeclaredMethod("starvationFlag", Map.class);
        starvationFlag.setAccessible(true);

        assertEquals(Boolean.TRUE, starvationFlag.invoke(null, Map.of("starvationFallback.used", true)));
    }

    @Test
    void anchorFromKeepsRagDomainTokenAndCapsOversizedCompoundAnchor() throws Exception {
        Method anchorFrom = DynamicContextCompressor.class.getDeclaredMethod("anchorFrom", String.class);
        anchorFrom.setAccessible(true);

        assertEquals("RAG", anchorFrom.invoke(null, "RAG retrieval quality"));
        String longCompound = "데이터베이스관리시스템성능비교최적화벤치마크지표분석세부항목정렬기준";
        String anchor = String.valueOf(anchorFrom.invoke(null, longCompound));

        assertTrue(anchor.length() <= 32, anchor);
    }

    private static void assertParserCatchNarrowed(String source, String parserCall) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "compression numeric parser fallbacks must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "compression numeric parser fallbacks should catch only NumberFormatException");
    }

    @Test
    void failSoftExceptionBreadcrumbsUseStableOperationalLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"prompt.context.composer.exception\", e.getClass().getSimpleName())"),
                "prompt composer fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("TraceStore.put(\"prompt.memory.compressor.exception\", ex.getClass().getSimpleName())"),
                "memory compressor fail-soft trace must not expose Java exception class names");
        assertFalse(source.contains("TraceStore.put(\"compress.exception\", e.getClass().getSimpleName())"),
                "RAG compressor fail-soft trace must not expose Java exception class names");
        assertTrue(source.contains("TraceStore.put(\"prompt.context.composer.exception\", \"prompt_context_composer_failed\")"),
                "prompt composer should leave a stable operational fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"prompt.memory.compressor.exception\", \"memory_compressor_failed\")"),
                "memory compressor should leave a stable operational fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"compress.exception\", \"compress_failed\")"),
                "RAG compressor should leave a stable operational fail-soft label");
        assertTrue(source.contains("TraceStore.put(\"overdrive.compress.error\", e.getClass().getSimpleName())"),
                "RAG compressor should leave an overdrive-specific error type breadcrumb");
    }

    @Test
    void anchorProbeTraceStoresReasonsAsTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/AnchorProbeHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"prompt.context.composer.anchorProbe.reason\", selection.reason());"));
        assertFalse(source.contains("TraceStore.put(\"prompt.context.composer.spreadProbe.reason\", selection.reason());"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(selection.reason(), \"\"));"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(selection.reason(), \"\"));"));
    }

    @Test
    void anchorProbeFallbackCatchesUseRedactedSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/AnchorProbeHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"anchorProbe.trace\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"anchorProbe.matrixTileKey\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"anchorProbe.anchorForTrace\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"anchorProbe.hostOf\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"anchorProbe.metadataCopy\", ignore);"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertFalse(source.contains("failure.getMessage()"));
        assertFalse(source.contains("failure.toString()"));
    }

    @Test
    void dynamicContextFallbackCatchesUseRedactedSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.matrixTileKey\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.promptContextEvent\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.promptIneligibleProbe\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.dynamicGateTrace\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.queryAuxMapTrace\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.toDouble\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.rebuildMetadata\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.metadataCopy\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.hostOf\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.compressEvent\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.memoryCompressionTrace\", ignored);"));
        assertTrue(source.contains("traceSkipped(\"dynamicCompressor.safeHash\", ignore);"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertFalse(source.contains("failure.getMessage()"));
        assertFalse(source.contains("failure.toString()"));
    }

    @Test
    void anchorProbeTraceDoesNotStoreRawFreeFormReasonText() {
        String rawReason = "private probe reason with user text and student detail";
        AnchorProbeHandler.Selection selection = new AnchorProbeHandler.Selection(
                List.of(),
                List.of(64, 32, 5),
                List.of(12, 6, 3),
                5,
                true,
                true,
                true,
                rawReason,
                "SPREAD_PROBE",
                0.5d,
                0.4d,
                0.3d,
                0.2d);

        new AnchorProbeHandler().trace(selection, "private query text", 12, 3);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("private probe reason"), trace);
        assertFalse(trace.contains("student detail"), trace);
        assertTrue(String.valueOf(TraceStore.get("prompt.context.composer.anchorProbe.reason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("prompt.context.composer.spreadProbe.reason")).startsWith("hash:"));
    }

    @Test
    void ablationGuidedComposerKeepsAnchorAndReducesPenalizedWebNoise() {
        TraceStore.put("ablation.events.count", 4);
        TraceStore.put("ablation.probabilities", List.of(
                Map.of("step", "web.await", "guard", "missing_future", "delta", 0.90d),
                Map.of("step", "final", "guard", "citation", "delta", 0.02d)));
        List<Content> web = List.of(
                content("https://web.example/noise-1", "unrelated broad web context " + "n".repeat(260)),
                content("https://web.example/anchor", "keepanchor focused web evidence " + "w".repeat(260)),
                content("https://web.example/noise-2", "another unrelated web result " + "x".repeat(260)),
                content("https://web.example/noise-3", "more unrelated web result " + "y".repeat(260)));
        List<Content> rag = List.of(
                content("vector://doc/a", "keepanchor vector evidence " + "r".repeat(260)),
                content("vector://doc/b", "supporting vector evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, rag);

        assertTrue(out.decision().activated());
        assertEquals("missing_future", out.decision().topFactor());
        assertTrue(out.web().stream().anyMatch(c -> c.textSegment().text().contains("keepanchor")));
        assertTrue(out.web().size() < web.size());
        assertEquals("ablation-spread-v2", TraceStore.get("prompt.context.composer.version"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void spreadProbeKeepsThreeToFiveDiverseEvidenceBundlesByDefault() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://official.example/alpha", "keepanchor alpha compliance evidence " + "a".repeat(220),
                        Map.of("intentAxis", "alpha", "authorityScore", 0.94d, "rerankConfidence", 0.88d)),
                content("https://official.example/beta", "keepanchor beta operating evidence " + "b".repeat(220),
                        Map.of("intentAxis", "beta", "authorityScore", 0.90d, "rerankConfidence", 0.82d)),
                content("https://docs.example/gamma", "keepanchor gamma implementation evidence " + "c".repeat(220),
                        Map.of("intentAxis", "gamma", "authorityScore", 0.84d, "rerankConfidence", 0.80d)),
                content("https://noise.example/alpha-copy", "keepanchor alpha compliance evidence " + "a".repeat(220),
                        Map.of("intentAxis", "alpha", "authorityScore", 0.50d, "rerankConfidence", 0.40d)));
        List<Content> rag = List.of(
                content("vector://doc/delta", "keepanchor delta vector evidence " + "d".repeat(220),
                        Map.of("intentAxis", "delta", "authorityScore", 0.76d, "rerankConfidence", 0.78d)),
                content("vector://doc/epsilon", "keepanchor epsilon memory evidence " + "e".repeat(220),
                        Map.of("intentAxis", "epsilon", "authorityScore", 0.72d, "rerankConfidence", 0.74d)));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, rag);

        int total = out.web().size() + out.rag().size();
        assertTrue(out.decision().activated());
        assertTrue(total >= 3 && total <= 5);
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .allMatch(c -> "SPREAD_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .allMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.anchorKeyHash")));
        assertEquals(List.of(64, 48, 32, 16, 5), TraceStore.get("prompt.context.composer.spreadProbe.kSchedule"));
        assertEquals(6, TraceStore.get("prompt.context.composer.spreadProbe.inputCount"));
        assertEquals(total, TraceStore.get("prompt.context.composer.spreadProbe.outputCount"));
        assertTrue(((Number) TraceStore.get("prompt.context.composer.spreadProbe.reductionRatio")).doubleValue() > 0.0d);
        assertEquals(false, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertTrue(((Number) TraceStore.get("prompt.context.composer.spreadProbe.anchorDiversity")).doubleValue() >= 0.30d);
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void dynamicGateSuppressesNoisyNonAnchorOverflowTileBeforePromptSelection() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://docs.example/alpha", "keepanchor alpha verified evidence " + "a".repeat(220),
                        withExtra(branchMeta("alpha", 0.92d, 0.05d, 0.05d, 1.10d, "PASS"),
                                Map.of("branch_quality_matrix_tile", 2, "authorityScore", 0.92d, "rerankConfidence", 0.86d))),
                content("https://docs.example/beta", "keepanchor beta verified evidence " + "b".repeat(220),
                        withExtra(branchMeta("beta", 0.90d, 0.05d, 0.05d, 1.05d, "PASS"),
                                Map.of("branch_quality_matrix_tile", 2, "authorityScore", 0.90d, "rerankConfidence", 0.84d))),
                content("https://docs.example/gamma", "keepanchor gamma verified evidence " + "c".repeat(220),
                        withExtra(branchMeta("gamma", 0.88d, 0.05d, 0.05d, 1.00d, "PASS"),
                                Map.of("branch_quality_matrix_tile", 2, "authorityScore", 0.88d, "rerankConfidence", 0.82d))),
                content("https://noise.example/one", "noise-only branch filler " + "n".repeat(220),
                        withExtra(branchMeta("noise1", 0.10d, 0.95d, 0.92d, 0.10d, "PASS"),
                                Map.of("branch_quality_matrix_tile", 2, "authorityScore", 0.10d, "rerankConfidence", 0.10d))),
                content("https://noise.example/two", "second noise-only branch filler " + "m".repeat(220),
                        withExtra(branchMeta("noise2", 0.10d, 0.94d, 0.91d, 0.10d, "PASS"),
                                Map.of("branch_quality_matrix_tile", 2, "authorityScore", 0.10d, "rerankConfidence", 0.10d)))
        );

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, List.of());

        String promptText = out.web().toString();
        assertTrue(out.decision().activated());
        assertTrue((Boolean) TraceStore.get("prompt.context.composer.dynamicGate.applied"));
        assertEquals(2, ((Number) TraceStore.get("prompt.context.composer.dynamicGate.suppressedCount")).intValue());
        assertTrue(String.valueOf(TraceStore.get("prompt.context.composer.dynamicGate.matrixTileCounts")).contains("t2=5"));
        assertFalse(promptText.contains("noise-only branch filler"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void spreadProbeDoesNotTreatProviderOnlyLabelsAsCitationEvidence() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                metadataOnly("alpha broad provider-only note " + "a".repeat(220),
                        Map.of("source", "web:selfask:alpha", "provider", "brave", "title", "Alpha Lane")),
                metadataOnly("beta broad provider-only note " + "b".repeat(220),
                        Map.of("source", "web:selfask:beta", "provider", "naver", "title", "Beta Lane")),
                metadataOnly("gamma broad provider-only note " + "c".repeat(220),
                        Map.of("source", "web:selfask:gamma", "provider", "serpapi", "title", "Gamma Lane")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertEquals(true, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertEquals("spread_evidence_fallback", TraceStore.get("prompt.context.composer.spreadProbe.reason"));
        assertTrue(out.web().stream()
                .noneMatch(c -> "SPREAD_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void spreadProbeKeepsDistinctSelfAskSourceLabelCandidatesAndHashesAnchor() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                metadataOnly("keepanchor alpha selfask evidence " + "a".repeat(220),
                        Map.of("source", "web:selfask", "provider", "brave", "intentAxis", "alpha")),
                metadataOnly("keepanchor beta selfask evidence " + "b".repeat(220),
                        Map.of("source", "web:selfask", "provider", "brave", "intentAxis", "beta")),
                metadataOnly("keepanchor gamma selfask evidence " + "c".repeat(220),
                        Map.of("source", "web:selfask", "provider", "brave", "intentAxis", "gamma")),
                metadataOnly("keepanchor delta selfask evidence " + "d".repeat(220),
                        Map.of("source", "web:selfask", "provider", "brave", "intentAxis", "delta")),
                metadataOnly("keepanchor epsilon selfask evidence " + "e".repeat(220),
                        Map.of("source", "web:selfask", "provider", "brave", "intentAxis", "epsilon")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertTrue(out.web().size() >= 3 && out.web().size() <= 5);
        assertEquals(false, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertTrue(out.web().stream()
                .allMatch(c -> "SPREAD_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertTrue(out.web().stream()
                .noneMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.anchor")));
        assertTrue(out.web().stream()
                .allMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.anchorHash")));
        assertTrue(out.web().stream()
                .allMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.anchorLen")));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void spreadProbeReadsBranchQualityMetadataBeforePromptHandoff() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://noise.example/alpha", "keepanchor alpha weak evidence " + "a".repeat(220),
                        Map.of("branch_quality_intent_axis", "alpha",
                                "branch_quality_authority_score", 0.10d,
                                "branch_quality_rerank_confidence", 0.10d,
                                "branch_quality_context_contribution", 0.10d,
                                "branch_quality_duplicate_ratio", 0.90d,
                                "branch_quality_risk_penalty", 0.70d,
                                "branch_quality_rrf_weight", 0.20d)),
                content("https://noise.example/beta", "keepanchor beta weak evidence " + "b".repeat(220),
                        Map.of("branch_quality_intent_axis", "beta",
                                "branch_quality_authority_score", 0.12d,
                                "branch_quality_rerank_confidence", 0.12d,
                                "branch_quality_context_contribution", 0.12d,
                                "branch_quality_duplicate_ratio", 0.88d,
                                "branch_quality_risk_penalty", 0.70d,
                                "branch_quality_rrf_weight", 0.20d)),
                content("https://official.example/gamma", "keepanchor gamma branch quality evidence " + "c".repeat(220),
                        Map.of("branch_quality_intent_axis", "gamma",
                                "branch_quality_authority_score", 0.96d,
                                "branch_quality_rerank_confidence", 0.94d,
                                "branch_quality_context_contribution", 0.92d,
                                "branch_quality_duplicate_ratio", 0.05d,
                                "branch_quality_risk_penalty", 0.05d,
                                "branch_quality_rrf_weight", 1.20d)),
                content("https://docs.example/delta", "keepanchor delta branch quality evidence " + "d".repeat(220),
                        Map.of("branch_quality_intent_axis", "delta",
                                "branch_quality_authority_score", 0.91d,
                                "branch_quality_rerank_confidence", 0.90d,
                                "branch_quality_context_contribution", 0.88d,
                                "branch_quality_duplicate_ratio", 0.08d,
                                "branch_quality_risk_penalty", 0.08d,
                                "branch_quality_rrf_weight", 1.10d)));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertTrue(out.web().stream()
                .anyMatch(c -> c.textSegment().metadata().getString("url").contains("official.example/gamma")));
        assertTrue(out.web().stream()
                .filter(c -> c.textSegment().metadata().getString("url").contains("official.example/gamma"))
                .allMatch(c -> numberMeta(c, "_nova.authorityScore") < 0.90d));
        assertTrue(out.web().stream()
                .filter(c -> c.textSegment().metadata().getString("url").contains("official.example/gamma"))
                .allMatch(c -> numberMeta(c, "_nova.rerankConfidence") < 0.90d));
        assertEquals(false, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void branchQualityLanePriorInfluencesSelectionWithoutReplacingDocumentAuthority() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://neutral-alpha.example/a", "keepanchor alpha broad evidence " + "a".repeat(220),
                        branchMeta("alpha", 0.15d, 0.80d, 0.50d, 0.20d, "PASS")),
                content("https://neutral-beta.example/b", "keepanchor beta broad evidence " + "b".repeat(220),
                        branchMeta("beta", 0.16d, 0.80d, 0.50d, 0.20d, "PASS")),
                content("https://neutral-gamma.example/c", "keepanchor gamma broad evidence " + "c".repeat(220),
                        branchMeta("gamma", 0.17d, 0.80d, 0.50d, 0.20d, "PASS")),
                content("https://neutral-delta.example/d", "keepanchor delta broad evidence " + "d".repeat(220),
                        branchMeta("delta", 0.18d, 0.80d, 0.50d, 0.20d, "PASS")),
                content("https://neutral-epsilon.example/e", "keepanchor epsilon broad evidence " + "e".repeat(220),
                        branchMeta("epsilon", 0.19d, 0.80d, 0.50d, 0.20d, "PASS")),
                content("https://neutral-zeta.example/zeta", "keepanchor zeta high lane-prior evidence " + "z".repeat(220),
                        branchMeta("zeta", 0.98d, 0.02d, 0.02d, 1.25d, "PASS")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.web().stream()
                .anyMatch(c -> c.textSegment().metadata().getString("url").contains("neutral-zeta.example/zeta")));
        assertTrue(out.web().stream()
                .filter(c -> c.textSegment().metadata().getString("url").contains("neutral-zeta.example/zeta"))
                .allMatch(c -> numberMeta(c, "_nova.authorityScore") < 0.90d));
        assertEquals(false, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void spreadProbeDoesNotPromoteSuppressedOrPromptIneligibleDocs() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://blocked.example/suppress", "keepanchor suppressed evidence " + "s".repeat(220),
                        branchMeta("suppress", 0.99d, 0.01d, 0.01d, 1.25d, "SUPPRESS")),
                content("https://blocked.example/ineligible", "keepanchor ineligible evidence " + "i".repeat(220),
                        withPromptEligible(branchMeta("ineligible", 0.99d, 0.01d, 0.01d, 1.25d, "PASS"), false)),
                content("https://safe-alpha.example/alpha", "keepanchor safe alpha evidence " + "a".repeat(220),
                        branchMeta("alpha", 0.70d, 0.10d, 0.05d, 0.90d, "PASS")),
                content("https://safe-beta.example/beta", "keepanchor safe beta evidence " + "b".repeat(220),
                        branchMeta("beta", 0.70d, 0.10d, 0.05d, 0.90d, "PASS")),
                content("https://safe-gamma.example/gamma", "keepanchor safe gamma evidence " + "c".repeat(220),
                        branchMeta("gamma", 0.70d, 0.10d, 0.05d, 0.90d, "PASS")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.web().stream()
                .noneMatch(c -> c.textSegment().metadata().getString("url").contains("blocked.example")));
        assertEquals(false, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void allPromptIneligibleCandidatesFailSoftToOriginalEvidence() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://blocked.example/a", "keepanchor blocked alpha evidence " + "a".repeat(220),
                        withPromptEligible(branchMeta("alpha", 0.99d, 0.01d, 0.01d, 1.25d, "PASS"), false)),
                content("https://blocked.example/b", "keepanchor blocked beta evidence " + "b".repeat(220),
                        branchMeta("beta", 0.99d, 0.01d, 0.01d, 1.25d, "SUPPRESS")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertEquals("all_prompt_ineligible", out.decision().reason());
        assertTrue(out.decision().failSoft());
        assertEquals(2, out.web().size());
        assertEquals("all_prompt_ineligible", TraceStore.get("prompt.context.composer.spreadProbe.reason"));
        assertEquals(true, TraceStore.get("prompt.context.composer.spreadProbe.failSoft"));
        assertEquals(true, TraceStore.get("prompt.context.composer.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void branchQualityRiskPenaltyReducesScoreEvenWhenAnchorMatches() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://risk.example/high", "keepanchor risky branch evidence " + "r".repeat(220),
                        branchMeta("risk", 0.95d, 0.05d, 1.00d, 1.25d, "PASS")),
                content("https://risk.example/low", "keepanchor low risk branch evidence " + "l".repeat(220),
                        branchMeta("low", 0.95d, 0.05d, 0.00d, 1.25d, "PASS")),
                content("https://risk.example/other-a", "keepanchor other alpha evidence " + "a".repeat(220),
                        branchMeta("alpha", 0.60d, 0.20d, 0.10d, 0.80d, "PASS")),
                content("https://risk.example/other-b", "keepanchor other beta evidence " + "b".repeat(220),
                        branchMeta("beta", 0.60d, 0.20d, 0.10d, 0.80d, "PASS")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        Content highRisk = byUrl(out.web(), "risk.example/high");
        Content lowRisk = byUrl(out.web(), "risk.example/low");
        assertNotNull(highRisk);
        assertNotNull(lowRisk);
        assertTrue(numberMeta(lowRisk, "_nova.compositionScore") > numberMeta(highRisk, "_nova.compositionScore"));
        assertEquals("branch_quality_risk", highRisk.textSegment().metadata().getString("_nova.penaltyFactor"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void branchQualityRerankMetadataDoesNotReplaceGrandasOrRerankConfidence() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                content("https://rerank.example/lane-only", "keepanchor lane only rerank evidence " + "l".repeat(220),
                        branchMeta("lane", 0.95d, 0.02d, 0.00d, 1.25d, "PASS")),
                content("https://rerank.example/grandas", "keepanchor grandas rerank evidence " + "g".repeat(220),
                        withExtra(branchMeta("grandas", 0.70d, 0.10d, 0.00d, 0.90d, "PASS"),
                                Map.of("grandas_adjusted_score", 0.72d))),
                content("https://rerank.example/other-a", "keepanchor other alpha evidence " + "a".repeat(220),
                        branchMeta("alpha", 0.60d, 0.20d, 0.00d, 0.80d, "PASS")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        Content laneOnly = byUrl(out.web(), "rerank.example/lane-only");
        Content grandas = byUrl(out.web(), "rerank.example/grandas");
        assertNotNull(laneOnly);
        assertNotNull(grandas);
        assertTrue(numberMeta(laneOnly, "_nova.rerankConfidence") < 0.90d);
        assertEquals(0.72d, numberMeta(grandas, "_nova.rerankConfidence"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void anchorProbeNarrowsActivatedPromptEvidenceToOneToThreeDocs() {
        DynamicContextCompressor anchorCompressor = compressorWithProbeMode("anchor");
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                plain("unrelated broad web context " + "n".repeat(240)),
                content("https://web.example/anchor-a", "keepanchor focused web evidence " + "a".repeat(240)),
                plain("another unrelated web result " + "x".repeat(240)),
                content("https://web.example/anchor-b", "keepanchor official citation evidence " + "b".repeat(240)));
        List<Content> rag = List.of(
                plain("low signal vector note " + "r".repeat(180)),
                content("vector://doc/anchor", "keepanchor vector citation evidence " + "v".repeat(220)));

        DynamicContextCompressor.PromptContextComposition out =
                anchorCompressor.composeForPrompt("secret keepanchor query", web, rag);

        int total = out.web().size() + out.rag().size();
        assertTrue(out.decision().activated());
        assertTrue(total >= 1 && total <= 3);
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .allMatch(c -> "ANCHOR_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .noneMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.origText")));
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .allMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.origHash")));
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .allMatch(c -> c.textSegment().metadata().toMap().containsKey("_nova.origLen")));
        assertTrue(Stream.concat(out.web().stream(), out.rag().stream())
                .noneMatch(c -> c.textSegment().text().contains("unrelated")));
        assertEquals(List.of(32, 16, 8, 3), TraceStore.get("prompt.context.composer.anchorProbe.kSchedule"));
        assertEquals(6, TraceStore.get("prompt.context.composer.anchorProbe.inputCount"));
        assertEquals(total, TraceStore.get("prompt.context.composer.anchorProbe.outputCount"));
        assertTrue(((Number) TraceStore.get("prompt.context.composer.anchorProbe.reductionRatio")).doubleValue() > 0.0d);
        assertEquals(false, TraceStore.get("prompt.context.composer.anchorProbe.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void anchorProbeTracesConfiguredLargeKReductionFromFortyEightToEight() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.RagCompressorProps cfg = props.getRagCompressor();
        cfg.setProbeMode("anchor");
        cfg.setMaxDocs(64);
        cfg.setMaxContents(64);
        cfg.setMinDocs(8);
        cfg.setAnchorProbeKSchedule(List.of(48, 24, 8));
        cfg.setAnchorProbeFinalMinDocs(8);
        cfg.setAnchorProbeFinalMaxDocs(8);
        DynamicContextCompressor anchorCompressor = new DynamicContextCompressor(props);
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        java.util.List<Content> web = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            web.add(content("https://h" + i + ".example/evidence",
                    "keepanchor verified source evidence lane " + i + " " + "x".repeat(220),
                    Map.of("intentAxis", "lane-" + i,
                            "authorityScore", 0.94d,
                            "rerankConfidence", 0.88d)));
        }

        DynamicContextCompressor.PromptContextComposition out =
                anchorCompressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertEquals(8, out.web().size());
        assertEquals(List.of(48, 24, 8), TraceStore.get("prompt.context.composer.anchorProbe.kSchedule"));
        assertEquals(List.of(48, 24, 8), TraceStore.get("prompt.context.composer.anchorProbe.stageCounts"));
        assertEquals(48, TraceStore.get("overdrive.compress.stage.48"));
        assertEquals(24, TraceStore.get("overdrive.compress.stage.24"));
        assertEquals(8, TraceStore.get("overdrive.compress.stage.8"));
        assertEquals(64, TraceStore.get("prompt.context.composer.anchorProbe.inputCount"));
        assertEquals(8, TraceStore.get("prompt.context.composer.anchorProbe.outputCount"));
        assertEquals(8, TraceStore.get("prompt.context.composer.anchorProbe.finalCap"));
        assertEquals(false, TraceStore.get("prompt.context.composer.anchorProbe.failSoft"));
        assertTrue(out.web().stream()
                .allMatch(c -> "ANCHOR_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertTrue(((Number) TraceStore.get("prompt.context.composer.anchorProbe.reductionRatio")).doubleValue()
                >= 0.87d);
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void composerAndProbeProjectQueryAuxMapAsHashOnlyTrace() {
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", true);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedCount", 2L);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes",
                List.of("111111111111", "222222222222"));
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.reason", "cue_seeded_landmark_anchors");
        List<Content> web = List.of(
                content("https://web.example/route-a", "bike route evidence " + "a".repeat(240),
                        Map.of("intentAxis", "route-a", "authorityScore", 0.92d, "rerankConfidence", 0.86d)),
                content("https://web.example/route-b", "river place evidence " + "b".repeat(240),
                        Map.of("intentAxis", "route-b", "authorityScore", 0.90d, "rerankConfidence", 0.84d)));
        List<Content> rag = List.of(
                content("vector://doc/route", "local landmark evidence " + "v".repeat(220),
                        Map.of("intentAxis", "local-route", "authorityScore", 0.78d, "rerankConfidence", 0.80d)));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret bike place near river", web, rag);

        assertTrue(out.decision().activated());
        assertEquals(true, TraceStore.get("prompt.context.composer.queryAuxMap.applied"));
        assertEquals("kg.brainState", TraceStore.get("prompt.context.composer.queryAuxMap.source"));
        assertEquals(2L, TraceStore.get("prompt.context.composer.queryAuxMap.seedCount"));
        assertEquals(List.of("111111111111", "222222222222"),
                TraceStore.get("prompt.context.composer.queryAuxMap.seedHashes"));
        assertEquals("cue_seeded_landmark_anchors",
                TraceStore.get("prompt.context.composer.queryAuxMap.reason"));
        assertEquals(true, TraceStore.get("prompt.context.composer.spreadProbe.queryAuxMap.applied"));
        assertEquals(List.of("111111111111", "222222222222"),
                TraceStore.get("prompt.context.composer.spreadProbe.queryAuxMap.seedHashes"));
        assertFalse(TraceStore.getAll().toString().contains("secret bike place near river"));
        assertFalse(TraceStore.getAll().toString().contains("Han River"));
    }

    @Test
    void anchorProbeFallsBackWhenCandidatesHaveNoAnchorOrCitationSignal() {
        DynamicContextCompressor anchorCompressor = compressorWithProbeMode("anchor");
        TraceStore.put("blackbox.risk.riskScore", 0.95d);
        List<Content> web = List.of(
                plain("unrelated one"),
                plain("unrelated two"),
                plain("unrelated three"),
                plain("unrelated four"),
                plain("unrelated five"));

        DynamicContextCompressor.PromptContextComposition out =
                anchorCompressor.composeForPrompt("secret keepanchor query", web, null);

        assertTrue(out.decision().activated());
        assertEquals(4, out.web().size());
        assertEquals(true, TraceStore.get("prompt.context.composer.anchorProbe.failSoft"));
        assertTrue(out.web().stream()
                .noneMatch(c -> "ANCHOR_PROBE".equals(c.textSegment().metadata().getString("_nova.probeMode"))));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void queryTransformerRiskPrefersOriginalAnchorEvidenceOverRewriteMetadata() {
        TraceStore.put("orch.debug.ablation.strike", List.of(
                Map.of("factor", "qtOpen", "deltaProb", 0.80d)));
        List<Content> web = List.of(
                content("https://web.example/rewrite", "generic rewritten branch result",
                        Map.of("retrieval_stage", "query_transformer_rewrite")),
                content("https://web.example/original", "keepanchor original evidence",
                        Map.of("retrieval_stage", "original_query")));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("keepanchor", web, List.of());

        assertTrue(out.decision().activated());
        assertEquals("qtOpen", out.decision().topFactor());
        assertTrue(out.web().get(0).textSegment().text().contains("keepanchor"));
    }

    @Test
    void alreadyCompressedDocsAreNotTrimmedAgain() {
        TraceStore.put("blackbox.risk.riskScore", 0.90d);
        String longText = "keepanchor " + "z".repeat(500);
        Content compressed = content("https://web.example/compressed", longText,
                Map.of("_nova.compressed", "true"));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("keepanchor", List.of(compressed), null);

        assertEquals(longText, out.web().get(0).textSegment().text());
        assertEquals("true", out.web().get(0).textSegment().metadata().getString("_nova.compressed"));
        assertEquals("true", out.web().get(0).textSegment().metadata().getString("_nova.anchorHit"));
    }

    @Test
    void disabledOrBelowThresholdReturnsOriginalLists() {
        List<Content> web = List.of(content("https://web.example/a", "keepanchor evidence"));
        DynamicContextCompressor.PromptContextComposition below =
                compressor.composeForPrompt("keepanchor", web, null);

        assertSame(web, below.web());
        assertFalse(below.decision().activated());
        assertEquals("below_threshold", below.decision().reason());

        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        DynamicContextCompressor disabled = new DynamicContextCompressor(props);
        DynamicContextCompressor.PromptContextComposition off =
                disabled.composeForPrompt("keepanchor", web, null);

        assertSame(web, off.web());
        assertFalse(off.decision().enabled());
        assertEquals("disabled", off.decision().reason());
    }

    @Test
    void contextRefinerDefaultOffLeavesRequiredDisabledTraceContract() {
        List<Content> web = List.of(content("https://web.example/a", "keepanchor evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                compressor.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(false, TraceStore.get("prompt.context.refiner.enabled"));
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("disabled", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals(0, TraceStore.get("prompt.context.refiner.candidateCount"));
        assertEquals("none", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertEquals(false, TraceStore.get("prompt.context.refiner.providerDisabled"));
        assertEquals(false, TraceStore.get("prompt.context.refiner.failSoft"));
        assertEquals(0.618d, ((Number) TraceStore.get("prompt.context.refiner.phi")).doubleValue(), 0.0001d);
        assertEquals(0.75d, ((Number) TraceStore.get("prompt.context.refiner.boltzmannTemp")).doubleValue(), 0.0001d);
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerEnabledWithoutProviderRecordsProviderDisabledFailSoftTrace() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("needle.keptRatio", 0.15d);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.44d);
        TraceStore.put("cfvm.boltzmannWeight", 0.73d);
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("ensemble.sampling.skipped", "disabled");
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(true, TraceStore.get("prompt.context.refiner.enabled"));
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("provider_disabled", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals(0, TraceStore.get("prompt.context.refiner.candidateCount"));
        assertEquals("none", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.providerDisabled"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
        assertEquals("provider_disabled", TraceStore.get("prompt.context.refiner.disabledReason"));
        assertEquals(0.44d,
                ((Number) TraceStore.get("prompt.context.refiner.contaminationScore")).doubleValue(), 0.0001d);
        assertEquals(0.618d, ((Number) TraceStore.get("prompt.context.refiner.phi")).doubleValue(), 0.0001d);
        assertEquals(0.75d, ((Number) TraceStore.get("prompt.context.refiner.boltzmannTemp")).doubleValue(), 0.0001d);
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerClassifiesFailedEnsembleAsModelUnavailableWithoutOutboundRetry() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("ensemble.bypass.reason", "ensemble_final_answer_failed");
        TraceStore.put("ensemble.judge.fail", "ensemble_judge_failed");
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("provider_disabled", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.providerDisabled"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
        assertEquals("model_unavailable", TraceStore.get("prompt.context.refiner.disabledReason"));
        assertTrue(out.contextRefinementSummary().contains("disabledReason=model_unavailable"),
                out.contextRefinementSummary());
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerCompositionCarriesRedactedSummaryAndClampedSignals() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("needle.keptRatio", 0.15d);
        TraceStore.put("tailSignal", 2.50d);
        TraceStore.put("prompt.memory.compressor.contaminationScore", 0.44d);
        TraceStore.put("starvationFallback.used", true);
        TraceStore.put("cfvm.boltzmannWeight", 0.73d);
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("ensemble.candidates.count", 3);
        TraceStore.put("gate.citation.passed", true);
        TraceStore.put("gate.finalSigmoid.result", "PASS");
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals("selected", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals(3, TraceStore.get("prompt.context.refiner.candidateCount"));
        assertEquals("ensemble_trace_best", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertTrue(out.contextRefinementSummary().contains("reason=selected"), out.contextRefinementSummary());
        assertTrue(out.contextRefinementSummary().contains("selected=ensemble_trace_best"), out.contextRefinementSummary());
        assertFalse(out.contextRefinementSummary().contains("secret keepanchor query"), out.contextRefinementSummary());
        assertEquals(0.15d, out.contextRefinementSignals().get("needleKeptRatio"), 0.0001d);
        assertEquals(1.0d, out.contextRefinementSignals().get("tailSignal"), 0.0001d);
        assertEquals(0.44d, out.contextRefinementSignals().get("contextContamination"), 0.0001d);
        assertEquals(1.0d, out.contextRefinementSignals().get("afterFilterStarvation"), 0.0001d);
        assertEquals(0.73d, out.contextRefinementSignals().get("cfvmBoltzmannWeight"), 0.0001d);
        assertEquals(0.618d, out.contextRefinementSignals().get("hypernovaCvarPhi"), 0.0001d);
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerRequiresCitationAndFinalGatePassBeforeSelection() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("ensemble.candidates.count", 3);
        TraceStore.put("gate.citation.passed", false);
        TraceStore.put("gate.finalSigmoid.result", "PASS");
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("citation_gate_failed", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals("none", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
        assertTrue(out.contextRefinementSummary().contains("reason=citation_gate_failed"), out.contextRefinementSummary());
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerRequiresFinalSigmoidPassBeforeSelection() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("ensemble.candidates.count", 3);
        TraceStore.put("gate.citation.passed", true);
        TraceStore.put("gate.finalSigmoid.result", "BLOCK");
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("final_gate_failed", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals("none", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
        assertTrue(out.contextRefinementSummary().contains("reason=final_gate_failed"), out.contextRefinementSummary());
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void contextRefinerFailsSoftWhenGateEvidenceIsMissing() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setAblationGuidedEnabled(false);
        enableContextRefiner(props.getRagCompressor());
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        TraceStore.put("ensemble.candidates.count", 3);
        List<Content> web = List.of(content("https://web.example/a", "keepanchor cited evidence"));

        DynamicContextCompressor.PromptContextComposition out =
                local.composeForPrompt("secret keepanchor query", web, null);

        assertSame(web, out.web());
        assertEquals(false, TraceStore.get("prompt.context.refiner.activated"));
        assertEquals("gate_evidence_missing", TraceStore.get("prompt.context.refiner.reason"));
        assertEquals("none", TraceStore.get("prompt.context.refiner.selectedCandidate"));
        assertEquals(true, TraceStore.get("prompt.context.refiner.failSoft"));
        assertFalse(TraceStore.getAll().toString().contains("secret keepanchor query"));
    }

    @Test
    void memoryCompressionDropsInternalArtifactsAndKeepsAnchorContext() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setMemoryMaxLines(3);
        props.getRagCompressor().setMemoryMaxChars(180);
        DynamicContextCompressor local = new DynamicContextCompressor(props);
        String memory = String.join("\n",
                "Important session memory",
                "- keepanchor decision was verified by cited evidence",
                "import java.util.List;",
                "Exception: at com.example.Secret api_key bearer token",
                "app/src/main/java_clean legacy duplicate note",
                "- safe follow-up note");

        String out = local.compressMemoryForPrompt("keepanchor current question", memory);

        assertTrue(out.contains("keepanchor"));
        assertFalse(out.contains("import java"));
        assertFalse(out.contains("api_key"));
        assertFalse(out.contains("java_clean"));
        assertTrue(out.length() <= 180);
        assertEquals(true, TraceStore.get("prompt.memory.compressor.activated"));
        assertEquals(memory.length(), ((Number) TraceStore.get("prompt.memory.compressor.inputLen")).intValue());
        assertTrue(((Number) TraceStore.get("prompt.memory.compressor.lineDropCount")).intValue() >= 3);
        assertFalse(TraceStore.getAll().toString().contains("keepanchor current question"));
    }

    private static Content content(String url, String text) {
        return content(url, text, Map.of());
    }

    private static Content content(String url, String text, Map<String, Object> extraMetadata) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("url", url);
        metadata.put("source", url.startsWith("vector:") ? "rag" : "test");
        metadata.putAll(extraMetadata);
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    private static Content metadataOnly(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    private static Content plain(String text) {
        return Content.from(TextSegment.from(text));
    }

    private static Map<String, Object> branchMeta(
            String axis,
            double contextContribution,
            double duplicateRatio,
            double riskPenalty,
            double rrfWeight,
            String action) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("branch_quality_branch_id", axis == null ? "BQ" : axis.toUpperCase(java.util.Locale.ROOT));
        metadata.put("branch_quality_intent_axis", axis);
        metadata.put("branch_quality_authority_score", 0.99d);
        metadata.put("branch_quality_rerank_confidence", 0.99d);
        metadata.put("branch_quality_context_contribution", contextContribution);
        metadata.put("branch_quality_duplicate_ratio", duplicateRatio);
        metadata.put("branch_quality_risk_penalty", riskPenalty);
        metadata.put("branch_quality_rrf_weight", rrfWeight);
        metadata.put("branch_quality_action", action);
        metadata.put("promptEligible", "true");
        return metadata;
    }

    private static Map<String, Object> withPromptEligible(Map<String, Object> metadata, boolean promptEligible) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(metadata);
        out.put("promptEligible", Boolean.toString(promptEligible));
        return out;
    }

    private static Map<String, Object> withExtra(Map<String, Object> metadata, Map<String, Object> extra) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(metadata);
        out.putAll(extra);
        return out;
    }

    private static double numberMeta(Content content, String key) {
        Object raw = content.textSegment().metadata().toMap().get(key);
        return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
    }

    private static Content byUrl(List<Content> docs, String needle) {
        return docs.stream()
                .filter(c -> c.textSegment().metadata().getString("url").contains(needle))
                .findFirst()
                .orElse(null);
    }

    private static DynamicContextCompressor compressorWithProbeMode(String probeMode) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.RagCompressorProps cfg = props.getRagCompressor();
        cfg.setProbeMode(probeMode);
        cfg.setMaxContents(4);
        cfg.setMaxCharsPerContent(180);
        cfg.setAnchorWindowChars(80);
        return new DynamicContextCompressor(props);
    }

    private static void enableContextRefiner(NovaOrchestrationProperties.RagCompressorProps cfg) {
        try {
            cfg.getClass().getMethod("setContextRefinerEnabled", boolean.class).invoke(cfg, true);
        } catch (ReflectiveOperationException e) {
            fail("context refiner toggle missing: " + e.getClass().getSimpleName());
        }
    }
}
