package com.example.lms.service;

import com.example.lms.file.FileIngestionService;
import com.example.lms.storage.LocalFileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentIdentityTest {
    @TempDir Path temp;

    @AfterEach void clearTrace() { com.example.lms.search.TraceStore.clear(); }

    @Test
    void inspectHashesActualBytesEvenWhenNameSizeAndMimeMatch() {
        var first = inspect("alpha");
        var second = inspect("bravo");
        assertNotEquals(first.get("sha256"), second.get("sha256"));
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex("alpha"), first.get("sha256"));
        assertEquals("attachment-inspect.v2", first.get("schemaVersion"));
        assertEquals(first.get("legacyMetadataFingerprint"), second.get("legacyMetadataFingerprint"));
    }

    @Test
    void documentsCarryByteIdentityAndSourceLocationWhenObservationIsOff() throws Exception {
        Path path = temp.resolve("report.md");
        Files.writeString(path, "# Findings\nA bounded fact.\n");
        var upload = new MockMultipartFile("files", "report.md", "text/markdown", Files.readAllBytes(path));
        var storage = mock(LocalFileStorageService.class);
        when(storage.save(upload, "chat")).thenReturn(path.toString());
        var service = new AttachmentService(storage, new FileIngestionService());
        ReflectionTestUtils.setField(service, "environment",
                new MockEnvironment().withProperty("interaction.evidence-neutral.mode", "OFF"));
        var owner = AttachmentOwnerIdentity.forAnonymous("synthetic-owner");
        var saved = service.saveAll(List.of(upload), "session-a", owner).get(0);
        var documents = service.asDocumentsForSession(List.of(saved.id()), "session-a", owner);
        assertFalse(documents.isEmpty());
        var meta = documents.get(0).metadata();
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(path)),
                meta.getString("contentSha256"));
        assertEquals("attachment:" + saved.id(), meta.getString("sourceId"));
        assertEquals(1L, meta.getLong("sourceRevision"));
        assertEquals("report.md", meta.getString("name"));
        assertNotNull(meta.getString("chunkId"));
        assertNotNull(meta.getString("locator"));
        assertEquals("DATA_ONLY", meta.getString("executionAuthority"));
        assertTrue(service.asDocumentsForSession(List.of(saved.id()), "session-b", owner).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inspect(String text) {
        var service = new AttachmentInspectionService(new FileIngestionService(), null,
                mock(ObjectProvider.class), mock(ObjectProvider.class), new MockEnvironment());
        var upload = new MockMultipartFile("files", "same.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        var result = service.inspect(List.of(upload), "session-a", List.of());
        return ((List<Map<String, Object>>) result.get("files")).get(0);
    }

    @Test void aggregateStructuredExcerptBudgetIsSharedAcrossAttachments() throws Exception {
        var storage = mock(LocalFileStorageService.class);
        var service = new AttachmentService(storage, new FileIngestionService());
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 16; i++) {
            Path path = temp.resolve("report-" + i + ".md");
            Files.writeString(path, "a".repeat(8_000) + "\nDone " + i);
            var upload = new MockMultipartFile("files", path.getFileName().toString(), "text/markdown", Files.readAllBytes(path));
            when(storage.save(upload, "chat")).thenReturn(path.toString());
            ids.add(service.saveAll(List.of(upload), "session-a").get(0).id());
        }
        var documents = service.asDocumentsForSession(ids, "session-a");
        assertTrue(documents.stream().mapToInt(d -> d.text().length()).sum() <= 16_000);
        assertEquals(16, documents.stream().map(d -> d.metadata().getString("attachmentId")).distinct().count());
    }

    @Test void emptyStructuredDocumentIsDistinctFromFailedFallback() throws Exception {
        Path path = temp.resolve("blank.md");
        Files.writeString(path, " \n ");
        var upload = new MockMultipartFile("files", "blank.md", "text/markdown", Files.readAllBytes(path));
        var storage = mock(LocalFileStorageService.class);
        when(storage.save(upload, "chat")).thenReturn(path.toString());
        var service = new AttachmentService(storage, new FileIngestionService());
        var id = service.saveAll(List.of(upload)).get(0).id();
        assertTrue(service.asDocuments(List.of(id)).isEmpty());
        assertEquals("structured_document_empty", com.example.lms.search.TraceStore.get("attachment.text.emptyReason"));
    }

    @Test void wrongOwnerAndChangedBytesAreRejectedEvenWithCachedText() throws Exception {
        Path path = temp.resolve("private.md");
        Files.writeString(path, "original");
        var upload = new MockMultipartFile("files", "private.md", "text/markdown", Files.readAllBytes(path));
        var storage = mock(LocalFileStorageService.class);
        when(storage.save(upload, "chat")).thenReturn(path.toString());
        var service = new AttachmentService(storage, new FileIngestionService());
        var owner = AttachmentOwnerIdentity.forAnonymous("synthetic-a");
        var id = service.saveAll(List.of(upload), "session-a", owner).get(0).id();
        assertTrue(service.asDocumentsForSession(List.of(id), "session-a",
                AttachmentOwnerIdentity.forAnonymous("synthetic-b")).isEmpty());
        service.cacheExtractedText(id, "cached original");
        Files.writeString(path, "modified");
        assertTrue(service.asDocumentsForSession(List.of(id), "session-a", owner).isEmpty());
        assertEquals("content_digest_mismatch", com.example.lms.search.TraceStore.get("attachment.extraction.skippedReason"));
    }
}
