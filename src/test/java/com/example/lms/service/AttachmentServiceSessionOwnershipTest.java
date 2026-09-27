package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentServiceSessionOwnershipTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionBoundAttachmentCannotBeLoadedOrReattachedByAnotherSession() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService());
        Path file = Files.createTempFile("attachment-session-", ".txt");
        Files.writeString(file, "owned attachment text");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-owned", new AttachmentDto(
                    "att-owned",
                    "owned.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            service.attachToSession("session-a", List.of("att-owned"));
            service.attachToSession("session-b", List.of("att-owned"));

            assertEquals(1, service.findBySession("session-a").size());
            assertTrue(service.findBySession("session-b").isEmpty());
            assertEquals(1, service.asDocumentsForSession(List.of("att-owned"), "session-a").size());
            assertTrue(service.asDocumentsForSession(List.of("att-owned"), "session-b").isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionBoundAttachmentCannotBeDeletedByAnotherSession() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService());
        Path file = Files.createTempFile("attachment-delete-session-", ".txt");
        Files.writeString(file, "owned attachment text");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-delete-owned", new AttachmentDto(
                    "att-delete-owned",
                    "owned.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            service.attachToSession("session-owner", List.of("att-delete-owned"));

            assertTrue(service.find("att-delete-owned").isPresent());
            assertTrue(service.findBySession("session-owner").stream()
                    .anyMatch(dto -> "att-delete-owned".equals(dto.id())));

            assertEquals(false, service.deleteForSession("att-delete-owned", "session-other"));
            assertTrue(service.find("att-delete-owned").isPresent());

            assertEquals(true, service.deleteForSession("att-delete-owned", "session-owner"));
            assertTrue(service.find("att-delete-owned").isEmpty());
            assertTrue(service.findBySession("session-owner").isEmpty());
            Map<String, List<String>> sessionIndex =
                    (Map<String, List<String>>) ReflectionTestUtils.getField(service, "sessionIndex");
            assertTrue(sessionIndex == null || !sessionIndex.containsKey("session-owner"));
            assertEquals(Boolean.TRUE, TraceStore.get("attachment.delete.ok"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void preSessionAttachmentCannotAttachOrDeleteAsAnotherOwner() {
        com.example.lms.storage.LocalFileStorageService storage =
                mock(com.example.lms.storage.LocalFileStorageService.class);
        when(storage.save(any(), eq("chat"))).thenReturn("/uploads/chat/owner-proof.txt");
        when(storage.delete("/uploads/chat/owner-proof.txt")).thenReturn(true);
        AttachmentService service = new AttachmentService(storage, new FileIngestionService());
        AttachmentOwnerIdentity ownerA = AttachmentOwnerIdentity.forAnonymous("owner-a");
        AttachmentOwnerIdentity ownerB = AttachmentOwnerIdentity.forAnonymous("owner-b");
        MockMultipartFile file = new MockMultipartFile(
                "files", "owner-proof.txt", "text/plain", new byte[] {1});

        AttachmentDto saved = service.saveAll(List.of(file), ownerA).get(0);

        assertFalse(service.attachToSession("session-b", List.of(saved.id()), ownerB));
        assertTrue(service.findBySession("session-b", ownerB).isEmpty());
        assertFalse(service.deleteForSession(saved.id(), "session-b", ownerB));
        verify(storage, never()).delete(any());

        assertTrue(service.attachToSession("session-a", List.of(saved.id()), ownerA));
        assertEquals(1, service.findBySession("session-a", ownerA).size());
        assertTrue(service.deleteForSession(saved.id(), "session-a", ownerA));
        verify(storage).delete("/uploads/chat/owner-proof.txt");
    }

    @Test
    void whitespaceDistinctAnonymousAndAdministratorOwnersCannotCrossAttachmentBoundaries() {
        assertWhitespaceDistinctOwnerIsDenied(
                AttachmentOwnerIdentity.forAnonymous("owner-a"),
                AttachmentOwnerIdentity.forAnonymous(" owner-a "),
                "/uploads/chat/anonymous-owner-proof.txt");
        assertWhitespaceDistinctOwnerIsDenied(
                AttachmentOwnerIdentity.forAdministrator("admin-a"),
                AttachmentOwnerIdentity.forAdministrator(" admin-a "),
                "/uploads/chat/administrator-owner-proof.txt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void laterStorageFailureRollsBackEarlierMetadataAndPhysicalFile() {
        com.example.lms.storage.LocalFileStorageService storage =
                mock(com.example.lms.storage.LocalFileStorageService.class);
        when(storage.save(any(), eq("chat")))
                .thenReturn("/uploads/chat/first.txt")
                .thenThrow(new IllegalStateException("second save rejected"));
        when(storage.delete("/uploads/chat/first.txt")).thenReturn(true);
        AttachmentService service = new AttachmentService(storage, new FileIngestionService());
        AttachmentOwnerIdentity owner = AttachmentOwnerIdentity.forAnonymous("rollback-owner");

        assertThrows(IllegalStateException.class, () -> service.saveAll(List.of(
                new MockMultipartFile("files", "first.txt", "text/plain", new byte[] {1}),
                new MockMultipartFile("files", "second.txt", "text/plain", new byte[] {2})), owner));

        Map<String, AttachmentDto> repo =
                (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
        Map<String, String> owners =
                (Map<String, String>) ReflectionTestUtils.getField(service, "ownerHashById");
        assertTrue(repo == null || repo.isEmpty());
        assertTrue(owners == null || owners.isEmpty());
        verify(storage).delete("/uploads/chat/first.txt");
    }

    private static void assertWhitespaceDistinctOwnerIsDenied(
            AttachmentOwnerIdentity owner,
            AttachmentOwnerIdentity whitespaceVariant,
            String storedPath) {
        assertNotEquals(owner, whitespaceVariant);
        com.example.lms.storage.LocalFileStorageService storage =
                mock(com.example.lms.storage.LocalFileStorageService.class);
        when(storage.save(any(), eq("chat"))).thenReturn(storedPath);
        when(storage.delete(storedPath)).thenReturn(true);
        AttachmentService service = new AttachmentService(storage, new FileIngestionService());
        AttachmentDto saved = service.saveAll(List.of(new MockMultipartFile(
                "files", "owner-proof.txt", "text/plain", new byte[] {1})), owner).get(0);

        assertFalse(service.attachToSession("foreign-session", List.of(saved.id()), whitespaceVariant));
        assertTrue(service.attachToSession("owner-session", List.of(saved.id()), owner));
        assertTrue(service.find(saved.id(), whitespaceVariant).isEmpty());
        assertTrue(service.findBySession("owner-session", whitespaceVariant).isEmpty());
        assertFalse(service.deleteForSession(saved.id(), "owner-session", whitespaceVariant));
        assertTrue(service.find(saved.id(), owner).isPresent());

        assertTrue(service.deleteForSession(saved.id(), "owner-session", owner));
        verify(storage).delete(storedPath);
    }
}
