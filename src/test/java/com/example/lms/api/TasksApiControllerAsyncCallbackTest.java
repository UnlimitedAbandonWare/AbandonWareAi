package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.integrations.n8n.N8nNotifier;
import com.example.lms.integrations.n8n.N8nProps;
import com.example.lms.jobs.JobService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TasksApiControllerAsyncCallbackTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void syncRejectsMissingRequestWithoutCallingChatService() {
        ChatService chatService = mock(ChatService.class);
        InlineJobService jobService = new InlineJobService();
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<?> response = controller.askSync(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(chatService);
    }

    @Test
    void syncRejectsBlankMessageWithoutCallingChatService() {
        ChatService chatService = mock(ChatService.class);
        InlineJobService jobService = new InlineJobService();
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<?> response = controller.askSync(new TasksApiController.TaskAskRequest(
                "   ", null, null, null, 42L, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(chatService);
    }

    @Test
    void asyncCallbackPayloadToleratesNullChatResponseFields() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.continueChat(any(ChatRequestDto.class)))
                .thenReturn(ChatResult.of(null, null, false));
        InlineJobService jobService = new InlineJobService();
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<Map<String, String>> response = controller.askAsync(new TasksApiController.TaskAskRequest(
                "hello", null, null, null, 42L, null, "https://callback.example/hook"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(notifier.payload);
        assertEquals("job-1", notifier.payload.get("taskId"));
        assertEquals("", notifier.payload.get("content"));
        assertEquals("", notifier.payload.get("modelUsed"));
        assertEquals(Boolean.FALSE, notifier.payload.get("ragUsed"));
    }

    @Test
    void asyncRejectsMissingRequestWithoutEnqueueing() {
        ChatService chatService = mock(ChatService.class);
        InlineJobService jobService = new InlineJobService();
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<Map<String, String>> response = controller.askAsync(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("bad_request", response.getBody().get("error"));
        assertEquals("missing_request", response.getBody().get("code"));
        assertEquals(0, jobService.enqueueCalls);
        assertEquals(0, jobService.executeCalls);
        assertNull(notifier.payload);
    }

    @Test
    void asyncRejectsBlankMessageWithoutEnqueueing() {
        ChatService chatService = mock(ChatService.class);
        InlineJobService jobService = new InlineJobService();
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<Map<String, String>> response = controller.askAsync(new TasksApiController.TaskAskRequest(
                "", null, null, null, 42L, null, "https://callback.example/hook"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("bad_request", response.getBody().get("error"));
        assertEquals("missing_message", response.getBody().get("code"));
        assertEquals(0, jobService.enqueueCalls);
        assertEquals(0, jobService.executeCalls);
        assertNull(notifier.payload);
        verifyNoInteractions(chatService);
    }

    @Test
    void asyncReturnsServerErrorWhenJobIdIsMissing() {
        ChatService chatService = mock(ChatService.class);
        InlineJobService jobService = new InlineJobService();
        jobService.nextJobId = null;
        CapturingNotifier notifier = new CapturingNotifier();
        TasksApiController controller = new TasksApiController(chatService, jobService, notifier);

        ResponseEntity<Map<String, String>> response = controller.askAsync(new TasksApiController.TaskAskRequest(
                "hello", null, null, null, 42L, null, null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("job_enqueue_failed", response.getBody().get("error"));
        assertEquals(1, jobService.enqueueCalls);
        assertEquals(0, jobService.executeCalls);
        assertNull(notifier.payload);
        assertEquals(Boolean.TRUE, TraceStore.get("api.tasks.async.jobEnqueueFailed"));
        assertEquals(1L, TraceStore.get("api.tasks.async.jobEnqueueFailed.count"));
        assertEquals("job_enqueue_failed", TraceStore.get("api.tasks.async.skipped.reason"));
        assertEquals(5, TraceStore.get("api.tasks.async.messageLength"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue("42"),
                TraceStore.get("api.tasks.async.sessionHash"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("hello"));
    }

    private static final class InlineJobService implements JobService {
        private String nextJobId = "job-1";
        private int enqueueCalls;
        private int executeCalls;

        @Override
        public String enqueue(String payload) {
            enqueueCalls++;
            return nextJobId;
        }

        @Override
        public String enqueue(String jobType, Object payload, Map<String, Object> metadata, String correlationId) {
            enqueueCalls++;
            return nextJobId;
        }

        @Override
        public <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess) {
            executeCalls++;
            onSuccess.accept(work.get());
        }

        @Override
        public String status(String jobId) {
            return "SUCCEEDED";
        }
    }

    private static final class CapturingNotifier extends N8nNotifier {
        private Map<String, Object> payload;

        private CapturingNotifier() {
            super(WebClient.builder(), new N8nProps());
        }

        @Override
        public void notify(String callbackUrl, Map<String, Object> payload) {
            this.payload = payload;
        }
    }
}
