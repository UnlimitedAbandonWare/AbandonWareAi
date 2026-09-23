package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBreakBeanNameCollisionContractTest {

    @Test
    void novaProtocolRuleBreakWebFilterUsesExplicitBeanName() throws Exception {
        String source = Files.readString(Path.of("main/java/com/nova/protocol/filter/RuleBreakInterceptor.java"));

        assertTrue(source.contains("@Component(\"novaProtocolRuleBreakWebFilter\")"),
                "com.nova.protocol and com.example.lms both define RuleBreakInterceptor; the web filter needs a distinct bean name");
        assertTrue(source.contains("RuleBreak token rejected tokenLength={0} errorType={1}"));
        assertFalse(source.contains("LOG.log(System.Logger.Level.DEBUG, tok"));
    }

    @Test
    void guardRuleBreakInterceptorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "guard rulebreak interceptor fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void guardRuleBreakInterceptorFailSoftPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java"));

        assertTrue(source.contains("traceSuppressed(\"ruleBreak.preHandle\", t);"));
        assertTrue(source.contains("traceSuppressed(\"ruleBreak.contextHolder\", t);"));
        assertTrue(source.contains("traceSuppressed(\"ruleBreak.contextEmit\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"ruleBreak.afterCompletion\", t);"));
        assertTrue(source.contains("TraceStore.put(\"rulebreak.interceptor.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"rulebreak.interceptor.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"rulebreak.interceptor.suppressed.errorType\", safeErrorType);"));
    }

    @Test
    void guardRuleBreakInterceptorDoesNotEmitRawRemoteAddressInDebugEvents() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java"));

        assertFalse(source.contains("\"remote\", req.getRemoteAddr()"),
                "fail-soft debug events should not persist raw client addresses");
        assertTrue(source.contains("\"remoteHash\", SafeRedactor.hashValue(req.getRemoteAddr())"),
                "client address diagnostics should be hash-only when emitted");
    }
}
