package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentServiceDocumentLimitTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void oversizedEmptyReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/AttachmentService.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains(
                "com.example.lms.search.TraceStore.put(\"attachment.text.emptyReason\",\n                                \"attachment_extraction_skipped_too_large\");"));
        assertFalse(source.contains(
                "com.example.lms.search.TraceStore.put(\"attachment.text.emptyReason\",\n                                com.example.lms.trace.SafeRedactor.safeMessage(\"attachment_extraction_skipped_too_large\", 120));"));
        assertTrue(source.contains(
                "com.example.lms.search.TraceStore.put(\"attachment.text.emptyReason\",\n                                com.example.lms.trace.SafeRedactor.traceLabelOrFallback(\"attachment_extraction_skipped_too_large\", \"unknown\"));"));
    }

    @Test
    void attachmentFallbackCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/AttachmentService.java"));

        assertTrue(source.contains("traceSuppressed(\"attachment.extractText\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.utf8Fallback\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.asDocuments.item\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.propertyLong\", ignore);"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizedAttachmentIsSkippedFailSoftWithoutRawPathTrace() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService());
        MockEnvironment env = new MockEnvironment()
                .withProperty("attachments.documents.maxBytes", "8")
                .withProperty("attachments.inline.maxDocBytes", "8");
        ReflectionTestUtils.setField(service, "environment", env);

        Path file = Files.createTempFile("attachment-limit-", ".txt");
        Files.writeString(file, "this file is larger than eight bytes");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-1", new AttachmentDto(
                    "att-1",
                    "large.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            var documents = service.asDocuments(List.of("att-1"));

            assertTrue(documents.isEmpty());
            assertEquals("attachment_extraction_skipped_too_large",
                    TraceStore.get("attachment.text.emptyReason"));
            assertEquals("too_large", TraceStore.get("attachment.extraction.skippedReason"));
            assertEquals(0, TraceStore.get("attachment.localDocs.count"));
            assertEquals(null, TraceStore.get("attachment.rawPath"));
            assertEquals(null, TraceStore.get("query"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankUtf8FallbackRecordsEmptyReason() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService() {
            @Override
            public String extractText(String fileName, String mimeType, byte[] content) {
                return null;
            }
        });

        Path file = Files.createTempFile("attachment-blank-", ".txt");
        Files.writeString(file, "   \n\t  ");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-blank", new AttachmentDto(
                    "att-blank",
                    "blank.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            var documents = service.asDocuments(List.of("att-blank"));

            assertTrue(documents.isEmpty());
            assertEquals("utf8_fallback_blank", TraceStore.get("attachment.text.emptyReason"));
            assertEquals("utf8_fallback_blank", TraceStore.get("attachment.extraction.skippedReason"));
            assertEquals(0, TraceStore.get("attachment.localDocs.count"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
