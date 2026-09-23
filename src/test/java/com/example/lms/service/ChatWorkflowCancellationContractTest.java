package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowCancellationContractTest {

    @Test
    void terminalPersistenceDelegatesToOneCancellationAwareOwner() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String persistenceOwner = Files.readString(
                Path.of("main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java"));

        int terminalStart = source.indexOf("boolean finalVerificationOutcomeKnown = false;");
        int verifier = source.indexOf("verifier.verifyDetailed(", terminalStart);
        int postprocessor = source.indexOf("finalAnswerPostProcessor.process(", verifier);
        int memoryStage = source.indexOf("if (finalized.memorySaveAllowed())", postprocessor);
        int finalPipelineTrace = source.indexOf("emitRagPipelineEvent(\"answer\"", memoryStage);

        assertCheckpointBetween(source, terminalStart, verifier,
                "session deletion must stop the final verifier");
        assertCheckpointBetween(source, verifier, postprocessor,
                "cancellation during verification must stop postprocessing");
        assertCheckpointBetween(source, postprocessor, memoryStage,
                "cancellation during postprocessing must stop durable memory work");
        assertCheckpointBetween(source, memoryStage, finalPipelineTrace,
                "cancellation during terminal memory work must stop final trace emission");

        String memoryBlock = source.substring(memoryStage, finalPipelineTrace);
        assertTrue(count(memoryBlock, "FinalizedMemoryPersistence.persist(") == 1,
                "ChatWorkflow must delegate terminal persistence to one extracted owner");
        assertTrue(count(memoryBlock, "new FinalizedMemoryPersistence.Stage(") == 4,
                "learning, memory, understanding, and reinforcement remain four explicit stages");
        assertTrue(count(persistenceOwner, "tryBeginCommit()") == 1,
                "the extracted owner must claim the durable commit exactly once");
        assertTrue(count(persistenceOwner, "runTerminalSideEffect(") == 1,
                "one loop-owned fence must protect every declared terminal stage");
        assertTrue(persistenceOwner.contains("cursor instanceof CancellationException"));
        assertTrue(persistenceOwner.contains("cursor instanceof InterruptedException"));
        assertTrue(persistenceOwner.contains("Thread.currentThread().interrupt()"));
    }

    @Test
    void finalAnswerGenerationRechecksCancellationBeforeRecordingSuccess() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int draftCall = source.indexOf("draft = callWithRetryReportingSuccess(");
        int cancelGuard = source.indexOf("throwIfCancelled(sessionIdLong);", draftCall);
        int finalCatch = source.indexOf("} catch (CancellationException ce) {", draftCall);
        String finalDraftBlock = source.substring(draftCall, finalCatch);
        int successRecord = finalDraftBlock.indexOf(
                "recordModelSuccess(primarySuccess.modelId(), primarySuccess.endpoint())");

        assertTrue(draftCall > 0, "final LLM draft call should be locatable");
        assertTrue(cancelGuard > draftCall, "final LLM draft should be followed by a cancellation checkpoint");
        assertTrue(finalDraftBlock.contains("callWithRetryReportingSuccess("));
        assertTrue(finalDraftBlock.contains("primarySuccessRef::set"),
                "the primary retry path must defer health mutation until after cancellation is checked");
        assertTrue(successRecord > finalDraftBlock.indexOf("throwIfCancelled(sessionIdLong);"),
                "cancelled final LLM calls must not record model success");
        assertTrue(count(finalDraftBlock,
                "recordModelSuccess(primarySuccess.modelId(), primarySuccess.endpoint())") == 1,
                "the final-answer path must commit primary model success exactly once");
        assertFalse(finalDraftBlock.contains("ModelRuntimeHealthTracker.recordLocalSuccessSignal()"),
                "workflow attempt completion must not bypass the controller-owned semantic boundary");
        assertTrue(finalDraftBlock.contains("if (primaryPermit != null)"),
                "breaker completion must be owned by the admitted primary call");
        assertTrue(finalDraftBlock.indexOf("primaryPermit.completeSuccess(ms);")
                        > finalDraftBlock.indexOf("throwIfCancelled(sessionIdLong);"),
                "the admitted call may complete successfully only after cancellation is rechecked");
    }

    @Test
    void everyRetrySuccessUsesOneCancellationAwareOwner() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        int retryStart = source.indexOf("private String callWithRetryReportingSuccess(ChatModel model,");
        int retryEnd = source.indexOf("private static List<Content> filterPromptEligibleSelfAsk", retryStart);
        String retryCore = source.substring(retryStart, retryEnd);
        int compatStart = source.indexOf("private String tryEndpointCompatFallback(");
        int compatEnd = source.indexOf("private void recordEndpointCompatFailure", compatStart);
        String compatMethod = source.substring(compatStart, compatEnd);

        assertTrue(retryCore.contains("Consumer<LlmCallSuccess> successSink"));
        assertFalse(retryCore.contains("recordModelSuccess("),
                "the reporting core may only emit metadata, never mutate health directly");
        assertTrue(count(retryCore, "successSink.accept(new LlmCallSuccess(") == 4,
                "preflight, normal, sampling-heal, and model-required-heal must each report once");
        assertTrue(retryCore.contains("fallbackModel,")
                        && retryCore.contains("OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS"),
                "MODEL_REQUIRED self-heal must credit the actual fallback model");
        assertTrue(compatMethod.contains("Consumer<LlmCallSuccess> successSink"));
        assertFalse(compatMethod.contains("recordModelSuccess("),
                "endpoint fallback must not bypass deferred success ownership");
        assertTrue(count(compatMethod, "successSink.accept(new LlmCallSuccess(") == 2,
                "responses and completions fallback must report their actual endpoint once");
    }

    @Test
    void everyFinalStageFallbackRechecksAndClearsCancellationBeforeReturning() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int stageStart = source.indexOf("if (nightmareBreaker != null) {");
        int stageEnd = source.indexOf("boolean verifyAnswer =", stageStart);
        String stage = source.substring(stageStart, stageEnd);
        String fallbackCall = "return sanitizeFallbackResult(NoEvidenceChatFallback.orEvidenceFallback(";

        int cursor = 0;
        int fallbackCount = 0;
        while ((cursor = stage.indexOf(fallbackCall, cursor)) >= 0) {
            String prefix = stage.substring(Math.max(0, cursor - 220), cursor);
            assertTrue(prefix.contains("throwIfCancelled(sessionIdLong);"),
                    "fallback must prefer a concurrently requested cancellation");
            assertTrue(prefix.contains("clearCancel(sessionIdLong);"),
                    "fallback must not leave a stale cancellation flag for the next turn");
            fallbackCount++;
            cursor += fallbackCall.length();
        }
        assertTrue(fallbackCount >= 4, "breaker, budget, and retry fallback paths must be covered");
    }

    @Test
    void directCancellationAndConfigurationReturnsCannotLeakSessionCancelFlag() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int draftCall = source.indexOf("draft = callWithRetryReportingSuccess(");
        int cancellationCatch = source.indexOf("} catch (CancellationException ce) {", draftCall);
        int configurationBranch = source.indexOf("LlmConfigurationException cfg =", cancellationCatch);
        String cancellationBlock = source.substring(cancellationCatch, configurationBranch);
        assertTrue(cancellationBlock.contains("clearCancel(sessionIdLong);"),
                "a delegate cancellation must not leave the session flag set for the next turn");

        int configurationReturn = source.indexOf("return ChatResult.of(userMsg, usedModel + \":fail:\"", configurationBranch);
        String configurationBlock = source.substring(configurationBranch, configurationReturn);
        assertTrue(configurationBlock.contains("throwIfCancelled(sessionIdLong);"),
                "a concurrent user cancellation must win over a configuration-error response");
        assertTrue(configurationBlock.contains("clearCancel(sessionIdLong);"),
                "configuration-error return must not leak a stale cancel flag");
    }

    @Test
    void onlyClientCancellationMayBecomeNormalCancelledContent() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int draftCall = source.indexOf("draft = callWithRetryReportingSuccess(");
        int cancellationCatch = source.indexOf("} catch (CancellationException ce) {", draftCall);
        int exceptionCatch = source.indexOf("} catch (Exception e) {", cancellationCatch);
        String cancellationBlock = source.substring(cancellationCatch, exceptionCatch);

        int classification = cancellationBlock.indexOf("ce instanceof ClientCancellationException");
        int rethrow = cancellationBlock.indexOf("throw ce;");
        int normalReturn = cancellationBlock.indexOf("return ChatResult.of(");
        assertTrue(source.contains("class ClientCancellationException extends CancellationException"));
        assertTrue(classification >= 0, "client cancellation must have an explicit subtype");
        assertTrue(rethrow > classification && rethrow < normalReturn,
                "autolearn/provider cancellation must propagate before cancelled content is returned");
        assertTrue(source.contains("throw new ClientCancellationException(\"cancelled by client\")"));
    }

    private static int count(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static void assertCheckpointBetween(
            String source,
            int start,
            int end,
            String message) {
        assertTrue(start >= 0 && end > start, "terminal stage anchors should be locatable");
        int checkpoint = source.indexOf("throwIfCancelled(sessionIdLong);", start);
        assertTrue(checkpoint > start && checkpoint < end, message);
    }
}
