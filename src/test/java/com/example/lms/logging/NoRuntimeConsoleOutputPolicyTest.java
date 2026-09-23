package com.example.lms.logging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NoRuntimeConsoleOutputPolicyTest {

    private static final Pattern CONSOLE_OUTPUT =
            Pattern.compile("System\\.(out|err)\\.(print|println|printf)\\s*\\(");

    @Test
    void runtimeSourcesUseStructuredLoggingInsteadOfConsoleOutput() throws Exception {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("main/java");
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isAllowedCliEntrypoint(path))
                    .forEach(path -> collectConsoleOutput(path, offenders));
        }

        assertTrue(offenders.isEmpty(),
                "Use structured logging for runtime sources instead of System.out/err: " + offenders);
    }

    private static boolean isAllowedCliEntrypoint(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith("/com/abandonware/ai/agent/integrations/AnnIndexer.java")
                || normalized.contains("/com/example/lms/replay/")
                || normalized.contains("/com/example/lms/tools/");
    }

    private static void collectConsoleOutput(Path path, List<String> offenders) {
        try {
            String text = Files.readString(path);
            if (CONSOLE_OUTPUT.matcher(text).find()) {
                offenders.add(path.toString());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan " + path, e);
        }
    }
}
