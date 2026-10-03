package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JevSurfacePolicyTest {
    @Test void globalOffDominatesSurfaceOn() {
        var env=new MockEnvironment().withProperty("demo.jev.surface.focus.mode","on");
        assertEquals("off",new JevSurfacePolicy(env).resolve("focus").mode());
    }
    @Test void focusOverrideDoesNotChangeCue() {
        var env=new MockEnvironment().withProperty("demo.jev.mode","shadow")
                .withProperty("demo.jev.surface.focus.mode","on")
                .withProperty("demo.jev.surface.focus.decision-wait-ms","900");
        var policy=new JevSurfacePolicy(env);
        assertEquals("on",policy.resolve("focus").mode());
        assertEquals(900,policy.resolve("focus").decisionWaitMs());
        assertEquals("shadow",policy.resolve("cue").mode());
        assertEquals(150,policy.resolve("cue").decisionWaitMs());
    }
    @Test void missingOverridesPreserveAllDefaults() {
        var policy=new JevSurfacePolicy(new MockEnvironment());
        for(String surface: new String[]{"focus","cue","main","untrusted.path"}) {
            assertEquals(new JevSurfacePolicy.EffectivePolicy("off",150,800),policy.resolve(surface));
        }
    }
    @Test void malformedSurfaceModeDoesNotEnableCalls() {
        var env=new MockEnvironment().withProperty("demo.jev.mode","on")
                .withProperty("demo.jev.surface.focus.mode","garbage")
                .withProperty("demo.jev.surface.focus.decision-wait-ms","NaN");
        assertEquals("off",new JevSurfacePolicy(env).resolve("focus").mode());
        assertEquals(150,new JevSurfacePolicy(env).resolve("focus").decisionWaitMs());
    }
    @Test void shadowReturnsBaselineWithoutJoin() throws Exception {
        var env=new MockEnvironment().withProperty("demo.jev.mode","on")
                .withProperty("demo.jev.surface.focus.mode","shadow")
                .withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z")
                .withProperty("demo.jev.decision-wait-ms","0");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var advisor=new JevDecisionAdvisor(env,Clock.systemUTC(),req->{
            entered.countDown();
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.WEB,null,null);
        },name->"synthetic")) {
            try {
                var advice=advisor.advise("focus","synthetic question","RECENT_ONLY");
                assertEquals("shadow",advice.reasonCode());
                assertFalse(advice.usable());
                assertTrue(entered.await(2,TimeUnit.SECONDS));
            } finally {release.countDown();}
        }
    }
}
