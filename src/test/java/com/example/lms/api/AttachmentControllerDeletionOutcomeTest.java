package com.example.lms.api;

import com.example.lms.conversation.archive.ConversationArchiveIngestService;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.lifecycle.JsonlLifecycleReceiptStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentInspectionService;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.storage.LocalFileStorageService;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Characterizes metadata acknowledgement separately from the physical cleanup receipt. */
class AttachmentControllerDeletionOutcomeTest {
    @TempDir Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedFileFailureIsRecordedWhileCurrentEndpointAcknowledgesMetadataRemoval(boolean throwsFailure)
            throws Exception {
        Fixture fixture = fixture(throwsFailure ? "throw" : "false");
        AttachmentDto saved = fixture.upload();
        Path physical = fixture.resolve(saved.url());
        byte[] before = Files.readAllBytes(physical);
        if (throwsFailure) {
            doThrow(new IllegalStateException("synthetic-private-storage-detail"))
                    .when(fixture.storage).delete(saved.url());
        } else {
            doReturn(false).when(fixture.storage).delete(saved.url());
        }

        fixture.mvc.perform(delete("/api/attachments/{id}", saved.id()).param("sessionId", "7"))
                .andExpect(status().isOk());

        assertTrue(fixture.service.find(saved.id()).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(physical));
        assertEquals(Boolean.FALSE, TraceStore.get("attachment.delete.physicalDeleted"));
        assertEquals("storage_delete_failed", TraceStore.get("attachment.delete.physicalDeleteReason"));
        assertEquals(List.of("CREATED", "DELETE_REQUESTED", "DELETE_FAILED"), fixture.states());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic-private-storage-detail"));
        fixture.mvc.perform(delete("/api/attachments/{id}", saved.id()).param("sessionId", "7"))
                .andExpect(status().isNotFound());
        verify(fixture.storage, times(1)).delete(saved.url());
        assertTrue(Files.isRegularFile(physical));
    }

    @Test
    void successfulPhysicalDeletionRemovesBytesAndAcknowledgesMetadataRemoval() throws Exception {
        Fixture fixture = fixture("success");
        AttachmentDto saved = fixture.upload();
        Path physical = fixture.resolve(saved.url());
        assertTrue(Files.isRegularFile(physical));

        fixture.mvc.perform(delete("/api/attachments/{id}", saved.id()).param("sessionId", "7"))
                .andExpect(status().isOk());

        assertFalse(Files.exists(physical));
        assertTrue(fixture.service.find(saved.id()).isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("attachment.delete.physicalDeleted"));
        assertEquals(List.of("CREATED", "DELETE_REQUESTED", "DELETED"), fixture.states());
    }

    @Test
    void alreadyMissingFileIsDistinctFromRetainedByteFailure() throws Exception {
        Fixture fixture = fixture("already-missing");
        AttachmentDto saved = fixture.upload();
        Path physical = fixture.resolve(saved.url());
        Files.delete(physical);

        fixture.mvc.perform(delete("/api/attachments/{id}", saved.id()).param("sessionId", "7"))
                .andExpect(status().isOk());

        assertFalse(Files.exists(physical));
        assertTrue(fixture.service.find(saved.id()).isEmpty());
        assertEquals(Boolean.FALSE, TraceStore.get("attachment.delete.physicalDeleted"));
        assertEquals(List.of("CREATED", "DELETE_REQUESTED", "DELETE_FAILED"), fixture.states());
    }

    private Fixture fixture(String name) {
        Path root = tempDir.resolve(name).toAbsolutePath().normalize();
        Path receipt = tempDir.resolve(name + "-lifecycle.jsonl");
        LocalFileStorageService storage = spy(new LocalFileStorageService());
        ReflectionTestUtils.setField(storage, "rootDir", root.toString());
        ReflectionTestUtils.setField(storage, "maxBytes", 1_048_576L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AttachmentService service = new AttachmentService(storage, new FileIngestionService(),
                new JsonlLifecycleReceiptStore(receipt, mapper));
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatSession session = new ChatSession("synthetic session", "fixture-owner", "ANON");
        session.setId(7L);
        when(history.getSessionWithMessages(7L)).thenReturn(session);
        ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
        when(owner.ownerKey()).thenReturn("fixture-owner");
        AttachmentController controller = new AttachmentController(service, mock(AttachmentInspectionService.class),
                mock(ConversationArchiveIngestService.class), history, owner);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        return new Fixture(root, receipt, mapper, storage, service, controller, mvc);
    }

    private record Fixture(Path root, Path receipt, ObjectMapper mapper, LocalFileStorageService storage,
                           AttachmentService service, AttachmentController controller, MockMvc mvc) {
        AttachmentDto upload() {
            return controller.upload(List.of(new MockMultipartFile("files", "fixture.txt", "text/plain",
                    "retained-byte-fixture".getBytes(StandardCharsets.UTF_8))), "7", null).get(0);
        }
        Path resolve(String url) {
            assertTrue(url.startsWith("/uploads/"));
            Path path = root.resolve(url.substring("/uploads/".length())).normalize();
            assertTrue(path.startsWith(root));
            return path;
        }
        List<String> states() throws Exception {
            var states = new java.util.ArrayList<String>();
            for (String line : Files.readAllLines(receipt)) {
                if (!line.isBlank()) states.add(mapper.readTree(line).path("state").asText());
            }
            return states;
        }
    }
}
