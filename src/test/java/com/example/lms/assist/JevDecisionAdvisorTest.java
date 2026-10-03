package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    void shortOnWait(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.allow-paid","true")
                .withProperty("demo.jev.decision-wait-ms","30");
    }
    static void releaseAfter(CountDownLatch latch){
        try{if(!latch.await(2,TimeUnit.SECONDS))throw new IllegalStateException("synthetic latch timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
    static void awaitReason(JevDecisionAdvisor advisor,String reason)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<deadline&&!reason.equals(advisor.status().get("reason")))Thread.sleep(5);
        assertEquals(reason,advisor.status().get("reason"));
    }
    static final class MutableClock extends Clock {
        final AtomicLong now=new AtomicLong(100_000);
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(now.get());}
        void advance(long millis){now.addAndGet(millis);}
    }

    @Test void metaDisplayProfileUsesGatewayZdrKeyAndKeepsCostDefaults()throws Exception{
        var sources=new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "meta-display",new org.springframework.core.io.ClassPathResource("application-meta-display.yml"));
        var profile=new MockEnvironment();
        for(var source:sources)profile.getPropertySources().addLast(source);
        assertEquals(false,profile.getProperty("jev.gateway.zero-data-retention",Boolean.class));
        assertEquals("off",profile.getProperty("demo.jev.mode"));
        assertEquals(true,profile.getProperty("demo.jev.free-only",Boolean.class));
        assertEquals(false,profile.getProperty("demo.jev.allow-paid",Boolean.class));
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
    @Test void key401StillLatchesAuthBlocked(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(401,"auth_invalid",null);})){
            assertEquals("auth_invalid",advisor.advise("cue","질문","CUE").reasonCode());assertEquals(1,calls.get());
            assertEquals("auth_blocked",advisor.advise("cue","다음 질문","CUE").reasonCode());
            assertEquals(1,calls.get(),"auth_blocked latch must not keep spending calls");
            assertEquals("auth_blocked",advisor.status().get("reason"));
        }
    }
    @Test void planGate403IsReportedAsPlanGateNotAuthBlocked(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(403,"plan_gate",null);})){
            assertEquals("plan_gate",advisor.advise("cue","질문","CUE").reasonCode());
            assertEquals("plan_gate",advisor.status().get("reason"));
            assertEquals(false,advisor.status().get("callsAllowed"));
            assertEquals("plan_gate",advisor.advise("cue","다음 질문","CUE").reasonCode());
            assertEquals(1,calls.get(),"plan gate must prevent repeated evaluate calls");
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
    @Test void shadowLogCarriesBaselineAndStatusWithoutRawText()throws Exception{
        env.withProperty("demo.jev.mode","shadow").withProperty("demo.jev.allow-paid","true");
        var logged=new CountDownLatch(2);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(){
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event){
                super.append(event);logged.countDown();
            }
        };
        appender.list=new java.util.concurrent.CopyOnWriteArrayList<>();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(JevDecisionAdvisor.class);
        var level=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.INFO);appender.start();logger.addAppender(appender);
        try(var advisor=configured(req->verdict(JevDecisionAdvisor.Verdict.HYBRID))){
            assertEquals("shadow",advisor.advise("cue","확정 질문","CUE").reasonCode());
            assertEquals("shadow",advisor.advise("cue","확정 질문","확정 질문\nsecret").reasonCode());
            assertTrue(logged.await(2,TimeUnit.SECONDS));
            var messages=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(s->s.contains("baseline=CUE")));
            assertTrue(messages.stream().anyMatch(s->s.contains("baseline=other")));
            assertTrue(messages.stream().allMatch(s->s.contains("decision=HYBRID")&&s.contains("httpStatus=200")));
            assertTrue(messages.stream().noneMatch(s->s.contains("확정 질문")||s.contains("secret")));
        }finally{logger.detachAppender(appender);appender.stop();logger.setLevel(level);}
    }
    @Test void adviseAfterCloseDefersAndReturnsPermit()throws Exception{
        for(String mode:java.util.List.of("on","shadow")){
            env.withProperty("demo.jev.mode",mode).withProperty("demo.jev.allow-paid","true")
                    .withProperty("demo.jev.max-in-flight","1");
            try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
                advisor.close();
                for(int i=0;i<3;i++){
                    var advice=assertDoesNotThrow(()->advisor.advise("cue","confirmed question","CUE"));
                    assertFalse(advice.usable());assertEquals("busy",advice.reasonCode());
                    assertEquals(1,permits(advisor).availablePermits(),"rejected submission must return its permit");
                }
            }
        }
        assertEquals(0,calls.get());
    }
    @Test void unknownUppercaseBaselineLoggedAsOther()throws Exception{
        var messages=shadowMessages("KIM_MIN_SU");
        assertTrue(messages.stream().allMatch(s->s.contains("baseline=other")));
        assertTrue(messages.stream().noneMatch(s->s.contains("KIM_MIN_SU")));
    }
    @Test void emptyBaselineLoggedAsNone()throws Exception{
        var messages=shadowMessages(null,"");
        assertTrue(messages.stream().allMatch(s->s.contains("baseline=none")));
    }
    java.util.List<String> shadowMessages(String...baselines)throws Exception{
        env.withProperty("demo.jev.mode","shadow").withProperty("demo.jev.allow-paid","true");
        var logged=new CountDownLatch(baselines.length);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(){
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event){
                super.append(event);logged.countDown();
            }
        };
        appender.list=new java.util.concurrent.CopyOnWriteArrayList<>();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(JevDecisionAdvisor.class);
        var level=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.INFO);appender.start();logger.addAppender(appender);
        try(var advisor=configured(req->verdict(JevDecisionAdvisor.Verdict.HYBRID))){
            for(String baseline:baselines)assertEquals("shadow",advisor.advise("cue","synthetic question",baseline).reasonCode());
            assertTrue(logged.await(2,TimeUnit.SECONDS));
            var messages=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(baselines.length,messages.size());return messages;
        }finally{logger.detachAppender(appender);appender.stop();logger.setLevel(level);}
    }
    static java.util.concurrent.Semaphore permits(JevDecisionAdvisor advisor)throws Exception{
        var field=JevDecisionAdvisor.class.getDeclaredField("inFlight");field.setAccessible(true);
        return (java.util.concurrent.Semaphore)field.get(advisor);
    }
    void dailyCap(long cap){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.allow-paid","true")
                .withProperty("demo.jev.decision-wait-ms","1000")
                .withProperty("demo.jev.budget.enabled","true")
                .withProperty("demo.jev.budget.daily-max-calls",Long.toString(cap));
    }
    @Test void dailyCapDisabledByDefaultPreservesBaseline(){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.allow-paid","true");
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            for(int i=0;i<3;i++)assertTrue(advisor.advise("cue","confirmed question","CUE").usable());
            assertEquals(3,calls.get());assertEquals(true,advisor.status().get("callsAllowed"));
        }
    }
    @Test void zeroDailyCapBlocksWhenEnabled()throws Exception{
        dailyCap(0);
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            assertEquals("budget_skip",advisor.advise("cue","confirmed question","CUE").reasonCode());
            assertEquals(0,calls.get());assertEquals("budget_skip",advisor.status().get("reason"));
            assertEquals(false,advisor.status().get("callsAllowed"));assertEquals(2,permits(advisor).availablePermits());
        }
    }
    @Test void dailyCapCountsDispatchAttemptsNotSuccesses(){
        dailyCap(2);
        try(var advisor=configured(req->{calls.incrementAndGet();return failure(200,"invalid_response",null);})){
            assertEquals("invalid_response",advisor.advise("cue","first","CUE").reasonCode());
            assertEquals("invalid_response",advisor.advise("cue","second","CUE").reasonCode());
            assertEquals("budget_skip",advisor.advise("cue","third","CUE").reasonCode());assertEquals(2,calls.get());
            assertEquals(false,advisor.status().get("callsAllowed"));
        }
    }
    @Test void dailyCapResetsOnNextUtcDay(){
        dailyCap(1);var clock=new MutableClock();
        clock.now.set(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli());
        try(var advisor=new JevDecisionAdvisor(env,clock,req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);},
                name->"synthetic-fixture-key")){
            assertTrue(advisor.advise("cue","first","CUE").usable());
            assertEquals("budget_skip",advisor.advise("cue","same day","CUE").reasonCode());
            clock.advance(1000);
            assertEquals(true,advisor.status().get("callsAllowed"));
            assertTrue(advisor.advise("cue","next UTC day","CUE").usable());assertEquals(2,calls.get());
        }
    }
    @Test void clockBackwardsDoesNotResetDailyCap(){
        dailyCap(2);var clock=new MutableClock();
        clock.now.set(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        try(var advisor=new JevDecisionAdvisor(env,clock,req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);},
                name->"synthetic-fixture-key")){
            for(int i=0;i<2;i++)assertEquals("ok",advisor.advise("cue","confirmed","CUE").reasonCode());
            assertEquals("budget_skip",advisor.advise("cue","third","CUE").reasonCode());
            clock.now.set(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli());
            var status=advisor.status();var advice=advisor.advise("cue","clock moved backwards","CUE");
            assertAll(
                    ()->assertEquals("budget_skip",advice.reasonCode()),
                    ()->assertEquals("budget_skip",status.get("reason")),
                    ()->assertEquals(false,status.get("callsAllowed")),
                    ()->assertEquals(2,calls.get()));
        }
    }
    @Test void cap500Blocks501stDispatchMemoryOnly(){
        dailyCap(500);
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            for(int i=0;i<500;i++)assertEquals("ok",advisor.advise("cue","confirmed","CUE").reasonCode());
            assertEquals("budget_skip",advisor.advise("cue","501st","CUE").reasonCode());
            assertEquals(500,calls.get());
        }
    }
    @Test void statusDoesNotReserve(){
        dailyCap(1);
        try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            for(int i=0;i<100;i++)assertEquals(true,advisor.status().get("callsAllowed"));
            assertEquals(0,calls.get());
            assertEquals("ok",advisor.advise("cue","first","CUE").reasonCode());assertEquals(1,calls.get());
        }
    }
    @Test void yesterdayRefundDoesNotFreeTodaySlot(){
        dailyCap(1);var clock=new MutableClock();
        clock.now.set(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli());
        try(var advisor=new JevDecisionAdvisor(env,clock,req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);},
                name->"synthetic-fixture-key")){
            Object yesterday=org.springframework.test.util.ReflectionTestUtils.invokeMethod(advisor,"reserveDailyCall");
            assertNotNull(yesterday);clock.advance(1000);
            assertEquals("ok",advisor.advise("cue","today B","CUE").reasonCode());
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(advisor,"refundDailyCall",yesterday);
            assertEquals("budget_skip",advisor.advise("cue","today C","CUE").reasonCode());assertEquals(1,calls.get());
        }
    }
    @Test void refundNeverGoesNegative(){
        dailyCap(1);
        try(var advisor=configured(req->verdict(JevDecisionAdvisor.Verdict.WEB))){
            Object reservation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(advisor,"reserveDailyCall");
            assertNotNull(reservation);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(advisor,"refundDailyCall",reservation);
            var counter=(java.util.concurrent.atomic.AtomicReference<?>)org.springframework.test.util.ReflectionTestUtils.getField(advisor,"dailyCalls");
            assertNotNull(counter);
            assertEquals(0L,org.springframework.test.util.ReflectionTestUtils.<Long>invokeMethod(counter.get(),"count"));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(advisor,"refundDailyCall",reservation);
            assertEquals(0L,org.springframework.test.util.ReflectionTestUtils.<Long>invokeMethod(counter.get(),"count"));
        }
    }
    @Test void rejectedSubmissionRefundsReservation()throws Exception{
        for(String mode:java.util.List.of("on","shadow")){
            dailyCap(1);env.withProperty("demo.jev.mode",mode);
            try(var advisor=configured(req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);})){
                advisor.close();
                for(int i=0;i<3;i++){
                    assertEquals("busy",advisor.advise("cue","rejected","CUE").reasonCode());
                    assertEquals("ready",advisor.status().get("reason"),"unsent reservation must be refunded");
                    assertEquals(2,permits(advisor).availablePermits());
                }
            }
        }
        assertEquals(0,calls.get());
    }
    @Test void dailyCapNeverExceededUnderConcurrency()throws Exception{
        dailyCap(1);env.withProperty("demo.jev.max-in-flight","2");
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();releaseAfter(release);return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            java.util.function.Supplier<JevDecisionAdvisor.Advice> call=()->{
                ready.countDown();releaseAfter(start);return advisor.advise("cue","parallel","CUE");
            };
            var first=CompletableFuture.supplyAsync(call);var second=CompletableFuture.supplyAsync(call);
            assertTrue(ready.await(2,TimeUnit.SECONDS));start.countDown();
            var refused=(JevDecisionAdvisor.Advice)CompletableFuture.anyOf(first,second).get(2,TimeUnit.SECONDS);
            assertEquals("budget_skip",refused.reasonCode());release.countDown();
            var a=first.get(2,TimeUnit.SECONDS);var b=second.get(2,TimeUnit.SECONDS);
            assertEquals(1,(a.usable()?1:0)+(b.usable()?1:0));assertEquals(1,calls.get());
        }finally{start.countDown();release.countDown();}
    }
    @Test void timedOutDispatchKeepsDailyReservation()throws Exception{
        dailyCap(1);env.withProperty("demo.jev.decision-wait-ms","30");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();entered.countDown();releaseAfter(release);
            return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            assertEquals("timeout",advisor.advise("cue","slow","CUE").reasonCode());
            assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();
            assertEquals("budget_skip",advisor.status().get("reason"));
            assertEquals("budget_skip",advisor.advise("cue","second","CUE").reasonCode());assertEquals(1,calls.get());
        }finally{release.countDown();}
    }
    @Test void concurrentUtcRolloverCannotOverwriteNewDayReservation()throws Exception{
        dailyCap(1);env.withProperty("demo.jev.max-in-flight","2");
        var base=new MutableClock();base.now.set(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli());
        var sampledOldDay=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var blockedCaller=new java.util.concurrent.atomic.AtomicReference<Thread>();var reads=new AtomicInteger();
        Clock clock=new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return Instant.ofEpochMilli(millis());}
            @Override public long millis(){
                long captured=base.millis();
                if(Thread.currentThread()==blockedCaller.get()&&reads.incrementAndGet()==2){
                    sampledOldDay.countDown();releaseAfter(resume);
                }
                return captured;
            }
        };
        try(var advisor=new JevDecisionAdvisor(env,clock,req->{calls.incrementAndGet();return verdict(JevDecisionAdvisor.Verdict.WEB);},
                name->"synthetic-fixture-key")){
            var oldDayCaller=CompletableFuture.supplyAsync(()->{
                blockedCaller.set(Thread.currentThread());return advisor.advise("cue","old day","CUE");
            });
            assertTrue(sampledOldDay.await(2,TimeUnit.SECONDS));base.advance(1000);
            assertTrue(advisor.advise("cue","new day","CUE").usable());
            resume.countDown();
            assertEquals("budget_skip",oldDayCaller.get(2,TimeUnit.SECONDS).reasonCode(),
                    "stale day sample must not overwrite the new-day reservation");
            assertEquals("budget_skip",advisor.advise("cue","new day again","CUE").reasonCode());
            assertEquals(1,calls.get());
        }finally{resume.countDown();}
    }
    private void lateAuthLatches(int httpStatus)throws Exception{
        shortOnWait();
        var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();releaseAfter(release);
            return failure(httpStatus,httpStatus==401?"auth_invalid":"permission_denied",null);})){
            assertEquals("timeout",advisor.advise("cue","first","CUE").reasonCode());
            release.countDown();
            awaitReason(advisor,"auth_blocked");
            assertEquals("auth_blocked",advisor.advise("cue","second","CUE").reasonCode());
            assertEquals(1,calls.get(),"late auth rejection must prevent a second evaluate");
        }finally{release.countDown();}
    }
    @Test void late401AfterTimeoutLatchesWithoutSecondCall()throws Exception{lateAuthLatches(401);}
    @Test void late403AfterTimeoutAlsoLatches()throws Exception{lateAuthLatches(403);}
    @Test void latePlanGateAfterTimeoutLatchesWithoutSecondCall()throws Exception{
        shortOnWait();
        var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();releaseAfter(release);
            return failure(403,"plan_gate",null);})){
            assertEquals("timeout",advisor.advise("cue","first","CUE").reasonCode());
            release.countDown();
            awaitReason(advisor,"plan_gate");
            assertEquals("plan_gate",advisor.advise("cue","second","CUE").reasonCode());
            assertEquals(1,calls.get(),"late plan gate must prevent a second evaluate");
        }finally{release.countDown();}
    }
    @Test void lateRateLimitedAfterTimeoutPausesFurtherCalls()throws Exception{
        shortOnWait();
        var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();releaseAfter(release);
            return failure(429,"rate_limited",60_000L);})){
            assertEquals("timeout",advisor.advise("cue","first","CUE").reasonCode());
            release.countDown();
            awaitReason(advisor,"rate_limited");
            assertEquals("rate_limited",advisor.advise("cue","second","CUE").reasonCode());
            assertEquals(1,calls.get());
        }finally{release.countDown();}
    }
    @Test void lateSuccessAfterTimeoutIsNotApplied()throws Exception{
        shortOnWait();
        var release=new CountDownLatch(1);
        try(var advisor=configured(req->{calls.incrementAndGet();releaseAfter(release);
            return verdict(JevDecisionAdvisor.Verdict.WEB);})){
            var first=advisor.advise("cue","first","CUE");
            assertEquals("timeout",first.reasonCode());assertFalse(first.usable());
            release.countDown();
            JevDecisionAdvisor.Advice second=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline){
                second=advisor.advise("cue","second","CUE");
                if(!"busy".equals(second.reasonCode()))break;
                Thread.sleep(5);
            }
            assertNotNull(second);assertTrue(second.usable());
            assertEquals("timeout",first.reasonCode(),"late success cannot rewrite the first advice");
            assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test void rateLimitUntilIsMaxMergedNotShortened()throws Exception{
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.allow-paid","true")
                .withProperty("demo.jev.max-in-flight","2").withProperty("demo.jev.decision-wait-ms","1000");
        var clock=new MutableClock();
        var entered=new CountDownLatch(2);var releaseLong=new CountDownLatch(1);var releaseShort=new CountDownLatch(1);
        try(var advisor=new JevDecisionAdvisor(env,clock,req->{
            int index=calls.incrementAndGet();entered.countDown();
            releaseAfter(index==1?releaseLong:releaseShort);
            return failure(429,"rate_limited",index==1?60_000L:1_000L);
        },name->"synthetic-fixture-key")){
            var first=CompletableFuture.supplyAsync(()->advisor.advise("cue","first","CUE"));
            var second=CompletableFuture.supplyAsync(()->advisor.advise("cue","second","CUE"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            releaseLong.countDown();awaitReason(advisor,"rate_limited");
            releaseShort.countDown();CompletableFuture.allOf(first,second).get(2,TimeUnit.SECONDS);
            clock.advance(2_000);
            assertEquals("rate_limited",advisor.status().get("reason"),"shorter late retry must not shrink the block");
            assertEquals(2,calls.get());
        }finally{releaseLong.countDown();releaseShort.countDown();}
    }
    @Test void permitReleasedAfterLateCompletion()throws Exception{
        shortOnWait();env.withProperty("demo.jev.max-in-flight","1");
        var release=new CountDownLatch(1);
        try(var advisor=configured(req->{
            int index=calls.incrementAndGet();
            if(index==1){releaseAfter(release);return failure(200,"invalid_response",null);}
            return verdict(JevDecisionAdvisor.Verdict.WEB);
        })){
            assertEquals("timeout",advisor.advise("cue","first","CUE").reasonCode());
            assertEquals("busy",advisor.advise("cue","while busy","CUE").reasonCode());
            release.countDown();
            JevDecisionAdvisor.Advice next=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline){
                next=advisor.advise("cue","after completion","CUE");
                if(!"busy".equals(next.reasonCode()))break;
                Thread.sleep(5);
            }
            assertNotNull(next);assertTrue(next.usable());assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
}
