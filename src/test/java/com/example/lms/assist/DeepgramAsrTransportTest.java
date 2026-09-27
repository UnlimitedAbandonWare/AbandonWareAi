package com.example.lms.assist;

import com.example.lms.service.stt.DeepgramSttService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeepgramAsrTransportTest {
    static class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final DeepgramSttService service=mock(DeepgramSttService.class);
        final List<JsonNode> events=new CopyOnWriteArrayList<>();final List<String> failures=new CopyOnWriteArrayList<>();
        final Sinks.Many<DeepgramSttService.Transcript> results=Sinks.many().multicast().directBestEffort();
        final AtomicReference<Runnable> connected=new AtomicReference<>();final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicInteger bytes=new AtomicInteger();final AtomicReference<Disposable> input=new AtomicReference<>();
        final AtomicBoolean inputCompleted=new AtomicBoolean();
        final DeepgramAsrTransport transport;
        Fixture(boolean ready,boolean consume,Duration wall,long limit)throws Exception {
            when(service.isConfigured()).thenReturn(true);
            when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"),any())).thenAnswer(invocation->{
                Flux<byte[]> audio=invocation.getArgument(0);connected.set(invocation.getArgument(3));
                return results.asFlux().doOnSubscribe(s->{if(consume)input.set(audio.doOnComplete(()->inputCompleted.set(true)).subscribe(b->bytes.addAndGet(b.length)));if(ready)connected.get().run();})
                        .doFinally(s->{cancelled.set(true);if(input.get()!=null)input.get().dispose();});
            });
            transport=new DeepgramAsrTransport(json,service,events::add,failures::add,wall,limit);
        }
        void send(long seq)throws Exception {transport.send(json.writeValueAsString(Map.of("seq",seq,"pcm",Base64.getEncoder().encodeToString(new byte[640]))));}
        void result(String text,boolean fin,boolean speech,double start,double duration){results.tryEmitNext(new DeepgramSttService.Transcript(text,fin,speech,start,duration));}
        List<JsonNode> finals(){return events.stream().filter(e->e.path("final").asBoolean()).toList();}
        public void close(){transport.close();}
    }
    private Fixture fixture()throws Exception{return new Fixture(true,true,Duration.ofSeconds(600),DeepgramAsrTransport.MAX_AUDIO_BYTES);}
    @Test void finishStopsNewAudioAndWaitsForFinalDrainBeforeClosing()throws Exception {
        try(var f=fixture()){
            f.send(0);f.result("confirmed",true,false,0,1);
            var finished=f.transport.finish();
            assertTrue(f.inputCompleted.get());assertFalse(finished.isDone());assertTrue(f.transport.alive());
            assertSame(finished,f.transport.finish());assertThrows(IOException.class,()->f.send(1));
            f.result("tail",true,false,1,1);f.results.tryEmitComplete();
            finished.get(1,TimeUnit.SECONDS);
            assertEquals(1,f.finals().size());assertEquals("confirmed tail",f.finals().get(0).path("text").asText());
            assertTrue(f.failures.isEmpty());assertFalse(f.transport.alive());assertTrue(f.cancelled.get());
            assertEquals(640,f.bytes.get());
        }
    }
    @Test void finishDoesNotFinalizeUnconfirmedPartialOrDuplicateSpeechFinal()throws Exception {
        try(var f=fixture()){
            f.result("interim",false,false,0,1);var finished=f.transport.finish();f.results.tryEmitComplete();
            finished.get(1,TimeUnit.SECONDS);assertTrue(f.finals().isEmpty());
        }
        try(var f=fixture()){
            f.result("done",true,true,0,1);var finished=f.transport.finish();f.results.tryEmitComplete();
            finished.get(1,TimeUnit.SECONDS);assertEquals(1,f.finals().size());
        }
    }
    @Test void cancelDuringFinishFailsThePendingDrainAndIgnoresLateEvents()throws Exception {
        try(var f=fixture()){
            var finished=f.transport.finish();f.transport.close().get(1,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,()->finished.get(1,TimeUnit.SECONDS));
            int count=f.events.size();f.result("late",true,true,0,1);f.results.tryEmitComplete();
            assertEquals(count,f.events.size());assertFalse(f.transport.alive());assertTrue(f.cancelled.get());
        }
    }
    @Test void finishTimeoutReleasesTheOwnedStreamWithoutRetry()throws Exception {
        try(var f=fixture()){
            var finished=f.transport.finish();assertFalse(finished.isDone());
            assertThrows(ExecutionException.class,()->finished.get(4,TimeUnit.SECONDS));
            assertFalse(f.transport.alive());assertTrue(f.cancelled.get());
            assertEquals(List.of("ASR_FINISH_TIMEOUT"),f.failures);
        }
    }
    @Test void readyNamesTheActualDeepgramJavaTransport()throws Exception {
        try(var f=fixture()){assertEquals("java_ws",f.events.get(0).path("runtime").path("transport").asText());}
    }
    @Test void accountAndRequestFailuresRetainTerminalReasonsUsedByDisplay()throws Exception {
        var cases=Map.of("http_401","ASR_AUTH_FAILED","http_403","ASR_AUTH_FAILED","http_402","ASR_QUOTA_EXCEEDED",
                "http_429","ASR_RATE_LIMITED","http_400","ASR_AUDIO_FORMAT_INVALID","provider_error","ASR_PROVIDER_FAILED");
        for(var item:cases.entrySet())try(var f=fixture()){
            f.results.tryEmitError(new IllegalStateException("deepgram:"+item.getKey()));
            assertEquals(List.of(item.getValue()),f.failures);assertFalse(f.transport.alive());
            verify(f.service,times(1)).transcribePcm16Mono(any(),eq(16000),eq("ko"),any());
        }
    }
    @Test void actualWebsocketDrainKeepsFinalCaptionAndReleasesBridgeCapacity()throws Exception {
        var pcmBytes=new AtomicInteger();var closeStream=new AtomicBoolean();
        var server=reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0).route(routes->routes.get("/listen",(request,response)->
                response.sendWebsocket((in,out)->in.receiveFrames().concatMap(frame->{
                    if(frame instanceof io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame binary){
                        pcmBytes.addAndGet(binary.content().readableBytes());
                        return out.sendString(Mono.just("{\"type\":\"Results\",\"start\":0,\"duration\":1,\"is_final\":false,\"channel\":{\"alternatives\":[{\"transcript\":\"합성 전사\"}]}}"));
                    }
                    if(frame instanceof io.netty.handler.codec.http.websocketx.TextWebSocketFrame text&&text.text().contains("CloseStream")){
                        closeStream.set(true);
                        return out.sendString(Flux.just("{\"type\":\"Results\",\"start\":0,\"duration\":1,\"is_final\":true,\"speech_final\":false,\"channel\":{\"alternatives\":[{\"transcript\":\"합성 전사 완료\",\"words\":[{\"word\":\"합성\",\"start\":0,\"end\":0.5,\"confidence\":0.9,\"speaker\":0}]}]}}",
                                "{\"type\":\"Metadata\",\"duration\":0.02,\"channels\":1}")).then(out.sendClose(1000,""));
                    }
                    return Mono.empty();
                }).then()))).bindNow();
        var props=new com.example.lms.config.DeepgramProperties();props.setApiKey("synthetic");
        var constructor=DeepgramSttService.class.getDeclaredConstructor(com.example.lms.config.DeepgramProperties.class,ObjectMapper.class,
                org.springframework.web.reactive.socket.client.WebSocketClient.class,java.net.URI.class);constructor.setAccessible(true);
        var json=new ObjectMapper();var service=constructor.newInstance(props,json,new org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient(),java.net.URI.create("ws://127.0.0.1:"+server.port()+"/listen"));
        try(var sessions=new ConversateSessionService()){
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->new DeepgramAsrTransport(json,service,events,failure));
            try{
            String owner="d".repeat(64);var initial=sessions.start(owner);String id=initial.assistId();long epoch=initial.epoch();
            bridge.start(owner,id,epoch);bridge.chunk(owner,id,epoch,0,Base64.getEncoder().encodeToString(new byte[640]));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(sessions.status(owner,id).audio().partials()==0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(1,sessions.status(owner,id).audio().partials());
            var stopped=bridge.finish(owner,id,epoch);
            assertTrue(closeStream.get());assertEquals(640,pcmBytes.get());assertEquals(0,bridge.activeCount());
            assertEquals("STOPPED",stopped.audio().state());assertEquals("finished",stopped.audio().runtime().get("stopReason"));
            assertEquals("java_ws",stopped.audio().runtime().get("transport"));assertEquals("deepgram",stopped.audio().runtime().get("provider"));
            assertEquals(1,stopped.audio().finals());assertTrue(stopped.caption().isFinal());assertEquals("합성 전사 완료",stopped.caption().text());
            assertEquals(0,stopped.caption().words().get(0).speaker());assertEquals(0.9,stopped.caption().words().get(0).confidence());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->bridge.chunk(owner,id,epoch,1,Base64.getEncoder().encodeToString(new byte[640])));
            }finally{bridge.close();}
        }finally{server.disposeNow();}
    }
    @Test void captureLimitAndInputLossReleaseBridgeCapacity()throws Exception {
        for(boolean inputLoss:new boolean[]{false,true}){
            var json=new ObjectMapper();var service=mock(DeepgramSttService.class);var cancelled=new AtomicBoolean();
            when(service.isConfigured()).thenReturn(true);
            when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"),any())).thenAnswer(call->Flux.<DeepgramSttService.Transcript>never()
                    .doOnSubscribe(s->((Runnable)call.getArgument(3)).run()).doOnCancel(()->cancelled.set(true)));
            try(var sessions=new ConversateSessionService()){
                var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->
                    new DeepgramAsrTransport(json,service,events,failure,inputLoss?Duration.ofSeconds(600):Duration.ofMillis(150),DeepgramAsrTransport.MAX_AUDIO_BYTES));
                try{
                String owner="e".repeat(64);var initial=sessions.start(owner);bridge.start(owner,initial.assistId(),initial.epoch());
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
                while(bridge.activeCount()!=0&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(0,bridge.activeCount());assertTrue(cancelled.get());
                assertEquals(inputLoss?"ASR_INPUT_LOST":"ASR_CAPTURE_LIMIT",sessions.status(owner,initial.assistId()).reason());
                }finally{bridge.close();}
            }
        }
    }
    @Test void mergedCaptionDoesNotMislabelOneSegmentsConfidenceAsTheWholeUtterance()throws Exception {
        try(var f=fixture()){
            f.results.tryEmitNext(new DeepgramSttService.Transcript("First",true,false,0,1,0.9,List.of(),"Results"));
            f.results.tryEmitNext(new DeepgramSttService.Transcript("second",false,false,1,1,0.4,List.of(),"Results"));
            var caption=f.events.get(f.events.size()-1);assertEquals("First second",caption.path("text").asText());
            assertFalse(caption.hasNonNull("confidence"));
        }
    }
    @Test void utteranceEndFlushesOnceAndPreservesWordMetadata()throws Exception {
        try(var f=fixture()){
            var words=List.of(new DeepgramSttService.Word("합성",0,1,0.9,0));
            f.results.tryEmitNext(new DeepgramSttService.Transcript("합성",true,false,0,2,0.9,words,"Results"));
            assertEquals(0,f.finals().size());
            f.results.tryEmitNext(new DeepgramSttService.Transcript("",false,false,1,0,null,List.of(),"UtteranceEnd"));
            assertEquals(1,f.finals().size());assertEquals(0,f.finals().get(0).path("words").path(0).path("speaker").asInt(-1));
            f.results.tryEmitNext(new DeepgramSttService.Transcript("",false,false,1,0,null,List.of(),"UtteranceEnd"));
            f.result("합성",true,true,0,2);assertEquals(1,f.finals().size());
        }
    }
    @Test void subscribeDoesNotMeanConnectedAndAckIsLocalAcceptance()throws Exception {
        try(var f=new Fixture(false,true,Duration.ofSeconds(600),DeepgramAsrTransport.MAX_AUDIO_BYTES)){
            assertTrue(f.events.isEmpty());assertThrows(IOException.class,()->f.send(0));
            f.connected.get().run();assertEquals("ready",f.events.get(0).path("type").asText());f.send(0);
            assertEquals(640,f.bytes.get());assertEquals("local_pcm_accepted",f.events.get(1).path("scope").asText());
            verify(f.service,times(1)).transcribePcm16Mono(any(),eq(16000),eq("ko"),any());
        }
    }
    @Test void koreanSegmentsWaitForSpeechFinalAndDeduplicateByAudioTime()throws Exception {
        try(var f=fixture()){
            f.result("보증 기간은",false,false,0,1);f.result("보증 기간은",true,false,0,1);
            assertEquals(0,f.finals().size());
            f.result("2년이 아닙니다.",false,false,1,1);f.result("2년이 아닙니다.",true,true,1,1);
            assertEquals(1,f.finals().size());assertEquals("보증 기간은 2년이 아닙니다.",f.finals().get(0).path("text").asText());
            String id=f.finals().get(0).path("utteranceId").asText();
            assertTrue(f.events.stream().filter(e->e.path("type").asText().equals("transcript")).allMatch(e->id.equals(e.path("utteranceId").asText())));
            f.result("2년이 아닙니다.",true,true,1,1);assertEquals(1,f.finals().size());
            f.result("보증 기간은 2년이 아닙니다.",true,true,3,2);assertEquals(2,f.finals().size());
            assertNotEquals(id,f.finals().get(1).path("utteranceId").asText());
            long previous=0;for(var e:f.events)if(e.has("revision")){assertTrue(e.path("revision").asLong()>previous);previous=e.path("revision").asLong();}
        }
    }
    @Test void emptySpeechBoundaryFlushesConfirmedSegments()throws Exception {
        try(var f=fixture()){f.result("아니요, 20입니다.",true,false,0,1);f.result("",true,true,1,1);assertEquals(1,f.finals().size());assertEquals("아니요, 20입니다.",f.finals().get(0).path("text").asText());}
    }
    @Test void closingRejectsLateConnectionResultsAndAudio()throws Exception {
        try(var f=fixture()){
            f.send(0);f.close();int count=f.events.size();f.connected.get().run();f.result("late",true,true,0,1);
            assertThrows(IOException.class,()->f.send(1));assertEquals(count,f.events.size());assertEquals(640,f.bytes.get());assertTrue(f.cancelled.get());assertFalse(f.transport.alive());
        }
    }
    @Test void byteCapFailsWithoutReplay()throws Exception {
        try(var f=new Fixture(true,true,Duration.ofSeconds(600),640)){
            f.send(0);assertThrows(IOException.class,()->f.send(1));assertEquals(640,f.bytes.get());assertEquals(640,f.transport.acceptedBytes());assertEquals(1,f.failures.size());assertTrue(f.cancelled.get());
        }
    }
    @Test void unconsumedQueueIsBounded()throws Exception {
        try(var f=new Fixture(true,false,Duration.ofSeconds(600),DeepgramAsrTransport.MAX_AUDIO_BYTES)){
            for(int i=0;i<8;i++)f.send(i);assertThrows(IOException.class,()->f.send(8));assertEquals(8*640,f.transport.acceptedBytes());assertEquals(1,f.failures.size());
        }
    }
    @Test void wallDeadlineClosesEvenWithoutAudio()throws Exception {
        try(var f=new Fixture(true,true,Duration.ofMillis(40),DeepgramAsrTransport.MAX_AUDIO_BYTES)){
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(f.transport.alive()&&System.nanoTime()<until)Thread.sleep(5);
            assertFalse(f.transport.alive());assertEquals(List.of("ASR_CAPTURE_LIMIT"),f.failures);assertTrue(f.cancelled.get());
        }
    }
    @Test void transportErrorIsCategoricalAndNotRetried()throws Exception {
        try(var f=fixture()){
            f.results.tryEmitError(new IllegalStateException("private fixture body"));assertEquals(List.of("ASR_STREAM_FAILED"),f.failures);assertFalse(f.transport.alive());
            verify(f.service,times(1)).transcribePcm16Mono(any(),eq(16000),eq("ko"),any());
        }
    }
    @Test void configuredGuardAndProviderSelectionStayFailClosed() {
        var json=new ObjectMapper();var service=mock(DeepgramSttService.class);
        for(String provider:new String[]{"deepgram","unknown","local"})assertNull(ConversateAsrBridge.selectFactory(json,true,"","","",2,provider,service));
        when(service.isConfigured()).thenReturn(true);
        assertNull(ConversateAsrBridge.selectFactory(json,true,"","","",2,"deepgram",service)); // Key presence alone grants no budget.
        assertNull(ConversateAsrBridge.selectFactory(json,false,"","","",2,"deepgram",service));
        verify(service,never()).transcribePcm16Mono(any(),anyInt(),anyString(),any());
    }
}
