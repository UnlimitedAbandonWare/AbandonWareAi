package com.example.lms.assist;

import com.example.lms.service.stt.DeepgramSttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversateCloudSttTest {
    @TempDir Path dir;final ObjectMapper json=new ObjectMapper();final AtomicLong nanos=new AtomicLong();
    ConversateSttBudget budget(String cap){return new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"verification",cap,"5",Clock.systemUTC());}
    DeepgramSttService service(){var service=mock(DeepgramSttService.class);when(service.isConfigured()).thenReturn(true);return service;}
    ConversateCloudStt cloud(DeepgramSttService service,ConversateSttBudget budget){return new ConversateCloudStt(service,budget,json,nanos::get,Duration.ofMillis(80),Duration.ofSeconds(60));}
    DeepgramSttService.Transcript word(double start){return new DeepgramSttService.Transcript("다시",true,true,start,1);}
    @Test void budgetDeniesBothExplicitAndFallbackPathsBeforeAnySubscription(){
        var service=service();var cloud=cloud(service,budget("0.0001"));
        assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());assertThrows(Exception.class,()->cloud.openStream(event->{},reason->{}));
        verify(service,never()).transcribePcm16Mono(any(),anyInt(),anyString());
        verify(service,never()).transcribePcm16Mono(any(),anyInt(),anyString(),any());
    }
    @Test void repeated429And5xxOpenCircuitThenExactlyOneProbeRecovers()throws Exception{
        var service=service();var b=budget("1");var cloud=cloud(service,b);
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.error(new RuntimeException("deepgram:http_429")),Flux.error(new RuntimeException("deepgram:http_503")),Flux.just(word(0),word(0),word(1)));
        assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());
        assertEquals("OPEN",cloud.diagnostics().get("state"));assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());
        verify(service,times(2)).transcribePcm16Mono(any(),eq(16000),eq("ko"));
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());assertEquals("다시 다시",cloud.transcribe(new byte[64000]).block());
        assertEquals("CLOSED",cloud.diagnostics().get("state"));assertEquals(3,b.view().reserved().requests());
    }
    @Test void timeoutDiscardsPartialCancelsSocketAndDoesNotRefundUnknownCharge()throws Exception{
        var service=service();var b=budget("1");var cloud=cloud(service,b);var cancelled=new AtomicBoolean();
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.concat(Flux.just(new DeepgramSttService.Transcript("부분",false,false,0,1)),Flux.never()).doOnCancel(()->cancelled.set(true)));
        assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());assertTrue(cancelled.get());
        assertEquals(1,b.view().reserved().requests());assertTrue(b.view().reserved().verificationMicros()>0);
        assertEquals("timeout",cloud.diagnostics().get("reason"));
    }
    @Test void activeCaptureAllowsOneCallAndCancellationReleasesIt(){
        var service=service();var cloud=cloud(service,budget("1"));var cancelled=new AtomicInteger();
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.never().cast(DeepgramSttService.Transcript.class).doOnCancel(cancelled::incrementAndGet));
        var active=cloud.transcribe(new byte[640]).subscribe();assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());
        active.dispose();assertEquals(1,cancelled.get());assertEquals("CLOSED",cloud.diagnostics().get("state"));
    }
    @Test void halfOpenCooldownAllowsOneProbeOnly(){
        var service=service();var cloud=cloud(service,budget("1"));
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.error(new RuntimeException("deepgram:http_503")),Flux.error(new RuntimeException("deepgram:http_429")),Flux.never());
        assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());assertEquals("HALF_OPEN",cloud.diagnostics().get("state"));
        var probe=cloud.transcribe(new byte[640]).subscribe();assertThrows(Exception.class,()->cloud.transcribe(new byte[640]).block());
        verify(service,times(3)).transcribePcm16Mono(any(),eq(16000),eq("ko"));probe.dispose();
    }
    @Test void invalidAudioAndConflictingSegmentsCannotProduceText(){
        var service=service();var cloud=cloud(service,budget("1"));
        assertThrows(Exception.class,()->cloud.transcribe(new byte[320640]).block());verify(service,never()).transcribePcm16Mono(any(),anyInt(),anyString());
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"))).thenReturn(Flux.just(word(0),new DeepgramSttService.Transcript("변경",true,true,0,1)));
        assertThrows(Exception.class,()->cloud.transcribe(new byte[64000]).block());
    }
}
