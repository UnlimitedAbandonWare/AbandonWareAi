package com.example.lms.plugin.image;

import com.example.lms.plugin.image.jobs.ImageJob;
import com.example.lms.plugin.image.jobs.ImageJobOwnerKey;
import com.example.lms.plugin.image.jobs.ImageJobRepository;
import com.example.lms.plugin.image.jobs.ImageJobService;
import com.example.lms.plugin.image.jobs.ImageManifestWriter;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.plugin.image.debug.ImageJobDebugLedger;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationPluginControllerSecurityTest {

    @Test
    void syncGenerateDisabledRecordsDebugSignal() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver("owner-key"), guard, manifestWriter());
        ImageJobDebugLedger ledger = enabledLedger();
        ReflectionTestUtils.setField(controller, "debugLedger", ledger);
        ReflectionTestUtils.setField(controller, "syncGenerateEnabled", false);

        var response = controller.generateImage(request("private image prompt"));

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        Map<String, Object> snapshot = ledger.snapshot("controller:sync.generate.disabled");
        assertEquals(1, snapshot.get("signalCount"));
        assertEquals(true, snapshot.get("triggered"));
        String dump = String.valueOf(snapshot);
        assertTrue(dump.contains("SYNC_GENERATE_DISABLED"));
        assertTrue(dump.contains("CONFIG_SENTINEL"));
        assertFalse(dump.contains("private image prompt"));
    }

    @Test
    void syncGenerateNoApiKeyRecordsDebugSignal() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver("owner-key"), guard, manifestWriter());
        ImageJobDebugLedger ledger = enabledLedger();
        ReflectionTestUtils.setField(controller, "debugLedger", ledger);
        ReflectionTestUtils.setField(controller, "syncGenerateEnabled", true);
        when(imageService.isConfigured()).thenReturn(false);

        var response = controller.generateImage(request("private image prompt"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> snapshot = ledger.snapshot("controller:sync.generate.no_api_key");
        assertEquals(1, snapshot.get("signalCount"));
        assertEquals(true, snapshot.get("triggered"));
        String dump = String.valueOf(snapshot);
        assertTrue(dump.contains("NO_API_KEY"));
        assertTrue(dump.contains("CONFIG_SENTINEL"));
        assertFalse(dump.contains("private image prompt"));
    }

    @Test
    void enqueueStoresHeaderSessionId() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ClientOwnerKeyResolver ownerKeyResolver = ownerKeyResolver("owner-key");
        ImageManifestWriter manifestWriter = manifestWriter();
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver, guard, manifestWriter);
        ImageGenerationPluginRequest request = request("cat");
        ImageJob job = job("job-1", "sid-1", ImageJob.Status.PENDING);

        when(imageService.isConfigured()).thenReturn(true);
        when(jobService.enqueue(eq("cat"), eq("gpt-image-1"), eq("1024x1024"), eq("sid-1"), anyString())).thenReturn(job);
        when(jobService.estimate("job-1")).thenReturn(new ImageJobService.Eta(5L, "soon"));

        var response = controller.enqueue(request, "sid-1", new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jobService).enqueue("cat", "gpt-image-1", "1024x1024", "sid-1",
                ImageJobOwnerKey.hash("owner-key"));
    }

    @Test
    void enqueueFallsBackToHttpSessionId() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ClientOwnerKeyResolver ownerKeyResolver = ownerKeyResolver("owner-key");
        ImageManifestWriter manifestWriter = manifestWriter();
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver, guard, manifestWriter);
        ImageGenerationPluginRequest request = request("cat");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setSession(new org.springframework.mock.web.MockHttpSession(null, "http-session-1"));
        ImageJob job = job("job-1", "http-session-1", ImageJob.Status.PENDING);

        when(imageService.isConfigured()).thenReturn(true);
        when(jobService.enqueue(eq("cat"), eq("gpt-image-1"), eq("1024x1024"), anyString(), anyString())).thenReturn(job);
        when(jobService.estimate("job-1")).thenReturn(new ImageJobService.Eta(5L, "soon"));

        controller.enqueue(request, null, servletRequest);

        verify(jobService).enqueue("cat", "gpt-image-1", "1024x1024", "http-session-1",
                ImageJobOwnerKey.hash("owner-key"));
    }

    @Test
    void enqueueReturnsServerErrorWhenJobServiceDoesNotCreateJob() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ClientOwnerKeyResolver ownerKeyResolver = ownerKeyResolver("owner-key");
        ImageManifestWriter manifestWriter = manifestWriter();
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver, guard, manifestWriter);

        when(imageService.isConfigured()).thenReturn(true);
        when(jobService.enqueue(eq("secret image prompt"), eq("gpt-image-1"), eq("1024x1024"), anyString(), anyString()))
                .thenReturn(null);

        var response = controller.enqueue(request("secret image prompt"), "sid-1", new MockHttpServletRequest());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("IMAGE_JOB_ENQUEUE_FAILED", response.getBody().reason());
        assertEquals(null, response.getBody().id());
        assertEquals(null, response.getBody().publicUrl());
    }

    @Test
    void statusRejectsMismatchedSessionWithoutLeakingPublicUrl() {
        ImageGenerationPluginController controller = controllerWithJob("owner-session", false, "other-key");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "other-session");

        var response = controller.status("job-1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SESSION_MISMATCH", response.getBody().reason());
        assertEquals(null, response.getBody().publicUrl());
    }

    @Test
    void statusAllowsSameSession() {
        ImageGenerationPluginController controller = controllerWithJob("owner-session", false, "owner-key");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "owner-session");

        var response = controller.status("job-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("/generated/job-1.png", response.getBody().publicUrl());
    }

    @Test
    void statusAllowsAdminToken() {
        ImageGenerationPluginController controller = controllerWithJob("owner-session", true, "other-key");

        var response = controller.status("job-1", new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("/generated/job-1.png", response.getBody().publicUrl());
    }

    @Test
    void historyRejectsArbitrarySessionBrowsing() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ClientOwnerKeyResolver ownerKeyResolver = ownerKeyResolver("other-key");
        ImageManifestWriter manifestWriter = manifestWriter();
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver, guard, manifestWriter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "other-session");
        when(jobRepo.findTop20BySessionIdOrderByCreatedAtDesc("owner-session"))
                .thenReturn(List.of(job("job-1", "owner-session", ImageJob.Status.SUCCEEDED)));

        var response = controller.history("owner-session", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SESSION_MISMATCH", response.getBody().get(0).reason());
    }

    @Test
    void historyAllowsSameSession() {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        ClientOwnerKeyResolver ownerKeyResolver = ownerKeyResolver("owner-key");
        ImageManifestWriter manifestWriter = manifestWriter();
        ImageGenerationPluginController controller = new ImageGenerationPluginController(
                imageService, jobService, jobRepo, ownerKeyResolver, guard, manifestWriter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "owner-session");
        when(jobRepo.findTop20BySessionIdOrderByCreatedAtDesc("owner-session"))
                .thenReturn(List.of(job("job-1", "owner-session", ImageJob.Status.SUCCEEDED)));

        var response = controller.history("owner-session", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("/generated/job-1.png", response.getBody().get(0).publicUrl());
    }

    private static ImageGenerationPluginController controllerWithJob(String sessionId, boolean admin, String currentOwnerKey) {
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        ImageJobService jobService = mock(ImageJobService.class);
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        AdminTokenGuardInterceptor guard = mock(AdminTokenGuardInterceptor.class);
        when(guard.isPresentedTokenAuthorized(org.mockito.ArgumentMatchers.any())).thenReturn(admin);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job("job-1", sessionId, ImageJob.Status.SUCCEEDED)));
        return new ImageGenerationPluginController(imageService, jobService, jobRepo,
                ownerKeyResolver(currentOwnerKey), guard, manifestWriter());
    }

    private static ImageGenerationPluginRequest request(String prompt) {
        ImageGenerationPluginRequest request = new ImageGenerationPluginRequest();
        request.setPrompt(prompt);
        request.setSize("1024x1024");
        return request;
    }

    private static ClientOwnerKeyResolver ownerKeyResolver(String ownerKey) {
        ClientOwnerKeyResolver resolver = mock(ClientOwnerKeyResolver.class);
        when(resolver.ownerKey()).thenReturn(ownerKey);
        return resolver;
    }

    private static ImageManifestWriter manifestWriter() {
        ImageManifestWriter writer = mock(ImageManifestWriter.class);
        when(writer.read(any(ImageJob.class))).thenReturn(Map.of("id", "job-1"));
        return writer;
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

    private static ImageJob job(String id, String sessionId, ImageJob.Status status) {
        ImageJob job = new ImageJob();
        job.setId(id);
        job.setPrompt("private prompt");
        job.setModel("gpt-image-1");
        job.setSize("1024x1024");
        job.setSessionId(sessionId);
        job.setOwnerKeyHash(ImageJobOwnerKey.hash("owner-key"));
        job.setStatus(status);
        job.setCreatedAt(Instant.now());
        if (status == ImageJob.Status.SUCCEEDED) {
            job.setPublicUrl("/generated/" + id + ".png");
        }
        return job;
    }
}
