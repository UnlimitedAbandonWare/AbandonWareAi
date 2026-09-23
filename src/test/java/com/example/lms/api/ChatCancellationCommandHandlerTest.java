package com.example.lms.api;

import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCancellationCommandHandlerTest {

    private final ChatCancellationCommandHandler handler = new ChatCancellationCommandHandler();

    @Test
    void missingSessionAndTokenReturnStableNonRevealingOutcomesWithoutRegistryCall() {
        ChatRunRegistry registry = mock(ChatRunRegistry.class);

        ChatCancellationCommandHandler.Result missingSession = handler.cancel(
                null, "run", () -> true, registry, () -> { });
        ChatCancellationCommandHandler.Result missingToken = handler.cancel(
                42L, null, () -> true, registry, () -> { });

        assertEquals("session_id_required", missingSession.reason());
        assertEquals("run_not_found_or_not_cancellable", missingToken.reason());
        assertFalse(missingSession.cancelled());
        assertFalse(missingToken.cancelled());
        verify(registry, never()).cancelExact(any(Long.class), any(), any(Runnable.class));
    }

    @Test
    void unauthorizedOrMissingExactRunNeverInvokesStoppedMarker() {
        ChatRunRegistry registry = mock(ChatRunRegistry.class);
        AtomicInteger markerCalls = new AtomicInteger();
        when(registry.cancelExact(eq(42L), eq("run"), any(Runnable.class))).thenReturn(false);

        ChatCancellationCommandHandler.Result unauthorized = handler.cancel(
                42L, "run", () -> false, registry, markerCalls::incrementAndGet);
        ChatCancellationCommandHandler.Result missingRun = handler.cancel(
                42L, "run", () -> true, registry, markerCalls::incrementAndGet);

        assertEquals("run_not_found_or_not_cancellable", unauthorized.reason());
        assertEquals("run_not_found_or_not_cancellable", missingRun.reason());
        assertEquals(0, markerCalls.get());
        verify(registry).cancelExact(eq(42L), eq("run"), any(Runnable.class));
    }

    @Test
    void successfulExactCancelDelegatesOnceAndReturnsTerminalOutcome() {
        ChatRunRegistry registry = mock(ChatRunRegistry.class);
        AtomicInteger markerCalls = new AtomicInteger();
        when(registry.cancelExact(eq(42L), eq("run"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });

        ChatCancellationCommandHandler.Result result = handler.cancel(
                42L, "run", () -> true, registry, markerCalls::incrementAndGet);

        assertTrue(result.cancelled());
        assertEquals("cancelled", result.reason());
        assertEquals(1, markerCalls.get());
        verify(registry).cancelExact(eq(42L), eq("run"), any(Runnable.class));
    }

    @Test
    void registryFailureReturnsCancelFailedWithoutMarker() {
        ChatRunRegistry registry = mock(ChatRunRegistry.class);
        AtomicInteger markerCalls = new AtomicInteger();
        when(registry.cancelExact(eq(42L), eq("run"), any(Runnable.class)))
                .thenThrow(new IllegalStateException("fixture"));

        ChatCancellationCommandHandler.Result result = handler.cancel(
                42L, "run", () -> true, registry, markerCalls::incrementAndGet);

        assertFalse(result.cancelled());
        assertEquals("cancel_failed", result.reason());
        assertEquals(0, markerCalls.get());
    }
}
