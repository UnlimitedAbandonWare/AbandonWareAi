package com.example.lms.service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ChatCatalogRecoveryFocusedTest {
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private ChatModelCatalogService catalog() {
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        return new ChatModelCatalogService(null, null, builder, "http://localhost:11434/v1", false);
    }
    private void tags(String body) {
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private void chat() {
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
    }
    @Test void selectedModelGetsOneTargetedCapabilityRetry() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        server.expect(requestTo("http://localhost:11434/api/show")).andRespond(withServerError());
        chat();
        var catalog = catalog();
        assertThat(catalog.choices().get(0).selectable()).isFalse();
        assertThat(catalog.resolve("fixture:chat").orElseThrow().selectable()).isTrue();
        server.verify();
    }
    @Test void transientInventoryFailureRetainsUnselectableObservationInsteadOfClaimingDeletion() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}"); chat();
        server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(withServerError());
        var catalog = catalog();
        assertThat(catalog.choices().get(0).selectable()).isTrue();
        ReflectionTestUtils.setField(catalog, "expiresAt", 0L);
        var rows = catalog.choices();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).reason()).isEqualTo("catalog_unavailable");
        assertThat(rows.get(0).selectable()).isFalse();
        server.verify();
    }
    @Test void successfulEmptyInventoryRemovesPreviouslyInstalledChoice() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}"); chat(); tags("{\"models\":[]}");
        var catalog = catalog(); catalog.choices();
        ReflectionTestUtils.setField(catalog, "expiresAt", 0L);
        assertThat(catalog.resolve("fixture:chat")).isEmpty();
        server.verify();
    }
    @Test void resolveConsumesOneDetailProbePerInventoryGeneration() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        server.expect(ExpectedCount.times(2), requestTo("http://localhost:11434/api/show"))
                .andRespond(withServerError());
        var catalog = catalog();
        assertThat(catalog.choices().get(0).reason()).isEqualTo("capability_not_observed");
        for (int i = 0; i < 20; i++) {
            var row = catalog.resolve("fixture:chat");
            assertThat(row).isPresent();
            assertThat(row.get().selectable()).isFalse();
        }
        server.verify();
    }
    @Test void malformedInventoryBodyRetainsPriorRowsInsteadOfReportingAbsence() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}"); chat();
        tags("{}");
        var catalog = catalog();
        assertThat(catalog.choices().get(0).selectable()).isTrue();
        ReflectionTestUtils.setField(catalog, "expiresAt", 0L);
        var rows = catalog.choices();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).reason()).isEqualTo("catalog_unavailable");
        assertThat(rows.get(0).selectable()).isFalse();
        assertThat(catalog.failureCode(null)).isEqualTo("backend_unavailable");
        server.verify();
    }
    @Test void observedEmptyInventoryIsAuthoritativeAbsence() {
        tags("{\"models\":[]}");
        var catalog = catalog();
        catalog.choices();
        assertThat(catalog.resolve("fixture:chat")).isEmpty();
        assertThat(catalog.failureCode(null)).isEqualTo("model_unavailable");
        server.verify();
    }
    @Test void successfulRefreshGrantsANewBoundedProbeOpportunity() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        server.expect(requestTo("http://localhost:11434/api/show")).andRespond(withServerError());
        server.expect(requestTo("http://localhost:11434/api/show")).andRespond(withServerError());
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        chat();
        var catalog = catalog();
        catalog.choices();
        assertThat(catalog.resolve("fixture:chat").orElseThrow().selectable()).isFalse();
        ReflectionTestUtils.setField(catalog, "expiresAt", 0L);
        assertThat(catalog.resolve("fixture:chat").orElseThrow().selectable()).isTrue();
        server.verify();
    }
    @Test void unauthorizedInventoryIsReportedSeparatelyFromAbsence() {
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        var catalog = catalog();
        catalog.choices();
        assertThat(catalog.failureCode(null)).isEqualTo("provider_unauthorized");
        server.verify();
    }
    @Test void recheckRefreshesOnceWithinCooldownThenServesCachedState() {
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        server.expect(requestTo("http://localhost:11434/api/show")).andRespond(withServerError());
        tags("{\"models\":[{\"name\":\"fixture:chat\"}]}");
        chat();
        var catalog = catalog();
        assertThat(catalog.choices().get(0).selectable()).isFalse();
        assertThat(catalog.recheck("fixture:chat").orElseThrow().selectable()).isTrue();
        assertThat(catalog.recheck("fixture:chat").orElseThrow().selectable()).isTrue();
        server.verify();
    }
    @Test void recheckAcceptsOnlyServerValidatedIds() {
        tags("{\"models\":[]}");
        tags("{\"models\":[]}");
        var catalog = catalog();
        catalog.choices();
        assertThat(catalog.recheck("<script>alert(1)</script>")).isEmpty();
        assertThat(catalog.recheck("never:seen")).isEmpty();
        server.verify();
    }
    @Test void slowDetailsDoNotEraseUnprobedInstalledModels() {
        tags("{\"models\":[{\"name\":\"fixture:first\"},{\"name\":\"fixture:second\"}]}");
        server.expect(requestTo("http://localhost:11434/api/show")).andRespond(request -> {
            try { Thread.sleep(3050); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var rows = catalog().choices();
        assertThat(rows).extracting(ChatModelCatalogService.Choice::id)
                .containsExactly("fixture:first", "fixture:second");
        assertThat(rows.get(1).reason()).isEqualTo("capability_not_observed");
        server.verify();
    }
}
