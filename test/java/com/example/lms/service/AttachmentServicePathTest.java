package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AttachmentService} focused on path resolution.  These tests
 * create a temporary file under the default uploads folder and verify that
 * {@link AttachmentService#asDocuments(List)} correctly resolves a web-style
 * URL (e.g. "/uploads/chat/sample.txt") to an absolute filesystem path.  When
 * the file exists the document list should contain a single entry with the
 * extracted text.  When the file is missing the service should skip
 * silently and return an empty list.
 */
public class AttachmentServicePathTest {
    private AttachmentService attachmentService;
    private Path uploadsDir;

    @BeforeEach
    public void setUp() throws IOException {
        // Create a temporary uploads/chat directory relative to the working directory
        uploadsDir = Path.of("uploads", "chat");
        Files.createDirectories(uploadsDir);
        // Instantiate the service with null storage (not used in asDocuments) and a default FileIngestionService
        attachmentService = new AttachmentService(null, new FileIngestionService());
    }

    @AfterEach
    public void cleanUp() throws IOException {
        // Delete any test files created under uploads/chat
        if (uploadsDir != null && Files.exists(uploadsDir)) {
            Files.walk(uploadsDir)
                    .sorted((a, b) -> b.compareTo(a)) // delete children first
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        }
    }

    @Test
    public void resolvesRelativeUrlToAbsolutePath() throws Exception {
        // Create a sample file
        Path file = uploadsDir.resolve("sample.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);
        // Manually register an AttachmentDto in the service using reflection
        String id = "test-id";
        AttachmentDto dto = new AttachmentDto(
                id,
                file.getFileName().toString(),
                Files.size(file),
                "text/plain",
                "/" + uploadsDir.resolve("sample.txt").toString().replace('\\', '/')
        );
        // Access the private repo field via reflection and insert our dto
        java.lang.reflect.Field repoField = AttachmentService.class.getDeclaredField("repo");
        repoField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, AttachmentDto> repo = (java.util.Map<String, AttachmentDto>) repoField.get(attachmentService);
        repo.put(id, dto);

        List<dev.langchain4j.data.document.Document> docs = attachmentService.asDocuments(List.of(id));
        assertThat(docs).hasSize(1);
        String content = docs.get(0).text();
        assertThat(content).contains("hello world");
    }

    @Test
    public void missingFileYieldsEmptyList() throws Exception {
        // Register a DTO pointing to a non-existent file
        String id = "missing-id";
        AttachmentDto dto = new AttachmentDto(
                id,
                "missing.txt",
                0L,
                "text/plain",
                "/uploads/chat/missing.txt"
        );
        java.lang.reflect.Field repoField = AttachmentService.class.getDeclaredField("repo");
        repoField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, AttachmentDto> repo = (java.util.Map<String, AttachmentDto>) repoField.get(attachmentService);
        repo.put(id, dto);
        List<dev.langchain4j.data.document.Document> docs = attachmentService.asDocuments(List.of(id));
        assertThat(docs).isEmpty();
    }
}