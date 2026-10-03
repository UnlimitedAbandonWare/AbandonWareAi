package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.rag.graph.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatAttachmentSelectionFlowTest {
    @Test void explicitSelectionWinsAndReferentRestoresOnlyOwnedApprovedSubset(){
        var authority=mock(GeneralGraphSourceAuthority.class);
        when(authority.selectedAttachmentIds(any())).thenReturn(List.of("selected"));
        var controller=mock(ChatApiController.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller,"generalGraphSourceAuthority",authority);
        var session=new ChatSession("fixture","owner","ANON");session.setId(42L);
        var request=ChatRequestDto.builder().message("그 보고서를 다시 비교해줘").sessionId(42L).attachmentIds(List.of("explicit")).build();
        ReflectionTestUtils.invokeMethod(controller,"restoreAttachmentSelection",request,session,null,"owner");
        assertEquals(List.of("explicit"),request.getAttachmentIds());verifyNoInteractions(authority);
        request.setAttachmentIds(List.of());
        ReflectionTestUtils.invokeMethod(controller,"restoreAttachmentSelection",request,session,null,"owner");
        assertEquals(List.of("selected"),request.getAttachmentIds());
        verify(authority,times(1)).selectedAttachmentIds(any());
        request.setAttachmentIds(List.of());session.setOwnerKey("foreign");
        ReflectionTestUtils.invokeMethod(controller,"restoreAttachmentSelection",request,session,null,"owner");
        assertEquals(List.of(),request.getAttachmentIds());verifyNoMoreInteractions(authority);
    }
    @Test void consentIsPerRequestAndUsedSourcesCannotBeForgedFromWire()throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=mapper.readValue("{\"attachmentGraphConsent\":true,\"usedAttachmentSources\":[{\"sourceId\":\"attachment:33333333-3333-3333-3333-333333333333\",\"sourceRevision\":2}]}",ChatRequestDto.class);
        assertEquals(Boolean.TRUE,ChatRequestDto.class.getMethod("getAttachmentGraphConsent").invoke(request));
        assertEquals(List.of(),ChatRequestDto.class.getMethod("getUsedAttachmentSources").invoke(request));
        assertNull(ChatRequestDto.class.getMethod("getAttachmentGraphConsent").invoke(new ChatRequestDto()));
    }
}
