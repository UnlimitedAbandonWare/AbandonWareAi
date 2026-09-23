package com.example.lms.service.guard;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAwareGuardFailSoftBreadcrumbTest {

    @Test
    void topGuardFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertGuardStage(source, "definitionalProbe.context");
        assertGuardStage(source, "forceEscalateOverDegrade.trace");
        assertGuardStage(source, "forceEscalateOverDegrade.debugEvent");
        assertGuardStage(source, "forceEscalateOverDegrade.orchTrace");
        assertGuardStage(source, "forceEscalateOverDegrade.highRiskTrace");
        assertGuardStage(source, "forceEscalateOverDegrade.outer");
        assertGuardStage(source, "guard.inconsistentTemplate.trace");
        assertGuardStage(source, "guard.inconsistentTemplate.ablation");
        assertGuardStage(source, "guard.inconsistentTemplate.debugEvent");
        assertGuardStage(source, "guard.inconsistentTemplate.suppressionTrace");
        assertGuardStage(source, "guard.weakDraft.suppressionTrace");
        assertGuardStage(source, "guard.escalation.domainStats");
        assertGuardStage(source, "guard.escalation.trace");
        assertGuardStage(source, "guard.escalation.ablation");
        assertGuardStage(source, "guard.escalation.debugEvent");
        assertGuardStage(source, "guard.degradedToEvidence.emptyTrace");
        assertGuardStage(source, "guard.degradedToEvidence.finalActionTrace");
        assertGuardStage(source, "guard.degradedToEvidence.metricsTrace");
        assertGuardStage(source, "diagnostics.context");
        assertGuardStage(source, "fixHints.context");
        assertGuardStage(source, "guard.degradedToEvidence.fixHintsTrace");
        assertGuardStage(source, "scorecard.blockTrace");
        assertGuardStage(source, "guard.detour.orchTrace");
        assertGuardStage(source, "guard.detour.forceEscalate");
        assertGuardStage(source, "guard.degrade.contradictionTrace");
        assertGuardStage(source, "extractHttpDomain");
    }

    private static void assertGuardStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[guard] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing EvidenceAwareGuard fail-soft stage: " + stage);
    }
}
