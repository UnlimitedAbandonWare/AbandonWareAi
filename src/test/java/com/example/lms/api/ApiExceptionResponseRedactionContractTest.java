package com.example.lms.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.multipart.MultipartFile;

class ApiExceptionResponseRedactionContractTest {

    @Test
    void thinApiErrorResponsesDoNotReturnRawExceptionMessages() throws Exception {
        String pki = read("main/java/com/example/lms/api/PkiValidationController.java");
        String model = read("main/java/com/example/lms/api/ModelSettingsController.java");
        String soak = read("main/java/com/example/lms/api/internal/SoakApiController.java");
        String pkiStorage = read("main/java/com/example/lms/service/PkiValidationStorageService.java");
        String feedback = read("main/java/com/example/lms/api/FeedbackController.java");
        String admin = read("main/java/com/example/lms/api/AdminController.java");
        String securityAdvice = read("main/java/com/example/lms/api/ApiSecurityExceptionAdvice.java");

        assertFalse(pki.contains("new FileUploadResponse(e.getMessage(), null, false)"));
        assertFalse(pki.contains("new FileUploadResponse(com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 180), null, false)"));
        assertFalse(pki.contains("\"서버 내부 오류: \" + com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(pki.contains("publicPkiUploadError(e)"));
        assertFalse(pki.contains("\" + e.getMessage()"));
        assertFalse(model.contains("Map.of(\"message\", e.getMessage())"));
        assertFalse(model.contains("Map.of(\"message\", SafeRedactor.safeMessage(e.getMessage(), 180))"));
        assertTrue(model.contains("publicModelSettingError(e)"));
        assertFalse(model.contains("newModelId +"));
        assertFalse(model.contains("기본 모델이 '\" + newModelId"));
        assertTrue(model.contains("modelHash"));
        assertTrue(model.contains("modelLength"));
        assertFalse(soak.contains("\"rgb soak failed: \" + e.getMessage()"));
        assertFalse(soak.contains("\"rgb soak failed: \" + com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(soak.contains("publicRgbSoakError(e)"));
        assertFalse(pkiStorage.contains("\" + this.rootLocation"));
        assertTrue(pkiStorage.contains("pathHash="));
        assertFalse(feedback.contains("\"feedback error: \" + e.getMessage()"));
        assertFalse(feedback.contains("\"feedback error: \" + SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(feedback.contains("publicFeedbackError(e)"));
        assertFalse(admin.contains("\" + ioe.getMessage()"));
        assertFalse(admin.contains("\" + ex.getMessage()"));
        assertFalse(admin.contains("SafeRedactor.safeMessage(ioe.getMessage(), 180)"));
        assertFalse(admin.contains("SafeRedactor.safeMessage(ex.getMessage(), 180)"));
        assertTrue(admin.contains("publicFineTuningError("));
        assertFalse(admin.contains("\"Job ID '\" + jobId"));
        assertTrue(admin.contains("publicFineTuningJobNotFound(jobId)"));
        assertFalse(admin.contains("log.info(\"파인튜닝 요청 수신: {}\", options);"));
        assertFalse(admin.contains("log.error(\"파인튜닝 파일 처리 중 오류 발생\", ioe);"));
        assertFalse(admin.contains("log.error(\"파인튜닝 작업 생성 중 오류 발생\", ex);"));
        assertTrue(admin.contains("fineTuningOptionsSummary"));
        assertTrue(admin.contains("SafeRedactor.hashValue(String.valueOf(options))"));
        assertFalse(securityAdvice.contains("ex == null ? null : ex.getMessage()"));
        assertFalse(securityAdvice.contains("SafeRedactor.safeMessage(ex.getMessage(), 180)"));
        assertTrue(securityAdvice.contains(
                "SafeRedactor.traceLabelOrFallback(ex.getMessage(), \"\")"));

        for (String source : new String[]{pki, model, soak, feedback, admin, securityAdvice}) {
            assertTrue(source.contains("SafeRedactor.safeMessage")
                            || source.contains("SafeRedactor.hashValue")
                            || source.contains("SafeRedactor.traceLabelOrFallback"),
                    "API exception response details must pass through SafeRedactor.safeMessage or SafeRedactor.hashValue");
        }
    }

    @Test
    void apiSecurityExceptionAdviceHashesThrowableMessageInResponseBody() {
        String raw = "missing auth ownerToken=" + com.example.lms.test.SecretFixtures.openAiKey() + " at C:\\Users\\nninn\\secret.txt";
        ApiSecurityExceptionAdvice advice = new ApiSecurityExceptionAdvice();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/private/" + com.example.lms.test.SecretFixtures.openAiKey() + "");

        ResponseEntity<Map<String, Object>> response = advice.handleAuthMissing(
                new AuthenticationCredentialsNotFoundException(raw),
                request);
        Map<String, Object> body = response.getBody();
        String rendered = String.valueOf(body);

        assertEquals(401, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("unauthenticated", body.get("error"));
        assertTrue(String.valueOf(body.get("message")).startsWith("hash:"), rendered);
        assertFalse(body.containsKey("path"));
        assertTrue(String.valueOf(body.get("pathHash")).startsWith("hash:"), rendered);
        assertEquals(("/api/private/" + com.example.lms.test.SecretFixtures.openAiKey()).length(), body.get("pathLength"));
        assertFalse(rendered.contains("/api/private/" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(rendered.contains(raw));
        assertFalse(rendered.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(rendered.contains("C:\\Users\\nninn"));
        assertFalse(rendered.contains("missing auth ownerToken"));
        assertFalse(body.containsKey("exception"));
        assertFalse(rendered.contains("AuthenticationCredentialsNotFoundException"));
        assertFalse(rendered.contains("org.springframework.security"));
    }

    @Test
    void pkiValidationBadRequestResponseDoesNotExposeLocalExceptionMessage() {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\pki-validation.txt";
        com.example.lms.service.PkiValidationStorageService storageService =
                org.mockito.Mockito.mock(com.example.lms.service.PkiValidationStorageService.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid pki upload at " + rawPath))
                .when(storageService)
                .save(org.mockito.ArgumentMatchers.nullable(MultipartFile.class));
        PkiValidationController controller = new PkiValidationController(storageService);

        ResponseEntity<FileUploadResponse> response = controller.upload(null);
        String message = String.valueOf(response.getBody().message());

        assertFalse(message.contains(rawPath));
        assertFalse(message.contains("invalid pki upload"));
        assertFalse(message.contains("IllegalArgumentException"));
        assertFalse(message.contains("errorType="));
        assertTrue(message.contains("errorCode=pki_upload_failed"));
        assertTrue(message.contains("errorHash="));
        assertTrue(message.contains("errorLength="));
    }

    @Test
    void pkiValidationServerErrorResponseDoesNotExposeLocalExceptionMessage() {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\pki-runtime.txt";
        com.example.lms.service.PkiValidationStorageService storageService =
                org.mockito.Mockito.mock(com.example.lms.service.PkiValidationStorageService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("pki storage failed at " + rawPath))
                .when(storageService)
                .save(org.mockito.ArgumentMatchers.nullable(MultipartFile.class));
        PkiValidationController controller = new PkiValidationController(storageService);

        ResponseEntity<FileUploadResponse> response = controller.upload(null);
        String message = String.valueOf(response.getBody().message());

        assertFalse(message.contains(rawPath));
        assertFalse(message.contains("pki storage failed"));
        assertFalse(message.contains("RuntimeException"));
        assertFalse(message.contains("errorType="));
        assertTrue(message.contains("errorCode=pki_upload_failed"));
        assertTrue(message.contains("errorHash="));
        assertTrue(message.contains("errorLength="));
    }

    @Test
    void feedbackFailureResponseDoesNotExposeLocalExceptionMessage() {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\feedback.txt";
        com.example.lms.service.MemoryReinforcementService memoryService =
                org.mockito.Mockito.mock(com.example.lms.service.MemoryReinforcementService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("feedback write failed at " + rawPath))
                .when(memoryService)
                .applyFeedbackToRatedAssistant(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyString()
                );
        com.example.lms.service.ChatHistoryService historyService =
                org.mockito.Mockito.mock(com.example.lms.service.ChatHistoryService.class);
        com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver =
                org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);
        com.example.lms.domain.ChatSession session =
                new com.example.lms.domain.ChatSession("owned", "owner-key", "ANON");
        session.setId(7L);
        com.example.lms.domain.ChatMessage ratedMessage =
                new com.example.lms.domain.ChatMessage(session, "assistant", "assistant message");
        ratedMessage.setId(71L);
        session.setMessages(java.util.List.of(ratedMessage));
        org.mockito.Mockito.when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        org.mockito.Mockito.when(ownerKeyResolver.ownerKey()).thenReturn("owner-key");
        FeedbackController controller = new FeedbackController(memoryService, historyService, ownerKeyResolver);

        ResponseEntity<?> response = controller.feedback(new com.example.lms.dto.FeedbackDto(
                7L,
                "assistant message",
                "NEGATIVE",
                "corrected answer"
        ));
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawPath));
        assertFalse(body.contains("feedback write failed"));
        assertFalse(body.contains("RuntimeException"));
        assertFalse(body.contains("errorType="));
        assertTrue(body.contains("feedback error"));
        assertTrue(body.contains("errorCode=feedback_failed"));
        assertTrue(body.contains("errorHash="));
        assertTrue(body.contains("errorLength="));
    }

    @Test
    void modelSettingFailureResponseDoesNotExposeLocalExceptionMessage() {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\models\\private-model.txt";
        com.example.lms.service.ModelSettingsService modelSettingsService =
                org.mockito.Mockito.mock(com.example.lms.service.ModelSettingsService.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("model lookup failed at " + rawPath))
                .when(modelSettingsService)
                .changeCurrentModel(org.mockito.ArgumentMatchers.anyString());
        ModelSettingsController controller = new ModelSettingsController(modelSettingsService);

        ResponseEntity<?> response = controller.saveDefaultModel(Map.of("model", "private-model"));
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawPath));
        assertFalse(body.contains("model lookup failed"));
        assertFalse(body.contains("IllegalArgumentException"));
        assertFalse(body.contains("errorType"));
        assertTrue(body.contains("errorCode=model_setting_rejected"));
        assertTrue(body.contains("errorHash="));
        assertTrue(body.contains("errorLength="));
    }

    @Test
    void modelSettingNullPayloadReturnsBadRequestWithoutThrowableDetails() {
        com.example.lms.service.ModelSettingsService modelSettingsService =
                org.mockito.Mockito.mock(com.example.lms.service.ModelSettingsService.class);
        ModelSettingsController controller = new ModelSettingsController(modelSettingsService);

        ResponseEntity<?> response = controller.saveDefaultModel(null);
        String body = String.valueOf(response.getBody());

        assertEquals(400, response.getStatusCode().value());
        assertFalse(body.contains("NullPointerException"));
        assertTrue(body.contains("message"));
        org.mockito.Mockito.verifyNoInteractions(modelSettingsService);
    }

    @Test
    void soakRgbFailureResponseDoesNotExposeLocalExceptionMessage() {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\soak\\rgb.log";
        com.example.lms.service.soak.SoakTestService soakService =
                org.mockito.Mockito.mock(com.example.lms.service.soak.SoakTestService.class);
        com.example.lms.scheduler.TrainingJobRunner runner =
                org.mockito.Mockito.mock(com.example.lms.scheduler.TrainingJobRunner.class);
        org.springframework.beans.factory.ObjectProvider<com.example.lms.scheduler.TrainingJobRunner> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(runner);
        org.mockito.Mockito.doThrow(new RuntimeException("rgb report write failed at " + rawPath))
                .when(runner)
                .runOnce(false, "soak_api");
        com.example.lms.api.internal.SoakApiController controller =
                new com.example.lms.api.internal.SoakApiController(soakService, provider);

        ResponseEntity<?> response = controller.rgb();
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawPath));
        assertFalse(body.contains("rgb report write failed"));
        assertFalse(body.contains("RuntimeException"));
        assertFalse(body.contains("errorType="));
        assertTrue(body.contains("rgb soak failed"));
        assertTrue(body.contains("errorCode=rgb_soak_failed"));
        assertTrue(body.contains("errorHash="));
        assertTrue(body.contains("errorLength="));
    }

    @Test
    void soakRgbUnavailableResponseUsesStableErrorCode() {
        com.example.lms.service.soak.SoakTestService soakService =
                org.mockito.Mockito.mock(com.example.lms.service.soak.SoakTestService.class);
        org.springframework.beans.factory.ObjectProvider<com.example.lms.scheduler.TrainingJobRunner> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        com.example.lms.api.internal.SoakApiController controller =
                new com.example.lms.api.internal.SoakApiController(soakService, provider);

        ResponseEntity<?> response = controller.rgb();

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("ok"));
        assertEquals("training_job_runner_unavailable", body.get("error"));
        assertFalse(body.toString().contains("TrainingJobRunner not available"));
    }

    @Test
    void adminFineTuningIoFailureResponseDoesNotExposeLocalExceptionMessage() throws Exception {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\fine-tuning\\train.jsonl";
        com.example.lms.service.FineTuningService fineTuningService =
                org.mockito.Mockito.mock(com.example.lms.service.FineTuningService.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("fine-tuning file failed at " + rawPath))
                .when(fineTuningService)
                .startFineTuningJob(org.mockito.ArgumentMatchers.any());
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.startFineTuning(fineTuningOptions());
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawPath));
        assertFalse(body.contains("fine-tuning file failed"));
        assertFalse(body.contains("IOException"));
        assertFalse(body.contains("errorType"));
        assertTrue(body.contains("file_processing_failed"));
        assertTrue(body.contains("errorHash="));
        assertTrue(body.contains("errorLength="));
    }

    @Test
    void adminFineTuningIoFailureWithNullMessageReturnsStableRedactedResponse() throws Exception {
        com.example.lms.service.FineTuningService fineTuningService =
                org.mockito.Mockito.mock(com.example.lms.service.FineTuningService.class);
        org.mockito.Mockito.doThrow(new java.io.IOException((String) null))
                .when(fineTuningService)
                .startFineTuningJob(org.mockito.ArgumentMatchers.any());
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.startFineTuning(fineTuningOptions());

        assertEquals(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("file_processing_failed", body.get("error"));
        assertFalse(body.containsKey("errorType"));
        assertEquals("", body.get("errorHash"));
        assertEquals(0, body.get("errorLength"));
    }

    @Test
    void adminFineTuningJobFailureResponseDoesNotExposeLocalExceptionMessage() throws Exception {
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\fine-tuning\\job.log";
        com.example.lms.service.FineTuningService fineTuningService =
                org.mockito.Mockito.mock(com.example.lms.service.FineTuningService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("fine-tuning job failed at " + rawPath))
                .when(fineTuningService)
                .startFineTuningJob(org.mockito.ArgumentMatchers.any());
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.startFineTuning(fineTuningOptions());
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawPath));
        assertFalse(body.contains("fine-tuning job failed"));
        assertFalse(body.contains("IllegalStateException"));
        assertFalse(body.contains("errorType"));
        assertTrue(body.contains("job_create_failed"));
        assertTrue(body.contains("errorHash="));
        assertTrue(body.contains("errorLength="));
    }

