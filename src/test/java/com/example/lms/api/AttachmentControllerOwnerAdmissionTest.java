package com.example.lms.api;

import com.example.lms.conversation.archive.ConversationArchiveIngestService;
import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.AttachmentInspectionService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentControllerOwnerAdmissionTest {

    @Test
    void foreignAnonymousSessionUploadRejectsBeforeAttachmentService() {
        Fixture fixture = fixture(anonymousSession(7L, "owner-a"), "owner-b");

        assertThatThrownBy(() -> fixture.controller().upload(
                List.of(file("proof.txt")), "7", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_forbidden");

        verifyNoInteractions(fixture.attachments(), fixture.inspection(), fixture.archive());
    }

    @Test
    void foreignSessionInspectArchiveAndDeleteRejectBeforeEveryDownstreamService() {
        Fixture fixture = fixture(anonymousSession(7L, "owner-a"), "owner-b");

        assertThatThrownBy(() -> fixture.controller().inspect(
                List.of(file("inspect.txt")), "7", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_forbidden");
        assertThatThrownBy(() -> fixture.controller().ingestConversationArchive(
                List.of(file("archive.zip")), "7", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_forbidden");
        assertThatThrownBy(() -> fixture.controller().delete("att-1", "7", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_forbidden");

        verifyNoInteractions(fixture.attachments(), fixture.inspection(), fixture.archive());
    }

    @Test
    void matchingAdminSessionPassesHashOnlyIdentityToAttachmentService() {
        ChatSession session = adminSession(9L, "admin-a");
        Fixture fixture = fixture(session, "ignored-owner-cookie");
        TestingAuthenticationToken auth = new TestingAuthenticationToken("admin-a", "n/a", "ROLE_ADMIN");
        List<MultipartFile> files = List.of(file("admin.txt"));
        AttachmentOwnerIdentity expected = AttachmentOwnerIdentity.forAdministrator("admin-a");
        when(fixture.attachments().saveAll(files, "9", expected)).thenReturn(List.of());

        fixture.controller().upload(files, "9", auth);

        verify(fixture.attachments()).saveAll(files, "9", expected);
        verify(fixture.ownerKeyResolver(), never()).ownerKey();
    }

    @Test
    void seventeenFilesRejectBeforeOwnerResolutionOrAnyWrite() {
        Fixture fixture = fixture(null, "owner-a");
        List<MultipartFile> files = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            files.add(sizedFile(1L));
        }

        assertThatThrownBy(() -> fixture.controller().upload(files, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too_many_files");

        verifyNoInteractions(fixture.history(), fixture.attachments(), fixture.inspection(), fixture.archive());
        verify(fixture.ownerKeyResolver(), never()).ownerKey();
    }

    @Test
    void aggregateAboveTwentyFiveMiBRejectsBeforeFirstWrite() {
        Fixture fixture = fixture(null, "owner-a");
        long thirteenMiB = 13L * 1024L * 1024L;

        assertThatThrownBy(() -> fixture.controller().inspect(
                List.of(sizedFile(thirteenMiB), sizedFile(thirteenMiB)), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attachments_too_large");

        verifyNoInteractions(fixture.history(), fixture.attachments(), fixture.inspection(), fixture.archive());
        verify(fixture.ownerKeyResolver(), never()).ownerKey();
    }

    @Test
    void aggregateOverflowRejectsBeforeArchiveOrVectorWork() {
        Fixture fixture = fixture(null, "owner-a");

        assertThatThrownBy(() -> fixture.controller().ingestConversationArchive(
                List.of(sizedFile(Long.MAX_VALUE), sizedFile(1L)), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attachments_too_large");

        verifyNoInteractions(fixture.history(), fixture.attachments(), fixture.inspection(), fixture.archive());
        verify(fixture.ownerKeyResolver(), never()).ownerKey();
    }

    private static Fixture fixture(ChatSession session, String ownerKey) {
        AttachmentService attachments = mock(AttachmentService.class);
        AttachmentInspectionService inspection = mock(AttachmentInspectionService.class);
        ConversationArchiveIngestService archive = mock(ConversationArchiveIngestService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        if (session != null) {
            when(history.getSessionWithMessages(session.getId())).thenReturn(session);
        }
        when(ownerKeyResolver.ownerKey()).thenReturn(ownerKey);
        return new Fixture(
                new AttachmentController(attachments, inspection, archive, history, ownerKeyResolver),
                attachments,
                inspection,
                archive,
                history,
                ownerKeyResolver);
    }

    private static ChatSession anonymousSession(long id, String ownerKey) {
        ChatSession session = new ChatSession("anonymous", ownerKey, "ANON");
        session.setId(id);
        return session;
    }

    private static ChatSession adminSession(long id, String username) {
        ChatSession session = new ChatSession("admin", new Administrator(username, "n/a", "Admin"));
        session.setId(id);
        return session;
    }

    private static MultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", new byte[] {1});
    }

    private static MultipartFile sizedFile(long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    private record Fixture(
            AttachmentController controller,
            AttachmentService attachments,
            AttachmentInspectionService inspection,
            ConversationArchiveIngestService archive,
            ChatHistoryService history,
            ClientOwnerKeyResolver ownerKeyResolver) {
    }
}
