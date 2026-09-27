package com.example.lms.guard;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class GuardProfileRequestIsolationTest {
    @AfterEach
    void clearContext() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void independentRequestContextsRetainTheirOwnSelection() {
        GuardProfileProps profiles = profiles();
        GuardContext first = GuardContext.defaultContext();
        GuardContext second = GuardContext.defaultContext();
        GuardContextHolder.set(first);
        profiles.setCurrentProfile(GuardProfile.PROFILE_MEMORY);
        GuardContextHolder.set(second);
        profiles.setCurrentProfile(GuardProfile.PROFILE_FREE);
        GuardContextHolder.set(first);
        assertEquals(GuardProfile.PROFILE_MEMORY, profiles.currentProfile());
        GuardContextHolder.set(second);
        assertEquals(GuardProfile.PROFILE_FREE, profiles.currentProfile());
    }

    @Test
    void copiedContextRetainsSelectionWithoutAliasingSubsequentSelection() {
        GuardProfileProps profiles = profiles();
        GuardContext original = GuardContext.defaultContext();
        GuardContextHolder.set(original);
        profiles.setCurrentProfile(GuardProfile.PROFILE_FREE);
        GuardContext copied = original.copy();
        profiles.setCurrentProfile(GuardProfile.PROFILE_MEMORY);
        GuardContextHolder.set(copied);
        assertEquals(GuardProfile.PROFILE_FREE, profiles.currentProfile());
        GuardContextHolder.set(original);
        assertEquals(GuardProfile.PROFILE_MEMORY, profiles.currentProfile());
    }

    @Test
    void existingContextAwareExecutorCarriesSelectionAndRestoresWorkerState() throws Exception {
        GuardProfileProps profiles = profiles();
        ExecutorService raw = Executors.newSingleThreadExecutor();
        ContextAwareExecutorService executor = new ContextAwareExecutorService(raw);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            GuardContextHolder.set(GuardContext.defaultContext());
            profiles.setCurrentProfile(GuardProfile.STRICT);
            Future<GuardProfile> first = executor.submit(() -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                return profiles.currentProfile();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            GuardContextHolder.set(GuardContext.defaultContext());
            profiles.setCurrentProfile(GuardProfile.PROFILE_FREE);
            release.countDown();
            assertEquals(GuardProfile.STRICT, first.get(5, TimeUnit.SECONDS));
            assertNull(raw.submit(GuardContextHolder::get).get(5, TimeUnit.SECONDS),
                    "existing executor finally must restore the previous worker context");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void configuredDefaultUpdateDoesNotReplaceCurrentRequestSelection() {
        GuardProfileProps profiles = profiles();
        GuardContextHolder.set(GuardContext.defaultContext());
        profiles.setCurrentProfile(GuardProfile.PROFILE_FREE);
        profiles.setProfile("PROFILE_HEX");
        assertEquals(GuardProfile.PROFILE_FREE, profiles.currentProfile());
        GuardContextHolder.clear();
        assertEquals(GuardProfile.PROFILE_HEX, profiles.currentProfile());
    }

    @Test
    void explicitProgrammaticSetterOutsideRequestKeepsExistingFallbackContract() {
        GuardProfileProps profiles = profiles();
        profiles.setCurrentProfile(GuardProfile.STRICT);
        assertEquals(GuardProfile.STRICT, profiles.currentProfile());
    }

    @Test
    void selectedProfileDoesNotOverwriteIndependentGuardLevel() {
        GuardProfileProps profiles = profiles();
        GuardContext context = GuardContext.defaultContext();
        context.setGuardLevel("high");
        GuardContextHolder.set(context);
        profiles.setCurrentProfile(GuardProfile.PROFILE_FREE);
        assertEquals("high", context.getGuardLevel());
        assertEquals(GuardProfile.PROFILE_FREE, profiles.currentProfile());
    }

    @Test
    void malformedConfiguredDefaultRetainsMemoryFallback() {
        GuardProfileProps profiles = profiles();
        profiles.setProfile("invalid-fixture-profile");
        assertEquals(GuardProfile.PROFILE_MEMORY, profiles.currentProfile());
    }

    private static GuardProfileProps profiles() {
        GuardProfileProps profiles = new GuardProfileProps();
        profiles.setProfile("PROFILE_MEMORY");
        return profiles;
    }
}
