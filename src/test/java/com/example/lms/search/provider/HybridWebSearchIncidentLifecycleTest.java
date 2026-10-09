package com.example.lms.search.provider;

import com.example.lms.debug.ApiFailureRecorder;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HybridWebSearchIncidentLifecycleTest {
    @TempDir Path temporary;
    @AfterEach void clear() { TraceStore.clear(); }
    private static final String EXECUTION="hash:111111111111", ATTEMPT="hash:222222222222";
    private static final String SNIPPET="Synthetic evidence https://example.org/evidence";

    @Test void retainedLiveFallbackMasksOnlyItsCorrelatedFailureWithoutRecoveringIt() {
        var recorder=recorder();
        try {
            var provider=provider(recorder);
            failed(recorder);
            receipts(EXECUTION, true);
            merge(provider,List.of(SNIPPET),Map.of());
            var incident=recorder.snapshot().get(0);
            assertEquals("brave/web",incident.maskedBy());
            assertEquals(1,incident.consecutive());
            assertNull(incident.recoveredAt());
        } finally {recorder.close();}
    }

    @Test void cacheForeignReceiptAndDiscardedCandidatesCannotMaskTheFailure() {
        for(String scenario:List.of("cache","foreign","no-receipt","discarded","superseded")) {
            TraceStore.clear();
            var recorder=recorder();
            try {
                var provider=provider(recorder);
                failed(recorder);
                receipts("foreign".equals(scenario)?"hash:333333333333":EXECUTION,!"no-receipt".equals(scenario));
                if("superseded".equals(scenario))recorder.recordSearchTerminal("naver","unconfirmed","hash:444444444444",401,"AUTH_OR_CONFIG","{}",true,true);
                merge(provider,"discarded".equals(scenario)?List.of():List.of(SNIPPET),
                        "cache".equals(scenario)?Map.of("braveCacheOnly",true):Map.of());
                assertNull(recorder.snapshot().get(0).maskedBy(),scenario);
                assertNull(recorder.snapshot().get(0).recoveredAt(),scenario);
            } finally {recorder.close();}
        }
    }

    private void failed(ApiFailureRecorder recorder) {
        recorder.recordSearchTerminal("naver","unconfirmed",ATTEMPT,401,"AUTH_OR_CONFIG","{}",true,true);
        TraceStore.put("searchExecutionId",EXECUTION);
        TraceStore.append("web.naver.filter.runs",Map.of("searchExecutionId",EXECUTION,"providerAttemptId",ATTEMPT,
                "clientAttemptObserved",true,"providerReceiptObserved",true,"httpStatus",401,"failureClass","AUTH_OR_CONFIG"));
    }
    private void receipts(String execution,boolean receipt) {
        TraceStore.append("web.brave.attempt.runs",Map.of("searchExecutionId",execution,
                "clientAttemptObserved",true,"providerReceiptObserved",receipt,"httpStatus",200,"outcome","OK",
                "afterFilterCount",1,"finishedAtEpochMs",System.currentTimeMillis()));
    }
    private void merge(HybridWebSearchProvider provider,List<String> merged,Map<String,Object> extra) {
        ReflectionTestUtils.invokeMethod(provider,"emitMergeBoundaryEvent","test", "synthetic query",1,
                List.of(SNIPPET),List.of(),merged,extra,null);
    }
    private HybridWebSearchProvider provider(ApiFailureRecorder recorder) {
        var provider=new HybridWebSearchProvider(mock(NaverSearchService.class),mock(BraveSearchService.class));
        if(ReflectionUtils.findField(HybridWebSearchProvider.class,"apiFailureRecorder")!=null)
            ReflectionTestUtils.setField(provider,"apiFailureRecorder",recorder);
        return provider;
    }
    private ApiFailureRecorder recorder() {
        return new ApiFailureRecorder(mock(DebugEventStore.class),temporary.resolve(java.util.UUID.randomUUID()+".json").toString());
    }
}
