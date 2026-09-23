package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import com.example.lms.lifecycle.JsonlLifecycleReceiptStore;
import com.example.lms.search.TraceStore;
import com.example.lms.storage.LocalFileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentServicePhysicalDeletionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void explicitDeleteRemovesMetadataAndPhysicalFile() {
        Fixture fixture = fixture("explicit");
        AttachmentDto saved = fixture.service().saveAll(List.of(file("proof.txt"))).get(0);
        Path physical = resolveUpload(fixture.root(), saved.url());
        assertTrue(Files.isRegularFile(physical));

        fixture.service().delete(saved.id());

        assertFalse(Files.exists(physical));
        assertTrue(fixture.service().find(saved.id()).isEmpty());
    }

    @Test
    void createAndDeletePersistHashOnlyLifecycleTombstonesAcrossStoreRestart() throws Exception {
        Path root = tempDir.resolve("receipt-upload").toAbsolutePath().normalize();
        Path receiptPath = tempDir.resolve("attachment-lifecycle.jsonl");
        LocalFileStorageService storage = configuredStorage(root);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AttachmentService service = new AttachmentService(
                storage,
                new FileIngestionService(),
                new JsonlLifecycleReceiptStore(receiptPath, objectMapper));
        String privateName = "private-attachment-name-sentinel.txt";
        AttachmentDto saved = service.saveAll(List.of(file(privateName))).get(0);

        service.delete(saved.id());

        List<String> states = receiptStates(receiptPath, objectMapper);
        assertEquals(List.of("CREATED", "DELETE_REQUESTED", "DELETED"), states);
        String persisted = Files.readString(receiptPath);
        assertFalse(persisted.contains(privateName));
        assertFalse(persisted.contains(saved.id()));
        assertFalse(persisted.contains(saved.url()));
        String subjectHash = DurableLifecycleReceiptStore.hash("attachment-id", saved.id());
        assertEquals(
                DurableLifecycleReceiptStore.State.DELETED,
                new JsonlLifecycleReceiptStore(receiptPath, objectMapper)
                        .find(DurableLifecycleReceiptStore.Lifecycle.ATTACHMENT, subjectHash)
                        .orElseThrow()
                        .state());
    }

    @Test
    void failedPhysicalDeletePersistsDeleteFailedTombstone() throws Exception {
        Path root = tempDir.resolve("failed-receipt-upload").toAbsolutePath().normalize();
        Path receiptPath = tempDir.resolve("attachment-delete-failed.jsonl");
        LocalFileStorageService storage = configuredStorage(root);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AttachmentService service = new AttachmentService(
                storage,
                new FileIngestionService(),
                new JsonlLifecycleReceiptStore(receiptPath, objectMapper));
        AttachmentDto saved = service.saveAll(List.of(file("failed-delete-sentinel.txt"))).get(0);
        Files.delete(resolveUpload(root, saved.url()));

        service.delete(saved.id());

        assertEquals(
                List.of("CREATED", "DELETE_REQUESTED", "DELETE_FAILED"),
                receiptStates(receiptPath, objectMapper));
        JsonNode terminal = objectMapper.readTree(
                Files.readAllLines(receiptPath).get(2));
        assertEquals("STORAGE_DELETE_FAILED", terminal.path("reason").asText());
    }

    @Test
    void sessionDeleteRemovesMetadataAndPhysicalFile() {
        Fixture fixture = fixture("session");
        AttachmentDto saved = fixture.service()
                .saveAll(List.of(file("session.txt")), "owner-session")
                .get(0);
        Path physical = resolveUpload(fixture.root(), saved.url());
        assertTrue(Files.isRegularFile(physical));

        assertTrue(fixture.service().deleteForSession(saved.id(), "owner-session"));

        assertFalse(Files.exists(physical));
        assertTrue(fixture.service().find(saved.id()).isEmpty());
    }

    @Test
    void expiryRemovesMetadataAndPhysicalFile() {
        Fixture fixture = fixture("expiry");
        AttachmentDto saved = fixture.service().saveAll(List.of(file("expired.txt"))).get(0);
        Path physical = resolveUpload(fixture.root(), saved.url());
        retainedAt(fixture.service()).put(saved.id(), 0L);
        ReflectionTestUtils.setField(
                fixture.service(),
                "environment",
                new MockEnvironment().withProperty("attachments.retention.ttl-ms", "1"));

        assertEquals(1, fixture.service().evictExpired(2L));

        assertFalse(Files.exists(physical));
        assertTrue(fixture.service().find(saved.id()).isEmpty());
    }

    @Test
    void missingPhysicalFileIsIdempotent() throws Exception {
        Fixture fixture = fixture("missing");
        AttachmentDto saved = fixture.service().saveAll(List.of(file("missing.txt"))).get(0);
        Path physical = resolveUpload(fixture.root(), saved.url());
        Files.delete(physical);

        assertDoesNotThrow(() -> fixture.service().delete(saved.id()));
        assertTrue(fixture.service().find(saved.id()).isEmpty());
        assertFalse(Files.exists(physical));
    }

    @Test
    void storageDeleteRejectsLexicalEscapeAndMissingPath() throws Exception {
        Fixture fixture = fixture("lexical");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside-fixture");
        issuedStoredPaths(fixture.storage()).add("/uploads/../outside.txt");
        issuedStoredPaths(fixture.storage()).add("/uploads/");
        issuedStoredPaths(fixture.storage()).add("/uploads/missing.txt");

        assertFalse(fixture.storage().delete("/uploads/../outside.txt"));
        assertFalse(fixture.storage().delete("/uploads/"));
        assertFalse(fixture.storage().delete("/uploads/missing.txt"));
        assertTrue(Files.isRegularFile(outside));
    }

    @Test
    void storageDeleteRejectsUnissuedFileInsideUploadRoot() throws Exception {
        Fixture fixture = fixture("unissued");
        Files.createDirectories(fixture.root());
        String filename = "01234567-89ab-cdef-0123-456789abcdef.txt";
        Path sentinel = Files.writeString(fixture.root().resolve(filename), "unissued-fixture");

        assertFalse(fixture.storage().delete("/uploads/" + filename));
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void storageDeleteRejectsLinkTraversalWithoutTouchingOutside() throws Exception {
        Fixture fixture = fixture("link-root");
        Files.createDirectories(fixture.root());
        Path outsideDir = Files.createDirectory(tempDir.resolve("outside-dir"));
        Path outside = Files.writeString(outsideDir.resolve("outside.txt"), "outside-fixture");
        Path link = fixture.root().resolve("linked");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic-link fixture unavailable on this host");
        }
        issuedStoredPaths(fixture.storage()).add("/uploads/linked/outside.txt");

        assertFalse(fixture.storage().delete("/uploads/linked/outside.txt"));
        assertTrue(Files.isRegularFile(outside));
    }

    private Fixture fixture(String name) {
        Path root = tempDir.resolve(name).toAbsolutePath().normalize();
        LocalFileStorageService storage = configuredStorage(root);
        AttachmentService service = new AttachmentService(storage, new FileIngestionService());
        return new Fixture(root, storage, service);
    }

    private static LocalFileStorageService configuredStorage(Path root) {
        LocalFileStorageService storage = new LocalFileStorageService();
        ReflectionTestUtils.setField(storage, "rootDir", root.toString());
        ReflectionTestUtils.setField(storage, "maxBytes", 1024L * 1024L);
        return storage;
    }

    private static List<String> receiptStates(Path receiptPath, ObjectMapper objectMapper) throws Exception {
        List<String> states = new ArrayList<>();
        for (String line : Files.readAllLines(receiptPath)) {
            if (!line.isBlank()) {
                states.add(objectMapper.readTree(line).path("state").asText());
            }
        }
        return states;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile(
                "file",
                name,
                "text/plain",
                "physical-delete-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Path resolveUpload(Path root, String storedUrl) {
        assertTrue(storedUrl.startsWith("/uploads/"));
        String relative = storedUrl.substring("/uploads/".length()).replace('/', java.io.File.separatorChar);
        Path resolved = root.resolve(relative).normalize();
        assertTrue(resolved.startsWith(root));
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> retainedAt(AttachmentService service) {
        return (Map<String, Long>) ReflectionTestUtils.getField(service, "retainedAtEpochMsById");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> issuedStoredPaths(LocalFileStorageService storage) {
        return (Set<String>) ReflectionTestUtils.getField(storage, "issuedStoredPaths");
    }

    private record Fixture(Path root, LocalFileStorageService storage, AttachmentService service) {
    }
}
