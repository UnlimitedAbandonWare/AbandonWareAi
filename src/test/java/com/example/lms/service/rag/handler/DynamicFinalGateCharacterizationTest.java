package com.example.lms.service.rag.handler;

import com.example.lms.config.RetrieverChainConfig;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Current RC-06 boundary observations, not a new evidence-quality or BLOCK policy. */
class DynamicFinalGateCharacterizationTest {
    private final List<DynamicRetrievalHandlerChain> ownedChains = new ArrayList<>();

    @BeforeEach
    void clearState() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void cleanup() {
        for (DynamicRetrievalHandlerChain chain : ownedChains) {
            ((ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor")).shutdownNow();
        }
        clearState();
    }

    static Stream<Arguments> nonEmptyInputs() {
        return Stream.of(
                Arguments.of("single-sourced", documents(1, false, true), false, 0.0d),
                Arguments.of("repeated-source", documents(4, false, true), true, 0.75d),
                Arguments.of("distinct-sources", documents(4, true, true), true, 0.0d),
                Arguments.of("without-source", documents(4, false, false), true, 1.0d));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonEmptyInputs")
    void recordsCurrentCardinalityMappingThroughDynamicRepairStage(
            String fixture, List<Content> input, boolean expectedStrong, double expectedDuplicateRatio) {
        RecordingGate gate = new RecordingGate("hard", null);
        DynamicRetrievalHandlerChain chain = chain(input, gate);
        RetrievalHandler selected = selectDynamic(chain);
        List<Content> output = new ArrayList<>();

        selected.handle(Query.from("bounded fixture question"), output);

        assertSame(chain, selected);
        assertEquals(input, output);
        assertEquals(List.of(new GateArguments(1.0d, 0.0d, expectedStrong)), gate.calls);
        assertEquals("PASS", TraceStore.get("retrieval.finalSigmoidGate.result"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.finalSigmoidGate.applied"));
        assertEquals(expectedDuplicateRatio, number(TraceStore.get("cfvm.sig.dupRatio")), 1e-9);
        assertEquals(1.0d, number(TraceStore.get("cfvm.sig.coverage")), 1e-9);
        assertEquals(input.size(), ((Number) TraceStore.get("cfvm.sig.docCount")).intValue());
    }

    static Stream<Arguments> emptyModes() {
        return Stream.of(Arguments.of("hard", "BLOCK"), Arguments.of("soft", "WARN"),
                Arguments.of("degrade", "DEGRADE"), Arguments.of("log-only", "PASS"));
    }

    @ParameterizedTest
    @MethodSource("emptyModes")
    void recordsRealGateModeOnEmptyRetrieval(String mode, String expectedResult) {
        RecordingGate gate = new RecordingGate(mode, null);
        List<Content> output = new ArrayList<>();

        selectDynamic(chain(List.of(), gate)).handle(Query.from("empty fixture"), output);

        assertTrue(output.isEmpty());
        assertEquals(List.of(new GateArguments(0.0d, 0.0d, false)), gate.calls);
        assertEquals(expectedResult, TraceStore.get("retrieval.finalSigmoidGate.result"));
        assertNull(TraceStore.get("retrieval.finalSigmoidGate.blockTrim.count"));
    }

    @Test
    void absentOptionalGatePreservesRetrievedDocuments() {
        List<Content> input = documents(4, true, true);
        List<Content> output = new ArrayList<>();

        selectDynamic(chain(input, null)).handle(Query.from("optional gate fixture"), output);

        assertEquals(input, output);
        assertNull(TraceStore.get("retrieval.finalSigmoidGate.applied"));
    }

    @Test
    void forcedBlockPreservesExistingThreeDocumentPrefixContract() {
        List<Content> input = documents(6, true, true);
        List<Content> output = new ArrayList<>();
        RecordingGate gate = new RecordingGate("hard", FinalSigmoidGate.GateResult.BLOCK);

        selectDynamic(chain(input, gate)).handle(Query.from("block fixture"), output);

        assertEquals(input.subList(0, 3), output);
        assertEquals(3, TraceStore.get("retrieval.finalSigmoidGate.blockTrim.count"));
        assertEquals(1, gate.calls.size());
    }

    @Test
    void forcedDegradeCurrentlyPreservesTheFullRetrievedList() {
        List<Content> input = documents(6, true, true);
        List<Content> output = new ArrayList<>();
        RecordingGate gate = new RecordingGate("degrade", FinalSigmoidGate.GateResult.DEGRADE);

        selectDynamic(chain(input, gate)).handle(Query.from("degrade fixture"), output);

        assertEquals(input, output);
        assertEquals("DEGRADE", TraceStore.get("retrieval.finalSigmoidGate.result"));
        assertNull(TraceStore.get("retrieval.finalSigmoidGate.degradeTrim.count"));
    }

    @Test
    void diagnosticScoresAreNotTreatedAsCurrentGateInputs() {
        RecordingGate gate = new RecordingGate("hard", null);
        DynamicRetrievalHandlerChain chain = chain(documents(1, true, true), gate);
        TraceStore.put("gate.finalSigmoid.compositeScore", 0.1d);
        TraceStore.put("gate.finalSigmoid.policyRisk", 0.9d);
        TraceStore.put("cfvm.sig.coverage", 0.0d);
        TraceStore.put("cfvm.sig.dupRatio", 1.0d);

        selectDynamic(chain).handle(Query.from("current request fixture"), new ArrayList<>());

        assertEquals(List.of(new GateArguments(1.0d, 0.0d, false)), gate.calls);
        assertEquals(1.0d, number(TraceStore.get("gate.finalSigmoid.compositeScore")), 1e-9);
        assertEquals(0.0d, number(TraceStore.get("gate.finalSigmoid.policyRisk")), 1e-9);
    }

    @Test
    void changingQueryDoesNotMakeCurrentCoverageASemanticRelevanceScore() {
        RecordingGate gate = new RecordingGate("hard", null);
        DynamicRetrievalHandlerChain chain = chain(documents(1, false, true), gate);
        RetrievalHandler selected = selectDynamic(chain);

        selected.handle(Query.from("fixture body"), new ArrayList<>());
        double firstCoverage = number(TraceStore.get("cfvm.sig.coverage"));
        TraceStore.clear();
        selected.handle(Query.from("unrelated alternate topic"), new ArrayList<>());

        assertEquals(1.0d, firstCoverage, 1e-9);
        assertEquals(firstCoverage, number(TraceStore.get("cfvm.sig.coverage")), 1e-9);
        assertEquals(gate.calls.get(0), gate.calls.get(1));
    }

    private DynamicRetrievalHandlerChain chain(List<Content> input, FinalSigmoidGate gate) {
        EvidenceRepairHandler repair = mock(EvidenceRepairHandler.class);
        when(repair.retrieve(any(Query.class))).thenReturn(input);
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null, null, null, repair, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(chain, "searchFailureRecoveryMaxSubqueries", 4);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCitationMin", 3);
        ReflectionTestUtils.setField(chain, "finalSigmoidGate", gate);
        ownedChains.add(chain);
        return chain;
    }

    @SuppressWarnings("unchecked")
    private static RetrievalHandler selectDynamic(DynamicRetrievalHandlerChain chain) {
        ObjectProvider<DynamicRetrievalHandlerChain> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chain);
        return new RetrieverChainConfig().retrievalHandler(provider, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "", 12000, "dynamic");
    }

    private static List<Content> documents(int count, boolean distinctSources, boolean withSource) {
        return IntStream.range(0, count).mapToObj(i -> withSource
                ? Content.from(TextSegment.from("fixture body " + i,
                    Metadata.from(Map.of("source", "https://fixture-" + (distinctSources ? i : 0) + ".example/doc"))))
                : Content.from("fixture body " + i)).toList();
    }

    private static double number(Object value) {
        assertInstanceOf(Number.class, value);
        return ((Number) value).doubleValue();
    }

    private record GateArguments(double compositeScore, double policyRisk, boolean strongEvidence) { }

    private static final class RecordingGate extends FinalSigmoidGate {
        private final List<GateArguments> calls = new ArrayList<>();
        private final GateResult forced;

        RecordingGate(String mode, GateResult forced) {
            super(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", mode);
            this.forced = forced;
        }

        @Override
        public GateResult check(double compositeScore, double policyRisk, boolean strongEvidence) {
            calls.add(new GateArguments(compositeScore, policyRisk, strongEvidence));
            GateResult actual = super.check(compositeScore, policyRisk, strongEvidence);
            return forced == null ? actual : forced;
        }
    }
}
