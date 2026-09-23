package ai.abandonware.nova.orch.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class TraceCorrelationRedactionContractTest {

    @Test
    void uawTickSeedStoresTraceCorrelationAsHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawTickTraceSeedAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"sid\", sid);"));
        assertFalse(source.contains("TraceStore.put(\"trace.id\", trace);"));
        assertFalse(source.contains("TraceStore.put(\"trace.runId\", trace);"));
        assertFalse(source.contains("data.put(\"sid\", sid);"));
        assertFalse(source.contains("data.put(\"trace\", trace);"));

        assertTrue(source.contains("TraceStore.put(\"sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("TraceStore.put(\"trace.id\", SafeRedactor.hashValue(trace));"));
        assertTrue(source.contains("TraceStore.put(\"trace.runId\", SafeRedactor.hashValue(trace));"));
        assertTrue(source.contains("data.put(\"sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("data.put(\"trace\", SafeRedactor.hashValue(trace));"));
    }

    @Test
    void debugPortMdcBridgeStoresTraceCorrelationAsHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"));

        assertFalse(source.contains("TraceStore.putIfAbsent(\"trace.id\", rid);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"sid\", sid);"));
        assertFalse(source.contains("TraceStore.put(\"ctx.mdc.bridge.rid\", SafeRedactor.redact(rid));"));
        assertFalse(source.contains("TraceStore.put(\"ctx.mdc.bridge.sid\", SafeRedactor.redact(sid));"));
        assertFalse(source.contains("ev.put(\"rid\", SafeRedactor.redact(rid));"));
        assertFalse(source.contains("ev.put(\"sid\", SafeRedactor.redact(sid));"));
        assertFalse(source.contains("ev.put(\"rid\", SafeRedactor.redact(_rid));"));
        assertFalse(source.contains("ev.put(\"sid\", SafeRedactor.redact(_sid));"));
        assertFalse(source.contains("extra.put(\"rid\", SafeRedactor.redact(rid));"));
        assertFalse(source.contains("extra.put(\"sid\", SafeRedactor.redact(sid));"));
        assertFalse(source.contains("extra.put(\"rid\", SafeRedactor.redact(_rid));"));
        assertFalse(source.contains("extra.put(\"sid\", SafeRedactor.redact(_sid));"));
        assertFalse(source.contains("ev.put(\"url\", SafeRedactor.redact(String.valueOf(request.url())));"));
        assertFalse(source.contains("extra.put(\"url\", SafeRedactor.redact(String.valueOf(request.url())));"));

        assertTrue(source.contains("TraceStore.putIfAbsent(\"trace.id\", SafeRedactor.hashValue(rid));"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("TraceStore.put(\"ctx.mdc.bridge.rid\", SafeRedactor.hashValue(rid));"));
        assertTrue(source.contains("TraceStore.put(\"ctx.mdc.bridge.sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("ev.put(\"rid\", SafeRedactor.hashValue(rid));"));
        assertTrue(source.contains("ev.put(\"sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("ev.put(\"rid\", SafeRedactor.hashValue(_rid));"));
        assertTrue(source.contains("ev.put(\"sid\", SafeRedactor.hashValue(_sid));"));
        assertTrue(source.contains("extra.put(\"rid\", SafeRedactor.hashValue(rid));"));
        assertTrue(source.contains("extra.put(\"sid\", SafeRedactor.hashValue(sid));"));
        assertTrue(source.contains("extra.put(\"rid\", SafeRedactor.hashValue(_rid));"));
        assertTrue(source.contains("extra.put(\"sid\", SafeRedactor.hashValue(_sid));"));
        assertTrue(source.contains("putUrlDiagnostics(ev, request);"));
        assertTrue(source.contains("putUrlDiagnostics(extra, request);"));
        assertTrue(source.contains("target.put(\"urlHash\", SafeRedactor.hashValue(raw));"));
        assertTrue(source.contains("target.put(\"urlLength\", raw == null ? 0 : raw.length());"));
    }

    @Test
    void conversationBreadcrumbStoresTraceCorrelationAsHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ConversationBreadcrumbAspect.java"));

        assertFalse(source.contains("TraceStore.putIfAbsent(\"conversation.sid\", convSid);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"conversation.requestSid\", requestSid);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"conversation.chatSessionId\", chatSessionId);"));
        assertFalse(source.contains("TraceContext.current().setFlag(\"conversation.sid\", convSid);"));
        assertFalse(source.contains("data.put(\"chatSessionId\", chatSessionId);"));
        assertFalse(source.contains("row.put(\"requestId\", firstNonBlank(MDC.get(\"x-request-id\"), TraceStore.getString(\"requestId\")));"));
        assertFalse(source.contains("row.put(\"sessionId\", convSid);"));

        assertTrue(source.contains("TraceStore.putIfAbsent(\"conversation.sid\", SafeRedactor.hashValue(convSid));"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"conversation.requestSid\", SafeRedactor.hashValue(requestSid));"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"conversation.chatSessionHash\", SafeRedactor.hashValue(String.valueOf(chatSessionId)));"));
        assertTrue(source.contains("TraceContext.current().setFlag(\"conversation.sid\", SafeRedactor.hashValue(convSid));"));
        assertTrue(source.contains("data.put(\"queryRedacted\", true);"));
        assertTrue(source.contains("data.put(\"stage\", \"conversation_sid\");"));
        assertTrue(source.contains("data.put(\"relevance\", 0.0d);"));
        assertTrue(source.contains("data.put(\"routeDecision\", \"conversation_sid_applied\");"));
        assertTrue(source.contains("data.put(\"chatSessionHash\", SafeRedactor.hashValue(String.valueOf(chatSessionId)));"));
        assertTrue(source.contains("row.put(\"requestId\", SafeRedactor.hashValue(firstNonBlank(MDC.get(\"x-request-id\"), TraceStore.getString(\"requestId\"))));"));
        assertTrue(source.contains("row.put(\"sessionId\", SafeRedactor.hashValue(convSid));"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"cihRag.breadcrumb.queryRedacted\", true);"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"cihRag.breadcrumb.stage\", \"conversation_sid\");"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"cihRag.breadcrumb.relevance\", 0.0d);"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"cihRag.breadcrumb.routeDecision\", \"conversation_sid_applied\");"));
    }

    @Test
    void conversationBreadcrumbAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ConversationBreadcrumbAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "conversation breadcrumb fail-soft paths need redacted breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void webFailSoftSoakKpiJsonStoresTraceCorrelationAsHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertFalse(source.contains("j.put(\"rid\", rid);"));
        assertFalse(source.contains("j.put(\"sessionId\", sessionId);"));
        assertTrue(source.contains("j.put(\"rid\", SafeRedactor.hashValue(rid));"));
        assertTrue(source.contains("j.put(\"sessionId\", SafeRedactor.hashValue(sessionId));"));
    }

    @Test
    void novaAnalyzeRetrieverLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), rawThrowableLogLines.toString());
    }
}
