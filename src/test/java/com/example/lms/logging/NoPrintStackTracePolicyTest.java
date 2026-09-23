package com.example.lms.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoPrintStackTracePolicyTest {

    @Test
    void activeSourcesDoNotPrintStackTracesDirectly() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("main/java"), Path.of("app/src/main/java_clean"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectPrintStackTraceCall(path, offenders));
            }
        }

        assertTrue(offenders.isEmpty(),
                "Use structured logging instead of printStackTrace(): " + offenders);
    }

    private static void collectPrintStackTraceCall(Path path, List<String> offenders) {
        try {
            String text = Files.readString(path);
            if (text.contains(".printStackTrace(")) {
                offenders.add(path.toString());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan " + path, e);
        }
    }
}
