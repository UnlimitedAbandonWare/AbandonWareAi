package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class P6CatalogRefreshContractTest {
    private record Fixture(ChatModelCatalogService catalog, MockRestServiceServer server) {}
    private Fixture fixture(boolean local) {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        return new Fixture(new ChatModelCatalogService(null, null, builder,
                local ? "http://localhost:11434/v1" : "https://remote.invalid", true), server);
    }
    private static String tags(String id) { return "{\"models\":[{\"name\":\"" + id + "\"}]}"; }
    private static final String PUBLIC = "https://openrouter.ai/api/v1/models?limit=1000&output_modalities=text";
    private void localReply(Fixture fixture, String id) {
        fixture.server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withSuccess(tags(id), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
    }

    @Test void refreshDoesNotBlockCachedReadersAndSharesOneFlight() throws Exception {
        Fixture f = fixture(true);
        localReply(f, "fixture:initial");
        List<ChatModelCatalogService.Choice> baseline = f.catalog.choices();
        f.server.reset();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(request -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("latch timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            return withSuccess(tags("fixture:new"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        f.server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            f.catalog.expireCache();
            Future<?> refresh = workers.submit(() -> f.catalog.choices());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<List<ChatModelCatalogService.Choice>> reader = workers.submit(() -> f.catalog.choices());
            assertEquals(baseline, assertDoesNotThrow(() -> reader.get(1, TimeUnit.SECONDS)));
            release.countDown();
            refresh.get(3, TimeUnit.SECONDS);
            f.server.verify();
        } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test void lateRefreshCannotOverwriteANewerInvalidation() throws Exception {
        Fixture f = fixture(true);
        localReply(f, "fixture:initial"); f.catalog.choices(); f.server.reset();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            return withSuccess(tags("fixture:old"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        f.server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            f.catalog.expireCache();
            Future<?> old = workers.submit(() -> f.catalog.choices());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> workers.submit(f.catalog::expireCache).get(1, TimeUnit.SECONDS));
            release.countDown(); old.get(3, TimeUnit.SECONDS); f.server.verify(); f.server.reset();
            localReply(f, "fixture:new");
            assertEquals("fixture:new", f.catalog.choices().get(0).id());
            f.server.verify();
        } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test void publicFailureKeepsLastSuccessWithShortFailureTtl() {
        Fixture f = fixture(false);
        f.server.expect(requestTo(PUBLIC)).andRespond(withSuccess(
                "{\"data\":[{\"id\":\"vendor/fixture\",\"architecture\":{\"output_modalities\":[\"text\"]}}]}",
                MediaType.APPLICATION_JSON));
        f.catalog.choices(true); f.server.reset();
        ReflectionTestUtils.setField(f.catalog, "publicExpiresAt", 0L);
        f.server.expect(requestTo(PUBLIC)).andRespond(withServerError());
        var retained = f.catalog.choices(true);
        assertEquals(1, retained.size());
        assertEquals("catalog:openrouter:vendor/fixture", retained.get(0).id());
        assertFalse(retained.get(0).selectable());
        assertEquals("stale", retained.get(0).status());
        long expiry = (long) ReflectionTestUtils.getField(f.catalog, "publicExpiresAt");
        assertTrue(expiry - System.currentTimeMillis() <= 60_000);
        f.server.verify();
    }

    @Test void successfulEmptyPublicCatalogIsNotAFailure() {
        Fixture f = fixture(false);
        f.server.expect(requestTo(PUBLIC)).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(f.catalog.choices(true).isEmpty());
        long expiry = (long) ReflectionTestUtils.getField(f.catalog, "publicExpiresAt");
        assertTrue(expiry - System.currentTimeMillis() > 60_000);
        f.server.verify();
    }
}
