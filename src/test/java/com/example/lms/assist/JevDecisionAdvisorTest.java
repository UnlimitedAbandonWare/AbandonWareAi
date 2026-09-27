package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Jev 신호조절기 단위 검증 — 모든 모드에서 결정적 로컬 경로로 fail-open 되는지가 핵심 계약이다. */
class JevDecisionAdvisorTest {
    final MockEnvironment env=new MockEnvironment();
    final AtomicInteger calls=new AtomicInteger();

    JevDecisionAdvisor advisor(JevDecisionAdvisor.Transport transport,java.util.function.Function<String,String> secrets){
        return new JevDecisionAdvisor(env,Clock.systemUTC(),transport,secrets);
    }
    JevDecisionAdvisor configured(JevDecisionAdvisor.Transport transport){
        return advisor(transport,name->"AI_GATEWAY_API_KEY".equals(name)?"synthetic-fixture-key":null);
    }
    static JevDecisionAdvisor.EvalResponse verdict(JevDecisionAdvisor.Verdict v){
        return new JevDecisionAdvisor.EvalResponse(200,v,null,null);
    }
    static JevDecisionAdvisor.EvalResponse failure(int status,String reason,Long retryAfterMs){
        return new JevDecisionAdvisor.EvalResponse(status,null,reason,retryAfterMs);
    }

    @Test void offModeNeverTouchesTransportAndReportsDisabled(){
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            var advice=advisor.advise("cue","확정 질문","CUE");
            assertEquals("off",advice.mode());assertEquals("off",advice.decision());assertEquals("disabled",advice.reasonCode());
            assertFalse(advice.usable());assertEquals(0,calls.get(),"OFF mode must not make HTTP calls");
            assertEquals("off",advisor.status().get("mode"));assertEquals(false,advisor.status().get("callsAllowed"));
        }
    }
    @Test void onModeWithoutKeyDefersNotConfigured(){
        env.withProperty("demo.jev.mode","on");
        try(var advisor=advisor(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);},name->null)){
            var advice=advisor.advise("focus","확정 질문","RECENT_ONLY");
            assertEquals("defer",advice.decision());assertEquals("jev_not_configured",advice.reasonCode());
            assertFalse(advice.usable());assertEquals(0,calls.get());
            assertEquals("jev_not_configured",advisor.status().get("reason"));assertEquals(false,advisor.status().get("callsAllowed"));
        }
    }
    @Test void onModeReturnsExactlyOneVerdictPerQuestion(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            var advice=advisor.advise("focus","확정 질문","RECENT_ONLY");
            assertTrue(advice.usable());assertEquals(JevDecisionAdvisor.Verdict.WEB,advice.verdict());
            assertEquals("WEB",advice.decision());assertEquals("ok",advice.reasonCode());assertTrue(advice.latencyMs()>=0);
            assertEquals(1,calls.get(),"one confirmed question = at most one evaluate call");
            assertEquals("ready",advisor.status().get("reason"));assertEquals(true,advisor.status().get("callsAllowed"));
        }
    }
    @Test void decisionWaitTimeoutDefers(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.decision-wait-ms","40")
                .withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();
            try{Thread.sleep(3000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            var advice=advisor.advise("cue","확정 질문","CUE");
            assertFalse(advice.usable());assertEquals("defer",advice.decision());assertEquals("timeout",advice.reasonCode());
        }
    }
    @Test void malformedResponseDefers(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(200,"invalid_response",null);})){
            var advice=advisor.advise("cue","확정 질문","CUE");
            assertEquals("defer",advice.decision());assertEquals("invalid_response",advice.reasonCode());assertEquals(1,calls.get());
        }
    }
    @Test void transportThrowDefers(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();throw new IllegalStateException("synthetic_transport");})){
            var advice=advisor.advise("cue","확정 질문","CUE");
            assertEquals("defer",advice.decision());assertEquals("transport_error",advice.reasonCode());
        }
    }
    @Test void authBlockedLatchesWithoutSecondCall(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(401,"auth_blocked",null);})){
            assertEquals("auth_blocked",advisor.advise("cue","질문","CUE").reasonCode());assertEquals(1,calls.get());
            assertEquals("auth_blocked",advisor.advise("cue","다음 질문","CUE").reasonCode());
            assertEquals(1,calls.get(),"auth_blocked latch must not keep spending calls");
            assertEquals("auth_blocked",advisor.status().get("reason"));
        }
    }
    @Test void rateLimitedPausesFurtherCalls(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(429,"rate_limited",60000L);})){
            assertEquals("rate_limited",advisor.advise("cue","질문","CUE").reasonCode());assertEquals(1,calls.get());
            assertEquals("rate_limited",advisor.advise("cue","다음 질문","CUE").reasonCode());assertEquals(1,calls.get());
        }
    }
    @Test void closedFreeWindowSkipsOnBudget(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2000-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            var advice=advisor.advise("cue","질문","CUE");
            assertEquals("budget_skip",advice.reasonCode());assertEquals(0,calls.get());
            assertEquals("budget_skip",advisor.status().get("reason"));assertEquals(false,advisor.status().get("callsAllowed"));
        }
    }
    @Test void allowPaidAdmitsAfterFreeWindow(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2000-01-01T00:00:00Z")
                .withProperty("demo.jev.allow-paid","true");
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.CLARIFY);})){
            var advice=advisor.advise("cue","질문","CUE");
            assertTrue(advice.usable());assertEquals("CLARIFY",advice.decision());assertEquals(1,calls.get());
        }
    }
    @Test void shadowNeverBlocksCallerAndNeverApplies()throws Exception{
        env.withProperty("demo.jev.mode","shadow").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();entered.countDown();
            try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return verdict(JevDecisionAdvisor.Verdict.HYBRID);})){
            long began=System.nanoTime();
            var advice=advisor.advise("cue","확정 질문","CUE");
            assertTrue(System.nanoTime()-began<2_000_000_000L,"shadow must return without waiting for the verdict");
            assertEquals("shadow",advice.mode());assertEquals("defer",advice.decision());assertEquals("shadow",advice.reasonCode());
            assertFalse(advice.usable(),"SHADOW verdict is observational and never steers routing");
            assertTrue(entered.await(3,TimeUnit.SECONDS));release.countDown();
            assertEquals(1,calls.get());
        }
    }
    @Test void invalidModeFallsBackToOff(){
        env.withProperty("demo.jev.mode","ON_LOUDLY");
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            assertEquals("off",advisor.mode());assertEquals(0,calls.get());
        }
    }
}
