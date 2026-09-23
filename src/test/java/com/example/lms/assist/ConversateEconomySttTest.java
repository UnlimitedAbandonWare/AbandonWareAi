package com.example.lms.assist;

import com.example.lms.agent.GroqFreeTierGuard;
import com.example.lms.learning.gemini.GeminiGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversateEconomySttTest {
    @TempDir Path dir;final ObjectMapper json=new ObjectMapper();final GeminiGateway gemini=mock(GeminiGateway.class);
    ConversateCloudStt cloud(String budgetCap){
        return cloud(budgetCap,"economy");
    }
    ConversateCloudStt cloud(String budgetCap,String mode){
        var env=new MockEnvironment();var budget=new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"verification",budgetCap,"5",Clock.systemUTC());
        var cloud=new ConversateCloudStt(null,null,mode,budget,json,System::nanoTime,Duration.ofSeconds(3),Duration.ofSeconds(60));
        ReflectionTestUtils.setField(cloud,"environment",env);ReflectionTestUtils.setField(cloud,"gemini",gemini);
        ReflectionTestUtils.setField(cloud,"groqGuard",new GroqFreeTierGuard(env));
        when(gemini.speechConfigured()).thenReturn(true);when(gemini.transcribeAudio(any())).thenReturn(Mono.just("서울역 3시 김민수"));return cloud;
    }
    byte[] speech(int seconds){var bytes=new byte[seconds*32000];for(int i=0;i<bytes.length;i+=2){short value=(short)(2000*Math.sin(i*.1));bytes[i]=(byte)value;bytes[i+1]=(byte)(value>>8);}return bytes;}
    @Test void localFallbackCanUseGeminiWithoutALegacySttCredential(){
        var cloud=cloud("1","local");ReflectionTestUtils.setField(cloud,"utteranceProvider","economy");
        assertTrue(cloud.configured());
        var streaming=cloud("1","soniox");ReflectionTestUtils.setField(streaming,"utteranceProvider","economy");
        assertFalse(streaming.configured(),"Explicit streaming never silently switches to a batch adapter");
    }
    @Test void digitalSilenceDoesNotReserveOrCallAnyProvider()throws Exception {
        var cloud=cloud("1");assertEquals("",cloud.transcribe(new byte[160000]).block());
        verify(gemini,never()).transcribeAudio(any());assertEquals(0,cloud.budgetView().reserved().requests());
    }
    @Test void existingFiveTenFifteenSecondSegmentsAreAcceptedAndNoModelLadderRuns()throws Exception {
        var cloud=cloud("1");for(int seconds:List.of(5,10,15))assertEquals("서울역 3시 김민수",cloud.transcribe(speech(seconds)).block());
        verify(gemini,times(3)).transcribeAudio(argThat(wav->wav.length>=160044&&wav.length<=480044));
        assertEquals(3,cloud.budgetView().reserved().requests());
        assertThrows(Exception.class,()->cloud.transcribe(speech(17)).block());
    }
    @Test void paidFallbackHonorsExistingBudgetAndNeverCallsAfterDenial(){
        var cloud=cloud("0.00001");assertThrows(Exception.class,()->cloud.transcribe(speech(5)).block());verify(gemini,never()).transcribeAudio(any());
    }
    @Test void failedGeminiSegmentIsNotAutomaticallyRetried(){
        var cloud=cloud("1");when(gemini.transcribeAudio(any())).thenReturn(Mono.error(new java.io.IOException("gemini_speech_http_403")));
        assertThrows(Exception.class,()->cloud.transcribe(speech(5)).block());verify(gemini,times(1)).transcribeAudio(any());
    }
    @Test void normalizedPcmWavHasNoCompressionOrSampleRateChange(){
        var cloud=cloud("1");var valid=new java.util.concurrent.atomic.AtomicBoolean();
        when(gemini.transcribeAudio(any())).thenAnswer(call->{byte[] wav=call.getArgument(0);var b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            valid.set(b.getInt(24)==16000&&b.getShort(22)==1&&b.getShort(34)==16&&b.getInt(40)==160000);return Mono.just("서울역");});
        cloud.transcribe(speech(5)).block();assertTrue(valid.get());
    }
    @Test void verifiedGroqUsesOneWireAndNoPaidReservationOrParallelGemini()throws Exception {
        var cloud=cloud("1");var guard=mock(GroqFreeTierGuard.class);var reservation=new GroqFreeTierGuard.Reservation("whisper-large-v3-turbo",0,1,"a".repeat(64));
        when(guard.speechVerified(anyString())).thenReturn(true);when(guard.reserve(anyString(),anyString(),eq(0L),eq(5L))).thenReturn(reservation);
        ReflectionTestUtils.setField(cloud,"groqGuard",guard);
        var calls=new java.util.concurrent.atomic.AtomicInteger();ReflectionTestUtils.setField(cloud,"webClientBuilder",org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->{
            assertEquals("https://api.groq.com/openai/v1/audio/transcriptions",request.url().toString());calls.incrementAndGet();
            return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header("Content-Type","application/json").header("x-ratelimit-remaining-requests","19").body("{\"text\":\"서울역\"}").build());}));
        assertEquals("서울역",cloud.transcribe(speech(5)).block());assertEquals(1,calls.get());verify(gemini,never()).transcribeAudio(any());
        assertEquals(0,cloud.budgetView().reserved().requests());verify(guard).observe(eq(reservation),eq(200),anyMap());
    }
    @Test void deniedGroqAdmissionSelectsGeminiButFailedGroqWireNeverFansOut()throws Exception {
        var cloud=cloud("1");var guard=mock(GroqFreeTierGuard.class);when(guard.speechVerified(anyString())).thenReturn(true);
        when(guard.reserve(anyString(),anyString(),anyLong(),anyLong())).thenThrow(new java.io.IOException("groq_retry_after"));
        ReflectionTestUtils.setField(cloud,"groqGuard",guard);var calls=new java.util.concurrent.atomic.AtomicInteger();
        ReflectionTestUtils.setField(cloud,"webClientBuilder",org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->{calls.incrementAndGet();return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.FORBIDDEN).build());}));
        assertEquals("서울역 3시 김민수",cloud.transcribe(speech(5)).block());assertEquals(0,calls.get());verify(gemini,times(1)).transcribeAudio(any());
        reset(guard);when(guard.speechVerified(anyString())).thenReturn(true);
        when(guard.reserve(anyString(),anyString(),anyLong(),anyLong())).thenReturn(new GroqFreeTierGuard.Reservation("whisper-large-v3-turbo",0,1,"a".repeat(64)));
        assertThrows(Exception.class,()->cloud.transcribe(speech(5)).block());assertEquals(1,calls.get());verify(gemini,times(1)).transcribeAudio(any());
        verify(guard).observe(any(),eq(403),anyMap());
    }
}
