package com.example.lms.assist;
import com.example.lms.domain.*;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.*;
import com.example.lms.web.ClientOwnerKeyResolver;
import dev.langchain4j.data.document.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversatePreparedMaterialsTest {
    @Test void onlyExistingOwnerAndSessionLinkedDocumentsAreRead(){
        var repo=mock(ChatSessionRepository.class);var attachments=mock(AttachmentService.class);var keys=mock(ClientOwnerKeyResolver.class);
        var admin=new Administrator();admin.setUsername("alice");var session=new ChatSession();session.setAdministrator(admin);
        when(repo.findById(7L)).thenReturn(Optional.of(session));var owner=AttachmentOwnerIdentity.forAdministrator("alice");
        when(attachments.findIdsBySession("7",256,owner)).thenReturn(List.of("allowed"));
        when(attachments.asDocumentsForSession(List.of("allowed"),"7",owner)).thenReturn(List.of(Document.from("보증 기간은 2년입니다.",Metadata.from("attachmentId","allowed"))));
        var reader=new ConversatePreparedMaterials(repo,attachments,keys);
        assertEquals("allowed",reader.read("alice","7",List.of("allowed")).get(0).sourceId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reader.read("mallory","7",List.of("allowed")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reader.read("alice","7",List.of("other-session")));
        verify(attachments,times(1)).asDocumentsForSession(anyList(),anyString(),any());
        verify(repo,never()).save(any());assertTrue(session.getMessages()==null||session.getMessages().isEmpty());
    }
    @Test void databaseUnavailableIsCategoricalAndNeverProceedsToAttachments(){
        var repo=mock(ChatSessionRepository.class);var attachments=mock(AttachmentService.class);var keys=mock(ClientOwnerKeyResolver.class);
        when(repo.findById(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-driver-detail"));
        var reader=new ConversatePreparedMaterials(repo,attachments,keys);
        for(boolean list:List.of(true,false)){
            var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->{if(list)reader.choices("alice","7");else reader.read("alice","7",List.of("a"));});
            assertEquals(503,failure.getStatusCode().value());assertEquals("material_index_unavailable",failure.getReason());assertFalse(failure.getMessage().contains("synthetic-private-driver-detail"));
        }
        verifyNoInteractions(attachments);
    }
    @Test void emptyParsedMaterialIsNotClassifiedAsSuccessfulReadOrNoMatch(){
        var repo=mock(ChatSessionRepository.class);var attachments=mock(AttachmentService.class);var keys=mock(ClientOwnerKeyResolver.class);
        var admin=new Administrator();admin.setUsername("alice");var session=new ChatSession();session.setAdministrator(admin);when(repo.findById(7L)).thenReturn(Optional.of(session));var owner=AttachmentOwnerIdentity.forAdministrator("alice");
        var blank=mock(Document.class);when(blank.text()).thenReturn(" ");when(blank.metadata()).thenReturn(Metadata.from("attachmentId","a"));
        when(attachments.findIdsBySession("7",256,owner)).thenReturn(List.of("a"));when(attachments.asDocumentsForSession(List.of("a"),"7",owner)).thenReturn(List.of(blank));
        var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new ConversatePreparedMaterials(repo,attachments,keys).read("alice","7",List.of("a")));
        assertEquals(422,failure.getStatusCode().value());assertEquals("material_parse_failed",failure.getReason());
    }
}
