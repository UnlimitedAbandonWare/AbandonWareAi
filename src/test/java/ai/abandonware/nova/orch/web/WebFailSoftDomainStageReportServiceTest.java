package ai.abandonware.nova.orch.web;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFailSoftDomainStageReportServiceTest {

    @Test
    void snapshotDoesNotExposeRawCanonicalQueryText() {
        WebFailSoftDomainStageReportService service =
                new WebFailSoftDomainStageReportService(new NovaWebFailSoftProperties(), null, null);
        String rawQuery = "supersecretcodename internal patient plan";

        service.record(Map.of(
                "host", "forum.example.com",
                "stage", "DEV_COMMUNITY",
                "cred", "UNVERIFIED",
                "selected", true,
                "by", "classified:" + rawQuery,
                "classifiedBy", "classified:" + rawQuery,
                "canonicalQuery", rawQuery,
                "intent", "TECH_API",
                "overridePath", "starvationFallback"));

        String rendered = service.snapshot(5, 1).toString();

        assertFalse(rendered.contains(rawQuery));
        assertFalse(rendered.contains("supersecretcodename"));
        assertFalse(rendered.contains("patient plan"));
        assertTrue(rendered.contains("hash12"));
    }

    @Test
    void diagnosticReasonsUseTraceLabelsNotSafeMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/web/WebFailSoftDomainStageReportService.java"));

        assertFalse(source.contains("String safe = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String safe = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
    }

    @Test
    void numericSystemPropertyHelpersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/web/WebFailSoftDomainStageReportService.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignore) {\n            return def;\n        }\n    }\n\n    private static long sysLong"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("traceSuppressed(\"safeCred\", e);"));
        assertTrue(source.contains("traceSuppressed(\"sysInt\", e);"));
        assertTrue(source.contains("traceSuppressed(\"sysLong\", e);"));
    }

    @Test
    void numericSystemPropertyParseFailuresUseStableInvalidNumberLabel() throws Exception {
        Method method = WebFailSoftDomainStageReportService.class.getDeclaredMethod(
                "errorType", String.class, Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null, "sysInt", new NumberFormatException("private-token")));
        assertEquals("invalid_number", method.invoke(null, "sysLong", new NumberFormatException("private-token")));
        assertEquals("SecurityException", method.invoke(null, "sysBool", new SecurityException("private-token")));
    }

    @Test
    void booleanSystemPropertyHelperOnlyCatchesSecurityException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/web/WebFailSoftDomainStageReportService.java"))
                .replace("\r\n", "\n");

        int start = source.indexOf("private static boolean sysBool");
        int end = source.indexOf("\n    private static int clampInt", start);
        assertTrue(start >= 0 && end > start, "sysBool helper should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception ignore)"),
                "boolean system property fallback should not hide arbitrary failures");
        assertFalse(helper.contains("catch (SecurityException ignore)"));
        assertTrue(helper.contains("catch (SecurityException e)"),
                "boolean system property fallback should only absorb security-manager property access denial");
        assertTrue(helper.contains("traceSuppressed(\"sysBool\", e);"));
    }
}
