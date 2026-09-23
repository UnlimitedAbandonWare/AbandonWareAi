package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.security.AdminTokenGuardInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningControllerCurationDisabledTest {

    @Test
    void disabledCurationReturnsServiceUnavailableAndAppliedFalse() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();
        KnowledgeDelta empty = new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);
        when(curation.ingestWithResult(any(LearningEvent.class)))
                .thenReturn(new GeminiCurationService.CurationResult(false, "curation_disabled", empty));

        var response = controller.ingest(event(), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("applied"));
        assertEquals("curation_disabled", body.get("disabledReason"));
        assertEquals(0, body.get("triples"));
        assertEquals(0, body.get("rules"));
        assertEquals(0, body.get("memories"));
    }

    @Test
    void ingestRedactsUnsafeCurationDisabledReasonInResponse() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();
        KnowledgeDelta empty = new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        String unsafeReason = "ownerToken=" + "sk-" + "learningcontroller1234567890";

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);
        when(curation.ingestWithResult(any(LearningEvent.class)))
                .thenReturn(new GeminiCurationService.CurationResult(false, unsafeReason, empty));

        var response = controller.ingest(event(), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        String bodyText = String.valueOf(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertTrue(String.valueOf(body.get("disabledReason")).startsWith("hash:"));
        assertFalse(bodyText.contains("ownerToken"));
        assertFalse(bodyText.contains("learningcontroller1234567890"));
    }

    @Test
    void ingestNullBodyReturnsBadRequestBeforeCurationCall() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);

        var response = controller.ingest(null, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("bad_request", body.get("error"));
        assertEquals("missing_learning_event", body.get("disabledReason"));
        verifyNoInteractions(curation);
    }

    @Test
    void tuningStartShimNullDoesNotThrowOrExposeRequestFields() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();
        TuningJobRequest tuningRequest = new TuningJobRequest(
                "gs://private-bucket/train-ownerToken-secret.jsonl",
                "gemini-private-model",
                "private-suffix",
                1);

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);

        var response = controller.startTuning(tuningRequest, request);

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertNotNull(response.getBody());
        String bodyText = String.valueOf(response.getBody());
        assertFalse(bodyText.contains("private-bucket"));
        assertFalse(bodyText.contains("ownerToken-secret"));
        assertFalse(bodyText.contains("gemini-private-model"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("tuning_job_unavailable", body.get("error"));
        assertEquals("start_not_implemented", body.get("disabledReason"));
    }

    @Test
    void tuningStartNullBodyReturnsBadRequestBeforeServiceCall() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);

        var response = controller.startTuning(null, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("bad_request", body.get("error"));
        assertEquals("missing_tuning_request", body.get("disabledReason"));
        verifyNoInteractions(curation, batch);
    }

    @Test
    void tuningStatusShimNullDoesNotReturnBlankBodyOrExposeRawJobId() {
        GeminiCurationService curation = mock(GeminiCurationService.class);
        GeminiBatchService batch = mock(GeminiBatchService.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        LearningController controller = new LearningController(curation, batch, guard);
        MockHttpServletRequest request = new MockHttpServletRequest();
        String rawJobId = "gemini-job-C:\\Users\\nninn\\Desktop\\secret\\job-123";

        when(guard.isPresentedTokenAuthorized(request)).thenReturn(true);

        var response = controller.getJobStatus(rawJobId, request);

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertNotNull(response.getBody());
        String bodyText = String.valueOf(response.getBody());
        assertFalse(bodyText.contains(rawJobId));
        assertFalse(bodyText.contains("C:\\Users\\nninn"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("tuning_job_unavailable", body.get("error"));
        assertEquals("status_not_implemented", body.get("disabledReason"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(rawJobId), body.get("jobIdHash"));
        assertEquals(rawJobId.length(), body.get("jobIdLength"));
    }

    private static LearningEvent event() {
        return new LearningEvent("s1", "query", "answer", List.of(), List.of(), 1.0d, 0.0d);
    }
}
