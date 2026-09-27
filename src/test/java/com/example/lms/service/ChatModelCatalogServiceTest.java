package com.example.lms.service;

import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ChatModelCatalogServiceTest {
    @Test void explicitPublicDiscoveryIsCredentialFreeCachedAndNeverSelectable() {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        var catalog = new ChatModelCatalogService(null, null, builder, "https://remote.invalid", true);
        assertThat(catalog.choices(false)).isEmpty();
        server.expect(requestTo("https://openrouter.ai/api/v1/models?limit=1000&output_modalities=text"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"vendor/exact-preview\",\"architecture\":{\"output_modalities\":[\"text\"]}},"
                        + "{\"id\":\"vendor/embedding\",\"architecture\":{\"output_modalities\":[\"embeddings\"]}}]}", MediaType.APPLICATION_JSON));
        var rows = catalog.choices(true);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).modelId()).isEqualTo("vendor/exact-preview");
        assertThat(rows.get(0).provider()).isEqualTo("openrouter");
        assertThat(rows.get(0).selectable()).isFalse();
        assertThat(rows.get(0).release()).isEqualTo("preview");
        assertThat(rows.get(0).evidence()).isEqualTo("public_catalog_only");
        assertThat(catalog.choices(true)).isEqualTo(rows);
        server.verify();
    }
    @Test void installedChatDiscoveryIsBoundedCachedAndDoesNotEnableCloud() throws Exception {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        var cloud = mock(CloudModelRouteClassifier.class);
        when(cloud.classifyDefaultCatalog("chat")).thenReturn(List.of());
        server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(withSuccess(
                "{\"models\":[{\"name\":\"fixture:chat\"},{\"name\":\"fixture:embed\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andExpect(content().json("{\"model\":\"fixture:chat\"}"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andExpect(content().json("{\"model\":\"fixture:embed\"}"))
                .andRespond(withSuccess("{\"capabilities\":[\"embedding\"]}", MediaType.APPLICATION_JSON));
        var catalog = new ChatModelCatalogService(cloud, null, builder, "http://localhost:11434/v1", false);
        var rows = catalog.choices();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).modelId()).isEqualTo("fixture:chat");
        assertThat(rows.get(0).selectable()).isTrue();
        assertThat(rows.get(0).evidence()).isEqualTo("installed_chat_capability");
        assertThat(catalog.choices()).isSameAs(rows);
        assertThat(new ObjectMapper().writeValueAsString(rows)).doesNotContain("localhost", "credential", "apiKey");
        server.verify();
    }

    @Test void discoveryDoesNotContactRemoteOrCredentialBearingUrls() {
        assertThat(ChatModelCatalogService.loopback("http://localhost:11434")).isTrue();
        for (String url : List.of("http://192.168.1.1:11434", "https://example.com",
                "http://user:fixture@localhost:11434", "http://localhost:11434?secret=fixture"))
            assertThat(ChatModelCatalogService.loopback(url)).isFalse();
        assertThat(ChatModelCatalogService.validId("vendor/model:tag")).isTrue();
        assertThat(ChatModelCatalogService.validId("<script>")).isFalse();
    }

    @Test void failedDiscoveryIsNotReportedAsGenerationSuccess() {
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        server.expect(anything()).andRespond(withServerError());
        assertThat(new ChatModelCatalogService(null, null, builder, "http://localhost:11434", false).choices()).isEmpty();
        server.verify();
    }
}
