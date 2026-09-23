package com.example.lms.assist;

import com.example.lms.api.PublicChatAdmissionGuard;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusIntegrationTest {
    final String owner="a".repeat(64),client="a".repeat(32);
    @Test void hintsOffStillDeliversFocusAndDoesNotStopCapture(){
        var history=mock(NovaFocusHistoryService.class);
        var d=NovaFocusSettings.defaults();var enabled=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation());
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(1,enabled));
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        var closed=new java.util.concurrent.atomic.AtomicInteger();
        try(var focus=new NovaFocusService(history,provider,new PublicChatAdmissionGuard());var sessions=new ConversateSessionService()){
            ReflectionTestUtils.setField(sessions,"novaFocus",focus);var s=sessions.startPublicDisplay(owner);
            focus.attach(owner,"live",s.assistId(),s.epoch());sessions.registerCapture(owner,s.assistId(),s.epoch(),closed::incrementAndGet);
            sessions.control(owner,s.assistId(),s.epoch(),"hints_off");
            var u=new ConversateQuestionPolicy.Utterance("a","a",0,true,"노바 원리를 설명해줘");
            var v=sessions.submit(owner,s.assistId(),s.epoch(),u,"phone_voice","r");
            assertEquals("LISTENING",v.focus().phase());assertEquals("원리를 설명해줘",v.focus().draftText());assertEquals(u.text(),v.caption().text());
            var stale=sessions.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("a","a",0,false,"노바 오래된 중간 전사"),"phone_voice","r");
            assertEquals(u.text(),stale.caption().text());assertTrue(stale.caption().isFinal());assertEquals(v.focus().draftText(),stale.focus().draftText());
            focus.close(owner,s.assistId(),s.epoch(),"user_closed");
            assertEquals("RUNNING",sessions.status(owner,s.assistId()).state());assertEquals(0,closed.get());assertFalse(sessions.hintsEnabled(owner,s.assistId()));
        }
    }
    @Test void onlyProducerCanChangeFocusAndLensReadDoesNotOpenIt(){
        var owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-owner");
        var focus=mock(NovaFocusService.class);
        var http=new MockHttpServletRequest();http.setMethod("POST");http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");
        try(var sessions=new ConversateSessionService()){
            var c=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());ReflectionTestUtils.setField(c,"phoneTestEnabled",true);ReflectionTestUtils.setField(c,"novaFocus",focus);
            var v=c.phoneTest(new DisplayConversateController.Connection(null,0,client,true,false),http).getBody();
            assertTrue(v.focusProducer());
            var bad=new DisplayConversateController.FocusCommand(v.assistId(),v.epoch(),"b".repeat(32),"fold",null,null);
            assertEquals(403,assertThrows(ResponseStatusException.class,()->c.focusOpen(bad,http)).getStatusCode().value());
            assertEquals(403,assertThrows(ResponseStatusException.class,()->c.focusInputStatus(bad,http)).getStatusCode().value());
            var good=new DisplayConversateController.FocusCommand(v.assistId(),v.epoch(),client,"fold",null,null);
            c.focusOpen(good,http);verify(focus).open(anyString(),eq(v.assistId()),eq(v.epoch()),eq("fold"));
            c.focusOpen(new DisplayConversateController.FocusCommand(v.assistId(),v.epoch(),client,"auto",null,null),http);
            verify(focus,times(2)).open(anyString(),eq(v.assistId()),eq(v.epoch()),eq("fold"));
            c.focusClose(good,http);assertEquals("RUNNING",sessions.status(org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-owner"),v.assistId()).state());
            clearInvocations(focus);
            var link=c.lensLink(new DisplayConversateController.Connection(v.assistId(),v.epoch(),client),http).getBody();
            c.lensText(new DisplayConversateController.LensRead(link.token()),http);
            verify(focus,never()).open(anyString(),anyString(),anyLong(),anyString());verify(focus,never()).attach(anyString(),anyString(),anyString(),anyLong());
        }
    }
}
