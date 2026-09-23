package com.example.lms.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class StandardPromptBuilderSourceContractTest {

    @Test
    void standardPromptBuilderDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/prompt/StandardPromptBuilder.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
        assertFalse(source.contains("tracePromptBuilderSkipped("));
        assertTrue(source.contains("traceSkipped(\"memory\", error);"));
        assertTrue(source.contains("traceSkipped(\"web_snippets_probe\", error);"));
        assertTrue(source.contains("traceSkipped(\"rag_snippets_probe\", error);"));
        assertTrue(source.contains("traceSkipped(\"content_snippet\", error);"));
        assertTrue(source.contains("traceSkipped(\"local_document_snippet\", error);"));
    }
}
