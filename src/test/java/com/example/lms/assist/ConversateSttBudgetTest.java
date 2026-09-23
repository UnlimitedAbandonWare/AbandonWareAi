package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversateSttBudgetTest {
    @TempDir Path dir;final ObjectMapper json=new ObjectMapper();
    @Test void observationalModeIgnoresBothDollarCapsButRetainsPersistentUsage()throws Exception{
        var b=new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"verification","0.000001","0.000001",Clock.systemUTC());
        org.springframework.test.util.ReflectionTestUtils.setField(b,"enforceLimits",false);
        b.reserve(76);b.reserve(76);
        assertEquals(2,b.view().reserved().requests());assertEquals(152,b.view().reserved().reservedSeconds());
        assertEquals("observational",b.view().mode());assertTrue(b.view().reserved().totalMicros()>0);
        assertThrows(IOException.class,()->b.reserve(602));
    }
    @Test void observationalLedgerFailureCannotBlockAudioOrRewriteDamagedHistory()throws Exception{
        var file=dir.resolve("usage.jsonl");Files.writeString(file,"torn");
        var b=new ConversateSttBudget(json,true,file.toString(),"verification","invalid","invalid",Clock.systemUTC());
        org.springframework.test.util.ReflectionTestUtils.setField(b,"enforceLimits",false);
        assertTrue(b.configured());b.reserve(76);
        assertEquals(1,b.view().reserved().requests());assertEquals("torn",Files.readString(file));
    }
    ConversateSttBudget budget(String mode,String cap,Instant time){return new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),mode,cap,"5",Clock.fixed(time,ZoneOffset.UTC));}
    @Test void persistentReservationPrecedesUseAndNeverResetsOnRestart()throws Exception{
        Instant time=Instant.parse("2026-09-14T00:00:00Z");var b=budget("verification","0.16",time);
        assertEquals(80_000,b.reserve(600).verificationMicros());assertEquals(160_000,b.reserve(600).verificationMicros());
        var restarted=budget("verification","0.16",time);assertThrows(IOException.class,()->restarted.reserve(1));
        assertEquals(2,restarted.view().reserved().requests());assertEquals("not_assumed",restarted.view().freeCredit());
        String rows=Files.readString(dir.resolve("usage.jsonl"));assertFalse(rows.contains("transcript"));assertFalse(rows.contains("pcm"));
    }
    @Test void verificationTotalSurvivesMonthRolloverAndCrossingExposesBothMonths()throws Exception{
        var before=budget("verification","0.008",Instant.parse("2026-09-30T23:59:55Z"));
        var usage=before.reserve(60);assertEquals(8_000,usage.monthlyMicros());assertEquals(8_000,usage.nextMonthMicros());
        var after=budget("verification","0.008",Instant.parse("2026-10-01T00:00:01Z"));
        assertEquals(8_000,after.view().reserved().monthlyMicros());assertThrows(IOException.class,()->after.reserve(1));
        var operations=budget("operation","0.008",Instant.parse("2026-10-01T00:00:01Z"));
        assertEquals(8_000,operations.reserve(1).verificationMicros());
        assertThrows(IOException.class,()->before.reserve(1)); // clock reversal is closed, not a new allowance
    }
    @Test void monthlyCapAlsoAppliesToOperationsAndKnownCrossMonthExposure()throws Exception{
        var first=new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"operation","1","0.008",Clock.fixed(Instant.parse("2026-09-30T23:59:55Z"),ZoneOffset.UTC));
        first.reserve(60);
        var next=new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"operation","1","0.008",Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC));
        assertThrows(IOException.class,()->next.reserve(1));
    }
    @Test void concurrentProcessesCannotBothSpendFinalAllowance()throws Exception{
        Instant now=Instant.parse("2026-09-14T00:00:00Z");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Boolean> call=()->{start.await();try{budget("verification","0.008",now).reserve(60);return true;}catch(IOException denied){return false;}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(1,(a.get()?1:0)+(b.get()?1:0));
            assertEquals(8_000,budget("verification","0.008",now).view().reserved().verificationMicros());
        }finally{pool.shutdownNow();}
    }
    @Test void tornOrTamperedLedgerFailsClosedAndNeverRewritesIt()throws Exception{
        var b=budget("verification","1",Instant.parse("2026-09-14T00:00:00Z"));b.reserve(1);var file=dir.resolve("usage.jsonl");
        Files.writeString(file,"partial",StandardOpenOption.APPEND);byte[] torn=Files.readAllBytes(file);
        assertThrows(IOException.class,()->b.reserve(1));assertArrayEquals(torn,Files.readAllBytes(file));
        Files.writeString(file,"{\"schema\":1,\"usage\":{},\"sha256\":\"invalid\"}\n");
        assertThrows(IOException.class,()->b.reserve(1));
    }
    @Test void invalidConfigurationNeverBreaksLocalApplicationConstruction(){
        for(String mode:new String[]{"invalid","verification"}){
            var b=new ConversateSttBudget(json,true,"relative.jsonl",mode,"1","5",Clock.systemUTC());
            assertFalse(b.configured());assertThrows(IOException.class,()->b.reserve(1));
        }
        assertFalse(new ConversateSttBudget(json,true,dir.resolve("x").toString(),"operation","1","6",Clock.systemUTC()).configured());
    }
}
