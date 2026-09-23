package com.abandonware.ai.agent.integrations;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnIndexerRedactionContractTest {

    @Test
    void buildMessageDoesNotPrintRawOutputDirectory() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/integrations/AnnIndexer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\" into \" + outDir"),
                "AnnIndexer build message should not print the raw output directory");
        assertTrue(source.contains("\" into \" + pathDiagnostic(outDir)"),
                "AnnIndexer build message should use a hash/length path diagnostic");
        assertTrue(source.contains("SafeRedactor.hashValue(path)"),
                "path diagnostic should include a stable path hash");
        assertTrue(source.contains("\" pathLength=\" + path.length()"),
                "path diagnostic should include path length only");
    }
}
