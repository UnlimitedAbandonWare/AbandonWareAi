package com.example.lms.api;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.FeedbackDto;
import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class FeedbackControllerInputTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void nullFeedbackBodyReturnsStableBadRequestBeforeServiceCall() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        FeedbackController controller = controller(memoryService, mock(ChatHistoryService.class), mock(ClientOwnerKeyResolver.class));

        var response = controller.feedback(null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("missing_feedback", response.getBody());
        verifyNoInteractions(memoryService);
    }

    @Test
    void foreignGuestSessionFeedbackIsRejectedBeforeMemoryWrite() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        ChatSession session = new ChatSession("foreign", "owner-a", "ANON");
        session.setId(7L);

        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");

        var response = controller.feedback(
                new FeedbackDto(7L, "assistant message", "NEGATIVE", "corrected"),
                null);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("session_forbidden", response.getBody());
        assertEquals(Boolean.TRUE, TraceStore.get("api.feedback.rejected"));
        assertEquals(1L, TraceStore.get("api.feedback.rejected.count"));
        assertEquals("session_forbidden", TraceStore.get("api.feedback.skipped.reason"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue("7"), TraceStore.get("api.feedback.sessionHash"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("assistant message"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("corrected"));
        verify(historyService).getSessionWithMessages(7L);
        verifyNoInteractions(memoryService);
    }

    @Test
    void nullAndUnknownRatingsReturn400BeforeAnyCollaboratorCall() throws Exception {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        MockMvc mvc = validationMvc(memoryService, historyService, ownerKeyResolver, mutationGuard);

        mvc.perform(post("/api/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"message\":\"answer\",\"rating\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"message\":\"answer\",\"rating\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(memoryService, historyService, ownerKeyResolver, mutationGuard);
    }

    @Test
    void oversizedMessageAndCorrectionReturn400BeforeAnyCollaboratorCall() throws Exception {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        MockMvc mvc = validationMvc(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ObjectMapper mapper = new ObjectMapper();

        mvc.perform(post("/api/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new FeedbackDto(7L, "m".repeat(4001), "POSITIVE", null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new FeedbackDto(7L, "answer", "NEGATIVE", "c".repeat(4001)))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(memoryService, historyService, ownerKeyResolver, mutationGuard);
    }

    @Test
    void exact4000CharacterBoundariesReachAuthorizedRatedRecord() throws Exception {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        MockMvc mvc = validationMvc(memoryService, historyService, ownerKeyResolver, mutationGuard);
        String answer = "m".repeat(4000);
        String correction = "c".repeat(4000);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(11L, answer));

        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(mutationGuard.accept(any(), eq(7L), eq(11L), eq(answer), eq("POSITIVE"), eq(correction)))
                .thenReturn(FeedbackMutationGuard.Decision.ACCEPT);

        mvc.perform(post("/api/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                new FeedbackDto(7L, answer, "POSITIVE", correction))))
                .andExpect(status().isOk());

        verify(memoryService).applyFeedbackToRatedAssistant(
                "7", 11L, contentHash(answer), answer, true, correction);
    }

    @Test
    void adminOwnedSessionAcceptsOnlyExactAuthenticatedAdministrator() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        Administrator owner = new Administrator("admin-a", "unused", "Admin A");
        ChatSession session = new ChatSession("admin feedback", owner);
        session.setId(9L);
        session.setMessages(List.of(assistant(21L, "stored answer")));
        when(historyService.getSessionWithMessages(9L)).thenReturn(session);

        var accepted = controller.feedback(
                new FeedbackDto(9L, "stored answer", "POSITIVE", null),
                new TestingAuthenticationToken("admin-a", "unused", "ROLE_ADMIN"));
        var denied = controller.feedback(
                new FeedbackDto(9L, "stored answer", "POSITIVE", null),
                new TestingAuthenticationToken("admin-b", "unused", "ROLE_ADMIN"));

        assertEquals(200, accepted.getStatusCode().value());
        assertEquals(403, denied.getStatusCode().value());
        verify(memoryService, times(1)).applyFeedbackToRatedAssistant(
                "9", 21L, contentHash("stored answer"), "stored answer", true, null);
        verifyNoInteractions(ownerKeyResolver);
    }

    @Test
    void durableMessageIdTargetsOnlyThatAssistantRecord() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(
                7L,
                "owner-a",
                assistant(51L, "answer A"),
                assistant(52L, "answer B"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(mutationGuard.accept(any(), eq(7L), eq(52L), eq("answer B"), eq("POSITIVE"), eq(null)))
                .thenReturn(FeedbackMutationGuard.Decision.ACCEPT);

        var response = controller.feedback(
                new FeedbackDto(7L, 52L, "answer B", "POSITIVE", null),
                null);

        assertEquals(200, response.getStatusCode().value());
        verify(memoryService).applyFeedbackToRatedAssistant(
                "7",
                52L,
                com.example.lms.trace.SafeRedactor.hashValue("answer B"),
                "answer B",
                true,
                null);
    }

    @Test
    void unknownDurableMessageIdReturnsRatedRecordNotFoundWithoutMutation() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(51L, "same answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(7L, 999L, "same answer", "NEGATIVE", null),
                null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("rated_record_not_found", response.getBody());
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void durableMessageIdRejectsNonAssistantRoleWithoutMutation() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatMessage userMessage = new ChatMessage(null, "user", "same answer");
        userMessage.setId(41L);
        ChatSession session = anonymousSession(7L, "owner-a", userMessage);
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(7L, 41L, "same answer", "NEGATIVE", null),
                null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("rated_record_not_found", response.getBody());
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void durableMessageIdCannotResolveAnAssistantFromAnotherSession() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession authorizedSession = anonymousSession(7L, "owner-a", assistant(51L, "local answer"));
        ChatSession foreignSession = anonymousSession(8L, "owner-a", assistant(81L, "foreign answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(authorizedSession);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(
                        7L,
                        foreignSession.getMessages().get(0).getId(),
                        "foreign answer",
                        "POSITIVE",
                        null),
                null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("rated_record_not_found", response.getBody());
        verify(historyService).getSessionWithMessages(7L);
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void durableAssistantIdWithMismatchedContentRetainsConflictContract() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(51L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(7L, 51L, "different answer", "NEGATIVE", null),
                null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("feedback_target_mismatch", response.getBody());
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void recordDeletedAfterAdmissionReturnsNotFoundAndAbortsReplayGuard() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(51L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(mutationGuard.accept(any(), eq(7L), eq(51L), eq("stored answer"), eq("POSITIVE"), eq(null)))
                .thenReturn(FeedbackMutationGuard.Decision.ACCEPT);
        doThrow(MemoryReinforcementService.RatedRecordNotFoundException.notFound())
                .when(memoryService)
                .applyFeedbackToRatedAssistant(
                        "7",
                        51L,
                        contentHash("stored answer"),
                        "stored answer",
                        true,
                        null);

        var response = controller.feedback(
                new FeedbackDto(7L, 51L, "stored answer", "POSITIVE", null),
                null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("rated_record_not_found", response.getBody());
        verify(mutationGuard).abort(
                any(), eq(7L), eq(51L), eq("stored answer"), eq("POSITIVE"), eq(null));
        verify(mutationGuard, never()).commit(
                any(), eq(7L), eq(51L), eq("stored answer"), eq("POSITIVE"), eq(null));
    }

    @Test
    void feedbackMustMatchAssistantRecordInsideAuthorizedSession() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(31L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(7L, "different answer", "NEGATIVE", "correction"),
                null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("feedback_target_mismatch", response.getBody());
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void userRoleWithSameContentCannotBeRatedAsAssistantAnswer() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatMessage userMessage = new ChatMessage(null, "user", "same text");
        userMessage.setId(41L);
        ChatSession session = anonymousSession(7L, "owner-a", userMessage);
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");

        var response = controller.feedback(
                new FeedbackDto(7L, "same text", "NEGATIVE", null),
                null);

        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(memoryService, mutationGuard);
    }

    @Test
    void duplicateMatchingAnswersBindTheLastDurableAssistantRecord() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackMutationGuard mutationGuard = mock(FeedbackMutationGuard.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver, mutationGuard);
        ChatSession session = anonymousSession(
                7L,
                "owner-a",
                assistant(51L, "same answer"),
                assistant(52L, "same answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(mutationGuard.accept(any(), eq(7L), eq(52L), eq("same answer"), eq("POSITIVE"), eq(null)))
                .thenReturn(FeedbackMutationGuard.Decision.ACCEPT);

        var response = controller.feedback(
                new FeedbackDto(7L, "same answer", "POSITIVE", null),
                null);

        assertEquals(200, response.getStatusCode().value());
        verify(mutationGuard).accept(any(), eq(7L), eq(52L), eq("same answer"), eq("POSITIVE"), eq(null));
        verify(memoryService).applyFeedbackToRatedAssistant(
                "7", 52L, contentHash("same answer"), "same answer", true, null);
    }

    @Test
    void identicalReplayMutatesOnceWhileChangedRatingOrCorrectionConflicts() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(61L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        FeedbackDto accepted = new FeedbackDto(7L, "stored answer", "POSITIVE", "correction-a");

        var first = controller.feedback(accepted, null);
        var replay = controller.feedback(accepted, null);
        var ratingConflict = controller.feedback(
                new FeedbackDto(7L, "stored answer", "NEGATIVE", "correction-a"),
                null);
        var correctionConflict = controller.feedback(
                new FeedbackDto(7L, "stored answer", "POSITIVE", "correction-b"),
                null);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(409, ratingConflict.getStatusCode().value());
        assertEquals(409, correctionConflict.getStatusCode().value());
        verify(memoryService, times(1)).applyFeedbackToRatedAssistant(
                "7",
                61L,
                contentHash("stored answer"),
                "stored answer",
                true,
                "correction-a");
    }

    @Test
    void duplicateWhileFirstMutationIsPendingReturnsRetryableConflictAndNeverReportsReplay() throws Exception {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(71L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        CountDownLatch enteredMutation = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredMutation.countDown();
            assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            return null;
        }).when(memoryService).applyFeedbackToRatedAssistant(
                "7", 71L, contentHash("stored answer"), "stored answer", true, null);
        FeedbackDto request = new FeedbackDto(7L, "stored answer", "POSITIVE", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<org.springframework.http.ResponseEntity<?>> first =
                    executor.submit(() -> controller.feedback(request, null));
            assertTrue(enteredMutation.await(5, TimeUnit.SECONDS));

            var inProgress = controller.feedback(request, null);
            assertEquals(409, inProgress.getStatusCode().value());
            assertEquals("feedback_in_progress", inProgress.getBody());

            releaseMutation.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(200, controller.feedback(request, null).getStatusCode().value());
            verify(memoryService, times(1)).applyFeedbackToRatedAssistant(
                    "7", 71L, contentHash("stored answer"), "stored answer", true, null);
        } finally {
            releaseMutation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedMutationAbortsPendingAdmissionSoRetryCanSucceed() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FeedbackController controller = controller(memoryService, historyService, ownerKeyResolver);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(81L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        doThrow(new IllegalStateException("bounded failure"))
                .doNothing()
                .when(memoryService)
                .applyFeedbackToRatedAssistant(
                        "7",
                        81L,
                        contentHash("stored answer"),
                        "stored answer",
                        true,
                        "correction");
        FeedbackDto request = new FeedbackDto(7L, "stored answer", "POSITIVE", "correction");

        var failed = controller.feedback(request, null);
        var retried = controller.feedback(request, null);

        assertEquals(400, failed.getStatusCode().value());
        assertEquals(200, retried.getStatusCode().value());
        verify(memoryService, times(2)).applyFeedbackToRatedAssistant(
                "7",
                81L,
                contentHash("stored answer"),
                "stored answer",
                true,
                "correction");
    }

    @Test
    void durableCommitFailureDoesNotAbortOrRepeatCompletedMutation() {
        MemoryReinforcementService memoryService = mock(MemoryReinforcementService.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        FailingCommitReceiptStore receiptStore = new FailingCommitReceiptStore();
        FeedbackMutationGuard mutationGuard = new FeedbackMutationGuard(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
                receiptStore);
        FeedbackController controller = controller(
                memoryService,
                historyService,
                ownerKeyResolver,
                mutationGuard);
        ChatSession session = anonymousSession(7L, "owner-a", assistant(91L, "stored answer"));
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        FeedbackDto request = new FeedbackDto(7L, "stored answer", "POSITIVE", null);

        var failedReceipt = controller.feedback(request, null);
        var retry = controller.feedback(request, null);

        assertEquals(503, failedReceipt.getStatusCode().value());
        assertEquals("feedback_receipt_unavailable", failedReceipt.getBody());
        assertEquals(409, retry.getStatusCode().value());
        assertEquals("feedback_in_progress", retry.getBody());
        verify(memoryService, times(1)).applyFeedbackToRatedAssistant(
                "7", 91L, contentHash("stored answer"), "stored answer", true, null);
    }

    private static MockMvc validationMvc(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver,
            FeedbackMutationGuard mutationGuard) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return standaloneSetup(controller(memoryService, historyService, ownerKeyResolver, mutationGuard))
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static ChatSession anonymousSession(long id, String ownerKey, ChatMessage... messages) {
        ChatSession session = new ChatSession("feedback", ownerKey, "ANON");
        session.setId(id);
        session.setMessages(List.of(messages));
        for (ChatMessage message : messages) {
            message.setSession(session);
        }
        return session;
    }

    private static ChatMessage assistant(long id, String content) {
        ChatMessage message = new ChatMessage(null, "assistant", content);
        message.setId(id);
        return message;
    }

    private static String contentHash(String content) {
        return com.example.lms.trace.SafeRedactor.hashValue(content);
    }

    private static FeedbackController controller(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        return controller(memoryService, historyService, ownerKeyResolver, new FeedbackMutationGuard());
    }

    private static FeedbackController controller(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver,
            FeedbackMutationGuard mutationGuard) {
        return new FeedbackController(memoryService, historyService, ownerKeyResolver, mutationGuard);
    }

    private static final class FailingCommitReceiptStore implements DurableLifecycleReceiptStore {
        private Receipt latest;

        @Override
        public Optional<Receipt> find(Lifecycle lifecycle, String subjectHash) {
            return latest != null
                    && latest.lifecycle() == lifecycle
                    && latest.subjectHash().equals(subjectHash)
                    ? Optional.of(latest)
                    : Optional.empty();
        }

        @Override
        public Optional<Receipt> latest(Lifecycle lifecycle) {
            return latest != null && latest.lifecycle() == lifecycle
                    ? Optional.of(latest)
                    : Optional.empty();
        }

        @Override
        public void record(Receipt receipt) {
            if (receipt.state() == State.COMMITTED) {
                throw new IllegalStateException("fixture commit failure");
            }
            latest = receipt;
        }
    }
}
