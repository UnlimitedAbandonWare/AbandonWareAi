package com.example.lms.assist;

import com.example.lms.service.stt.SonioxSttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateSonioxBindingTest {
    @TempDir Path dir;
    @Configuration(proxyBeanMethods=false)
    @Import({ConversateAsrBridge.class,ConversateCloudStt.class,ConversateSttBudget.class,SonioxSttService.class})
    static class Config{
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ConversateSessionService sessions(){return new ConversateSessionService();}
        @Bean com.example.lms.api.PublicChatAdmissionGuard admission(){return mock(com.example.lms.api.PublicChatAdmissionGuard.class);}
    }
    @Test void explicitSonioxNeedsBothOptInAndBudgetAndMakesNoStartupRequest(){
        var context=new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("conversate.enabled=true","conversate.asr.enabled=true","conversate.asr.provider=soniox","soniox.api-key"+"=synthetic","soniox.stt.enabled=true","conversate.asr.cloud.enabled=false");
        context.run(c->{assertNull(c.getStartupFailure());assertFalse(c.getBean(ConversateAsrBridge.class).available());});
        context.withPropertyValues("conversate.asr.cloud.enabled=true","conversate.asr.cloud.ledger="+dir.resolve("ledger.jsonl")).run(c->{
            assertNull(c.getStartupFailure());assertTrue(c.getBean(ConversateAsrBridge.class).available());assertEquals("soniox",c.getBean(ConversateCloudStt.class).diagnostics().get("provider"));assertFalse(java.nio.file.Files.exists(dir.resolve("ledger.jsonl")));
        });
        context.withPropertyValues("soniox.stt.enabled=false","conversate.asr.cloud.enabled=true","conversate.asr.cloud.ledger="+dir.resolve("ledger.jsonl")).run(c->assertFalse(c.getBean(ConversateAsrBridge.class).available()));
    }
    @Test void sonioxUsesTheExistingReservationBreakerAndFinalOnlyAssembly()throws Exception{
        var json=new ObjectMapper();var service=mock(SonioxSttService.class);when(service.isConfigured()).thenReturn(true);when(service.model()).thenReturn("stt-rt-v5");when(service.disabledReason()).thenReturn("");
        var b=new ConversateSttBudget(json,true,dir.resolve("ledger.jsonl").toString(),"verification","1","5",Clock.systemUTC());var nanos=new AtomicLong();
        var c=new ConversateCloudStt(null,service,"soniox",b,json,nanos::get,Duration.ofSeconds(1),Duration.ofSeconds(60));
        when(service.transcribePcm16Mono(any(),any())).thenReturn(Flux.error(new RuntimeException("soniox:quota_exceeded")),Flux.error(new RuntimeException("soniox:rate_limited")),Flux.just(new SonioxSttService.Transcript("부분",false,0),new SonioxSttService.Transcript("확정",true,0)));
        assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());assertEquals("quota_exceeded",c.diagnostics().get("reason"));
        assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());assertEquals("OPEN",c.diagnostics().get("state"));assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());verify(service,times(2)).transcribePcm16Mono(any(),any());
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());assertEquals("확정",c.transcribe(new byte[640]).block());assertEquals(3,b.view().reserved().requests());
    }
    @Test void exhaustedBudgetMakesZeroSonioxAttempts()throws Exception{
        var json=new ObjectMapper();var s=mock(SonioxSttService.class);when(s.isConfigured()).thenReturn(true);
        var b=new ConversateSttBudget(json,true,dir.resolve("deny.jsonl").toString(),"verification","0.0001","5",Clock.systemUTC());
        var c=new ConversateCloudStt(null,s,"soniox",b,json,System::nanoTime,Duration.ofSeconds(12),Duration.ofSeconds(60));
        assertThrows(Exception.class,()->c.openStream(e->{},r->{}));assertThrows(Exception.class,()->c.transcribe(new byte[640]).block());verify(s,never()).transcribePcm16Mono(any(),any());
    }
}
