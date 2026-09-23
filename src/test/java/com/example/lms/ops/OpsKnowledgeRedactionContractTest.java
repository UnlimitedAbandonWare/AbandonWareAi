package com.example.lms.ops;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class OpsKnowledgeRedactionContractTest {

    @Test
    void opsKnowledgeAndSoakFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/ops/RagOpsLedgerService.java"),
                Path.of("main/java/com/example/lms/service/soak/SoakDatasetIngestService.java"),
                Path.of("main/java/com/example/lms/service/soak/SoakQuickJsonlExporter.java"),
                Path.of("main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java"),
                Path.of("main/java/com/example/lms/service/trace/DebugCopilotService.java"),
                Path.of("main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java"),
                Path.of("main/java/com/example/lms/service/reinforcement/SnippetPruner.java"),
                Path.of("main/java/com/example/lms/service/rag/auth/DomainProfileLoader.java"))) {
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
    void opsKnowledgeAndSoakFailSoftLogsDoNotWriteRawLocalPaths() throws Exception {
        String soakExporter = Files.readString(
                Path.of("main/java/com/example/lms/service/soak/SoakQuickJsonlExporter.java"),
                StandardCharsets.UTF_8);
        String rgbParser = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbLogSignalParser.java"),
                StandardCharsets.UTF_8);

        assertFalse(soakExporter.contains("export dir create failed: {}"));
        assertTrue(soakExporter.contains("export dir create failed pathHash={} pathLength={}"));
        assertFalse(rgbParser.contains("logPath not found: {}"));
        assertTrue(rgbParser.contains("logPath not found pathHash={} pathLength={}"));
    }

    @Test
    void ragOpsLedgerFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ops/RagOpsLedgerService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("RAG ledger write skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("AutoLearn ledger write skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("AutoLearn diagnostic ledger write skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("summary unavailable. errorHash={} errorLength={}"));
        assertTrue(source.contains("recent unavailable. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void domainProfileExternalLoadFailureUsesStructuredSafeDiagnostics() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/auth/DomainProfileLoader.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("[DomainProfileLoader] external profile load skipped: {}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains(
                "[AWX][rag][auth] domainProfile external load skipped failureReason={} errorType={} pathHash={} pathLength={}"));
        assertTrue(source.contains("\"domain-profile-external-load-error\""));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(externalDir)"));
        assertTrue(source.contains("externalDir == null ? 0 : externalDir.length()"));
    }

    @Test
    void snippetPrunerLlmCallUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/reinforcement/SnippetPruner.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String snippetPruningPrompt ="));
        assertTrue(source.contains("UserMessage.from(snippetPruningPrompt)"));
    }

    @Test
    void answerUnderstandingLlmCallUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertTrue(source.contains("String answerUnderstandingPrompt = promptBuilder.build(question, finalAnswer);"));
        assertTrue(source.contains("geminiClient.generate(answerUnderstandingPrompt)"));
    }

    @Test
    void answerUnderstandingFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[Understanding] Gemini call or parsing failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void debugCopilotEnrichFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/trace/DebugCopilotService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("[DebugCopilot] enrich failed (fail-soft). errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void knowledgeBaseVectorIndexFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[KB][INTEGRATE] vector index failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }
}
