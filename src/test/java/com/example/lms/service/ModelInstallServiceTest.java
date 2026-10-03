package com.example.lms.service;

import com.example.lms.config.LocalLlmProcessManager;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModelInstallServiceTest {
    @Test void cloudRegistrationAddsOnlyThatRouteToSelectableSet() {
        var cloud = mock(CloudModelRouteClassifier.class);
        var api3 = routeRow("api3", "groq", "openai/gpt-oss-120b", true, null);
        var economy = routeRow("openai-economy", "openai", "fixture-openai", true, null);
        when(cloud.classifyDefaultCatalog("chat")).thenReturn(List.of(api3, economy));
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://remote.invalid", false);
        var service = new ModelInstallService(mock(LocalLlmProcessManager.class), catalog, cloud);

        var first = service.install("cloud", null, "api3");
        assertThat(first.get("status")).isEqualTo("registered");
        assertThat(first.get("persistent")).isEqualTo(false);
        assertThat(first.get("selectableNow")).isEqualTo(true);
        assertThat(catalog.resolve("llmrouter.api3").orElseThrow().selectable()).isTrue();
        var other = catalog.resolve("llmrouter.openai-economy").orElseThrow();
        assertThat(other.selectable()).isFalse();
        assertThat(other.reason()).isEqualTo("remote_selection_disabled");

        assertThat(service.install("cloud", null, "api3").get("status")).isEqualTo("already_registered");
    }

    @Test void cloudRegistrationRejectsManifestDisabledUnknownAndDiscoveryRoutes() {
        var cloud = mock(CloudModelRouteClassifier.class);
        var disabled = routeRow("gpt5", "openai", "gpt-5.5", false, "cloud_manifest_disabled");
        var discovery = routeRow("openrouter", "openrouter", "vendor/model", false, null);
        when(discovery.capabilities()).thenReturn(List.of("model_discovery"));
        when(cloud.classifyDefaultCatalog("chat")).thenReturn(List.of(disabled, discovery));
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://remote.invalid", false);
        var service = new ModelInstallService(mock(LocalLlmProcessManager.class), catalog, cloud);

        var manifestBlocked = service.install("cloud", null, "gpt5");
        assertThat(manifestBlocked.get("status")).isEqualTo("rejected");
        assertThat(manifestBlocked.get("reason")).isEqualTo("cloud_manifest_disabled");
        assertThat(catalog.isRouteApproved("gpt5")).isFalse();

        var unknown = service.install("cloud", null, "no-such-route");
        assertThat(unknown.get("status")).isEqualTo("rejected");
        assertThat(unknown.get("reason")).isEqualTo("route_not_configured");

        var discoveryRow = service.install("cloud", null, "openrouter");
        assertThat(discoveryRow.get("reason")).isEqualTo("route_not_configured");
    }

    @Test void cloudRegistrationWithoutEligibilityStillKeepsRowUnselectable() {
        var cloud = mock(CloudModelRouteClassifier.class);
        var gated = routeRow("groq-paid", "groq", "vendor/large", false, "groq_free_account_evidence_needed");
        when(cloud.classifyDefaultCatalog("chat")).thenReturn(List.of(gated));
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://remote.invalid", false);
        var service = new ModelInstallService(mock(LocalLlmProcessManager.class), catalog, cloud);

        var result = service.install("cloud", null, "groq-paid");
        assertThat(result.get("status")).isEqualTo("registered");
        assertThat(result.get("selectableNow")).isEqualTo(false);
        assertThat(result.get("disabledReason")).isEqualTo("groq_free_account_evidence_needed");
        var row = catalog.resolve("llmrouter.groq-paid").orElseThrow();
        assertThat(row.selectable()).isFalse();
        assertThat(row.reason()).isEqualTo("groq_free_account_evidence_needed");
    }

    @Test void localInstallDelegatesToManagedEndpointAndExpiresCatalogWhenInstalled() {
        var manager = mock(LocalLlmProcessManager.class);
        when(manager.requestModelPull("fixture:chat")).thenReturn(
                Map.of("status", "accepted", "model", "fixture:chat"));
        var cloud = mock(CloudModelRouteClassifier.class);
        var catalog = spy(new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://remote.invalid", false));
        var service = new ModelInstallService(manager, catalog, cloud);

        var accepted = service.install("local", "fixture:chat", null);
        assertThat(accepted.get("status")).isEqualTo("accepted");
        verify(manager).requestModelPull("fixture:chat");

        when(manager.installPullStatus()).thenReturn(Map.of("status", "installed", "inFlight", false));
        service.status();
        verify(catalog).expireCache();
    }

    @Test void invalidTargetOrRejectedPullNeverTouchesAllowlist() {
        var manager = mock(LocalLlmProcessManager.class);
        var cloud = mock(CloudModelRouteClassifier.class);
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://remote.invalid", false);
        var service = new ModelInstallService(manager, catalog, cloud);

        var badTarget = service.install("openrouter", null, null);
        assertThat(badTarget.get("status")).isEqualTo("rejected");
        assertThat(badTarget.get("reason")).isEqualTo("invalid_target");
        verifyNoInteractions(manager);

        when(manager.requestModelPull("bad tag!")).thenReturn(
                Map.of("status", "rejected", "reason", "invalid_model_tag"));
        var badPull = service.install("local", "bad tag!", null);
        assertThat(badPull.get("reason")).isEqualTo("invalid_model_tag");
        assertThat(catalog.isRouteApproved("api3")).isFalse();
    }

    private static CloudModelRouteClassifier.CloudModelRouteRow routeRow(
            String routeKey, String provider, String modelId, boolean eligible, String disabledReason) {
        var row = mock(CloudModelRouteClassifier.CloudModelRouteRow.class);
        when(row.routeKey()).thenReturn(routeKey);
        when(row.provider()).thenReturn(provider);
        when(row.modelId()).thenReturn(modelId);
        when(row.eligible()).thenReturn(eligible);
        when(row.disabledReason()).thenReturn(disabledReason);
        when(row.capabilities()).thenReturn(List.of("chat"));
        when(row.metadata()).thenReturn(Map.of());
        return row;
    }
}
