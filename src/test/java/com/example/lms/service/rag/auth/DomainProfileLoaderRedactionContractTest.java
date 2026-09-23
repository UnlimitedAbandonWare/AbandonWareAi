package com.example.lms.service.rag.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DomainProfileLoaderRedactionContractTest {

    @Test
    void domainProfileLoaderDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/auth/DomainProfileLoader.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Domain profile loader fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
