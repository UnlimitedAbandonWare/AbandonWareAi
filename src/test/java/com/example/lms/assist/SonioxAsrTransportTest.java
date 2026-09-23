package com.example.lms.assist;

import com.example.lms.service.stt.SonioxSttService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SonioxAsrTransportTest {
    final ObjectMapper json=new ObjectMapper();
    SonioxSttService service(){var service=mock(SonioxSttService.class);when(service.isConfigured()).thenReturn(true);return service;}
    @Test void readinessWaitsForHandshakeAndCloseCancelsTheOwnedWire()throws Exception{
        var s=service();var ready=new AtomicReference<Runnable>();var cancelled=new AtomicBoolean();var events=new ArrayList<JsonNode>();
        when(s.transcribePcm16Mono(any(),any())).thenAnswer(call->{ready.set(call.getArgument(1));return Flux.<SonioxSttService.Transcript>never().doOnCancel(()->cancelled.set(true));});
        var t=new SonioxAsrTransport(json,s,events::add,r->{});assertTrue(events.isEmpty());ready.get().run();assertEquals("ready",events.get(0).path("type").asText());
        t.close().join();assertTrue(cancelled.get());assertFalse(t.alive());int count=events.size();ready.get().run();assertEquals(count,events.size());
    }
    @Test void partialFinalKeepOneUtteranceIdentityAndNewCaptureCannotReuseIt()throws Exception{
        var s=service();var events=new ArrayList<JsonNode>();
        when(s.transcribePcm16Mono(any(),any())).thenAnswer(call->{((Runnable)call.getArgument(1)).run();return Flux.concat(Flux.just(new SonioxSttService.Transcript("한국",false,0),new SonioxSttService.Transcript("한국어",true,0)),Flux.never());});
        var t=new SonioxAsrTransport(json,s,events::add,r->{});var first=events.get(1);var fin=events.get(2);
        assertFalse(first.path("final").asBoolean());assertTrue(fin.path("final").asBoolean());assertEquals(first.path("utteranceId"),fin.path("utteranceId"));assertTrue(fin.path("revision").asLong()>first.path("revision").asLong());
        var other=new ArrayList<JsonNode>();var second=new SonioxAsrTransport(json,s,other::add,r->{});assertNotEquals(first.path("utteranceId"),other.get(1).path("utteranceId"));t.close();second.close();
    }
    @Test void malformedAndOversizedPcmFailAndReleaseWire()throws Exception{
        var s=service();when(s.transcribePcm16Mono(any(),any())).thenAnswer(call->{((Runnable)call.getArgument(1)).run();return Flux.never();});
        var failed=new AtomicInteger();var t=new SonioxAsrTransport(json,s,e->{},r->failed.incrementAndGet());
        assertThrows(Exception.class,()->t.send(json.writeValueAsString(Map.of("seq",0,"pcm",Base64.getEncoder().encodeToString(new byte[8000])))));
        assertFalse(t.alive());assertEquals(1,failed.get());
    }
    @Test void finishDrainsFinalAfterInputCompletesAndIgnoresLateHandshake()throws Exception{
        var s=service();var events=new ArrayList<JsonNode>();var errors=new ArrayList<String>();var ready=new AtomicReference<Runnable>();
        when(s.transcribePcm16Mono(any(),any())).thenAnswer(call->{
            ready.set(call.getArgument(1));ready.get().run();
            Flux<byte[]> input=call.getArgument(0);
            return Flux.concat(Flux.just(new SonioxSttService.Transcript("한국",false,0)),
                    input.thenMany(Flux.just(new SonioxSttService.Transcript("한국어",true,0))));
        });
        var t=new SonioxAsrTransport(json,s,events::add,errors::add);
        t.send(json.writeValueAsString(Map.of("seq",0,"pcm",Base64.getEncoder().encodeToString(new byte[640]))));
        t.finish().get(1,java.util.concurrent.TimeUnit.SECONDS);
        var transcripts=events.stream().filter(e->e.path("type").asText().equals("transcript")).toList();
        assertEquals(2,transcripts.size());assertFalse(transcripts.get(0).path("final").asBoolean());assertTrue(transcripts.get(1).path("final").asBoolean());
        assertEquals(transcripts.get(0).path("utteranceId"),transcripts.get(1).path("utteranceId"));
        assertTrue(transcripts.get(1).path("revision").asLong()>transcripts.get(0).path("revision").asLong());
        assertTrue(errors.isEmpty());assertFalse(t.alive());int count=events.size();ready.get().run();assertEquals(count,events.size());t.close().join();
    }
}
