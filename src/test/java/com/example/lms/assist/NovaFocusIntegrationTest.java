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
    @Test void audioEpochRolloverKeepsFocusButExplicitContextResetClosesIt(){
        var history=mock(NovaFocusHistoryService.class);var d=NovaFocusSettings.defaults();
        var enabled=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation());
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(1,enabled));
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        try(var focus=new NovaFocusService(history,provider,new PublicChatAdmissionGuard());var sessions=new ConversateSessionService()){
            ReflectionTestUtils.setField(sessions,"novaFocus",focus);var first=sessions.startPublicDisplay(owner);
            focus.attach(owner,"live",first.assistId(),first.epoch());
            sessions.submit(owner,first.assistId(),first.epoch(),new ConversateQuestionPolicy.Utterance("a","a",0,true,"노바 first"),"phone_voice","r");
            sessions.audioMetrics(owner,first.assistId(),first.epoch(),new ConversateSessionService.AudioMetrics(0,0,1,0,0,"WAITING"));
            var next=sessions.nextSegment(owner,first.assistId(),first.epoch());
            focus.attach(owner,"live",next.assistId(),next.epoch());
            assertEquals("first",focus.view(owner,next.assistId(),next.epoch()).draftText());
            var reset=sessions.control(owner,next.assistId(),next.epoch(),"context_reset");
            assertEquals(next.epoch(),reset.epoch());assertEquals("RUNNING",reset.state());
            assertFalse(focus.active(next.assistId()));assertEquals("context_reset",reset.focus().reason());
        }
    }
    final String owner="a".repeat(64),client="a".repeat(32);
    @Test void previewReadKeepsFoldTargetWhileOrdinaryReadSelectsLens() throws Exception {
        var owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-preview-owner");
        var focus=mock(NovaFocusService.class);
        var http=new MockHttpServletRequest();http.setMethod("POST");http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");
        try(var sessions=new ConversateSessionService()){
            var c=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
            ReflectionTestUtils.setField(c,"phoneTestEnabled",true);ReflectionTestUtils.setField(c,"novaFocus",focus);
            var v=c.phoneTest(new DisplayConversateController.Connection(null,0,client,true,false),http).getBody();
            var auto=new DisplayConversateController.FocusCommand(v.assistId(),v.epoch(),client,"auto",null,null);
            var link=c.lensLink(new DisplayConversateController.Connection(v.assistId(),v.epoch(),client),http).getBody();
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
            var preview=mapper.readValue("{\"token\":\""+link.token()+"\",\"preview\":true}",DisplayConversateController.LensRead.class);
            c.lensText(preview,http);c.focusOpen(auto,http);
            verify(focus).open(anyString(),eq(v.assistId()),eq(v.epoch()),eq("fold"));
            clearInvocations(focus);
            c.lensText(new DisplayConversateController.LensRead(link.token()),http);c.focusOpen(auto,http);
            verify(focus).open(anyString(),eq(v.assistId()),eq(v.epoch()),eq("lens"));
            assertEquals("LensRead[redacted]",preview.toString());
        }
    }
    @Test void testChannelPreviewDoesNotRegisterLensSubscriber(){
        var relay=new DisplayRelay(java.time.Clock.systemUTC());
        var channel="test-"+"c".repeat(32);
        var p=relay.activate(channel,owner,client,"synthetic-session");
        relay.poll(channel,"b".repeat(32),LensDisplayPrefs.defaults(1000),true);
        assertEquals(0,relay.debug(channel,p).get("subscribers"));
        relay.poll(channel,"b".repeat(32),LensDisplayPrefs.defaults(1000));
        assertEquals(1,relay.debug(channel,p).get("subscribers"));
    }
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
            assertEquals("LISTENING",v.focus().phase());assertEquals("원리를 설명해줘",v.focus().draftText());assertNull(v.caption());
            var stale=sessions.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("a","a",0,false,"노바 오래된 중간 전사"),"phone_voice","r");
            assertNull(stale.caption());assertEquals(v.focus().draftText(),stale.focus().draftText());
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
            var badMemory=new DisplayConversateController.FocusMemoryCommand(v.assistId(),v.epoch(),"b".repeat(32),null,"fixture",1,1);
            assertEquals(403,assertThrows(ResponseStatusException.class,()->c.focusMemorySave(badMemory,http)).getStatusCode().value());
            assertEquals(403,assertThrows(ResponseStatusException.class,()->c.focusMemoryDelete(badMemory,http)).getStatusCode().value());
            verify(focus,never()).memorySave(anyString(),anyString(),anyLong(),any());
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
