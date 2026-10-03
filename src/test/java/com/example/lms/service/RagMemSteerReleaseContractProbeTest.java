package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.ChatWorkflow.EvidenceReleaseState;
import com.example.lms.service.ChatWorkflow.FinalVerificationReleaseDecision;
import com.example.lms.service.ChatWorkflow.RetrievalReleaseContract;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.service.rag.RagEvidenceAttributionService.PromotionReason;
import com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult;
import com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rag-mem-steer-0928-76d11b5a: measured release-contract + memory-gate behavior
 * for the M1-M9 dynamic toggle matrix.
 *
 * <p>Exercises the package-private statics the live workflow uses per turn:
 * {@code buildRetrievalReleaseContract -> deriveEvidenceReleaseState ->
 * applyEvidenceReleasePolicy -> FinalAnswerPostProcessor.process}. Assertions
 * record the <em>observed</em> contract so matrix cells are evidence, not guesses.</p>
 */
class RagMemSteerReleaseContractProbeTest {

    private final FinalAnswerPostProcessor postProcessor = new FinalAnswerPostProcessor(new OutputSanitizer());

    private static ChatRequestDto req(Boolean rag, Boolean web, SearchMode mode) {
        return ChatRequestDto.builder()
                .sessionId(1L).message("probe")
                .useRag(rag).useWebSearch(web).searchMode(mode)
                .build();
    }

    private static ChatRequestDto.RetrievalRequestIntent intent(Boolean web, Boolean rag) {
        return new ChatRequestDto.RetrievalRequestIntent(web, rag);
    }

    private static FinalVerificationReleaseDecision approve(String content) {
        return new FinalVerificationReleaseDecision(content, "APPROVE", "verification_accepted", true, false, true);
    }

    private static RagEvidenceMetadata webEvidence() {
        return new RagEvidenceMetadata("[1]", "WEB", "doc", "https://example.test/x",
                null, null, null, 1, 0.9, "probe");
    }

    private static PromotionResult webPromoted() {
        return new PromotionResult(PromotionStatus.PROMOTED, PromotionReason.PROMOTED,
                List.of(webEvidence()), 1, 1, 0, 0, 0, 0);
    }

    private static PromotionResult failed() {
        return PromotionResult.callerFailure();
    }

    // ---- M1 turn-2: RAG OFF while web stays on ----
    @Test
    void m1_turn2_ragOffKeepsWebContractAndDropsRagRequirement() {
        ChatRequestDto r = req(false, true, SearchMode.AUTO);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(true, false), true, false, false);

