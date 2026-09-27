package com.example.lms.api;

import com.example.lms.conversation.archive.ConversationArchiveIngestService;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.AttachmentInspectionService;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.VectorStoreService;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AttachmentControllerConversationArchiveTest {

    @Test
    void controllerRejectsNonZipThroughConversationArchiveEndpoint() {
        ConversationArchiveIngestService ingestService = new ConversationArchiveIngestService(
                mock(VectorStoreService.class),
                provider(null));
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        AttachmentController controller = new AttachmentController(
                mock(AttachmentService.class),
                mock(AttachmentInspectionService.class),
                ingestService,
                mock(ChatHistoryService.class),
                ownerKeyResolver);
        MockMultipartFile txt = new MockMultipartFile(
                "files",
                "ConversationExport.txt",
                "text/plain",
                "Alice : hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.ingestConversationArchive(List.of(txt), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unsupported_archive_type");
    }

    @Test
    void deleteRequiresSessionIdBeforeTouchingAttachmentStore() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        AttachmentController controller = new AttachmentController(
                attachmentService,
                mock(AttachmentInspectionService.class),
                mock(ConversationArchiveIngestService.class),
                mock(ChatHistoryService.class),
                mock(ClientOwnerKeyResolver.class));

        assertThatThrownBy(() -> controller.delete("att-1", " ", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing_session");
        verify(attachmentService, never()).deleteForSession("att-1", " ");
    }

    @Test
    void deleteRejectsAttachmentOutsideRequestedSession() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatSession session = new ChatSession("owned", "owner-a", "ANON");
        session.setId(7L);
        when(history.getSessionWithMessages(7L)).thenReturn(session);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        AttachmentController controller = new AttachmentController(
                attachmentService,
                mock(AttachmentInspectionService.class),
                mock(ConversationArchiveIngestService.class),
                history,
                ownerKeyResolver);

        assertThatThrownBy(() -> controller.delete("att-1", "7", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attachment_not_found");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
