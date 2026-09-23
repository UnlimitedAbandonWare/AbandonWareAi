package com.example.lms.agent;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRedactionContractTest {

    @Test
    void agentFailSoftLogsDoNotWriteRawThrowableTextOrQueries() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/agent/SynthesisService.java"),
                Path.of("main/java/com/example/lms/agent/CuriosityTriggerService.java"),
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                Path.of("main/java/com/example/lms/agent/KnowledgeConsistencyVerifier.java"),
                Path.of("main/java/com/example/lms/agent/KnowledgeDecayService.java"))) {
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

        String exploration = Files.readString(
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                StandardCharsets.UTF_8);
        assertFalse(exploration.contains("Query expansion failed for '{}': {}"));
        assertFalse(exploration.contains("No evidence found for gap '{}'"));
        assertTrue(exploration.contains("queryHash12={} queryLength={}"));
        assertTrue(exploration.contains("SafeRedactor.hash12(q)"));
        assertTrue(exploration.contains("SafeRedactor.hash12(gap.getQuery())"));

        String curiosity = Files.readString(
                Path.of("main/java/com/example/lms/agent/CuriosityTriggerService.java"),
                StandardCharsets.UTF_8);
        assertFalse(curiosity.contains("JSON parse error, raw='{}'"));
        assertFalse(curiosity.contains("fallback.put(\"description\", safeTruncate(candidate, 200));"));
        assertTrue(curiosity.contains("JSON parse error, candidateHash={} candidateLength={}"));
        assertTrue(curiosity.contains("SafeRedactor.hashValue(candidate)"));
        assertTrue(curiosity.contains("fallback.put(\"description\", SafeRedactor.safeMessage(candidate, 200));"));
    }

    @Test
    void agentFailSoftLogsUseHashAndLengthOnly() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/agent/SynthesisService.java"),
                Path.of("main/java/com/example/lms/agent/CuriosityTriggerService.java"),
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                Path.of("main/java/com/example/lms/agent/KnowledgeConsistencyVerifier.java"),
                Path.of("main/java/com/example/lms/agent/KnowledgeDecayService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);

            assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"), source.toString());
            assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"), source.toString());
            assertTrue(code.contains("errorHash={} errorLength={}"), source.toString());
            assertTrue(code.contains("SafeRedactor.hashValue(messageOf("), source.toString());
        }

        String exploration = Files.readString(
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                StandardCharsets.UTF_8);
        assertTrue(exploration.contains("Query expansion failed queryHash12={} queryLength={} errorHash={} errorLength={}"));
        assertTrue(exploration.contains("[AutonomousExploration] Retrieval failed. errorHash={} errorLength={}"));
        assertTrue(exploration.contains("[AutonomousExploration] Curation failed. errorHash={} errorLength={}"));
        assertTrue(exploration.contains("[AutonomousExploration] Unexpected error type={} errorHash={} errorLength={}"));
    }

    @Test
    void knowledgeGapLogsUseFingerprintsForDomainSubjectAndEntity() throws Exception {
        String gapLogger = Files.readString(
                Path.of("main/java/com/example/lms/agent/KnowledgeGapLogger.java"),
                StandardCharsets.UTF_8);
        String exploration = Files.readString(
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                StandardCharsets.UTF_8);
        String scheduler = Files.readString(
                Path.of("main/java/com/example/lms/agent/KnowledgeCurationScheduler.java"),
                StandardCharsets.UTF_8);

        assertFalse(gapLogger.contains("domain='{}', subject='{}', intent='{}'"));
        assertTrue(gapLogger.contains("domainHash={} domainLength={} subjectHash={} subjectLength={} intentHash={} intentLength={}"));
        assertTrue(gapLogger.contains("@JsonIgnore"));
        assertTrue(gapLogger.contains("evt.getDomainHash()"));
        assertTrue(gapLogger.contains("evt.getSubjectHash()"));
        assertTrue(gapLogger.contains("evt.getIntentHash()"));

        assertFalse(exploration.contains("domain='{}', subject='{}'"));
        assertTrue(exploration.contains("domainHash={} domainLength={} subjectHash={} subjectLength={}"));
        assertTrue(exploration.contains("SafeRedactor.hashValue(gap.getSubject())"));

        assertFalse(scheduler.contains("entity='{}', domain='{}'"));
        assertFalse(scheduler.contains("status={}, entity={}, conf={}"));
        assertTrue(scheduler.contains("entityHash={} entityLength={} domainHash={} domainLength={}"));
        assertTrue(scheduler.contains("status={} entityHash={} entityLength={} conf={}"));
    }

    @Test
    void curiosityTriggerPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String curiosity = Files.readString(
                Path.of("main/java/com/example/lms/agent/CuriosityTriggerService.java"),
                StandardCharsets.UTF_8);

        assertFalse(curiosity.contains("String prompt ="));
        assertFalse(curiosity.contains("generate(prompt,"));
        assertTrue(curiosity.contains("String curiosityGapPrompt ="));
        assertTrue(curiosity.contains("generate(curiosityGapPrompt,"));
    }

    @Test
    void synthesisPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String synthesis = Files.readString(
                Path.of("main/java/com/example/lms/agent/SynthesisService.java"),
                StandardCharsets.UTF_8);

        assertFalse(synthesis.contains("String prompt ="));
        assertFalse(synthesis.contains("generate(prompt,"));
        assertTrue(synthesis.contains("String synthesisPrompt ="));
        assertTrue(synthesis.contains("generate(synthesisPrompt,"));
    }

    @Test
    void knowledgeMaintenanceLogsUseFingerprintsForDomainAndEntity() throws Exception {
        String decay = Files.readString(
                Path.of("main/java/com/example/lms/agent/KnowledgeDecayService.java"),
                StandardCharsets.UTF_8);
        String consistency = Files.readString(
                Path.of("main/java/com/example/lms/agent/KnowledgeConsistencyVerifier.java"),
                StandardCharsets.UTF_8);

        assertFalse(decay.contains("Failed to save updated confidence for {}:{}"));
        assertFalse(decay.contains("Updated confidence for {}:{} from {} to {}"));
        assertTrue(decay.contains("domainHash={} domainLength={} entityHash={} entityLength={}"));
        assertTrue(decay.contains("SafeRedactor.hashValue(dk.getDomain())"));
        assertTrue(decay.contains("SafeRedactor.hashValue(dk.getEntityName())"));

        assertFalse(consistency.contains("Conflict detected for {}:{} -> {} appears"));
        assertFalse(consistency.contains("external consistency check for {}:{}"));
        assertTrue(consistency.contains("domainHash={} domainLength={} entityHash={} entityLength={}"));
        assertTrue(consistency.contains("conflictCount={}"));
        assertTrue(consistency.contains("SafeRedactor.hashValue(domain)"));
        assertTrue(consistency.contains("SafeRedactor.hashValue(entity)"));
    }

    @Test
    void curiosityGapFallbackPromptRedactsSecretLikeEventFields() throws Exception {
        var method = CuriosityTriggerService.class.getDeclaredMethod("buildGapFallback", List.class, int.class);
        method.setAccessible(true);

        String token = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        var event = new KnowledgeGapLogger.GapEvent(
                "query " + token,
                "domain " + token,
                "subject " + token,
                "intent " + token);

        String rendered = (String) method.invoke(null, List.of(event), 2000);

        assertFalse(rendered.contains(token));
    }

    @Test
    void agentSilentFallbacksUseTraceSuppressionBreadcrumbs() throws Exception {
        String exploration = Files.readString(
                Path.of("main/java/com/example/lms/agent/AutonomousExplorationService.java"),
                StandardCharsets.UTF_8);
        String curiosity = Files.readString(
                Path.of("main/java/com/example/lms/agent/CuriosityTriggerService.java"),
                StandardCharsets.UTF_8);
        String decay = Files.readString(
                Path.of("main/java/com/example/lms/agent/KnowledgeDecayService.java"),
                StandardCharsets.UTF_8);
        String synthesis = Files.readString(
                Path.of("main/java/com/example/lms/agent/SynthesisService.java"),
                StandardCharsets.UTF_8);
        String helper = Files.readString(
                Path.of("main/java/com/example/lms/agent/AgentTraceSuppressions.java"),
                StandardCharsets.UTF_8);

        assertTrue(exploration.contains("AgentTraceSuppressions.traceSuppressed(\"autonomousExploration.evidenceSnippet\", e);"));
        assertTrue(curiosity.contains("AgentTraceSuppressions.traceSuppressed(\"curiosity.tryParseJson\", e);"));
        assertTrue(decay.contains("AgentTraceSuppressions.traceSuppressed(\"knowledgeDecay.synergyLookup\", ignore);"));
        assertTrue(synthesis.contains("AgentTraceSuppressions.traceSuppressed(\"synthesis.isJson\", ignore);"));
        assertTrue(synthesis.contains("AgentTraceSuppressions.traceSuppressed(\"synthesis.extractSources\", ignore);"));

        assertTrue(helper.contains("TraceStore.put(\"agent.suppressed.\" + safeStage, true);"));
        assertTrue(helper.contains("TraceStore.inc(\"agent.suppressed.count\");"));
        assertFalse(helper.contains("failure.getMessage()"));
    }

    @Test
    void agentTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        AgentTraceSuppressions.traceSuppressed("curiosity.tryParseJson", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("agent.suppressed.curiosity.tryParseJson"));
        assertEquals("invalid_number", TraceStore.get("agent.suppressed.curiosity.tryParseJson.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void agentTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        TraceStore.clear();
        String rawStage = "curiosity.tryParseJson " + com.example.lms.test.SecretFixtures.openAiKey();

        AgentTraceSuppressions.traceSuppressed(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("agent.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("agent.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("agent.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }
}
