package com.example.lms.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.lms.search.TraceStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class VersionPurityHealthIndicatorTest {

    private final String originalClasspath = System.getProperty("java.class.path", "");

    @AfterEach
    void restoreClasspath() {
        System.setProperty("java.class.path", originalClasspath);
        TraceStore.clear();
    }

    @Test
    void exactPinnedLangchain4jJarsRemainUp() {
        setClasspath("langchain4j-core-1.0.1.jar", "langchain4j-open-ai-1.0.1.jar");

        Health health = new VersionPurityHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("OK", health.getDetails().get("langchain4jVersion"));
        assertEquals(Set.of("1.0.1"), health.getDetails().get("langchain4jVersions"));
        assertEquals("PASS", TraceStore.get("llm.versionPurity"));
    }

    @Test
    void prereleaseLangchain4jJarIsRuntimeMismatch() {
        setClasspath("langchain4j-core-1.0.1-beta1.jar");

        Health health = new VersionPurityHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("MISMATCH", health.getDetails().get("langchain4jVersion"));
        assertEquals("langchain4j-version-purity", health.getDetails().get("reason"));
        assertEquals(Set.of("1.0.1-beta1"), health.getDetails().get("langchain4jVersions"));
        assertEquals("FAIL", TraceStore.get("llm.versionPurity"));
        assertTrue(String.valueOf(TraceStore.get("llm.versionPurity.mismatch")).contains("1.0.1-beta1"));
    }

    @Test
    void invalidClasspathEntryLeavesTraceBreadcrumb() {
        TraceStore.clear();
        System.setProperty("java.class.path", "\u0000");

        Health health = new VersionPurityHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("versionPurity.classpathEntry", TraceStore.get("version.purity.suppressed.stage"));
        assertEquals("InvalidPathException", TraceStore.get("version.purity.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("version.purity.suppressed.versionPurity.classpathEntry"));
        assertEquals("InvalidPathException",
                TraceStore.get("version.purity.suppressed.versionPurity.classpathEntry.errorType"));
        TraceStore.clear();
    }

    @Test
    void bootTimePurityGuardsUseExactPinnedVersionContract() throws Exception {
        String packageGuard = Files.readString(
                Path.of("main/java/com/example/lms/boot/VersionPurityCheck.java"),
                StandardCharsets.UTF_8);
        String manifestGuard = Files.readString(
                Path.of("main/java/com/example/lms/boot/StartupVersionPurityCheck.java"),
                StandardCharsets.UTF_8);
        String healthIndicator = Files.readString(
                Path.of("main/java/com/example/lms/boot/VersionPurityHealthIndicator.java"),
                StandardCharsets.UTF_8);

        assertTrue(packageGuard.contains("!EXPECTED_PREFIX.equals(implVer)"));
        assertTrue(manifestGuard.contains("!EXPECTED_PREFIX.equals(ver)"));
        assertTrue(healthIndicator.contains("traceSuppressed(\"versionPurity.classpathEntry\", ignore);"));
        assertFalse(packageGuard.contains("!implVer.startsWith(EXPECTED_PREFIX)"));
        assertFalse(manifestGuard.contains("!ver.startsWith(EXPECTED_PREFIX)"));
    }

    private static void setClasspath(String... jars) {
        String[] entries = new String[jars.length];
        for (int i = 0; i < jars.length; i++) {
            entries[i] = "C:\\awx-test\\" + jars[i];
        }
        System.setProperty("java.class.path", String.join(File.pathSeparator, entries));
    }
}
