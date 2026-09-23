package com.example.lms.plugin.image.debug;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.plugin.image.jobs.ImageJob;
import com.example.lms.plugin.image.jobs.ImageJobOwnerKey;
import com.example.lms.plugin.image.jobs.ImageJobRepository;
import com.example.lms.plugin.image.jobs.ImageJobService;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageJobDebugControllerTest {

    @Test
    void configSnapshotReturnsSafeStatusWithoutJobId() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("openai.image.enabled", "false")
                .withProperty("openai.image.sync.enabled", "false");
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                mock(ImageJobRepository.class), provider(null), mock(ImageJobDebugLedger.class), env,
                provider(imageService), ownerResolver("owner-key"), adminGuard(request, false));

        var response = controller.config();

        assertEquals(200, response.getStatusCode().value());
        String dump = String.valueOf(response.getBody());
        assertTrue(dump.contains("openai.image.enabled=false"));
        assertTrue(dump.contains("openai.image.sync.enabled=false"));
        assertFalse(dump.contains("apiKeyConfigured"));
        assertFalse(dump.contains("missing_openai_image_key"));
        assertTrue(dump.contains("image plugin disabled"));
        assertFalse(dump.contains("OPENAI_API_KEY"));
        assertFalse(dump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
        verifyNoInteractions(imageService);
    }

    @Test
    void invalidNumericConfigFallbackLeavesRedactedBreadcrumb() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("image.jobs.relay-delay-ms", "ownerToken=private-token");
        ImageJobDebugController controller = new ImageJobDebugController(
                mock(ImageJobRepository.class), provider(null), mock(ImageJobDebugLedger.class), env,
                provider(null), ownerResolver("owner-key"), adminGuard(new MockHttpServletRequest(), false));
        Logger logger = (Logger) LoggerFactory.getLogger(ImageJobDebugController.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var response = controller.config();
            assertEquals(200, response.getStatusCode().value());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        String rendered = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(rendered.contains("[AWX][image-debug] numeric config fallback"));
        assertTrue(rendered.contains("key=image.jobs.relay-delay-ms"));
        assertTrue(rendered.contains("errorType=NumberFormatException"));
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("private-token"));
    }

    @Test
    void debugSnapshotReturnsSanitizedJobAndConfigState() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJobService service = mock(ImageJobService.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("openai.image.enabled", "true")
                .withProperty("openai.image.sync.enabled", "false")
                .withProperty("image.jobs.relay-delay-ms", "300000")
                .withProperty("image.jobs.debug.session-ttl-ms", "32400000");
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(service), ledger, env, provider(imageService),
                ownerResolver("owner-key"), adminGuard(request, false));

        ImageJob job = new ImageJob();
        job.setId("job-private-id");
        job.setStatus(ImageJob.Status.PENDING);
        job.setProgress(20);
        job.setReason("SESSION_MISMATCH");
        job.setCreatedAt(Instant.parse("2026-06-24T00:00:00Z"));
        job.setPrompt("private prompt");
        job.setPublicUrl("/generated-images/private.png");
        job.setManifestUrl("/api/image-plugin/jobs/job-private-id/manifest");
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));

        when(repo.findById("job-private-id")).thenReturn(Optional.of(job));
        when(service.estimate("job-private-id")).thenReturn(new ImageJobService.Eta(300L, "soon"));
        when(imageService.isConfigured()).thenReturn(false);
        when(ledger.snapshot("job-private-id")).thenReturn(Map.of(
                "jobIdHash", SafeRedactor.hashValue("job-private-id"),
                "signalCount", 1,
                "suspicion", 0.7,
                "triggered", true));

        var response = controller.debug("job-private-id", request);

        assertEquals(200, response.getStatusCode().value());
        String dump = String.valueOf(response.getBody());
        assertTrue(dump.contains(SafeRedactor.hashValue("job-private-id")));
        assertTrue(dump.contains("publicUrlPresent=true"));
        assertTrue(dump.contains("apiKeyConfigured=false"));
        assertFalse(dump.contains("job-private-id"));
        assertFalse(dump.contains("private prompt"));
        assertFalse(dump.contains("/generated-images/private.png"));
    }

    @Test
    void debugReturnsNotFoundWithoutLedgerLookup() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), mock(ImageJobDebugLedger.class), new MockEnvironment(), provider(null),
                ownerResolver("owner-key"), adminGuard(request, false));

        var response = controller.debug("missing-job", request);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    void debugRejectsMismatchedOwnerWithoutLeakingJobState() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("other-owner"), adminGuard(request, false));

        ImageJob job = new ImageJob();
        job.setId("job-private-id");
        job.setStatus(ImageJob.Status.FAILED);
        job.setReason("NO_API_KEY");
        job.setPrompt("private prompt");
        job.setPublicUrl("/generated-images/private.png");
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));
        when(repo.findById("job-private-id")).thenReturn(Optional.of(job));

        var response = controller.debug("job-private-id", request);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("SESSION_MISMATCH", response.getBody().get("error"));
        String dump = String.valueOf(response.getBody());
        assertFalse(dump.contains("job-private-id"));
        assertFalse(dump.contains("private prompt"));
        assertFalse(dump.contains("/generated-images/private.png"));
        assertFalse(dump.contains("NO_API_KEY"));
        verifyNoInteractions(ledger);
    }

    @Test
    void debugAllowsAdminTokenForMismatchedOwner() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("other-owner"), adminGuard(request, true));

        ImageJob job = new ImageJob();
        job.setId("job-private-id");
        job.setStatus(ImageJob.Status.FAILED);
        job.setReason("NO_API_KEY");
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));
        when(repo.findById("job-private-id")).thenReturn(Optional.of(job));
        when(ledger.snapshot("job-private-id")).thenReturn(Map.of("signalCount", 1));

        var response = controller.debug("job-private-id", request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).contains("signalCount=1"));
    }

    @Test
    void debugAllowsLegacySessionWhenOwnerHashAbsent() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "legacy-session-id"));
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("other-owner"), adminGuard(request, false));

        ImageJob job = new ImageJob();
        job.setId("job-private-id");
        job.setSessionId("legacy-session-id");
        job.setStatus(ImageJob.Status.PENDING);
        when(repo.findById("job-private-id")).thenReturn(Optional.of(job));
        when(ledger.snapshot("job-private-id")).thenReturn(Map.of("legacySession", true));

        var response = controller.debug("job-private-id", request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).contains("legacySession=true"));
        assertFalse(String.valueOf(response.getBody()).contains("legacy-session-id"));
    }

    @Test
    void attemptScoreEndpointReturnsRedactedAttemptVerdict() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJob job = new ImageJob();
        job.setId("job-score-private");
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));
        when(repo.findById("job-score-private")).thenReturn(Optional.of(job));
        ImageJobDebugLedger ledger = enabledLedger();
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("owner-key"), adminGuard(request, false));

        String rawAttempt = "attempt-" + com.example.lms.test.SecretFixtures.openAiKey();
        String rawChange = "provider-timeout C:\\Users\\nninn\\private\\artifact.png";
        String rawNote = "negative signal " + com.example.lms.test.SecretFixtures.openAiKey();
        var response = controller.scoreAttempt("job-score-private",
                new ImageJobDebugController.AttemptScoreRequest(
                        rawAttempt, rawChange, 0.80d, 0.40d, 0.20d, rawNote),
                request);

        assertEquals(200, response.getStatusCode().value());
        String dump = String.valueOf(response.getBody());
        assertTrue(dump.contains("ROLLBACK"));
        assertTrue(dump.contains("hash:"));
        assertFalse(dump.contains("job-score-private"));
        assertFalse(dump.contains(rawAttempt));
        assertFalse(dump.contains(rawChange));
        assertFalse(dump.contains(rawNote));
        assertFalse(dump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void attemptScoreRejectsMismatchedOwnerWithoutLedgerMutation() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJob job = new ImageJob();
        job.setId("job-score-private");
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));
        when(repo.findById("job-score-private")).thenReturn(Optional.of(job));
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("other-owner"), adminGuard(request, false));

        var response = controller.scoreAttempt("job-score-private",
                new ImageJobDebugController.AttemptScoreRequest(
                        "attempt-private", "change-private", 0.80d, 0.40d, 0.20d, "private note"),
                request);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("SESSION_MISMATCH", response.getBody().get("error"));
        String dump = String.valueOf(response.getBody());
        assertFalse(dump.contains("job-score-private"));
        assertFalse(dump.contains("attempt-private"));
        assertFalse(dump.contains("change-private"));
        assertFalse(dump.contains("private note"));
        verifyNoInteractions(ledger);
    }

    @Test
    void attemptScoreAllowsLegacySessionWhenOwnerHashAbsent() {
        ImageJobRepository repo = mock(ImageJobRepository.class);
        ImageJob job = new ImageJob();
        job.setId("job-score-private");
        job.setSessionId("legacy-session-id");
        when(repo.findById("job-score-private")).thenReturn(Optional.of(job));
        ImageJobDebugLedger ledger = enabledLedger();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "legacy-session-id"));
        ImageJobDebugController controller = new ImageJobDebugController(
                repo, provider(null), ledger, new MockEnvironment(), provider(null),
                ownerResolver("other-owner"), adminGuard(request, false));

        var response = controller.scoreAttempt("job-score-private",
                new ImageJobDebugController.AttemptScoreRequest(
                        "attempt-private", "change-private", 0.40d, 0.70d, 0.0d, "legacy path"),
                request);

        assertEquals(200, response.getStatusCode().value());
        String dump = String.valueOf(response.getBody());
        assertTrue(dump.contains("PROMOTE"));
        assertFalse(dump.contains("job-score-private"));
        assertFalse(dump.contains("legacy-session-id"));
        assertFalse(dump.contains("attempt-private"));
        assertFalse(dump.contains("change-private"));
    }

    private static ImageJobDebugLedger enabledLedger() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ImageJobDebugLedger ledger = new ImageJobDebugLedger(provider(store));
        ReflectionTestUtils.setField(ledger, "enabled", true);
        ReflectionTestUtils.setField(ledger, "maxSignals", 20);
        ReflectionTestUtils.setField(ledger, "sessionTtlMs", 32_400_000L);
        ReflectionTestUtils.setField(ledger, "deltaThreshold", 0.35);
        ReflectionTestUtils.setField(ledger, "negativeThreshold", 0.55);
        return ledger;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static ClientOwnerKeyResolver ownerResolver(String ownerKey) {
        ClientOwnerKeyResolver resolver = mock(ClientOwnerKeyResolver.class);
        when(resolver.ownerKey()).thenReturn(ownerKey);
        return resolver;
    }

    private static AdminTokenGuardInterceptor adminGuard(MockHttpServletRequest request, boolean authorized) {
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        when(guard.isPresentedTokenAuthorized(request)).thenReturn(authorized);
        return guard;
    }
}
