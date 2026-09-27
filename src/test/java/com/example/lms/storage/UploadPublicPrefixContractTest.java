package com.example.lms.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UploadPublicPrefixContractTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"files", "/files", "/files/", " /files/ "})
    void savedUrlUsesConfiguredPublicPrefix(String prefix) throws Exception {
        try (var context = context(prefix)) {
            String url = context.getBean(LocalFileStorageService.class).save(file(), "chat");
            assertTrue(url.startsWith("/files/chat/"), "stored URL must match configured resource route");
            assertEquals("synthetic upload", Files.readString(root.resolve(url.substring("/files/".length()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/uploads/"})
    void defaultPrefixPreservesExistingUrl(String prefix) {
        try (var context = context(prefix)) {
            assertTrue(context.getBean(LocalFileStorageService.class).save(file(), "chat")
                    .startsWith("/uploads/chat/"));
        }
    }

    @Test
    void issuedCustomPrefixUrlCanBeDeletedWithoutAcceptingForgedPaths() throws Exception {
        try (var context = context("/files/")) {
            var service = context.getBean(LocalFileStorageService.class);
            String url = service.save(file(), "chat");
            assertTrue(url.startsWith("/files/chat/"));
            Path saved = root.resolve(url.substring("/files/".length()));
            assertTrue(Files.isRegularFile(saved));
            assertFalse(service.delete("/uploads/" + url.substring("/files/".length())));
            assertTrue(Files.exists(saved));
            assertTrue(service.delete(url));
            assertFalse(Files.exists(saved));
            assertFalse(service.delete(url));
        }
    }

    @Test
    void configuredPrefixDoesNotRelaxTraversalProtection() {
        try (var context = context("/files/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> context.getBean(LocalFileStorageService.class).save(file(), "../escape"));
        }
    }

    private AnnotationConfigApplicationContext context(String prefix) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("upload-fixture", Map.of(
                "lms.upload-dir", root.toString(), "lms.upload.max-bytes", "1048576",
                "lms.upload-public-prefix", prefix)));
        context.register(LocalFileStorageService.class);
        context.refresh();
        return context;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "proof.txt", "text/plain",
                "synthetic upload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
