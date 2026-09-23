package com.example.lms.reflection;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionDebtAuditTest {

    private static final List<Path> ACTIVE_ROOTS = List.of(
            Path.of("main/java"),
            Path.of("app/src/main/java_clean")
    );

    private static final Map<String, String> ALLOWLIST = Map.ofEntries(
            Map.entry("main/java/com/example/lms/api/ClasspathDiagnosticsController.java",
                    "operator-only classpath diagnostics, no runtime dependency bridge"),
            Map.entry("main/java/com/example/lms/infra/debug/ClasspathOriginReporter.java",
                    "classpath origin diagnostics use Modifier only"),
            Map.entry("main/java/com/example/lms/debug/DebugEventSanitizer.java",
                    "java.lang.reflect.Array is used only for safe diagnostic value serialization"),
            Map.entry("main/java/com/example/lms/trace/SafeRedactor.java",
                    "java.lang.reflect.Array is used only for redacted diagnostic value serialization"),
            Map.entry("main/java/com/example/lms/trace/FailureTagNormalizer.java",
                    "java.lang.reflect.Array is used only for bounded diagnostic collection sizing"),
            Map.entry("main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java",
                    "java.lang.reflect.Array is used only for filter literal serialization"),
            Map.entry("main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java",
                    "typed LangGraph graph invocation, not reflection"),
            Map.entry("main/java/com/example/lms/api/internal/InternalAgentToolController.java",
                    "feature-gated admin internal API calls typed AgentToolInvoker, not reflection"),
            Map.entry("main/java/com/example/lms/orchestration/OrchestrationSignals.java",
                    "BYPASS is a typed orchestration mode label, not placeholder success")
    );

    private static final List<ForbiddenPattern> FORBIDDEN = List.of(
            new ForbiddenPattern("Class.forName", Pattern.compile("\\bClass\\.forName\\s*\\(")),
            new ForbiddenPattern("getClass().getMethod", Pattern.compile("\\.getClass\\s*\\(\\s*\\)\\.getMethod\\s*\\(")),
            new ForbiddenPattern("getClass().getDeclaredMethod", Pattern.compile("\\.getClass\\s*\\(\\s*\\)\\.getDeclaredMethod\\s*\\(")),
            new ForbiddenPattern("getDeclaredField", Pattern.compile("\\.getDeclaredField\\s*\\(")),
            new ForbiddenPattern("setAccessible", Pattern.compile("\\bsetAccessible\\s*\\(")),
            new ForbiddenPattern("reflect.Field/Method import", Pattern.compile("import\\s+java\\.lang\\.reflect\\.(Field|Method|\\*)\\s*;")),
            new ForbiddenPattern("reflect.Field/Method reference", Pattern.compile("java\\.lang\\.reflect\\.(Field|Method)\\b")),
            new ForbiddenPattern("dynamic invoke", Pattern.compile("\\.invoke\\s*\\(")),
            new ForbiddenPattern("placeholder BYPASS success", Pattern.compile("return\\s+\"BYPASS\"")),
            new ForbiddenPattern("placeholder OCR marker", Pattern.compile("\\[\\[ocr\\]\\]")),
            new ForbiddenPattern("fake scanNewImages path", Pattern.compile("\\bscanNewImages\\b"))
    );

    @Test
    void activeReflectionDebtTargetsDoNotContainP0BridgePatterns() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path root : ACTIVE_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String normalized = normalize(file);
                    String source = stripComments(Files.readString(file));
                    for (ForbiddenPattern forbidden : FORBIDDEN) {
                        if (forbidden.pattern().matcher(source).find() && !ALLOWLIST.containsKey(normalized)) {
                            violations.add(normalized + " contains " + forbidden.name());
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "Reflection debt violations:\n" + String.join("\n", violations));
    }

    @Test
    void allowlistedReflectionPatternsHaveExplicitReasons() {
        for (Map.Entry<String, String> entry : ALLOWLIST.entrySet()) {
            assertTrue(Files.exists(Path.of(entry.getKey())), "missing allowlisted file: " + entry.getKey());
            assertFalse(entry.getValue().isBlank(), "missing allowlist reason: " + entry.getKey());
        }
    }

    private static String normalize(Path file) {
        return file.toString().replace('\\', '/');
    }

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    private record ForbiddenPattern(String name, Pattern pattern) {
    }
}
