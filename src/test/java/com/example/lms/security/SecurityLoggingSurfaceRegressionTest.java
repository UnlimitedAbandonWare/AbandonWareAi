package com.example.lms.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityLoggingSurfaceRegressionTest {
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("main/java"),
            Path.of("app/src/main/java_clean")
    );
    private static final Pattern LOG_GET_PASSWORD = Pattern.compile(
            "\\blog\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\([^;]*getPassword\\s*\\(",
            Pattern.DOTALL
    );
    private static final Pattern AUTH_TOKEN_LOG_USERNAME = Pattern.compile(
            "\\blog\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\([^;]*(?:token|토큰|cookie|쿠키)[^;]*,\\s*username\\s*\\)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AUTH_TOKEN_LOG_THROWABLE = Pattern.compile(
            "\\blog\\s*\\.\\s*(?:warn|error)\\s*\\([^;]*(?:token|토큰|cookie|쿠키)[^;]*,\\s*(?:e|ex|exception|cause)\\s*\\)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GUEST_IDENTITY_LOG_EXCEPTION_STRING = Pattern.compile(
            "\\blog\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\([^;]*Guest\\s+(?:cookie|IP)[^;]*\\.toString\\s*\\(",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WEB_SEARCH_FAILED_RAW_QUERY = Pattern.compile(
            "web search failed \\(query='\\{\\}'\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HYBRID_EMPTY_ARGS_RAW_QUERY = Pattern.compile(
            "searchWithTrace called but args invalid: query='\\{\\}'",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAVER_OVERLAY_RAW_QUERY = Pattern.compile(
            "NaverOverlay[^;]*query=\\{\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_QUERY_PLACEHOLDER_LOG = Pattern.compile(
            "query='\\{\\}'",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    void authLogsDoNotReadPasswordsDirectly() throws IOException {
        assertNoProductionSourceMatch(LOG_GET_PASSWORD, "log.*getPassword()");
    }

    @Test
    void authTokenLogsDoNotIncludeRawUsernames() throws IOException {
        assertNoProductionSourceMatch(AUTH_TOKEN_LOG_USERNAME, "auth token log username");
    }

    @Test
    void authTokenLogsDoNotAttachRawThrowables() throws IOException {
        assertNoProductionSourceMatch(AUTH_TOKEN_LOG_THROWABLE, "auth token log throwable");
    }

    @Test
    void guestIdentityLogsDoNotFormatRawExceptionStrings() throws IOException {
        assertNoProductionSourceMatch(GUEST_IDENTITY_LOG_EXCEPTION_STRING, "guest identity exception string");
    }

    @Test
    void webSearchFailureLogsDoNotFormatRawQueries() throws IOException {
        assertNoProductionSourceMatch(WEB_SEARCH_FAILED_RAW_QUERY, "web search failed raw query");
    }

    @Test
    void hybridEmptyFallbackInvalidArgsLogDoesNotFormatRawQuery() throws IOException {
        assertNoProductionSourceMatch(HYBRID_EMPTY_ARGS_RAW_QUERY, "hybrid empty args raw query");
    }

    @Test
    void naverOverlayLogsDoNotFormatRawQueries() throws IOException {
        assertNoProductionSourceMatch(NAVER_OVERLAY_RAW_QUERY, "naver overlay raw query");
    }

    @Test
    void productionLogsDoNotFormatRawQueryPlaceholders() throws IOException {
        assertNoProductionSourceMatch(RAW_QUERY_PLACEHOLDER_LOG, "raw query placeholder log");
    }

    private static void assertNoProductionSourceMatch(Pattern pattern, String label) throws IOException {
        StringBuilder offenders = new StringBuilder();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> files = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList();
                for (Path file : files) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String source;
                    try {
                        source = Files.readString(file);
                    } catch (NoSuchFileException ignored) {
                        continue;
                    }
                    if (pattern.matcher(source).find()) {
                        offenders.append(file).append(System.lineSeparator());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> label + " found in:" + System.lineSeparator() + offenders);
    }
}
