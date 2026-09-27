package com.example.lms.integrations.n8n;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class N8nNotifierErrorBodyRedactionTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void errorResponseBodyIsLoggedAsHashOnly() throws Exception {
        String errorBody = "{\"error\":\"" + com.example.lms.test.SecretFixtures.openAiKey() + "\"}";
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(errorBody)
                        .build()));
        Logger logger = (Logger) LoggerFactory.getLogger(N8nNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            N8nNotifier notifier = new N8nNotifier(builder, new N8nProps());

            notifier.notify("https://8.8.8.8/notify", Map.of("status", "done"));

            String rendered = waitForLogs(appender);
            assertThat(rendered)
                    .doesNotContain(errorBody)
                    .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "")
                    .contains("bodyHash=")
                    .contains("bodyLength=");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void invalidCallbackUrlIsSkippedWithoutLoggingRawUrl() {
        String callbackUrl = "file:///C:/secret/callback?token=raw-callback-secret";
        Logger logger = (Logger) LoggerFactory.getLogger(N8nNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            N8nNotifier notifier = new N8nNotifier(WebClient.builder(), new N8nProps());

            notifier.notify(callbackUrl, Map.of("status", "done"));

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(rendered)
                    .contains("invalid_callback_url")
                    .contains("callbackHash=")
                    .contains("callbackLength=")
                    .doesNotContain(callbackUrl)
                    .doesNotContain("raw-callback-secret");
            assertThat(TraceStore.get("n8n.callback.skipped")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("n8n.callback.skipped.reason")).isEqualTo("invalid_callback_url");
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain(callbackUrl)
                    .doesNotContain("raw-callback-secret");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void malformedCallbackUrlLeavesRedactedTraceBreadcrumb() {
        String callbackUrl = "http://[raw-callback-secret";
        N8nNotifier notifier = new N8nNotifier(WebClient.builder(), new N8nProps());

        notifier.notify(callbackUrl, Map.of("status", "done"));

        assertThat(TraceStore.get("n8n.callback.suppressed.callbackUri")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("n8n.callback.suppressed.callbackUri.errorType")).isEqualTo("invalid_url");
        assertThat(String.valueOf(TraceStore.getAll()))
                .doesNotContain(callbackUrl)
                .doesNotContain("raw-callback-secret");
    }

    @Test
    void arbitraryCallbacksRejectInsecureAndNonPublicDestinations() throws Exception {
        List<String> rejected = List.of(
                "http://127.0.0.1:8080/internal",
                "https://10.0.0.1/internal",
                "https://169.254.169.254/latest",
                "https://[::1]/internal",
                "http://8.8.8.8/callback",
                "https://user@8.8.8.8/callback");

        assertThat(rejected).allSatisfy(callbackUrl ->
                assertThat(allowedCallback(callbackUrl)).as(callbackUrl).isFalse());
    }

    @Test
    void publicHttpsCallbackRemainsAllowed() throws Exception {
        assertThat(allowedCallback("https://8.8.8.8/callback")).isTrue();
    }

    @Test
    void configuredDefaultRetainsLocalHttpCompatibility() {
        AtomicInteger exchanges = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            exchanges.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        });
        N8nProps props = new N8nProps();
        props.setEnabled(true);
        props.setNotifyUrl("http://127.0.0.1:5678/webhook");
        N8nNotifier notifier = new N8nNotifier(builder, props);

        notifier.notifyDefault(Map.of("status", "done"));

        assertThat(exchanges).hasValue(1);
    }

    private static boolean allowedCallback(String callbackUrl) {
        try {
            Method method = N8nNotifier.class.getDeclaredMethod("isAllowedCallbackUrl", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, callbackUrl);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("callback validator invocation failed", exception);
        }
    }

    private static String waitForLogs(ListAppender<ILoggingEvent> appender) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (!appender.list.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