        assertFalse(c.explicitDirectOff());
        assertTrue(c.webRequested());
        assertFalse(c.ragRequested(), "turn-2 contract drops the rag lane entirely");
        assertTrue(c.effectiveWeb());
        assertFalse(c.effectiveRag());
        assertTrue(c.retrievalContractRequested());

        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                webPromoted(), List.of(webEvidence()), false, c);
        assertEquals(EvidenceReleaseState.EVIDENCE_PRESENT, state,
                "web-only evidence satisfies a web-only contract");

        FinalVerificationReleaseDecision released = ChatWorkflow.applyEvidenceReleasePolicy(
                approve("answer"), state, false, c.explicitDirectOff(), false, webPromoted(), c);
        assertTrue(released.releaseAllowed());
        assertTrue(released.knowledgeWriteAllowed());
    }

    // ---- M1 residue probe: vector evidence cannot satisfy a dropped rag lane ----
    @Test
    void m1_ragOffTurn_cannotBeRequalifiedByStaleVectorEvidence() {
        ChatRequestDto r = req(false, true, SearchMode.AUTO);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(true, false), true, false, false);
        // derive only consults promotion.vectorCitableLocatorCount when ragRequested.
        assertFalse(c.ragRequested());
        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                webPromoted(), List.of(webEvidence()), false, c);
        assertEquals(EvidenceReleaseState.EVIDENCE_PRESENT, state);
    }

    // ---- M2: OFF turn then ON turn - contract is per-turn stateless ----
    @Test
    void m2_explicitOffThenOn_contractCarriesNoTurnResidue() {
        RetrievalReleaseContract off = ChatWorkflow.buildRetrievalReleaseContract(
                req(false, false, SearchMode.OFF), intent(false, false), false, false, false);
        assertTrue(off.explicitDirectOff());
        assertFalse(off.retrievalContractRequested());
        assertEquals(EvidenceReleaseState.NOT_APPLICABLE,
                ChatWorkflow.deriveEvidenceReleaseState(null, List.of(), false, off));

        RetrievalReleaseContract on = ChatWorkflow.buildRetrievalReleaseContract(
                req(true, true, SearchMode.AUTO), intent(true, true), true, true, false);
        assertTrue(on.retrievalContractRequested());
        assertTrue(on.webRequested() && on.ragRequested());
        assertFalse(on.explicitDirectOff());
    }

    // ---- M3 middle turn: searchMode OFF yields explicitDirectOff passthrough ----
    @Test
    void m3_offTurn_explicitDirectOffReleasesWithoutEvidenceContract() {
        ChatRequestDto r = req(false, false, SearchMode.OFF);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(false, false), false, false, false);
        assertTrue(c.explicitDirectOff());

        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(null, List.of(), false, c);
        assertEquals(EvidenceReleaseState.NOT_APPLICABLE, state);

        FinalVerificationReleaseDecision released = ChatWorkflow.applyEvidenceReleasePolicy(
                approve("plain answer"), state, false, c.explicitDirectOff(), false, null, c);
        assertTrue(released.releaseAllowed(), "OFF turn is never held by evidence policy");
    }

    // ---- M4: RAG ON + web OFF (scoped rag) needs vector locators ----
    @Test
    void m4_scopedRagWithoutVectorLocators_isMetadataIncomplete() {
        ChatRequestDto r = req(true, false, SearchMode.AUTO);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(false, true), false, true, false);
        assertTrue(c.ragRequested());
        assertFalse(c.webRequested());

        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                failed(), List.of(), false, c);
        assertEquals(EvidenceReleaseState.METADATA_INCOMPLETE, state,
                "rag lane requested but promotion produced no citable vector locator");
    }

    // ---- M5: default HYBRID turn - write gate is the first deny ----
    @Test
    void m5_defaultHybridTurn_memoryGateDeniesWriteDisabledBeforeAnyOtherCheck() {
        FinalAnswerPostProcessor.Result res = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        "A normal factual answer.",
                        "A normal factual answer.",
                        true,   // verificationOutcomeKnown
                        true,   // verificationAcceptedForMemory
                        false,  // memoryWriteEnabled <- HYBRID from omitted memoryMode
                        false, false, false, false, "probe query"));

        assertFalse(res.memorySaveAllowed());
        assertEquals("write_disabled", res.memoryDenyReason());
        assertEquals("A normal factual answer.", res.content());
    }

    @Test
    void m5_fullMemoryTurn_allGatesGreen_allowsSave() {
        FinalAnswerPostProcessor.Result res = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        "A normal factual answer.",
                        "A normal factual answer.",
                        true, true,
                        true,   // memoryWriteEnabled (FULL)
                        false, false, false, false, "probe query"));

        assertTrue(res.memorySaveAllowed());
        assertEquals("none", res.memoryDenyReason());
        assertEquals("A normal factual answer.", res.memoryContent());
    }

    // ---- M6: METADATA_INCOMPLETE - released but memory-blocked, or HOLD ----
    @Test
    void m6_metadataIncomplete_releasesWithKnowledgeWriteBlocked_whenEvidenceNotRequired() {
        ChatRequestDto r = req(true, true, SearchMode.AUTO);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(true, true), true, true, false);
        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                failed(), List.of(), false, c);
        assertEquals(EvidenceReleaseState.METADATA_INCOMPLETE, state);

        FinalVerificationReleaseDecision released = ChatWorkflow.applyEvidenceReleasePolicy(
                approve("unverified answer"), state, false, c.explicitDirectOff(), false, failed(), c);
        assertTrue(released.releaseAllowed(), "answer is released (no evidence_needed directive)");
        assertEquals("evidence_unverified_release", released.reasonCode());
        assertFalse(released.knowledgeWriteAllowed(), "long-term memory write stays blocked");
    }

    @Test
    void m6_metadataIncomplete_holds_whenEvidenceRequired() {
        ChatRequestDto r = req(true, true, SearchMode.AUTO);
        RetrievalReleaseContract c = ChatWorkflow.buildRetrievalReleaseContract(
                r, intent(true, true), true, true, true);
        EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                failed(), List.of(), false, c);
        assertEquals(EvidenceReleaseState.METADATA_INCOMPLETE, state);

        FinalVerificationReleaseDecision held = ChatWorkflow.applyEvidenceReleasePolicy(
                approve("draft"), state, true, c.explicitDirectOff(), false, failed(), c);
        assertFalse(held.releaseAllowed());
        assertEquals("evidence_release_metadata_incomplete", held.reasonCode());
    }

    @Test
    void m6_unverifiedReleaseChain_postprocessorDeniesMemoryPolicy() {
        // mirrors the workflow: !releaseDecision.knowledgeWriteAllowed() -> memoryDeniedByPolicy
        FinalAnswerPostProcessor.Result res = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        "A normal factual answer.",
                        "A normal factual answer.",
                        true, true,
                        true,   // FULL write enabled - policy deny is the binding gate
                        true,   // memoryDeniedByPolicy
                        false, false, false, "probe query"));

        assertFalse(res.memorySaveAllowed());
        assertEquals("memory_policy_denied", res.memoryDenyReason());
    }

    // ---- M8: EPHEMERAL - read side is gated before recall ----
    @Test
    void m8_ephemeral_readAndWriteBothDisabled() {
        com.example.lms.domain.enums.MemoryMode m =
                com.example.lms.domain.enums.MemoryMode.fromString("ephemeral");
        assertFalse(m.isReadEnabled(), "workflow skips session recall (memoryReadEnabled=false)");
        assertFalse(m.isWriteEnabled());
    }
}
