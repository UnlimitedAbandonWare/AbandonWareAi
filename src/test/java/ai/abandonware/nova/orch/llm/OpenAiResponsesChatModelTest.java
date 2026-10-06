package ai.abandonware.nova.orch.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.test.SecretFixtures;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesChatModelTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void mainAnswerContinuesOutputLimitOnceWithoutRepeatingItsPrefix(boolean oauth) throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<Map<?, ?>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(new ObjectMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            boolean first = requests.size() == 1;
            String text = first ? "The answer is " : "complete.";
            String response = "{\"id\":\"fixture-" + requests.size() + "\",\"model\":\"gpt-5.6-sol\","
                    + "\"status\":\"" + (first ? "incomplete" : "completed") + "\","
                    + (first ? "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}," : "")
                    + "\"output_text\":\"" + text + "\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2048,\"total_tokens\":2051}}";
            String body = oauth ? "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"" + text + "\"}\n\n"
                    + "event: response." + (first ? "incomplete" : "completed")
                    + "\ndata: {\"type\":\"response." + (first ? "incomplete" : "completed") + "\",\"response\":" + response + "}\n\n"
                    : response;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", oauth ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = oauth ? new OpenAiResponsesChatModel(base, "gpt-5.6-sol", 5_000L, () -> "synthetic-oauth-bearer")
                    : new OpenAiResponsesChatModel(base, "synthetic-api-key-value", "gpt-5.6-sol", 5_000L,
                            null, "primary", null, null, 2048);
            var answer = com.example.lms.llm.TimedChatModelCaller.chatResponse(model,
                    List.of(UserMessage.from("synthetic probe")), java.time.Duration.ofSeconds(10),
                    "chat_draft", "gpt-5.6-sol", null, null);
            assertEquals("The answer is complete.", answer.aiMessage().text());
            assertEquals(dev.langchain4j.model.output.FinishReason.STOP, answer.finishReason());
            assertEquals(4096, answer.tokenUsage().outputTokenCount());
            assertEquals(2, requests.size());
            var input = (List<?>) requests.get(1).get("input");
            assertEquals("assistant", ((Map<?, ?>) input.get(input.size() - 2)).get("role"));
            assertTrue(new ObjectMapper().writeValueAsString(input).contains("The answer is "));
            if (oauth) {
                assertFalse(requests.get(0).containsKey("max_output_tokens"));
                assertFalse(requests.get(1).containsKey("max_output_tokens"), "OAuth keeps its allowlisted wire contract");
            } else {
                assertEquals(2048, requests.get(0).get("max_output_tokens"));
                assertEquals(4096, requests.get(1).get("max_output_tokens"));
            }
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"completed","failed"})
    void oauthFoldReceivesUsefulSentenceBeforeCompletionWithoutReplay(String terminal) throws Exception {
        var first=new CountDownLatch(1);var release=new CountDownLatch(1);
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        String sentence="A useful first sentence. "+"x".repeat(40);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()){
                String delta=new ObjectMapper().writeValueAsString(Map.of("type","response.output_text.delta","sequence_number",2,"delta",sentence));
                out.write(("data: {\"type\":\"response.reasoning_text.delta\",\"sequence_number\":1,\"delta\":\"SYNTHETIC_PRIVATE_REASONING\"}\n\ndata: "+delta+"\n\ndata: "+delta+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                Map<String,Object> response="completed".equals(terminal)?Map.of("status","completed","model","gpt-5.6-luna","output_text",sentence):Map.of("status","failed","error",Map.of("code","server_error"));
                out.write(("data: "+new ObjectMapper().writeValueAsString(Map.of("type","response."+terminal,"sequence_number",3,"response",response))+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
            }
        });server.start();
        var registry=new com.example.lms.service.chat.ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(registry,"replayCapacity",16);
        var run=registry.beginOrJoin(9754L).context();var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            run.installFoldTextConsumer(text->{seen.add(text);first.countDown();});
            var future=pool.submit(()->{
                try(var binding=com.example.lms.service.chat.ChatRunExecutionContext.bind(run);var permit=run.permitFoldStreaming(true)){
                    return com.example.lms.llm.TimedChatModelCaller.chat(oauthFixtureModel(server),List.of(UserMessage.from("synthetic probe")),java.time.Duration.ofSeconds(5),"chat_draft","chatgpt-oauth:gpt-5.6-luna");
                }
            });
            assertTrue(first.await(3,TimeUnit.SECONDS));assertFalse(future.isDone(),"Fold useful text precedes provider completion");
            release.countDown();
            if("completed".equals(terminal)){
                String result=future.get(3,TimeUnit.SECONDS).text();run.requireFoldPrefix(result);
                assertEquals(sentence,result);
            }else{
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));
                assertNotNull(com.example.lms.llm.gateway.LlmResponseTerminalException.find(failure));
            }
            assertEquals(List.of("A useful first sentence."),seen);
            assertEquals(1,calls.get());
            assertTrue(run.foldTimings().containsKey("focus.stream.firstUsefulPublishedMs"));
        }finally{release.countDown();pool.shutdownNow();org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"shutdown");server.stop(0);}
    }

    @Test
    void oauthPayloadRetainsMemoryBeforeTheCurrentQuestion() throws Exception {
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                dev.langchain4j.data.message.SystemMessage.from("SYNTHETIC_SYSTEM_GUIDANCE"),
                UserMessage.from("기억: 코드워드 바람; 가운데 단계 검토; 제한 2개; 형식 짧은 표."),
                dev.langchain4j.data.message.AiMessage.from("OLDER_ASSISTANT_MARKER"),
                UserMessage.from("CURRENT_QUESTION_MARKER"));
        java.lang.reflect.Method serialize = OpenAiResponsesChatModel.class
                .getDeclaredMethod("responsesInput", List.class);
        serialize.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) serialize.invoke(null, messages);
        Map<String, Object> payload = OpenAiEndpointCompatibility
                .chatGptResponsesPayload("synthetic-model", input);
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(payload));

        assertEquals(4, wire.path("input").size());
        assertEquals(List.of("developer", "user", "assistant", "user"),
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(i -> wire.path("input").get(i).path("role").asText()).toList());
        assertEquals("SYNTHETIC_SYSTEM_GUIDANCE", wire.path("input").get(0).path("content").asText());
        String memory = wire.path("input").get(1).path("content").get(0).path("text").asText();
        assertTrue(memory.contains("코드워드 바람"));
        assertTrue(memory.contains("가운데 단계 검토"));
        assertTrue(memory.contains("제한 2개"));
        assertTrue(memory.contains("형식 짧은 표"));
        assertEquals("OLDER_ASSISTANT_MARKER", wire.path("input").get(2).path("content").asText());
        assertEquals("CURRENT_QUESTION_MARKER",
                wire.path("input").get(3).path("content").get(0).path("text").asText());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", ": heartbeat\n\n", ": heartbeat\r\n\r\n"})
    void sniffedSseConsumesTheOriginalBodyExactlyOnce(String prefix) {
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        var originalBody = reactor.core.publisher.Flux.defer(() -> {
            if (subscriptions.incrementAndGet() != 1)
                return reactor.core.publisher.Flux.<org.springframework.core.io.buffer.DataBuffer>error(
                        new IllegalStateException("Rejecting additional inbound receiver"));
            return reactor.core.publisher.Flux.<org.springframework.core.io.buffer.DataBuffer>just(
                    org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                            (prefix + oauthCompletedSse()).getBytes(StandardCharsets.UTF_8)));
        });
        var response = org.springframework.web.reactive.function.client.ClientResponse
                .create(org.springframework.http.HttpStatus.OK).body(originalBody).build();
        var model = new OpenAiResponsesChatModel("http://127.0.0.1:1/v1",
                "gpt-5.6-luna", 5_000L, () -> "synthetic-oauth-bearer");
        reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> decoded =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        model, "oauthSniffedBody", response, null, null);
        var events = decoded.collectList().block(java.time.Duration.ofSeconds(2));
        assertEquals(1, subscriptions.get(), "sniffing must not drain an already subscribed inbound body");
        assertNotNull(events);
        var data = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                .filter(java.util.Objects::nonNull).toList();
        assertEquals(List.of(
                "{\"type\":\"response.output_text.delta\",\"delta\":\"oauth answer\"}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"), data);
    }

    @Test
    void oauthContentTypeSseWithCharsetPreservesStreamingCompletion() throws Exception {
        assertOAuthContentTypeAnswer("text/event-stream; charset=utf-8", oauthCompletedSse(), "oauth answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"application/json", "application/vnd.openai+json"})
    void oauthContentTypeCompletedJsonUsesResponseMetadata(String contentType) throws Exception {
        assertOAuthContentTypeAnswer(contentType,
                "{\"id\":\"resp_fixture\",\"status\":\"completed\",\"model\":\"gpt-5.6-luna\","
                        + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"oauth answer\"}]}],"
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":2,\"total_tokens\":6}}", "oauth answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text/plain", "", "application/octet-stream"})
    void oauthContentTypeUnlabelledSseIsSniffed(String contentType) throws Exception {
        assertOAuthContentTypeAnswer(contentType, oauthCompletedSse(), "oauth answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text/plain", "", "application/octet-stream"})
    void oauthUnlabelledHeartbeatBeforeDataIsAccepted(String contentType) throws Exception {
        // A comment heartbeat can precede the first data or event field.
        assertOAuthContentTypeAnswer(contentType, ": heartbeat\n\n" + oauthCompletedSse(), "oauth answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text/plain", "", "application/octet-stream", "application/json"})
    void oauthContentTypeJsonOutputTextIsAccepted(String contentType) throws Exception {
        assertOAuthContentTypeAnswer(contentType,
                "{\"status\":\"completed\",\"output_text\":\"oauth answer\"}", "oauth answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text/html", "text/plain", "", "application/octet-stream"})
    void oauthContentTypeUnexpectedBodyFailsWithoutReplay(String contentType) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = startOAuthContentTypeServer(contentType, "<html>synthetic-private-body</html>", calls);
        try {
            var failure = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> oauthFixtureModel(server).chat(List.of(UserMessage.from("synthetic probe"))));
            assertEquals("chatgpt_oauth_unexpected_content_type", failure.reasonCode());
            assertEquals(LlmFailureClass.PROVIDER_ERROR, failure.failureClass());
            assertEquals(1, calls.get());
            assertFalse(failure.toString().contains("synthetic-private-body"));
            assertFalse(failure.toString().contains("synthetic-oauth-bearer"));
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"failed,responses_failed", "incomplete,output_limit_reached"})
    void oauthContentTypeJsonFailureRetainsResponseStateReason(String status, String reason) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = startOAuthContentTypeServer("application/json",
                "{\"status\":\"" + status + "\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}", calls);
        try {
            var failure = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> oauthFixtureModel(server).chat(List.of(UserMessage.from("synthetic probe"))));
            assertEquals(reason, failure.reasonCode());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "text/html; boundary=synthetic-private-parameter", "",
            "application/x-abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"})
    void oauthContentTypeDiagnosticsAreSanitizedAndBodyFree(String contentType) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = startOAuthContentTypeServer(contentType, "synthetic-private-body", calls);
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesChatModel.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var failure = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> oauthFixtureModel(server).chat(List.of(UserMessage.from("synthetic probe"))));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
            String headers = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("phase=http_headers ")).findFirst().orElseThrow();
            var matcher = java.util.regex.Pattern.compile(" contentType=([a-z0-9.+/-]{1,64})(?: |$)").matcher(headers);
            assertTrue(matcher.find(), headers);
            String expected = contentType.isEmpty() ? "absent" : contentType.split(";", 2)[0];
            assertEquals(expected.substring(0, Math.min(64, expected.length())), matcher.group(1));
            String retained = logs + failure + failure.getMessage();
            assertFalse(retained.contains("synthetic-private-body"));
            assertFalse(retained.contains("synthetic-private-parameter"));
            assertFalse(retained.contains("synthetic-oauth-bearer"));
            assertFalse(retained.contains("Authorization"));
            assertTrue(logs.contains("action=no_replay"));
            assertEquals(1, calls.get());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
            server.stop(0);
        }
    }

    @Test
    void oauthUpstreamHttpErrorKeepsStatusInTerminalTrace() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var model = new OpenAiResponsesChatModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "gpt-5.6-luna", 5_000L, () -> "synthetic-oauth-bearer");
            var failure = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("synthetic probe"))));
            assertEquals("chatgpt_oauth_http_503", failure.reasonCode());
            assertEquals(503, ((Number) TraceStore.get("chatgpt.oauth.upstreamHttpStatus")).intValue());
            assertTrue(String.valueOf(TraceStore.get("chatgpt.oauth.rootCause")).contains("Unavailable"));
        } finally { server.stop(0); }
    }

    @Test
    void oauthWrappedUpstreamHttpErrorPreservesStatusAndRootCause() {
        var model = new OpenAiResponsesChatModel("http://127.0.0.1:1/v1",
                "gpt-5.6-luna", 5_000L, () -> "synthetic-oauth-bearer");
        var wcre = org.springframework.web.reactive.function.client.WebClientResponseException.create(
                503, "Service Unavailable",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        var wrapped = new RuntimeException("reactor-wrapped", wcre);
        var terminal = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                OpenAiResponsesChatModel.class, "oauthFailure",
                "chatgpt_oauth_request_failed", LlmFailureClass.PROVIDER_ERROR, (String) null);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                model, "observeOauthTerminal", terminal, wrapped);
        assertEquals(503, ((Number) TraceStore.get("chatgpt.oauth.upstreamHttpStatus")).intValue());
        assertEquals(wcre.getClass().getSimpleName(), String.valueOf(TraceStore.get("chatgpt.oauth.rootCause")));
    }

    private static String oauthCompletedSse() {
        return "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"oauth answer\"}\n\n"
                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
    }

    @Test
    void oauthContentTypeUnsupportedOpenBodyRejectsBeforeEof() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("<html>synthetic-private-body".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            var model = new OpenAiResponsesChatModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "gpt-5.6-luna", 1_000L, () -> "synthetic-oauth-bearer");
            var failure = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("synthetic probe"))));
            assertEquals("chatgpt_oauth_unexpected_content_type", failure.reasonCode());
            assertEquals(LlmFailureClass.PROVIDER_ERROR, failure.failureClass());
            assertEquals(1, calls.get());
            assertEquals(1, release.getCount(), "failure must not depend on body EOF");
        } finally { release.countDown(); server.stop(0); }
    }

    private static OpenAiResponsesChatModel oauthFixtureModel(HttpServer server) {
        return new OpenAiResponsesChatModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "gpt-5.6-luna", 5_000L, () -> "synthetic-oauth-bearer");
    }

    private static void assertOAuthContentTypeAnswer(String contentType, String body, String expected) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = startOAuthContentTypeServer(contentType, body, calls);
        try {
            var response = oauthFixtureModel(server).chat(List.of(UserMessage.from("synthetic probe")));
            assertEquals(expected, response.aiMessage().text());
            if (body.contains("resp_fixture")) {
                assertEquals("resp_fixture", response.metadata().id());
                assertEquals("gpt-5.6-luna", response.metadata().modelName());
                assertEquals(6, response.tokenUsage().totalTokenCount());
            }
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    private static HttpServer startOAuthContentTypeServer(String contentType, String responseBody,
            java.util.concurrent.atomic.AtomicInteger calls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (!contentType.isEmpty()) exchange.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    @Test
    void responseUsagePreservesCacheReadCountAndServedModel() throws Exception {
        var request = new AtomicReference<String>("");
        var server = startResponsesServer("{\"status\":\"completed\",\"model\":\"gpt-5.6-luna\",\"output_text\":\"ok\",\"usage\":{\"input_tokens\":2048,\"output_tokens\":17,\"total_tokens\":2065,\"input_tokens_details\":{\"cached_tokens\":1024}}}", 200, request);
        try {
            var model = new OpenAiResponsesChatModel("http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "loopback-key-value", "gpt-5.6-luna", 700);
            var response = model.chat(List.of(UserMessage.from("public synthetic cache contract")));
            assertEquals("gpt-5.6-luna", response.metadata().modelName());
            assertTrue(response.tokenUsage() instanceof dev.langchain4j.model.openai.OpenAiTokenUsage);
            var usage = (dev.langchain4j.model.openai.OpenAiTokenUsage) response.tokenUsage();
            assertEquals(2065, usage.totalTokenCount());
            assertEquals(1024, usage.inputTokensDetails().cachedTokens());
        } finally { server.stop(0); }
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void routeFailureMessageUsesStableReasonInsteadOfExceptionClassName() {
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                "test-api-key",
                "gpt-5-mini",
                1_000L);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("error: responses-route-error"), text);
        assertFalse(text.contains("WebClientRequestException"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={" ", "sk-local", "test", "changeme", "${MISSING_KEY}"})
    void placeholderApiKeyReturnsExpectedFailureBeforeNetworkPath(String key) {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("responses-disabled-request", "responses-disabled-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "gpt-5-mini", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                key,
                "gpt-5-mini",
                1_000L,
                tracker);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("actionTaken: ROUTE_RESPONSES(no_api_key)"), text);
        assertFalse(text.contains("responses-route-error"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("failed", row.get("outcome"));
        assertEquals("disabled", row.get("failureClass"));
        assertEquals("configuration_error", row.get("terminalClass"));
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(0, row.get("responseUtf8ByteCount"));
        assertEquals(Boolean.FALSE, row.get("modelAdapterAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("responseObserved"));
    }

    @Test
    void outputCapMatchesSentPayloadAndOwnedOptionsHashWithoutInventingAbsentCap() throws Exception {
        for (Integer cap : new Integer[]{64, 32, null, 0, -1}) {
            AtomicReference<String> requestBody = new AtomicReference<>("");
            HttpServer server = startResponsesServer("{\"status\":\"completed\",\"output_text\":\"bounded ok\"}", 200, requestBody);
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timeline = pendingTimeline(tracker, "bounded-request", "bounded-session");
            try {
                OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "loopback-key-value", "gpt-usage-test", 1_000L,
                        tracker, "primary", null, null, cap);
                ChatResponse response = model.chat(List.of(UserMessage.from("bounded probe")));
                assertEquals("bounded ok", response.aiMessage().text());
                assertNull(response.tokenUsage(), "missing provider usage must remain unknown");
                Map<?, ?> payload = new ObjectMapper().readValue(requestBody.get(), Map.class);
                Integer expectedCap = cap != null && cap > 0 ? cap : null;
                assertEquals(expectedCap, payload.get("max_output_tokens"));
                assertEquals(expectedCap != null, payload.containsKey("max_output_tokens"));
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("maxOutputTokens", expectedCap);
                options.put("timeoutMs", 5_000L);
                options.put("maxRetries", 0);
                options.put("fallbackEnabled", false);
                String expectedJson = canonicalOptions(ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                        "openai", "gpt-usage-test", "openai_responses", options));
                List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timeline);
                assertEquals(1, rows.size());
                assertEquals(SafeRedactor.hashValue(expectedJson), rows.get(0).get("optionsHash"));
                assertEquals(exactSha256(requestBody.get()), rows.get(0).get("httpRequestBodyHash"));
                assertEquals(Boolean.FALSE, rows.get(0).get("providerAttemptObserved"));
                assertEquals(Boolean.FALSE, rows.get(0).get("wireAttemptObserved"));
            } finally {
                server.stop(0);
                TraceStore.clear();
            }
        }
    }

    @Test
    void responsesRouteDoesNotUseNoopOnErrorResume() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"));

        assertFalse(source.contains(".onErrorResume(e -> Mono.error(e))"));
        assertFalse(source.contains("import reactor.core.publisher.Mono;"));
    }

    @Test
    void malformedResponsesJsonWritesRedactedParseBreadcrumb() throws Exception {
        String body = "{\"status\":\"completed\",\"output_text\":\"" + SecretFixtures.openAiKey() + "\"";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            var failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("hello"))));
            assertEquals("responses_contract_error", failure.reasonCode());
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.failed")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.bodyPresent")));
            assertTrue(TraceStore.get("llm.responses.parse.bodyHash").equals(SafeRedactor.hashValue(body)));
            assertTrue(TraceStore.get("llm.responses.parse.bodyLength").equals(body.length()));
            assertTrue(TraceStore.get("llm.responses.parse.reason").equals("invalid_response_json"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.invalid.suppressed")));
            assertFalse(TraceStore.getAll().containsValue(body));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatCompletionsCompatibleChoicesResponseExtractsAssistantText() throws Exception {
        String body = "{\"status\":\"completed\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"compat hello\"}}]}";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            ChatResponse response = model.chat(List.of(UserMessage.from("hello")));

            assertTrue(response.aiMessage().text().contains("compat hello"));
            assertTrue("CHAT_COMPAT".equals(TraceStore.get("responsesModel.format")));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.emptyOutput")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitReasoningEffortAddsNestedResponsesOption() throws Exception {
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(
                "{\"status\":\"completed\",\"output_text\":\"reasoned response\"}", 200, receivedRequest);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "zai/glm-5.2",
                    5_000L,
                    null,
                    "primary",
                    null,
                    "high");

            ChatResponse response = model.chat(List.of(UserMessage.from("reason carefully")));

            assertEquals("reasoned response", response.aiMessage().text());
            var payload = new ObjectMapper().readTree(receivedRequest.get());
            assertEquals("high", payload.path("reasoning").path("effort").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyConstructorsOmitReasoningOption() throws Exception {
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(
                "{\"status\":\"completed\",\"output_text\":\"legacy response\"}", 200, receivedRequest);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            new OpenAiResponsesChatModel(baseUrl, "test-api-key", "gpt-5-mini", 5_000L)
                    .chat(List.of(UserMessage.from("legacy four argument constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));

            receivedRequest.set("");
            new OpenAiResponsesChatModel(
                    baseUrl,
                    "test-api-key",
                    "gpt-5-mini",
                    5_000L,
                    new ModelRuntimeHealthTracker())
                    .chat(List.of(UserMessage.from("legacy tracker constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));

            receivedRequest.set("");
            new OpenAiResponsesChatModel(
                    baseUrl,
                    "test-api-key",
                    "gpt-5-mini",
                    5_000L,
                    new ModelRuntimeHealthTracker(),
                    "fallback",
                    null)
                    .chat(List.of(UserMessage.from("legacy router constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callerInterruptIsRethrownInsteadOfBecomingDiagnosticSuccess() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = startBlockingResponsesServer(requestEntered, releaseResponse);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker, "responses-cancel-request-private", "responses-cancel-session-private");
        String rawPrompt = "private prompt must not enter diagnostics";
        String keyLikeSentinel = SecretFixtures.openAiKey();
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                keyLikeSentinel,
                "gpt-5-mini",
                30_000L,
                tracker,
                "primary",
                tracker.redactedRequestAttemptRoute(
                        "cancel-route-private", "cancel-model-private",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                        "openai_responses"));
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            TraceStore.clear();
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            try {
                response.set(model.chat(List.of(UserMessage.from(rawPrompt))));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                TraceStore.clear();
            }
        }, "openai-responses-interrupt-contract");
        caller.setDaemon(true);

        try {
            caller.start();
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS), "loopback request must start");
            caller.interrupt();
            caller.join(2_000L);

            assertFalse(caller.isAlive(), "interrupted blocking caller must terminate");
            assertNull(response.get(), "cancellation must not become a diagnostic ChatResponse");
            assertTrue(hasCancellationCause(failure.get()),
                    "cancellation cause must be preserved, failure=" + failure.get());
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("cancelled", row.get("outcome"));
            assertEquals("cancelled_neutral", row.get("failureClass"));
            assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("responseObserved"));
            String retained = new ObjectMapper().writeValueAsString(
                    tracker.redactedRequestAttemptLedger(timelineId));
            assertFalse(retained.contains(rawPrompt));
            assertFalse(retained.contains(keyLikeSentinel));
        } finally {
            releaseResponse.countDown();
            caller.interrupt();
            caller.join(2_000L);
            server.stop(0);
        }
    }

    @Test
    void typedQuotaReasonSurvivesResponsesConversionWithoutRetainingTheBody() throws Exception {
        String sentinel = SecretFixtures.openAiKey();
        String errorBody = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"free_limit_reached: "
                + sentinel + " synthetic-private-detail\"}}";
        HttpServer server = startResponsesServer(errorBody, 429, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "quota-request", "quota-session");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    baseUrl, "loopback-key-value", "gpt-quota-loopback", 5_000L, tracker, "primary",
                    tracker.redactedRequestAttemptRoute("quota-route", "gpt-quota-loopback",
                            baseUrl + "/responses", "openai_responses"));
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("synthetic quota probe"))));
            assertEquals("insufficient_quota", failure.reasonCode());
            assertEquals(LlmFailureClass.RATE_LIMIT_COOLDOWN, failure.failureClass());
            assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable());
            assertNull(failure.getCause());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            String retained = failure + new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
            assertFalse(retained.contains(sentinel));
            assertFalse(retained.contains(errorBody));
            assertFalse(retained.contains("synthetic-private-detail"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void trackerAndTypedRouteUseTheSameCategoricalHttpFailureClassWithoutRawEvidence() throws Exception {
        int[] statuses = {401, 403, 429, 503};
        LlmFailureClass[] expectedClasses = {
                LlmFailureClass.AUTH_MISSING,
                LlmFailureClass.AUTH_MISSING,
                LlmFailureClass.RATE_LIMIT_COOLDOWN,
                LlmFailureClass.HEALTH_DOWN
        };
        String[] expectedLedgerClasses = {
                "auth_missing", "auth_missing", "rate_limit_cooldown", "health_down"
        };
        String[] expectedReasonCodes = {
                "responses_http_401", "responses_http_403",
                "responses_http_429", "responses_http_503"
        };

        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            String rawPrompt = "responses-http-private-prompt-" + status;
            String keyLikeSentinel = SecretFixtures.openAiKey();
            String errorBody = "{\"error\":\"" + keyLikeSentinel + "-private-body-" + status + "\"}";
            HttpServer server = startResponsesServer(errorBody, status, new AtomicReference<>(""));
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = pendingTimeline(
                    tracker, "responses-http-request-" + status, "responses-http-session-" + status);
            try {
                OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        keyLikeSentinel,
                        "responses-http-model-private-" + status,
                        5_000L,
                        tracker,
                        "primary",
                        tracker.redactedRequestAttemptRoute(
                                "responses-http-route-private-" + status,
                                "responses-http-model-private-" + status,
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                                "openai_responses"));

                LlmGatewayException failure = assertThrows(
                        LlmGatewayException.class,
                        () -> model.chat(List.of(UserMessage.from(rawPrompt))),
                        "status=" + status);

                assertEquals(expectedClasses[index], failure.failureClass(), "status=" + status);
                assertEquals(expectedReasonCodes[index], failure.reasonCode(), "status=" + status);
                List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
                assertEquals(1, rows.size(), "status=" + status);
                Map<String, Object> row = rows.get(0);
                assertEquals("failed", row.get("outcome"), "status=" + status);
                assertEquals(expectedLedgerClasses[index], row.get("failureClass"), "status=" + status);
                assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"), "status=" + status);
                assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("responseObserved"), "status=" + status);
                String retained = new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
                assertFalse(retained.contains(rawPrompt), "status=" + status);
                assertFalse(retained.contains(errorBody), "status=" + status);
                assertFalse(retained.contains(keyLikeSentinel), "status=" + status);
            } finally {
                server.stop(0);
                TraceStore.clear();
            }
        }
    }

    @Test
    void trustedGatewayRoutingReceiptParserFailsClosedOutsideTheExactVercelResponsesBoundary() {
        String coherent = """
                {
                  "status":"completed",
                  "output_text":"ok",
                  "providerMetadata":{"gateway":{"routing":{
                    "finalProvider":"zai",
                    "attempts":[
                      {"provider":"bedrock","success":false,"statusCode":503},
                      {"provider":"zai","success":true,"statusCode":200}
                    ],
                    "totalProviderAttemptCount":2
                  }}}
                }
                """;

        OpenAiResponsesChatModel.GatewayProviderReceipt receipt =
                OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                        "https://ai-gateway.vercel.sh/v1/responses",
                        "zai/glm-5.2",
                        coherent);

        assertNotNull(receipt);
        assertEquals("zai", receipt.provider());
        assertEquals(200, receipt.statusCode());
        assertEquals(2, receipt.totalProviderAttemptCount());
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "http://127.0.0.1:18080/v1/responses", "zai/glm-5.2", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses?unsafe=true", "zai/glm-5.2", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "openai/gpt-5", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"totalProviderAttemptCount\":2", "\"totalProviderAttemptCount\":1")));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"success\":false", "\"success\":true")));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"providerMetadata\"", "\"provider_metadata\"")));
    }

    @Test
    void malformedTrustedGatewayRoutingReceiptRecordsRedactedSuppressionWithoutBodyLeak() throws Exception {
        String sensitiveBodyMarker = "gateway-private-body-sentinel";
        String malformed = "{\"providerMetadata\":\"" + sensitiveBodyMarker;

        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2", malformed));

        assertEquals(Boolean.TRUE,
                TraceStore.get("llm.responses.gatewayReceipt.parse.suppressed"));
        assertNotNull(TraceStore.get("llm.responses.gatewayReceipt.parse.errorType"));
        String retained = new ObjectMapper().writeValueAsString(TraceStore.getAll());
        assertFalse(retained.contains(sensitiveBodyMarker));
        assertFalse(retained.contains(malformed));
    }

    @Test
    void loopbackResponsesCannotSelfPromoteWithCoherentLookingGatewayMetadata() throws Exception {
        String assistantText = "loopback-attestation-result";
        String responseBody = """
                {
                  "status":"completed",
                  "output_text":"loopback-attestation-result",
                  "providerMetadata":{"gateway":{"routing":{
                    "finalProvider":"zai",
                    "attempts":[{"provider":"zai","success":true,"statusCode":200}],
                    "totalProviderAttemptCount":1
                  }}}
                }
                """;
        HttpServer server = startResponsesServer(responseBody, 200, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "loopback-attestation-request", "loopback-attestation-session");
        try {
            OpenAiResponsesChatModel model = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "zai/glm-5.2",
                    5_000L,
                    tracker);

            ChatResponse response = model.chat(List.of(UserMessage.from("loopback-attestation-prompt")));

            assertEquals(assistantText, response.aiMessage().text());
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("client_http_response", row.get("evidenceBoundary"));
            assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directResponsesHttpSuccessRecordsExactSentAndReceivedUtf8EvidenceWithoutOuterDuplicate() throws Exception {
        String rawPrompt = "responses-request-private-가";
        String assistantText = "responses-response-private-나";
        String responseBody = "{\"status\":\"completed\",\"output_text\":\"" + assistantText + "\"}";
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(responseBody, 200, receivedRequest);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "responses-request-id-private", "responses-session-id-private");
        try {
            OpenAiResponsesChatModel direct = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-pro-private",
                    1_000L,
                    tracker);
            ChatResponse response = tracker.decorateRequestAttempt(
                    direct,
                    "primary",
                    tracker.redactedRequestAttemptRoute(
                            "outer-route-private",
                            "outer-model-private",
                            "http://127.0.0.1:1/v1",
                            "openai_responses"),
                    ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                            "openai", "outer-model-private", "openai_responses",
                            Map.of("timeoutMs", 1_000L, "maxRetries", 0)))
                    .chat(List.of(UserMessage.from(rawPrompt)));

            assertEquals(assistantText, response.aiMessage().text());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size(), "the direct exchange must suppress the outer generic row");
            Map<String, Object> row = rows.get(0);
            String expectedPromptJson = canonicalMessages(rawPrompt);
            String expectedOptionsJson = canonicalOptions(ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    "openai", "outer-model-private", "openai_responses",
                    Map.of("timeoutMs", 1_000L, "maxRetries", 0)));
            assertEquals(SafeRedactor.hashValue(expectedPromptJson), row.get("promptHash"));
            assertEquals(SafeRedactor.hashValue(expectedOptionsJson), row.get("optionsHash"));
            assertEquals(SafeRedactor.hashValue(assistantText), row.get("responseHash"));
            assertEquals(expectedPromptJson.getBytes(StandardCharsets.UTF_8).length,
                    row.get("promptUtf8ByteCount"));
            assertEquals(expectedOptionsJson.getBytes(StandardCharsets.UTF_8).length,
                    row.get("optionsUtf8ByteCount"));
            assertEquals(assistantText.getBytes(StandardCharsets.UTF_8).length,
                    row.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(receivedRequest.get()), row.get("httpRequestBodyHash"));
            assertEquals(receivedRequest.get().getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpRequestBodyUtf8ByteCount"));
            assertEquals(exactSha256(responseBody), row.get("httpResponseBodyHash"));
            assertEquals(responseBody.getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, row.get("modelAdapterAttemptObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.TRUE, row.get("responseObserved"));

            String retained = new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
            assertFalse(retained.contains(rawPrompt));
            assertFalse(retained.contains(assistantText));
            assertFalse(retained.contains(responseBody));
            assertFalse(retained.contains("responses-request-id-private"));
            assertFalse(retained.contains("responses-session-id-private"));
            assertFalse(retained.contains("gpt-5-pro-private"));
            assertFalse(retained.contains("private-option"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directResponsesHttpErrorRetainsReceivedErrorBodyProofWithoutRawData() throws Exception {
        String errorBody = "{\"error\":\"" + SecretFixtures.openAiKey() + "-오류\"}";
        HttpServer server = startResponsesServer(errorBody, 503, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "responses-error-request-private", "responses-error-session-private");
        try {
            ChatResponse response = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-pro-private",
                    1_000L,
                    tracker).chat(List.of(UserMessage.from("responses-error-prompt-private")));

            assertTrue(response.aiMessage().text().contains("httpStatus: 503"));
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("failed", row.get("outcome"));
            assertEquals("hash:unknown", row.get("responseHash"));
            assertEquals(0, row.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(errorBody), row.get("httpResponseBodyHash"));
            assertEquals(errorBody.getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("responseObserved"));
            assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(timelineId))
                    + TraceStore.getAll()).contains(errorBody));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multimodalProviderFailureLogExceptionAndLedgerNeverRetainBase64Sentinel() throws Exception {
        byte[] marker = ("awx-provider-image-boundary-" + "91e4b7c3")
                .getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[8 + marker.length];
        byte[] signature = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, png, 0, signature.length);
        System.arraycopy(marker, 0, png, signature.length, marker.length);
        String encoded = Base64.getEncoder().encodeToString(png);
        int fragmentStart = encoded.length() / 3;
        String fragment = encoded.substring(fragmentStart, Math.min(encoded.length(), fragmentStart + 18));
        String markerText = new String(marker, StandardCharsets.UTF_8);

        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer("{\"error\":\"bounded\"}", 503, receivedRequest);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker, "multimodal-failure-request", "multimodal-failure-session");
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-api-key",
                "gpt-5-pro-private",
                5_000L,
                tracker,
                "primary",
                tracker.redactedRequestAttemptRoute(
                        "multimodal-failure-route",
                        "gpt-5-pro-private",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                        "openai_responses"));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesChatModel.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        try {
            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from(
                            TextContent.from("describe"),
                            ImageContent.from(encoded, "image/png")))));
            assertEquals(LlmFailureClass.DISABLED, failure.failureClass());
            assertEquals("vision_model_unavailable", failure.reasonCode());
            assertEquals("", receivedRequest.get());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            assertEquals("disabled", rows.get(0).get("failureClass"));
            assertEquals(Boolean.FALSE, rows.get(0).get("wireAttemptObserved"));
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            String retained = logged
                    + failure
                    + failure.getMessage()
                    + new ObjectMapper().writeValueAsString(rows)
                    + TraceStore.getAll();
            assertFalse(retained.contains(encoded));
            assertFalse(retained.contains(fragment));
            assertFalse(retained.contains(markerText));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            server.stop(0);
        }
    }

    @Test
    void malformedReceivedBodyAndTransportFailureKeepDistinctHttpObservationStates() throws Exception {
        String malformedBody = "{\"status\":\"completed\",\"output_text\":\"malformed-private-다\"";
        HttpServer server = startResponsesServer(malformedBody, 200, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String malformedTimeline = pendingTimeline(tracker, "malformed-request-private", "malformed-session-private");
        try {
            assertThrows(LlmGatewayException.class, () -> trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key", "gpt-5-pro-private", 1_000L, tracker)
                    .chat(List.of(UserMessage.from("malformed-prompt-private"))));
            Map<String, Object> malformed = tracker.redactedRequestAttemptLedger(malformedTimeline).get(0);
            assertEquals("failed", malformed.get("outcome"));
            assertEquals("hash:unknown", malformed.get("responseHash"));
            assertEquals(0, malformed.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(malformedBody), malformed.get("httpResponseBodyHash"));
            assertEquals(malformedBody.getBytes(StandardCharsets.UTF_8).length,
                    malformed.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, malformed.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, malformed.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, malformed.get("responseObserved"));
            assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(malformedTimeline))
                    + TraceStore.getAll()).contains(malformedBody));
        } finally {
            server.stop(0);
        }

        HttpServer portReservation = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int unusedPort = portReservation.getAddress().getPort();
        portReservation.stop(0);
        String transportTimeline = pendingTimeline(tracker, "transport-request-private", "transport-session-private");
        trackerAwareModel("http://127.0.0.1:" + unusedPort + "/v1", "test-api-key",
                "gpt-5-pro-private", 1_000L, tracker)
                .chat(List.of(UserMessage.from("transport-prompt-private")));
        Map<String, Object> transport = tracker.redactedRequestAttemptLedger(transportTimeline).get(0);
        String sentBody = "{\"model\":\"gpt-5-pro-private\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"transport-prompt-private\"}]}]}";
        assertEquals("failed", transport.get("outcome"));
        assertEquals("hash:unknown", transport.get("responseHash"));
        assertEquals(0, transport.get("responseUtf8ByteCount"));
        assertEquals(exactSha256(sentBody), transport.get("httpRequestBodyHash"));
        assertEquals(sentBody.getBytes(StandardCharsets.UTF_8).length,
                transport.get("httpRequestBodyUtf8ByteCount"));
        assertEquals("hash:unknown", transport.get("httpResponseBodyHash"));
        assertEquals(0, transport.get("httpResponseBodyUtf8ByteCount"));
        assertEquals(Boolean.TRUE, transport.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, transport.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, transport.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, transport.get("wireAttemptObserved"));
        assertEquals(Boolean.FALSE, transport.get("responseObserved"));
        assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(transportTimeline))
                + TraceStore.getAll()).contains("transport-prompt-private"));
    }

    private static HttpServer startResponsesServer(String responseBody) throws IOException {
        return startResponsesServer(responseBody, 200, new AtomicReference<>(""));
    }

    private static HttpServer startResponsesServer(
            String responseBody,
            int status,
            AtomicReference<String> receivedRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static HttpServer startBlockingResponsesServer(
            CountDownLatch requestEntered,
            CountDownLatch releaseResponse) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestEntered.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"status\":\"completed\",\"output_text\":\"late\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static boolean hasCancellationCause(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException
                    || cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String pendingTimeline(
            ModelRuntimeHealthTracker tracker,
            String requestId,
            String sessionId) {
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", "gpt-5-pro-private", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return timelineId;
    }

    private static OpenAiResponsesChatModel trackerAwareModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker tracker) {
        return new OpenAiResponsesChatModel(baseUrl, apiKey, modelName, timeoutMs, tracker);
    }

    private static String exactSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return "sha256:" + hex;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String canonicalMessages(String content) throws Exception {
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("role", "user");
        row.put("content", content);
        return new ObjectMapper().writeValueAsString(List.of(row));
    }

    private static String canonicalOptions(Map<String, ?> options) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper.writeValueAsString(options);
    }
}
