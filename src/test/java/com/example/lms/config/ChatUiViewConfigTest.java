package com.example.lms.config;

import com.example.lms.entity.ModelEntity;
import org.jsoup.Jsoup;
import com.example.lms.harmony.HarmonyDashboardPageController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ChatUiViewConfigTest {

    @Test
    void indexLandingUsesTheExistingHtmlViewInsteadOfForwardingBackToItsController() throws Exception {
        var view = new ChatUiViewConfig().chatUiResourceViewResolver().resolveViewName("index", Locale.KOREA);
        assertTrue(view != null, "form login landing must resolve before the internal forward resolver");
        MockHttpServletResponse response = new MockHttpServletResponse();
        view.render(Map.of(), new MockHttpServletRequest("GET", "/index"), response);
        assertTrue(response.getStatus() == 200);
        assertTrue(response.getContentAsString().contains("RAG/Agent Development Operations"));
        assertTrue(response.getContentAsString().contains("href=\"/chat-ui\""));
    }

    @Test
    void chatUiViewRendersPreparedModelDataWithoutTemplatePlaceholders() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var view = config.chatUiResourceViewResolver().resolveViewName("chat-ui", Locale.KOREA);

        ModelEntity gemma = model("gemma4:26b");
        ModelEntity qwen = model("qwen3:30b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        view.render(Map.of(
                        "models", List.of(gemma, qwen),
                        "currentModel", "qwen3:30b"
                ),
                new MockHttpServletRequest("GET", "/chat-ui"),
                response);

        String html = response.getContentAsString();
        var document = Jsoup.parse(html);
        assertTrue(document.selectFirst("[data-current-model]").text().equals("qwen3:30b"));
        assertTrue(document.selectFirst("#modelStatus").text().equals("qwen3:30b"));
        assertTrue(document.select("#modelSelect option").size() == 2);
        assertTrue(document.selectFirst("#modelSelect option[selected]").val().equals("qwen3:30b"));
        assertTrue(document.select("[data-admin-diagnostics]").isEmpty());
        assertTrue(document.body().attr("data-chat-surface").equals("web"));
        assertFalse(html.contains(">model</option>"));
        assertFalse(html.contains("th:if"));
        assertFalse(html.contains("th:each"));
        assertFalse(html.contains("th:text"));
    }

    @Test
    void loginViewRendersCredentialFormWithCsrfAndStableStatus() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var view = config.chatUiResourceViewResolver().resolveViewName("login", Locale.KOREA);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-123");
        request.setAttribute(CsrfToken.class.getName(), csrf);

        view.render(Map.of("loginStatus", "invalid_credentials"), request, response);

        String html = response.getContentAsString();
        assertTrue(html.contains("Operator sign-in"));
        assertTrue(html.contains("Sign-in failed."));
        assertTrue(html.contains("name=\"username\""));
        assertTrue(html.contains("name=\"password\""));
        assertTrue(html.contains("name=\"remember-me\""));
        assertTrue(html.contains("action=\"/login\""));
        assertTrue(html.contains("name=\"_csrf\" value=\"token-123\""));
        assertTrue(html.contains("href=\"/chat-ui\""));
        assertFalse(html.contains("Trace memory"));
        assertFalse(html.contains("auth disabled"));
        assertFalse(html.contains("LOGIN_STATUS"));
        assertFalse(html.contains("CSRF_INPUT"));
        assertFalse(html.matches("(?s).*\\sth:[A-Za-z-]+=\".*"));
    }

    @Test
    void harmonyDashboardViewRendersClasspathTemplateWithoutInternalForward() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var view = config.chatUiResourceViewResolver().resolveViewName("harmony-dashboard", Locale.KOREA);

        assertTrue(view != null, "harmony dashboard must resolve to a classpath HTML view");
        MockHttpServletResponse response = new MockHttpServletResponse();
        view.render(
                Map.of(),
                new MockHttpServletRequest("GET", "/harmony"),
                response);

        String html = response.getContentAsString();
        assertTrue(response.getStatus() < 400);
        assertTrue(html.contains("<title>Harmony Dashboard</title>"));
        assertTrue(html.contains("EventSource('/api/harmony/stream')"));
        assertTrue(html.contains("fetch('/api/harmony/score'"));
    }

    @Test
    void harmonyRouteRendersClasspathTemplateWithoutInternalForward() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var result = standaloneSetup(new HarmonyDashboardPageController())
                .setViewResolvers(config.chatUiResourceViewResolver())
                .build()
                .perform(get("/harmony"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(result.getResponse().getStatus() < 400);
        assertTrue(result.getResponse().getForwardedUrl() == null,
                "harmony dashboard must render without an internal forward");
        assertTrue(html.contains("<title>Harmony Dashboard</title>"));
        assertTrue(html.contains("EventSource('/api/harmony/stream')"));
        assertTrue(html.contains("fetch('/api/harmony/score'"));
    }

    @Test
    void compactAndDiagnosticProjectionIsExplicitAndModelNamesAreEscaped() throws Exception {
        var view = new ChatUiViewConfig().chatUiResourceViewResolver().resolveViewName("chat-ui", Locale.KOREA);
        var response = new MockHttpServletResponse();
        String syntheticId = "test<model>&name";
        var request = new MockHttpServletRequest("GET", "/chat");
        request.setAttribute(CsrfToken.class.getName(), new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "fixture"));
        view.render(Map.of("models", List.of(model(syntheticId)), "currentModel", syntheticId,
                "chatSurface", "compact", "chatDiagnosticsEnabled", true), request, response);
        var document = Jsoup.parse(response.getContentAsString());
        assertTrue(document.body().attr("data-chat-surface").equals("compact"));
        assertTrue(document.select(".conversation-sidebar").isEmpty());
        assertTrue(document.select("[data-admin-diagnostics]").size() == 1);
        assertTrue(document.selectFirst("#modelSelect option").text().equals(syntheticId));
        assertTrue(document.selectFirst("#modelSelect option").val().equals(syntheticId));
        assertTrue(document.selectFirst("meta[name=_csrf]").attr("content").equals("fixture"));
        assertTrue(document.getAllElements().stream().flatMap(e -> e.attributes().asList().stream())
                .noneMatch(a -> a.getKey().startsWith("th:")));
        assertTrue(document.select("#messageInput, #sendBtn, #stopBtn, #useRagToggle").size() == 4);
    }

    private static ModelEntity model(String id) {
        ModelEntity entity = new ModelEntity();
        entity.setModelId(id);
        entity.setOwner("test");
        return entity;
    }
}
