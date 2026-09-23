package com.example.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmsApplicationScanContractTest {

    @Test
    void applicationScanIncludesNovaProtocolPackage() {
        SpringBootApplication annotation = LmsApplication.class.getAnnotation(SpringBootApplication.class);

        assertTrue(Arrays.asList(annotation.scanBasePackages()).contains("com.nova.protocol"),
                "LmsApplication must scan com.nova.protocol so HYPERNOVA components are live beans");
    }

    @Test
    void mainSourceDoesNotCarryHardcodedDefaultAdminPassword() throws IOException {
        Path root = Path.of("main/java");
        String source = Files.walk(root)
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read source file for admin password scan", e);
                    }
                })
                .reduce("", (left, right) -> left + '\n' + right);

        assertFalse(source.contains("admin123"),
                "main/java must not carry a hardcoded default admin password, even in stale scaffold comments");
    }
}
