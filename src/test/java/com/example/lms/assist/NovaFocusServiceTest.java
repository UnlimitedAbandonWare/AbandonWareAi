package com.example.lms.assist;

import com.example.lms.api.PublicChatAdmissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusServiceTest {
    static class Time extends Clock {
        volatile long now;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Test void independentWorkerAndCloseFenceLateProviderCompletion() throws Exception {
        var history=mock(NovaFocusHistoryService.class);var answer=mock(NovaFocusAnswer.class);
        when(answer.answer(anyLong(),anyString(),any(),any())).thenCallRealMethod();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        when(history.context(anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Context(List.of(),"",List.of()));
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        when(answer.answer(anyLong(),anyString(),any())).thenAnswer(call->{started.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));finished.countDown();return "late";});
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"r","question");time.now=1200;service.maintain();assertTrue(started.await(3,TimeUnit.SECONDS));
            assertEquals("THINKING",service.view(owner,"assist",1).phase());
            service.close(owner,"assist",1,"closed");release.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));
            assertFalse(service.active("assist"));verify(history,never()).terminal(anyString(),anyString(),anyString(),eq("COMPLETED"),anyString());
            verify(answer).cancel(7L);assertNull(service.view(owner,"assist",2));
        }finally{release.countDown();}
    }
    @Test void persistedManualRetryDoesNotQueueAndConflictingPayloadFailsSynchronously(){
        var history=mock(NovaFocusHistoryService.class);var answer=mock(NovaFocusAnswer.class);
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        String owner="a".repeat(64),key=NovaFocusState.typedRequestId("retry");
        when(history.knownRequest(owner,"live",key,"question")).thenReturn(true);
        when(history.knownRequest(owner,"live",key,"different")).thenThrow(new IllegalArgumentException("focus_request_conflict"));
        var time=new Time();
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"retry"," question ");time.now=1200;service.maintain();
            assertEquals("focus_request_already_accepted",service.view(owner,"assist",1).reason());
            assertEquals("",service.view(owner,"assist",1).draftText());
            var conflict=assertThrows(IllegalArgumentException.class,()->service.input(owner,"assist",1,"retry","different"));
            assertEquals("focus_request_conflict",conflict.getMessage());
            verify(history,never()).accept(anyString(),anyString(),anyString(),anyString(),anyString());
            verifyNoInteractions(answer);
        }
    }
    @Test void settingsAndHistoryRequireExistingOwnerEpoch(){
        var history=mock(NovaFocusHistoryService.class);
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),new Time())){
            service.attach("a".repeat(64),"live","assist",1);
            String cacheScope=service.localStore("a".repeat(64),"assist",1).cacheScope();
            assertTrue(cacheScope.matches("[a-f0-9]{64}"));
            service.attach("b".repeat(64),"live","other",1);
            assertNotEquals(cacheScope,service.localStore("b".repeat(64),"other",1).cacheScope());
            assertThrows(IllegalArgumentException.class,()->service.inputAccepted("b".repeat(64),"assist",1,"r","question"));
            assertThrows(IllegalArgumentException.class,()->service.settings("b".repeat(64),"assist",1));
            assertThrows(IllegalArgumentException.class,()->service.settings("a".repeat(64),"assist",2));
            assertThrows(IllegalStateException.class,()->service.open("a".repeat(64),"assist",1,"lens"));
            assertFalse(service.rendered(new NovaFocusService.Receipt("wrong","wrong","wrong",1,"0".repeat(64),"presentation_done")));
            verify(history,never()).open(anyString(),anyString());
        }
    }
    @Test void turningVoiceWakeOffCancelsFocusWorkAndPreservesBinding() throws Exception {
        var history=mock(NovaFocusHistoryService.class);var answer=mock(NovaFocusAnswer.class);
        when(answer.answer(anyLong(),anyString(),any(),any())).thenCallRealMethod();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        var d=NovaFocusSettings.defaults();var enabled=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation());
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,enabled));
        when(history.settings(anyString(),anyString(),eq(0L),eq(d))).thenReturn(new NovaFocusHistoryService.Settings(1,d));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        when(history.context(anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Context(List.of(),"",List.of()));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(answer.answer(anyLong(),anyString(),any())).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return "late";});
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");service.input(owner,"assist",1,"r","question");
            time.now=1200;service.maintain();assertTrue(entered.await(3,TimeUnit.SECONDS));
            service.configure(owner,"assist",1,0,d);assertFalse(service.active("assist"));verify(answer).cancel(7L);
            assertNotNull(service.view(owner,"assist",1));verify(history).terminal(owner,"live","turn","CANCELLED",null);
            release.countDown();
        }finally{release.countDown();}
    }
}
