package ai.abandonware.nova.orch.web.brave;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BraveAdaptiveQpsRestTemplateInterceptorTest {

    @Test
    void braveAdaptiveQpsInterceptorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsRestTemplateInterceptor.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Brave adaptive QPS diagnostics need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSuppressed(\"response.status\", e);"));
        assertTrue(source.contains("traceSuppressed(\"response.headers\", e);"));
        assertTrue(source.contains("traceSuppressed(\"ratelimit.reset.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"quota.resetAt\", e);"));
        assertTrue(source.contains("traceSuppressed(\"quota.latch\", e);"));
        assertTrue(source.contains("traceSuppressed(\"quota.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"safeGetRate\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.delta\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.rfc1123\", e);"));
        assertTrue(source.contains("traceSuppressed(\"RatePair.toLong\", e);"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertFalse(source.contains("catch (DateTimeParseException ignore)"));
    }

    @Test
    void safeReasonDoesNotReturnRawFreeFormText() throws Exception {
        String rawReason = "cooldown private query api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "";

        Method method = BraveAdaptiveQpsRestTemplateInterceptor.class.getDeclaredMethod(
                "safeReason",
                String.class);
        method.setAccessible(true);
        String safe = String.valueOf(method.invoke(null, rawReason));

        assertFalse(safe.contains(rawReason), safe);
        assertFalse(safe.contains("private query"), safe);
        assertFalse(safe.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""), safe);
        assertTrue(safe.startsWith("hash:"), safe);
    }

    @Test
    void numericParserSuppressionUsesStableInvalidNumberLabel() throws Exception {
        Method method = BraveAdaptiveQpsRestTemplateInterceptor.class.getDeclaredMethod(
                "suppressedErrorType",
                String.class,
                Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null, "RatePair.toLong", new NumberFormatException("overflow")));
        assertEquals("invalid_number", method.invoke(null, "parseRetryAfterMs.delta", new NumberFormatException("overflow")));
        assertEquals("invalid_date", method.invoke(null, "parseRetryAfterMs.rfc1123",
                new java.time.format.DateTimeParseException("bad retry-after", "not-a-date", 0)));
        assertEquals("IllegalStateException", method.invoke(null, "response.status", new IllegalStateException("bad")));
    }

    @Test
    void retryAfterDeltaSecondsOverflowSaturatesBeforeCooldownCap() throws Exception {
        Method method = BraveAdaptiveQpsRestTemplateInterceptor.class.getDeclaredMethod(
                "parseRetryAfterMs",
                String.class);
        method.setAccessible(true);

        long parsedMillis = (long) method.invoke(null, Long.toString(Long.MAX_VALUE));

        assertEquals(Long.MAX_VALUE, parsedMillis);
    }
}