    @Test
    void adminFineTuningMissingJobResponseDoesNotExposeRawJobId() {
        String rawJobId = "ftjob-C:\\Users\\nninn\\Desktop\\secret\\job-123";
        com.example.lms.service.FineTuningService fineTuningService =
                org.mockito.Mockito.mock(com.example.lms.service.FineTuningService.class);
        org.mockito.Mockito.when(fineTuningService.checkJobStatus(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.checkStatus(rawJobId);
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawJobId));
        assertFalse(body.contains("C:\\Users\\nninn"));
        assertTrue(body.contains("job_not_found"));
        assertTrue(body.contains("jobIdHash="));
        assertTrue(body.contains("jobIdLength="));
    }

    @Test
    void adminFineTuningMissingNullJobIdReturnsStableRedactedResponse() {
        com.example.lms.service.FineTuningService fineTuningService =
                org.mockito.Mockito.mock(com.example.lms.service.FineTuningService.class);
        org.mockito.Mockito.when(fineTuningService.checkJobStatus(org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(java.util.Optional.empty());
        AdminController controller = new AdminController(fineTuningService);

        ResponseEntity<?> response = controller.checkStatus(null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(404, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals("job_not_found", body.get("error"));
        assertEquals("", body.get("jobIdHash"));
        assertEquals(0, body.get("jobIdLength"));
    }

    @Test
    void diagnosticApiErrorFieldsDoNotReturnRawExceptionMessages() throws Exception {
        String integrations = read("main/java/com/example/lms/api/IntegrationController.java");
        String classpath = read("main/java/com/example/lms/api/ClasspathDiagnosticsController.java");

        assertFalse(integrations.contains("r.put(\"brave.error\", e.getMessage())"));
        assertFalse(integrations.contains("r.put(\"wiki.error\", e.getMessage())"));
        assertFalse(integrations.contains("r.put(\"vector.error\", e.getMessage())"));
        assertFalse(integrations.contains("r.put(\"llm.error\", e.getMessage())"));
        assertFalse(integrations.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertFalse(classpath.contains("t.getMessage() == null ? \"\" : t.getMessage()"));
        assertFalse(classpath.contains("t.getClass().getName() + \":\""));

        assertTrue(integrations.contains("SafeRedactor.hashValue"));
        assertTrue(classpath.contains("classpathProbeError(t)"));
        assertTrue(classpath.contains("messageHash="));
        assertTrue(classpath.contains("messageLength="));
    }

    @Test
    void smallApiFailSoftCatchesEmitNamedBreadcrumbs() throws Exception {
        String securityAdvice = read("main/java/com/example/lms/api/ApiSecurityExceptionAdvice.java");
        String brainState = read("main/java/com/example/lms/api/BrainStateAdminController.java");

        assertTrue(securityAdvice.contains("API security auth lookup skipped errorType="));
        assertTrue(securityAdvice.contains("TraceStore.put(\"api.security.suppressed.stage\", safeStage);"));
        assertTrue(securityAdvice.contains("TraceStore.put(\"api.security.suppressed.errorType\", errorType(failure));"));
        assertTrue(brainState.contains("traceSuppressed(\"brainState.fileIngestion\", ex);"));
        assertTrue(brainState.contains("TraceStore.put(\"api.brainState.suppressed.stage\", safeStage);"));
        assertTrue(brainState.contains("TraceStore.put(\"api.brainState.suppressed.errorType\", errorType);"));
        assertTrue(brainState.contains("TraceStore.put(\"api.brainState.suppressed.\" + safeStage, true);"));
    }

    @Test
    void diagnosticFailSoftCatchesEmitNamedBreadcrumbs() throws Exception {
        String classpath = read("main/java/com/example/lms/api/ClasspathDiagnosticsController.java");
        String debugEvents = read("main/java/com/example/lms/api/DebugEventsDiagnosticsController.java");

        assertTrue(classpath.contains("traceSuppressed(\"classpath.probe\", t);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.resume\", ignore);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.hello\", e);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.initial\", e);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.tail\", e);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.heartbeat\", e);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"stream.sleep\", ie);"));
        assertTrue(debugEvents.contains("traceSuppressed(\"safeList\", ignore);"));
    }

    @Test
    void thinDiagnosticControllersEmitNamedFailSoftBreadcrumbs() throws Exception {
        String desktopRouter = read("main/java/com/example/lms/api/DesktopRouterStatusBridgeController.java");
        String domainProfile = read("main/java/com/example/lms/api/DomainProfileController.java");
        String embedding = read("main/java/com/example/lms/api/EmbeddingDiagnosticsController.java");
        String integration = read("main/java/com/example/lms/api/IntegrationController.java");
        String pki = read("main/java/com/example/lms/api/PkiValidationController.java");

        assertTrue(desktopRouter.contains("traceSuppressed(\"desktopRouter.parseUri\", ex);"));
        assertTrue(domainProfile.contains("traceSuppressed(\"domainProfile.authLookup\", ignore);"));
        assertTrue(embedding.contains("traceSuppressed(\"embedding.adminToken\", ignore);"));
        assertTrue(integration.contains("traceSuppressed(\"integration.brave\", e);"));
        assertTrue(integration.contains("traceSuppressed(\"integration.wiki\", e);"));
        assertTrue(integration.contains("traceSuppressed(\"integration.vector\", e);"));
        assertTrue(integration.contains("traceSuppressed(\"integration.llm\", e);"));
        assertTrue(pki.contains("traceSuppressed(\"pkiUpload.badRequest\", e);"));
        assertTrue(pki.contains("traceSuppressed(\"pkiUpload.runtime\", e);"));
        assertTrue(desktopRouter.contains("private static String errorType(RuntimeException failure)"));
        assertTrue(desktopRouter.contains("return \"invalid_url\";"));
    }

    @Test
    void integrationDiagnosticsDoNotExposeRawProviderExceptionMessages() {
        String rawDetail = "brave failed at C:\\Users\\nninn\\Desktop\\secret\\provider.log";
        com.example.lms.service.web.BraveSearchService brave =
                org.mockito.Mockito.mock(com.example.lms.service.web.BraveSearchService.class);
        org.mockito.Mockito.when(brave.searchSnippets(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException(rawDetail));
        com.example.lms.service.web.MediaWikiClient wiki =
                org.mockito.Mockito.mock(com.example.lms.service.web.MediaWikiClient.class);
        dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> embeddingStore =
                org.mockito.Mockito.mock(dev.langchain4j.store.embedding.EmbeddingStore.class);
        dev.langchain4j.model.chat.ChatModel chatModel =
                org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        IntegrationController controller = new IntegrationController(brave, wiki, embeddingStore, chatModel);

        ResponseEntity<Map<String, Object>> response = controller.integrations();
        String body = String.valueOf(response.getBody());

        assertFalse(body.contains(rawDetail));
        assertFalse(body.contains("C:\\Users\\nninn"));
        assertFalse(body.contains("brave failed"));
        assertFalse(body.contains("RuntimeException"));
        assertFalse(body.contains("errorType="));
        assertTrue(body.contains("brave.error"));
        assertTrue(body.contains("errorCode=integration_check_failed"));
        assertTrue(body.contains("errorHash"));
        assertTrue(body.contains("errorLength"));
    }

    @Test
    void classpathDiagnosticsDoNotExposeRawCodeSourceLocation() {
        Map<String, Object> response = new ClasspathDiagnosticsController().classpath();
        String body = String.valueOf(response);

        assertFalse(body.contains("location="));
        assertFalse(body.contains("file:/"));
        assertTrue(body.contains("locationHash"));
        assertTrue(body.contains("locationLength"));
    }

    @Test
    void classpathDiagnosticsDoNotExposeRawProbeExceptionMessage() throws Exception {
        ClasspathDiagnosticsController controller = new ClasspathDiagnosticsController();
        Method probe = ClasspathDiagnosticsController.class.getDeclaredMethod("probe", String.class);
        probe.setAccessible(true);

        String rawClassName = "C:\\Users\\nninn\\Desktop\\secret\\ownerToken=" + com.example.lms.test.SecretFixtures.openAiKey() + "";
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) probe.invoke(controller, rawClassName);
        String error = String.valueOf(response.get("error"));

        assertFalse(error.contains(rawClassName));
        assertFalse(error.contains("C:\\Users"));
        assertFalse(error.contains("ownerToken"));
        assertFalse(error.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(error.contains("ClassNotFoundException"));
        assertTrue(error.contains("class_not_found"));
        assertTrue(error.contains("messageHash="));
        assertTrue(error.contains("messageLength="));
    }

    @Test
    void thinApiControllersDoNotLogRawThrowableObjects() throws Exception {
        String feedback = read("main/java/com/example/lms/api/FeedbackController.java");
        String model = read("main/java/com/example/lms/api/ModelSettingsController.java");
        String admin = read("main/java/com/example/lms/api/AdminController.java");

        assertFalse(feedback.contains("log.error(\"feedback error\", e);"));
        assertFalse(model.contains("log.error(\"[ModelSettings] 모델 저장 중 서버 오류 발생\", e);"));
        assertFalse(admin.contains("log.error(\"파인튜닝 파일 처리 중 오류 발생\", ioe);"));
        assertFalse(admin.contains("log.error(\"파인튜닝 작업 생성 중 오류 발생\", ex);"));

        assertFalse(feedback.contains("SafeRedactor.safeMessage(String.valueOf(e)"));
        assertTrue(feedback.contains("[AWX][feedback] failed type={} errorHash={} errorLength={}"));
        assertTrue(feedback.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(feedback.contains("messageLength(e)"));
        assertFalse(model.contains("SafeRedactor.safeMessage(String.valueOf(e)"));
        assertFalse(model.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(model.contains("[ModelSettings] model save rejected errorHash={} errorLength={}"));
        assertTrue(model.contains("[ModelSettings] model save server error type={} errorHash={} errorLength={}"));
        assertTrue(model.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(model.contains("messageLength(e)"));
        assertFalse(admin.contains("SafeRedactor.safeMessage(String.valueOf(ioe)"));
        assertFalse(admin.contains("SafeRedactor.safeMessage(String.valueOf(ex)"));
        assertTrue(admin.contains("[AWX][fine-tuning] file-processing-failed type={} errorHash={} errorLength={}"));
        assertTrue(admin.contains("[AWX][fine-tuning] job-create-failed type={} errorHash={} errorLength={}"));
        assertTrue(admin.contains("SafeRedactor.hashValue(messageOf(ioe))"));
        assertTrue(admin.contains("SafeRedactor.hashValue(messageOf(ex))"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static com.example.lms.dto.FineTuningOptionsDto fineTuningOptions() {
        return new com.example.lms.dto.FineTuningOptionsDto(
                0.4,
                0.2,
                1,
                42L,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                new com.example.lms.dto.FineTuningOptionsDto.QualityWeightingDto(1.0, 0.0)
        );
    }
}
