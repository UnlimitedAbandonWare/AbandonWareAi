package com.example.lms.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageTest {

    @TempDir
    Path tempDir;

    private Path root;

    @AfterEach
    void cleanup() throws Exception {
        System.clearProperty("lms.upload.dir");
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort temp cleanup in tests.
                        }
                    });
        }
    }

    @Test
    void storesUploadWithoutOriginalFilename() {
        useTempUploadRoot();
        FileStorage storage = new FileStorage();
        MockMultipartFile upload = new MockMultipartFile(
                "file",
                "../../private-report.pdf",
                "application/pdf",
                "safe".getBytes()
        );

        String publicPath = storage.save(upload);

        assertThat(publicPath).startsWith("/uploads/legacy/");
        assertThat(publicPath).endsWith(".pdf");
        assertThat(publicPath).doesNotContain("private-report");
        assertThat(publicPath).doesNotContain("..");
    }

    @Test
    void rejectsDisallowedExtensions() {
        useTempUploadRoot();
        FileStorage storage = new FileStorage();
        MockMultipartFile upload = new MockMultipartFile(
                "file",
                "shell.jsp",
                "application/octet-stream",
                "unsafe".getBytes()
        );

        assertThatThrownBy(() -> storage.save(upload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("extension");
    }

    private void useTempUploadRoot() {
        root = tempDir.resolve("uploads").resolve("legacy");
        System.setProperty("lms.upload.dir", root.toString());
    }
}
