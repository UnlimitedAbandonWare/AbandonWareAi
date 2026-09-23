package com.example.lms.assist;

import com.example.lms.service.stt.DeepgramSttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class Fold6StreamBudgetTest {
    @TempDir Path dir;
    @Test void observationalStreamsExceedDollarCapButRetainSingleStreamCapacityAndSafeUsage() throws Exception {
        var json=new ObjectMapper();var service=mock(DeepgramSttService.class);
        when(service.isConfigured()).thenReturn(true);
        when(service.transcribePcm16Mono(any(),eq(16000),eq("ko"),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return Flux.never();});
        var budget=new ConversateSttBudget(json,true,dir.resolve("usage.jsonl").toString(),"verification","0.000001","0.000001",Clock.systemUTC());
        ReflectionTestUtils.setField(budget,"enforceLimits",false);
        var cloud=new ConversateCloudStt(service,budget,json,System::nanoTime,Duration.ofSeconds(1),Duration.ofSeconds(60));
        ReflectionTestUtils.setField(cloud,"streamSeconds",75L);
        var first=cloud.openStream(e->{},f->fail(f));
        try{assertThrows(java.io.IOException.class,()->cloud.openStream(e->{},f->{}));}
        finally{first.close().get(3,TimeUnit.SECONDS);}
        var next=cloud.openStream(e->{},f->fail(f));next.close().get(3,TimeUnit.SECONDS);
        assertEquals(2,budget.view().reserved().requests());assertFalse(budget.view().enforced());
        var bridge=new ConversateAsrBridge(null,json,null,cloud);
        try{var usage=bridge.displayUsage();assertEquals(false,usage.get("costLimitsEnforced"));assertEquals("USD",usage.get("currency"));
            assertTrue(((Number)usage.get("reservationEstimateUsd")).doubleValue()>0);
            assertFalse(usage.containsKey("accounts"));assertFalse(usage.toString().contains("usage.jsonl"));
        }finally{bridge.close();}
    }

    @Test void phoneDeadlineReservesBoundedStreamWithoutIncreasingSpendCap() throws Exception {
        var json = new ObjectMapper();
        var service = mock(DeepgramSttService.class);
        when(service.isConfigured()).thenReturn(true);
        when(service.transcribePcm16Mono(any(), eq(16000), eq("ko"), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(3)).run();
            return Flux.never();
        });
        var budget = new ConversateSttBudget(json, true, dir.resolve("budget.jsonl").toString(),
                "verification", "0.02", "5", Clock.systemUTC());
        var cloud = new ConversateCloudStt(service, budget, json, System::nanoTime,
                Duration.ofSeconds(1), Duration.ofSeconds(60));
        ReflectionTestUtils.setField(cloud, "streamSeconds", 75L);
        var stream = cloud.openStream(event -> {}, failure -> fail(failure));
        try {
            assertEquals(1, cloud.budgetView().reserved().requests());
            assertEquals(76, cloud.budgetView().reserved().reservedSeconds());
            assertEquals(20_000, cloud.budgetView().verificationCapMicros());
        } finally {
            stream.close().get(3, TimeUnit.SECONDS);
        }
        // Reservations remain conservative even after a short/cancelled stream.
        assertThrows(java.io.IOException.class, () -> cloud.openStream(event -> {}, failure -> {}));
        assertEquals(1, cloud.budgetView().reserved().requests());
        verify(service, times(1)).transcribePcm16Mono(any(), eq(16000), eq("ko"), any());
    }
}
