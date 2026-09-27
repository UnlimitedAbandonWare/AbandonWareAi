package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversateAsrFallbackTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode fallback(int number,boolean fin){return json.createObjectNode().put("type","fallback").put("utteranceId","asr-"+number).put("revision",3).put("final",fin).put("pcm",Base64.getEncoder().encodeToString(new byte[640]));}
    class Fixture implements AutoCloseable {
        final ConversateSessionService sessions=new ConversateSessionService();final ConversateCloudStt cloud=mock(ConversateCloudStt.class);
        final ConversateAsrBridgeTest.FakeTransport child=new ConversateAsrBridgeTest.FakeTransport();final ConversateAsrBridge bridge;
        final ConversateSessionService.Snapshot session;
        Fixture(){bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{child.events=events;events.accept(json.createObjectNode().put("type","ready"));return child;},cloud);session=sessions.start("owner");bridge.start("owner",session.assistId(),session.epoch());}
        ConversateSessionService.Snapshot state(){return sessions.status("owner",session.assistId());}
        public void close(){bridge.close();sessions.close();}
    }
    @Test void finalHandoffIsOnceAndLateLocalTextCannotOverwriteIt(){
        try(var f=new Fixture()){
            when(f.cloud.transcribe(any())).thenReturn(Mono.just("보증 기간은 몇 년인가요?"));
            var event=fallback(1,true);f.child.events.accept(event);f.child.events.accept(event);
            f.child.events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","asr-1").put("revision",4).put("final",true).put("text","다른 결과"));
            assertEquals(1,f.state().audio().finals());assertEquals(1,f.state().audio().duplicates());verify(f.cloud,times(1)).transcribe(any());
            f.child.events.accept(fallback(2,true));assertEquals(2,f.state().audio().finals());
        }
    }
    @Test void partialAndOversizedEventsNeverSubscribe(){
        try(var f=new Fixture()){f.child.events.accept(fallback(1,false));assertEquals("PAUSED",f.state().state());assertTrue(f.child.closed);verify(f.cloud,never()).transcribe(any());}
        try(var f=new Fixture()){
            var oversized=fallback(1,true).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)oversized).put("pcm","A".repeat(426669));f.child.events.accept(oversized);
            assertEquals("PAUSED",f.state().state());verify(f.cloud,never()).transcribe(any());
        }
    }
    @Test void pauseCancelsRemoteAndDiscardsLateFinal(){
        try(var f=new Fixture()){
            var sink=new AtomicReference<MonoSink<String>>();var cancelled=new AtomicBoolean();
            when(f.cloud.transcribe(any())).thenReturn(Mono.<String>create(sink::set).doOnCancel(()->cancelled.set(true)));
            f.child.events.accept(fallback(1,true));f.sessions.control("owner",f.session.assistId(),f.session.epoch(),"pause");
            assertTrue(cancelled.get());sink.get().success("보증 기간은 몇 년인가요?");assertEquals(0,f.state().audio().finals());assertEquals(0,f.bridge.activeCount());
        }
    }
    @Test void busyOrBudgetFailureKeepsExplicitTextFallback(){
        try(var f=new Fixture()){
            when(f.cloud.transcribe(any())).thenReturn(Mono.error(new IllegalStateException("stt_budget_exhausted")));
            f.child.events.accept(fallback(1,true));assertEquals("PAUSED",f.state().state());assertEquals("ASR_FALLBACK_UNAVAILABLE",f.state().reason());assertTrue(f.child.closed);
            assertEquals("stt_budget_exhausted",f.state().audio().runtime().get("reason"));
        }
        try(var f=new Fixture()){
            when(f.cloud.transcribe(any())).thenReturn(Mono.never());f.child.events.accept(fallback(1,true));f.child.events.accept(fallback(2,true));
            verify(f.cloud,times(1)).transcribe(any());assertEquals("PAUSED",f.state().state());
        }
    }
    @Test void runtimeMetadataIsAllowlistedAndStoppedCaptureRetainsItsLastSample()throws Exception{
        try(var f=new Fixture()){
            var event=json.createObjectNode().put("type","ready");
            event.set("runtime",json.createObjectNode().put("provider","whisper").put("device","cuda").put("reason","primary")
                .put("gpuUuid","GPU-3d34d77c-1173-6bc7-a96d-c2d9b7c4e670").put("vramFreeMiB",8192).put("vramObservedAt",1789385333000L)
                .put("queueLength",2).put("errors",0).put("text","SYNTHETIC_PRIVATE").put("modelPath","C:/private/model"));
            f.child.events.accept(event);var r=f.state().audio().runtime();
            assertEquals("whisper",r.get("provider"));assertEquals(8192L,r.get("vramFreeMiB"));assertEquals("RTX3060_b7c4e670",r.get("gpu"));
            assertFalse(json.writeValueAsString(r).contains("PRIVATE"));assertFalse(json.writeValueAsString(r).contains("GPU-3d34"));
            f.sessions.control("owner",f.session.assistId(),f.session.epoch(),"pause");
            assertEquals("STOPPED",f.state().audio().state());assertEquals(r,f.state().audio().runtime());
        }
        try(var f=new Fixture()){
            var event=json.createObjectNode().put("type","ready");event.set("runtime",json.createObjectNode().put("provider","C:/private").put("device","unknown").put("reason","RAW_SECRET").put("queueLength",999).put("errors",-1));
            f.child.events.accept(event);var r=f.state().audio().runtime();
            assertFalse(r.containsKey("provider"));assertFalse(r.containsKey("queueLength"));assertFalse(r.containsKey("errors"));
        }
    }
    @Test void readyUsesTheBudgetReservedDuringFactoryLaunch()throws Exception{
        try(var sessions=new ConversateSessionService()){
            var cloud=mock(ConversateCloudStt.class);
            var before=new ConversateSttBudget.View("verification",1000000,5000000,new ConversateSttBudget.Usage("2026-09",0,0,0,0,0,0),"conservative_reservation_usd_micros","not_assumed");
            var reserved=new ConversateSttBudget.View("verification",1000000,5000000,new ConversateSttBudget.Usage("2026-09",80134,80134,0,80134,1,601),"conservative_reservation_usd_micros","not_assumed");
            when(cloud.budgetView()).thenReturn(before,reserved);
            var child=new ConversateAsrBridgeTest.FakeTransport();
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{child.events=events;events.accept(json.createObjectNode().put("type","ready"));return child;},cloud);
            try{
                var s=sessions.start("owner");bridge.start("owner",s.assistId(),s.epoch());
                var status=(Map<?,?>)sessions.status("owner",s.assistId()).audio().runtime().get("cloudStatus");
                assertEquals(reserved,status.get("budget"));assertTrue((long)status.get("observedAt")>0);verify(cloud,times(2)).budgetView();
            }finally{bridge.close();}
        }
    }
}
