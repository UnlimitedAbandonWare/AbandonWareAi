package com.example.lms.assist;

import com.example.lms.service.stt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversateCloudRoutingTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    final AtomicLong nanos=new AtomicLong();
    final DeepgramSttService dg=mock(DeepgramSttService.class);
    final SonioxSttService sx=mock(SonioxSttService.class);
    ConversateCloudStt cloud(String provider,String cap){
        when(dg.isConfigured()).thenReturn(true);when(sx.isConfigured()).thenReturn(true);
        when(sx.model()).thenReturn("stt-rt-v5");
        return new ConversateCloudStt(dg,sx,provider,new ConversateSttBudget(json,true,dir.resolve("budget.jsonl").toString(),"verification",cap,"5",Clock.systemUTC()),json,nanos::get,Duration.ofSeconds(1),Duration.ofSeconds(60));
    }
    Flux<DeepgramSttService.Transcript> success(){return Flux.just(new DeepgramSttService.Transcript("synthetic",true,true,0,1));}
    @Test void autoUsesConfiguredLowerEstimatedCostAndRecordsBothAttempts()throws Exception{
        var c=cloud("auto","1");
        when(sx.transcribePcm16Mono(any(),any())).thenReturn(Flux.error(new IllegalStateException("soniox:rate_limited")));
        when(dg.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(success());
        assertEquals("synthetic",c.transcribe(new byte[32000]).block());
        var order=inOrder(sx,dg);order.verify(sx).transcribePcm16Mono(any(),any());order.verify(dg).transcribePcm16Mono(any(),eq(16000),eq("ko"));
        assertEquals(2,c.budgetView().reserved().requests());
        assertTrue(c.diagnostics().containsKey("providers"));
    }
    @Test void bothFailAtMostOnceAndCancellationNeverStartsAlternate(){
        var c=cloud("auto","1");
        when(sx.transcribePcm16Mono(any(),any())).thenReturn(Flux.error(new IllegalStateException("soniox:transport_error")));
        when(dg.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.error(new IllegalStateException("deepgram:http_503")));
        assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());
        verify(sx,times(1)).transcribePcm16Mono(any(),any());verify(dg,times(1)).transcribePcm16Mono(any(),eq(16000),eq("ko"));
        reset(sx,dg);when(sx.isConfigured()).thenReturn(true);when(dg.isConfigured()).thenReturn(true);
        when(sx.transcribePcm16Mono(any(),any())).thenReturn(Flux.never());
        var subscription=c.transcribe(new byte[640]).subscribe();subscription.dispose();
        verify(dg,never()).transcribePcm16Mono(any(),anyInt(),anyString());
    }
    @Test void budgetDenialDoesNotTryEitherProvider(){
        var c=cloud("auto","0.0001");
        assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());
        verify(sx,never()).transcribePcm16Mono(any(),any());verify(dg,never()).transcribePcm16Mono(any(),anyInt(),anyString());
    }
    @Test void accountingUsesConsumedPcmAndDoesNotReplaceReservation()throws Exception{
        var c=cloud("deepgram","1");
        when(dg.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenAnswer(call->{
            Flux<byte[]> audio=call.getArgument(0);return audio.thenMany(success());
        });
        assertEquals("synthetic",c.transcribe(new byte[32000]).block());
        var providers=(Map<?,?>)c.diagnostics().get("providers");var stats=(Map<?,?>)providers.get("deepgram");
        assertEquals(1000L,stats.get("acceptedAudioMs"));assertEquals(134L,stats.get("estimatedMicros"));
        assertEquals(1L,stats.get("successes"));assertEquals(0L,stats.get("handshakes"));
        assertTrue(c.budgetView().reserved().totalMicros()>(long)stats.get("estimatedMicros"));
    }
    @Test void costAndObservedFailuresCanReverseTheNextPreferredProvider(){
        var router=new ConversateSttRouting(nanos::get,Duration.ofSeconds(60));
        var rates=Map.of("deepgram",8000L,"soniox",2000L);
        assertEquals("soniox",router.order(java.util.List.of("deepgram","soniox"),1,rates).get(0));
        var attempt=router.begin("soniox",2000);attempt.failure("transport_error");attempt.close();
        assertEquals("deepgram",router.order(java.util.List.of("deepgram","soniox"),1,rates).get(0));
    }
    @Test void sonioxKeepaliveWallTimeIsNotPricedAsZeroAudio(){
        var router=new ConversateSttRouting(nanos::get,Duration.ofSeconds(60));
        var attempt=router.begin("soniox",2000,"pcm_stream");attempt.connected();
        nanos.addAndGet(Duration.ofSeconds(60).toNanos());attempt.close();
        var row=(Map<?,?>)router.view(Map.of("deepgram",8000L,"soniox",2000L)).get("soniox");
        assertEquals(0L,row.get("acceptedAudioMs"));assertEquals(60000L,row.get("connectedWallMs"));assertEquals(2000L,row.get("estimatedMicros"));
    }
    @Test void nativeThenJavaSonioxFailureCanReachDeepgramWithThreeReservations()throws Exception{
        var c=cloud("auto","1");var manager=mock(SonioxSidecarManager.class);
        ReflectionTestUtils.setField(c,"sidecar",manager);when(manager.configured()).thenReturn(true);
        var nativeFailure=new AtomicReference<Consumer<String>>();
        when(manager.connectAdmittedStream(any(),any())).thenAnswer(call->{
            Consumer<JsonNode> events=call.getArgument(0);nativeFailure.set(call.getArgument(1));events.accept(json.createObjectNode().put("type","ready"));return new SilentTransport();
        });
        var javaReady=new CompletableFuture<Void>();var dgReady=new CompletableFuture<Void>();
        var javaEvents=reactor.core.publisher.Sinks.many().unicast().<SonioxSttService.Transcript>onBackpressureBuffer();
        when(sx.transcribePcm16Mono(any(),any())).thenAnswer(call->{((Runnable)call.getArgument(1)).run();javaReady.complete(null);return javaEvents.asFlux();});
        when(dg.transcribePcm16Mono(any(),eq(16000),eq("ko"),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();dgReady.complete(null);return Flux.never();});
        var failures=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var transport=c.openStream(e->{},failures::add);
        try{
            nativeFailure.get().accept("ASR_SIDECAR_FAILED");javaReady.get(3,TimeUnit.SECONDS);
            javaEvents.tryEmitError(new IllegalStateException("soniox:transport_error"));dgReady.get(3,TimeUnit.SECONDS);
            assertEquals(3,c.budgetView().reserved().requests());assertTrue(failures.isEmpty());
            verify(manager,times(1)).connectAdmittedStream(any(),any());verify(sx,times(1)).transcribePcm16Mono(any(),any());
            verify(dg,times(1)).transcribePcm16Mono(any(),eq(16000),eq("ko"),any());
        }finally{transport.close().get(3,TimeUnit.SECONDS);}
    }
    @Test void nativeFallbackBudgetDenialStopsWithoutAnotherSubscription()throws Exception{
        var c=cloud("auto","0.09");var manager=mock(SonioxSidecarManager.class);
        ReflectionTestUtils.setField(c,"sidecar",manager);when(manager.configured()).thenReturn(true);
        var nativeFailure=new AtomicReference<Consumer<String>>();
        when(manager.connectAdmittedStream(any(),any())).thenAnswer(call->{
            Consumer<JsonNode> events=call.getArgument(0);nativeFailure.set(call.getArgument(1));events.accept(json.createObjectNode().put("type","ready"));return new SilentTransport();
        });
        var failure=new CompletableFuture<String>();var transport=c.openStream(e->{},failure::complete);
        try{
            nativeFailure.get().accept("ASR_SIDECAR_FAILED");assertEquals("ASR_BUDGET_EXHAUSTED",failure.get(3,TimeUnit.SECONDS));
            assertEquals(1,c.budgetView().reserved().requests());verify(sx,never()).transcribePcm16Mono(any(),any());verify(dg,never()).transcribePcm16Mono(any(),anyInt(),anyString(),any());
        }finally{transport.close().get(3,TimeUnit.SECONDS);}
    }
    @Test void disabledSonioxAndMalformedAudioNeverTriggerSonioxSubscription()throws Exception{
        var c=cloud("auto","1");when(sx.isConfigured()).thenReturn(false);
        when(dg.transcribePcm16Mono(any(),eq(16000),eq("ko"),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return Flux.never();});
        var failure=new CompletableFuture<String>();var transport=c.openStream(e->{},failure::complete);
        try{assertThrows(Exception.class,()->transport.send("{}"));assertNotNull(failure.get(2,TimeUnit.SECONDS));verify(sx,never()).transcribePcm16Mono(any(),any());}
        finally{transport.close().get(3,TimeUnit.SECONDS);}
    }
    static class SilentTransport implements ConversateAsrBridge.Transport{
        boolean closed;public void send(String line){}public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}public boolean alive(){return !closed;}
    }
}
