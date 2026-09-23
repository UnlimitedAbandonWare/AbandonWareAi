package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.*;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import reactor.core.scheduler.Schedulers;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatStreamDisconnectHttpTest {
    @Test @Timeout(30)
    void physicalDisconnectCancelsOnlyAAndLateAIsRejectedAfterBCompletes() throws Exception {
        try (Fixture f = new Fixture()) {
            String hook = "strengthflow-worker-cleanup";
            Schedulers.onScheduleHook(hook, task -> () -> {
                try { task.run(); }
                finally { if (Thread.currentThread() == f.worker.get()) f.workerDone.countDown(); }
            });
            try {
                byte[] body = f.body("CANCEL fixture").getBytes(StandardCharsets.UTF_8);
                try (Socket socket = new Socket("127.0.0.1", f.port())) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(("POST /api/chat/stream HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    assertTrue(reader.readLine().contains("200"));
                    assertTrue(f.started.await(5, TimeUnit.SECONDS));
                    socket.setSoLinger(true, 0); // Actual TCP reset, not a mocked Reactor cancellation.
                }
                assertTrue(f.cancelled.await(5, TimeUnit.SECONDS), "existing keepalive must expose a dead TCP peer");
                assertTrue(f.interrupted.await(5, TimeUnit.SECONDS));
                assertEquals(1, f.workerDone.getCount(), "dispose/interrupt does not mean an uncooperative child stopped");
                assertTrue(f.firstRun.get().isCancellationRequested());
                String second = f.post("normal fixture");
                assertTrue(second.contains("fixture answer"));
                assertFalse(f.firstRun.get().tryBeginCommit());
                assertFalse(f.firstRun.get().runTerminalSideEffect(() -> fail("late A durable action accepted")));
                f.release.countDown();
                assertTrue(f.workerDone.await(5, TimeUnit.SECONDS), "observe actual scheduled worker return separately");
                verify(f.history, never()).appendMessageReturningId(42L, "assistant", "late A answer");
                verify(f.history, times(1)).appendMessageReturningId(42L, "assistant", "fixture answer");
                assertFalse(f.registry.isRunning(42L));
                System.out.println("chatDisconnect tcpResetObserved=true cancelAccepted=true interrupted=true workerReturnObserved=true latePersist=0 replacementPersist=1");
            } finally { f.release.countDown(); Schedulers.resetOnScheduleHook(hook); }
        }
    }

    @Test @Timeout(20)
    void servletControllerNormalAndProviderErrorStayDistinct() throws Exception {
        try (Fixture f = new Fixture()) {
            assertInstanceOf(AnnotationConfigServletWebServerApplicationContext.class, f.context);
            assertTrue(f.post("normal fixture").contains("fixture answer"));
            String failed = f.post("provider error");
            assertTrue(failed.contains("error"));
            assertFalse(failed.contains("fixture answer"));
            verify(f.history, times(1)).appendMessageReturningId(42L, "assistant", "fixture answer");
        }
    }

    @Test @Timeout(20)
    void browserProtocolAcknowledgesReadyAndFinalOverRealServletHttp() throws Exception {
        try (Fixture f = new Fixture()) {
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.port() + "/api/chat/stream"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream").header("X-Chat-Run-Ack-Required", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(f.body("normal fixture"))).build(), HttpResponse.BodyHandlers.ofLines());
            assertEquals(200, response.statusCode());
            String runToken = null;
            int readyCount = 0, finalCount = 0;
            try (var lines = response.body()) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) continue;
                    var event = mapper.readTree(line.substring(5).strip());
                    if ("session".equals(event.path("type").asText())) {
                        assertEquals(42L, event.path("sessionId").asLong());
                        runToken = event.path("data").asText();
                        assertFalse(runToken.isBlank());
                        assertTrue(f.ack(client, mapper, runToken, "ready"));
                        readyCount++;
                    } else if ("final".equals(event.path("type").asText())) {
                        assertEquals("fixture answer", event.path("data").asText());
                        assertNotNull(runToken);
                        assertTrue(f.ack(client, mapper, runToken, "final"));
                        finalCount++;
                    }
                }
            }
            assertEquals(1, readyCount, "one browser-readable exact-run capability event");
            assertEquals(1, finalCount, "one final event after ready ACK");
            verify(f.history, times(1)).appendMessageReturningId(42L, "assistant", "fixture answer");
            System.out.println("chatAck readyEvents=1 readyAccepted=true finalEvents=1 finalAccepted=true responsePersist=1");
        }
    }

    @Test @Timeout(240)
    @EnabledIfEnvironmentVariable(named = "STRENGTH_FLOW_BROWSER", matches = "1")
    void browserFixture() throws Exception {
        try (Fixture f = new Fixture()) {
            Path proof = Path.of("data/agent-handoff/strengthening-flow-20260915-01a09efc/browser-fixture.json");
            Files.writeString(proof, "{\"port\":" + f.port() + ",\"transport\":\"servlet-tomcat\",\"providers\":\"synthetic\",\"history\":\"mock-only\"}");
            assertTrue(f.browserDone.await(210, TimeUnit.SECONDS), "browser fixture has a bounded lifetime");
            f.release.countDown();
        }
    }

    static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService service = mock(ChatService.class);
        final ChatRunRegistry registry = new ChatRunRegistry();
        final CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1), interrupted = new CountDownLatch(1),
                release = new CountDownLatch(1), workerDone = new CountDownLatch(1), browserDone = new CountDownLatch(1);
        final AtomicReference<ChatRunExecutionContext> firstRun = new AtomicReference<>();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AnnotationConfigServletWebServerApplicationContext context = new AnnotationConfigServletWebServerApplicationContext();
        Fixture() {
            ReflectionTestUtils.setField(registry, "replayCapacity", 64);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
            when(owners.ownerKey()).thenReturn("fixture-owner");
            ChatSession session = new ChatSession("fixture", "fixture-owner", "ANON"); session.setId(42L);
            when(history.getSessionWithMessages(42L)).thenReturn(session);
            when(history.getSessionWithMessages(42L, 1)).thenReturn(session);
            when(history.startNewSession(any(), any(), any(), any(), any())).thenReturn(Optional.of(session));
            when(history.appendMessageReturningId(anyLong(), eq("assistant"), anyString())).thenReturn(4201L);
            when(service.continueChat(any(ChatRequestDto.class), any())).thenAnswer(call -> {
                ChatRequestDto request = call.getArgument(0);
                if (request.getMessage().contains("CANCEL")) {
                    worker.set(Thread.currentThread());
                    ChatRunExecutionContext run = ChatRunExecutionContext.current(); firstRun.set(run);
                    run.observeCancellation("fixture-observer", at -> cancelled.countDown());
                    started.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (release.getCount() != 0 && System.nanoTime() < deadline) {
                        try { release.await(1, TimeUnit.SECONDS); }
                        catch (InterruptedException signal) { interrupted.countDown(); }
                    }
                    return ChatResult.of("late A answer", "synthetic", false);
                }
                if (request.getMessage().contains("provider error")) throw new IllegalStateException("synthetic provider unavailable");
                return ChatResult.of("fixture answer", "synthetic", false);
            });
            ChatApiController controller = new ChatApiController(history, service, null, settings, null, null, null, null, null, null, null, null, null, null, null,
                    new ChatStreamEmitter(), new ObjectMapper(), null, registry, owners);
            context.registerBean(ChatApiController.class, () -> controller);
            context.registerBean(FixtureEndpoints.class, () -> new FixtureEndpoints(this));
            context.register(WebConfig.class); context.refresh();
        }
        int port() { return context.getWebServer().getPort(); }
        String body(String message) { return "{\"message\":\"" + message + "\",\"sessionId\":42,\"useRag\":false,\"useWebSearch\":false,\"memoryMode\":\"EPHEMERAL\"}"; }
        String post(String message) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/chat/stream"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(message))).build(), HttpResponse.BodyHandlers.ofString()).body();
        }
        boolean ack(HttpClient client, ObjectMapper mapper, String runToken, String phase) throws Exception {
            String body = mapper.writeValueAsString(Map.of("sessionId", 42L, "runToken", runToken, "phase", phase));
            var reply = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/chat/ack"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                    .header("X-Chat-Run-Token", runToken).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reply.statusCode());
            return mapper.readTree(reply.body()).path("acknowledged").asBoolean();
        }
        public void close() { release.countDown(); context.close(); ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }

    @Configuration @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {
        @Bean TomcatServletWebServerFactory webServerFactory() { return new TomcatServletWebServerFactory(0); }
        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        @Bean ServletRegistrationBean<DispatcherServlet> registration(DispatcherServlet servlet) {
            var registration = new ServletRegistrationBean<>(servlet, "/"); registration.setAsyncSupported(true); return registration;
        }
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) { resolvers.add(new AuthenticationPrincipalArgumentResolver()); }
        @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            // This isolated @EnableWebMvc fixture has no Boot JSON-converter auto-configuration.
            converters.add(0, new MappingJackson2HttpMessageConverter(new ObjectMapper()));
        }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) { registry.addResourceHandler("/js/**", "/css/**").addResourceLocations("classpath:/static/js/", "classpath:/static/css/"); }
    }
    @RestController static class FixtureEndpoints {
        final Fixture fixture;
        FixtureEndpoints(Fixture fixture) { this.fixture = fixture; }
        @GetMapping(value = "/chat-ui", produces = "text/html;charset=UTF-8") String ui() throws IOException { return Files.readString(Path.of("main/resources/templates/chat-ui.html")); }
        @PostMapping("/fixture/finish") Map<String, Boolean> finish() { fixture.browserDone.countDown(); return Map.of("done", true); }
    }
}
