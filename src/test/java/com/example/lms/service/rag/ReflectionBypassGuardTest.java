package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionBypassGuardTest {

    private static final List<String> SCAN_ROOTS = List.of(
            "main/java/com/example/lms/service/rag",
            "main/java/com/example/lms/transform",
            "main/java/com/example/lms/service/ChatWorkflow.java",
            "main/java/com/abandonware/patch/config"
    );

    private static final List<String> ALLOWED_FILES = List.of(
            "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"
    );

    private static final Pattern PROHIBITED = Pattern.compile(
            "\\bClass\\.forName\\s*\\(|\\.getMethod\\s*\\(|\\.getDeclaredMethod\\s*\\(|\\.invoke\\s*\\(");
    private static final int SCAN_WINDOW_CHARS = 64;

    @Test
    void ragRuntimeDoesNotUseReflectionBypassApisOutsideAllowlist() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            Path p = Path.of(root);
            if (!Files.exists(p)) {
                continue;
            }
            if (Files.isRegularFile(p)) {
                inspect(p, violations);
                continue;
            }
            try (Stream<Path> paths = Files.walk(p)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> inspect(path, violations));
            }
        }

        assertTrue(violations.isEmpty(), "Reflection bypass API usage found: " + violations);
    }

    @Test
    void streamingScannerIgnoresCommentsAndDetectsCallsAcrossLineBreaks(@TempDir Path tempDir)
            throws IOException {
        Path commentsOnly = tempDir.resolve("CommentsOnly.java");
        Files.writeString(commentsOnly, """
                // Class.forName("commented")
                /* target.getDeclaredMethod(
                   "also-commented"); */
                final class CommentsOnly {}
                """, StandardCharsets.UTF_8);
        Path liveCall = tempDir.resolve("LiveCall.java");
        Files.writeString(liveCall, """
                final class LiveCall {
                    void invoke(Object target) throws Exception {
                        target.getDeclaredMethod
                                ("run");
                    }
                }
                """, StandardCharsets.UTF_8);

        assertFalse(containsProhibitedOutsideComments(commentsOnly));
        assertTrue(containsProhibitedOutsideComments(liveCall));
    }

    private static void inspect(Path path, List<String> violations) {
        String normalized = path.toString().replace('\\', '/');
        if (ALLOWED_FILES.contains(normalized)) {
            return;
        }
        try {
            if (containsProhibitedOutsideComments(path)) {
                violations.add(normalized);
            }
        } catch (IOException e) {
            violations.add(normalized + ":read_failed:" + e.getClass().getSimpleName());
        }
    }

    private static boolean containsProhibitedOutsideComments(Path path) throws IOException {
        try (BufferedReader buffered = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             PushbackReader reader = new PushbackReader(buffered, 1)) {
            StringBuilder window = new StringBuilder(SCAN_WINDOW_CHARS);
            boolean inBlockComment = false;
            boolean inLineComment = false;
            int value;

            while ((value = reader.read()) != -1) {
                char current = (char) value;
                if (inBlockComment) {
                    if (current == '*') {
                        int next = reader.read();
                        if (next == '/') {
                            inBlockComment = false;
                        } else if (next != -1) {
                            reader.unread(next);
                        }
                    }
                    continue;
                }
                if (inLineComment) {
                    if (current == '\r' || current == '\n') {
                        inLineComment = false;
                        appendCanonical(window, current);
                    }
                    continue;
                }
                if (current == '/') {
                    int next = reader.read();
                    if (next == '*') {
                        inBlockComment = true;
                        continue;
                    }
                    if (next == '/') {
                        inLineComment = true;
                        continue;
                    }
                    if (appendCanonical(window, current)) {
                        return true;
                    }
                    if (next != -1) {
                        reader.unread(next);
                    }
                    continue;
                }
                if (appendCanonical(window, current)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean appendCanonical(StringBuilder window, char value) {
        if (Character.isWhitespace(value)) {
            if (window.length() == 0 || window.charAt(window.length() - 1) == ' ') {
                return false;
            }
            window.append(' ');
        } else {
            window.append(value);
        }

        if (value == '(' && PROHIBITED.matcher(window).find()) {
            return true;
        }
        if (window.length() > SCAN_WINDOW_CHARS) {
            window.delete(0, window.length() - SCAN_WINDOW_CHARS);
        }
        return false;
    }
}
