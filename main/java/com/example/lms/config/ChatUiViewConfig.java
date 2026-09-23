package com.example.lms.config;

import com.example.lms.entity.ModelEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.util.HtmlUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

@Configuration
public class ChatUiViewConfig {

    @Bean
    public ViewResolver chatUiResourceViewResolver() {
        return new ChatUiResourceViewResolver();
    }

    private static final class ChatUiResourceViewResolver implements ViewResolver, Ordered {
        private static final View CHAT_UI = new ClasspathHtmlView("templates/chat-ui.html");
        private static final View LOGIN = new ClasspathHtmlView("templates/login.html");
        private static final View INDEX = new ClasspathHtmlView("templates/index.html");
        private static final View HARMONY_DASHBOARD = new ClasspathHtmlView("templates/harmony-dashboard.html");
        private static final View DEBUG_EVENTS = new ClasspathHtmlView("templates/debug-events.html");

        @Override
        public View resolveViewName(String viewName, Locale locale) {
            return switch (viewName) {
                case "chat-ui" -> CHAT_UI;
                case "login" -> LOGIN;
                case "index" -> INDEX;
                case "harmony-dashboard" -> HARMONY_DASHBOARD;
                case "debug-events" -> DEBUG_EVENTS;
                default -> null;
            };
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private static final class ClasspathHtmlView implements View {
        private final String path;
        private final ClassPathResource resource;

        private ClasspathHtmlView(String path) {
            this.path = path;
            this.resource = new ClassPathResource(path);
        }

        @Override
        public String getContentType() {
            return MediaType.TEXT_HTML_VALUE;
        }

        @Override
        public void render(Map<String, ?> model,
                           HttpServletRequest request,
                           HttpServletResponse response) throws Exception {
            if (!resource.exists()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            String html = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            if ("templates/chat-ui.html".equals(path)) {
                html = renderChatUiHtml(html, model, request);
            } else if ("templates/login.html".equals(path)) {
                html = renderLoginHtml(html, model, request);
            } else if ("templates/debug-events.html".equals(path)) {
                html = renderCsrfMeta(html, model, request);
            }
            response.getWriter().write(html);
        }

        private static String renderChatUiHtml(
                String html,
                Map<String, ?> model,
                HttpServletRequest request) {
            // This application deliberately uses classpath views (no Thymeleaf runtime).
            // Project the few server-owned values through an HTML parser, not regex conditions.
            var document = Jsoup.parse(html);
            document.outputSettings().prettyPrint(false);
            String surface = "compact".equals(model.get("chatSurface")) ? "compact" : "web";
            document.body().attr("data-chat-surface", surface);
            if (!Boolean.TRUE.equals(model.get("chatDiagnosticsEnabled"))) {
                document.select("[data-admin-diagnostics]").remove();
            }
            if ("compact".equals(surface)) document.select(".conversation-sidebar").remove();
            String selected = currentModel(model);
            document.select("[data-current-model], #modelStatus").forEach(element -> element.text(selected));
            Element select = document.getElementById("modelSelect");
            if (select != null) {
                select.empty();
                List<String> ids = modelIds(model.get("models"));
                if (ids.isEmpty()) ids.add(selected);
                for (String id : ids) {
                    Element option = select.appendElement("option").attr("value", id).text(id);
                    if (id.equals(selected)) option.attr("selected", "");
                }
            }
            // No inert template expressions may survive the explicit projection.
            for (Element element : document.getAllElements()) {
                for (var attribute : new ArrayList<>(element.attributes().asList())) {
                    if (attribute.getKey().startsWith("th:") || attribute.getKey().equals("xmlns:th")) {
                        element.removeAttr(attribute.getKey());
                    }
                }
            }
            return renderCsrfMeta(document.outerHtml(), model, request);
        }

        private static String renderLoginHtml(String html,
                                              Map<String, ?> model,
                                              HttpServletRequest request) {
            html = html.replace("    <!-- LOGIN_STATUS -->", loginStatusNotice(model));
            return html.replace("        <!-- CSRF_INPUT -->", csrfHiddenInput(model, request));
        }

        private static String loginStatusNotice(Map<String, ?> model) {
            String status = stringValue(model.get("loginStatus"));
            String message = switch (status) {
                case "invalid_credentials" -> "Sign-in failed.";
                case "signed_out" -> "Signed out.";
                default -> "Sign in to access operator surfaces.";
            };
            return "    <p class=\"notice\">" + message + "</p>";
        }

        private static String csrfHiddenInput(Map<String, ?> model, HttpServletRequest request) {
            CsrfToken csrf = csrfToken(model, request);
            if (csrf == null || stringValue(csrf.getParameterName()).isBlank() || stringValue(csrf.getToken()).isBlank()) {
                return "";
            }
            return "<input type=\"hidden\" name=\""
                    + HtmlUtils.htmlEscape(csrf.getParameterName())
                    + "\" value=\""
                    + HtmlUtils.htmlEscape(csrf.getToken())
                    + "\">";
        }

        private static CsrfToken csrfToken(Map<String, ?> model, HttpServletRequest request) {
            Object fromModel = model.get("_csrf");
            if (fromModel instanceof CsrfToken csrfToken) {
                return csrfToken;
            }
            Object fromRequest = request.getAttribute(CsrfToken.class.getName());
            if (fromRequest instanceof CsrfToken csrfToken) {
                return csrfToken;
            }
            Object fromRequestName = request.getAttribute("_csrf");
            return fromRequestName instanceof CsrfToken csrfToken ? csrfToken : null;
        }

        private static String renderCsrfMeta(
                String html,
                Map<String, ?> model,
                HttpServletRequest request) {
            CsrfToken csrf = csrfToken(model, request);
            String token = csrf == null ? "" : stringValue(csrf.getToken());
            String header = csrf == null ? "" : stringValue(csrf.getHeaderName());
            if (token.isBlank() || header.isBlank()) {
                html = html.replaceFirst("(?m)^\\s*<meta name=\"_csrf\"[^>]*>\\R?", "");
                return html.replaceFirst("(?m)^\\s*<meta name=\"_csrf_header\"[^>]*>\\R?", "");
            }
            html = html.replaceFirst("<meta name=\"_csrf\"[^>]*>",
                    Matcher.quoteReplacement("<meta name=\"_csrf\" content=\"" + HtmlUtils.htmlEscape(token) + "\">"));
            return html.replaceFirst("<meta name=\"_csrf_header\"[^>]*>",
                    Matcher.quoteReplacement("<meta name=\"_csrf_header\" content=\"" + HtmlUtils.htmlEscape(header) + "\">"));
        }

        private static String currentModel(Map<String, ?> model) {
            String currentModel = stringValue(model.get("currentModel"));
            return currentModel.isBlank() ? "gemma4:26b" : currentModel;
        }

        private static List<String> modelIds(Object source) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            if (source instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    String modelId = modelId(item);
                    if (!modelId.isBlank()) {
                        out.add(modelId);
                    }
                }
            }
            return new ArrayList<>(out);
        }

        private static String modelId(Object item) {
            if (item instanceof ModelEntity model) {
                return stringValue(model.getModelId());
            }
            if (item instanceof CharSequence text) {
                return stringValue(text);
            }
            return "";
        }

        private static String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }
    }
}
