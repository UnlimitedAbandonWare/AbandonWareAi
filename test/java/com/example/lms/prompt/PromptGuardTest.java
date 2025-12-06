package com.example.lms.prompt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Guard to ensure that ChatService does not assemble prompt strings via
 * concatenation at the call site.  All prompt construction should occur
 * through {@link com.example.lms.prompt.PromptBuilder}.  This test scans
 * the ChatService source file for evidence of string concatenation inside
 * SystemMessage or UserMessage factory methods.  If any such pattern is
 * found the test will fail, signalling a violation of the prompt guard
 * policy.
 */
public class PromptGuardTest {

    @Test
    public void chatServiceShouldNotConcatenatePromptStrings() throws Exception {
        // Gather all candidate ChatService source files.  Multiple versions may exist
        // (e.g. ChatService_old.java, ChatService_copy.java) but only those with
        // a .java extension are considered.  The goal is to enforce the prompt
        // construction rule across every ChatService implementation present in
        // the repository.
        Path serviceDir = Paths.get("src/main/java/com/example/lms/service");
        assertTrue(Files.isDirectory(serviceDir), "Service directory should exist");
        // Compile a regex pattern to detect concatenation inside SystemMessage.of/from
        // and UserMessage.of/from calls.  It matches an invocation of either factory
        // followed by an opening parenthesis, then any non-closing parenthesis
        // characters, a double quote, optional whitespace, and a plus sign.  The
        // plus sign is unescaped inside a character class.  A double quote must be
        // escaped within the Java string literal.
        Pattern concatPattern = Pattern.compile("(SystemMessage|UserMessage)\\.(of|from)\\([^)]*\\\"\\s*\\+");
        // Iterate through each Java file starting with "ChatService"
        try (java.util.stream.Stream<Path> files = Files.list(serviceDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("ChatService") && p.getFileName().toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            String src = Files.readString(file);
                            boolean hasConcat = concatPattern.matcher(src).find();
                            assertFalse(hasConcat, file.getFileName() + " should build prompts via PromptBuilder rather than string concatenation");
                        } catch (Exception e) {
                            fail("Failed to read " + file + ": " + e.getMessage());
                        }
                    });
        }

        // Also examine any patched ChatService file in the patch subpackage if present.  This
        // file is deliberately excluded from compilation but should still adhere to
        // the prompt guard rule when present.
        Path patchPath = Paths.get("src/main/java/com/example/lms/service/patch");
        if (Files.isDirectory(patchPath)) {
            try (java.util.stream.Stream<Path> files = Files.list(patchPath)) {
                files.filter(p -> p.getFileName().toString().startsWith("ChatService") && p.getFileName().toString().endsWith(".java"))
                        .forEach(file -> {
                            try {
                                String src = Files.readString(file);
                                boolean hasConcat = concatPattern.matcher(src).find();
                                assertFalse(hasConcat, file.getFileName() + " (patch) should build prompts via PromptBuilder rather than string concatenation");
                            } catch (Exception e) {
                                fail("Failed to read " + file + ": " + e.getMessage());
                            }
                        });
            }
        }
    }
}