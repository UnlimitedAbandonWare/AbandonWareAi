package com.example.lms;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Full production scan/security/registry, with external writers disabled by their existing properties. */
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = LmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1", "security.force-https=false",
                "spring.datasource.username=sa", "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
                "security.bootstrap-admin.password=", "agent.tools.api.enabled=true",
                "local-llm.enabled=false", "local-llm.autostart=false", "netty.enabled=false",
                "agent.subagent.glm.enabled=false", "llmrouter.models.openai-balanced.enabled=false",
                "llmrouter.models.light.enabled=false", "vectorstore.flush.scheduler.enabled=false",
                "vector.flush.scheduler.enabled=false", "vector.upstash.write-enabled=false",
                "upstash.vector.read-only=true", "abandonware.debug.ndjson.enabled=false",
                "uaw.autolearn.enabled=false", "uaw.autolearn.idle-trigger.enabled=false",
                "uaw.autolearn.retrain.enabled=false", "rgb.moe.debug.persist-enabled=false",
                "rag.offline-texture.write-enabled=false", "nova.orch.degraded-storage.enabled=false",
                "soak.enabled=false", "soak.quick-runner.enabled=false", "bm25.autoIndex=false",
                "lms.db.schema-autofix.chat-message-content.enabled=false",
                "lms.debug.events.enabled=true", "spring.main.banner-mode=off", "logging.level.root=WARN"
        })
abstract class Phase2ApplicationTestSupport {
    static final String FIXTURE_PASSWORD = "phase2-synthetic-login-only";
    static final List<String> DIAGNOSTICS = List.of("/api/diagnostics/debug/events",
            "/api/diagnostics/local-llm/smoke-history", "/api/diagnostics/debug/triadic-adjudication");
    @LocalServerPort int port;
    @Autowired AdministratorRepository administrators;
    @Autowired AdminService adminService;
    @Autowired ObjectMapper mapper;
    private final List<Long> fixtureIds = new ArrayList<>();

    String origin() { return "http://127.0.0.1:" + port; }

    String account(String role) {
        String username = "phase2-" + UUID.randomUUID();
        Administrator account = adminService.createIfAbsent(username, FIXTURE_PASSWORD, "synthetic fixture");
        account.setRole(role);
        administrators.saveAndFlush(account);
        fixtureIds.add(account.getId());
        return username;
    }

    @AfterEach void removeOwnedFixtures() {
        // HTTP server transactions do not participate in a test-method rollback.
        fixtureIds.forEach(administrators::deleteById);
        administrators.flush();
        fixtureIds.clear();
    }

    HttpClient client() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    HttpClient login(String username) throws Exception {
        HttpClient client = client();
        assertThat(get(client, "/login", null).statusCode()).isEqualTo(200);
        CookieManager cookies = (CookieManager) client.cookieHandler().orElseThrow();
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(cookie -> URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
        String form = "username=" + encode(username) + "&password=" + encode(FIXTURE_PASSWORD)
                + "&_csrf=" + encode(csrf);
        var response = client.send(request("/login", null).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/index");
        assertThat(get(client, "/index", null).statusCode()).isEqualTo(200);
        return client;
    }

    HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create(origin() + path)).timeout(Duration.ofSeconds(8));
        if (token != null) builder.header("X-Admin-Token", token);
        return builder;
    }

    HttpResponse<String> get(HttpClient client, String path, String token) throws Exception {
        return client.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    void assertJson(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(mapper.readTree(response.body())).isNotNull();
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
