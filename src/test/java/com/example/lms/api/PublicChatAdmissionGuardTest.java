package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicChatAdmissionGuardTest {

    private static final String OWNER_A = "a".repeat(64);
    private static final String OWNER_B = "b".repeat(64);
    private static final String OWNER_C = "c".repeat(64);

    @Test
    void perOwnerAndGlobalLimitsReleaseExactlyOnce() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(3, 2, 8);

        PublicChatAdmissionGuard.Lease first = guard.tryAcquire(OWNER_A).orElseThrow();
        PublicChatAdmissionGuard.Lease second = guard.tryAcquire(OWNER_A).orElseThrow();
        assertTrue(guard.tryAcquire(OWNER_A).isEmpty());
        PublicChatAdmissionGuard.Lease third = guard.tryAcquire(OWNER_B).orElseThrow();
        assertTrue(guard.tryAcquire(OWNER_C).isEmpty());

        first.close();
        first.close();
        PublicChatAdmissionGuard.Lease replacement = guard.tryAcquire(OWNER_C).orElseThrow();
        assertEquals(3, guard.activeLeaseCountForTest());

        second.close();
        third.close();
        replacement.close();
        assertEquals(0, guard.activeLeaseCountForTest());
        assertEquals(0, guard.activeOwnerCountForTest());
        assertEquals(3, guard.availableGlobalPermitsForTest());
    }

    @Test
    void boundedOwnerMapNeverEvictsAnActiveOwner() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(4, 2, 1);
        PublicChatAdmissionGuard.Lease active = guard.tryAcquire(OWNER_A).orElseThrow();

        assertTrue(guard.tryAcquire(OWNER_B).isEmpty());
        assertEquals(1, guard.activeOwnerCountForTest());

        active.close();
        PublicChatAdmissionGuard.Lease next = guard.tryAcquire(OWNER_B).orElseThrow();
        next.close();
    }

    @Test
    void invalidOrRawOwnerIdentityFailsClosed() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(2, 1, 2);

        assertTrue(guard.tryAcquire(null).isEmpty());
        assertTrue(guard.tryAcquire("raw-owner-key").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new PublicChatAdmissionGuard(0, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicChatAdmissionGuard(2, 3, 2));
    }

    @Test
    void allGenerationEntrypointsRejectBeforeHistoryRunAttachmentOrModelWork() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(1, 1, 4);
        PublicChatAdmissionGuard.Lease held = guard.tryAcquire(OWNER_A).orElseThrow();
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatService chat = mock(ChatService.class);
        ChatRunRegistry runs = mock(ChatRunRegistry.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ChatApiController controller = controller(guard, history, chat, runs, attachments);
        ChatRequestDto request = ChatRequestDto.builder().message("hello").build();

        try {
            assertAdmissionRejection(() -> controller.chatSync(request, null, new MockHttpServletRequest()));
            assertAdmissionRejection(() -> controller.chat(request, null, new MockHttpServletRequest()));
            assertAdmissionRejection(() -> controller.chatStream(
                    request, false, false, null, new MockHttpServletRequest()));
            verifyNoInteractions(history, chat, runs, attachments);
        } finally {
            held.close();
        }
    }

    @Test
    void reactiveAdaptiveWorkReleasesOnSuccessAndError() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(1, 1, 4);
        AdaptiveTranslationService adaptive = mock(AdaptiveTranslationService.class);
        ChatApiController controller = controller(
                guard,
                mock(ChatHistoryService.class),
                mock(ChatService.class),
                mock(ChatRunRegistry.class),
                mock(AttachmentService.class));
        ReflectionTestUtils.setField(controller, "adaptiveService", adaptive);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("translate")
                .useAdaptive(true)
                .build();

        when(adaptive.translate("translate", "ko", "en")).thenReturn(Mono.just("translated"));
        assertEquals("translated", controller.chat(request, null, new MockHttpServletRequest())
                .block(Duration.ofSeconds(2)).getBody().getContent());
        closeFreshLease(guard, ownerHash("owner-b"));

        when(adaptive.translate("translate", "ko", "en"))
                .thenReturn(Mono.error(new IllegalStateException("synthetic")));
        assertThrows(RuntimeException.class, () -> controller
                .chat(request, null, new MockHttpServletRequest())
                .block(Duration.ofSeconds(2)));
        closeFreshLease(guard, ownerHash("owner-b"));
    }

    @Test
    void sseWorkerFailureReleasesButExactAttachDoesNotConsumeAdmission() {
        PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(1, 1, 4);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatRunRegistry runs = mock(ChatRunRegistry.class);
        when(runs.interactiveSource(nullable(ChatRunRegistry.InteractiveClient.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ChatApiController controller = controller(
                guard, history, mock(ChatService.class), runs, mock(AttachmentService.class));
        when(history.startNewSession(
                anyString(), anyString(), nullable(String.class), anyString(), any()))
                .thenThrow(new IllegalStateException("synthetic-session-failure"));

        controller.chatStream(
                        ChatRequestDto.builder().message("hello").build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));
        closeFreshLease(guard, ownerHash("owner-b"));

        PublicChatAdmissionGuard.Lease held = guard.tryAcquire(OWNER_A).orElseThrow();
        try {
            ChatSession session = new ChatSession("owned", "owner-b", "ANON");
            session.setId(7L);
            when(history.getSessionWithMessages(7L)).thenReturn(session);
            when(runs.attachInteractiveExact(7L, "run-token")).thenReturn(Optional.of(Flux.empty()));
            MockHttpServletRequest attachRequest = new MockHttpServletRequest();
            attachRequest.addHeader("X-Chat-Run-Token", "run-token");

            assertTrue(controller.chatStream(
                            ChatRequestDto.builder().sessionId(7L).message("").build(),
                            true,
                            false,
                            null,
                            attachRequest)
                    .collectList()
                    .block(Duration.ofSeconds(2))
                    .isEmpty());
        } finally {
            held.close();
        }
    }

    private static ChatApiController controller(
            PublicChatAdmissionGuard guard,
            ChatHistoryService history,
            ChatService chat,
            ChatRunRegistry runs,
            AttachmentService attachments) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllSettings()).thenReturn(Map.of());
        ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
        when(owner.ownerKey()).thenReturn("owner-b");
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", new PublicRequestBudgetGuard());
        ReflectionTestUtils.setField(controller, "publicChatAdmissionGuard", guard);
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        ReflectionTestUtils.setField(controller, "ownerKeyResolver", owner);
        ReflectionTestUtils.setField(controller, "historyService", history);
        ReflectionTestUtils.setField(controller, "chatService", chat);
        ReflectionTestUtils.setField(controller, "runRegistry", runs);
        ReflectionTestUtils.setField(controller, "attachmentService", attachments);
        return controller;
    }

    private static void assertAdmissionRejection(org.junit.jupiter.api.function.Executable executable) {
        PublicChatAdmissionGuard.Rejection rejection =
                assertThrows(PublicChatAdmissionGuard.Rejection.class, executable);
        assertEquals(429, rejection.status().value());
        assertEquals("chat_admission_exceeded", rejection.reasonCode());
    }

    private static String ownerHash(String ownerKey) {
        return AttachmentOwnerIdentity.forActor("anonymousUser", ownerKey).hash();
    }

    private static void closeFreshLease(PublicChatAdmissionGuard guard, String ownerHash) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            Optional<PublicChatAdmissionGuard.Lease> lease = guard.tryAcquire(ownerHash);
            if (lease.isPresent()) {
                lease.orElseThrow().close();
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        } while (System.nanoTime() < deadline);
        fail("terminal work must release admission within the bounded wait");
    }
}
