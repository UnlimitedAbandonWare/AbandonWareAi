package com.example.lms.assist;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Red-team counterexample suite for the Jev v2 corrections (WP-1 model identity, WP-2 credential
 * and permit handling, WP-3 daily budget, WP-4 shadow log). It exercises only seams that already
 * exist on {@link JevDecisionAdvisor} / {@link JevGatewayClient}; it never adds product code and
 * never issues a real provider call (loopback fixtures and in-memory transports only).
 *
 * <p>Case ids map to {@code data/agent-handoff/clean-jev-v2-redteam-20260930/GATE.md}. A case that
 * documents an unfixed defect carries {@code @Disabled} with the defect id, so the shared sourceSet
 * stays green while the counterexample stays executable at the gate.</p>
 */
class JevRedTeamCounterexampleTest {
    private static final String KEY="AI_GATEWAY_API_KEY";
    private static final String FIXTURE_KEY="synthetic-fixture-secret";
    private static final String CONTRACT_200="{\"model\":\"typesafe-ai/jev\","
            + "\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}";
    private static final Instant OCT_ONE=Instant.parse("2026-10-01T00:00:00Z");
    private static final long DAY_MS=86_400_000L;

    record Hit(String method,String authorization,String body){}
    record Stub(int status,String body,String location){}

    /** Loopback gateway fixture; every hit is recorded so "no network" claims stay provable. */
    private HttpServer gateway(Stub stub,List<Hit> hits)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/evaluate",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            hits.add(new Hit(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),body));
            byte[] out=stub.body()==null?new byte[0]:stub.body().getBytes(StandardCharsets.UTF_8);
            if(stub.location()!=null)exchange.getResponseHeaders().add("Location",stub.location());
            exchange.sendResponseHeaders(stub.status(),out.length==0?-1:out.length);
            if(out.length>0){try(OutputStream os=exchange.getResponseBody()){os.write(out);}}
        });
        server.start();
        return server;
    }
    private static String endpoint(HttpServer server){
        return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/evaluate";
    }
    private static JevDecisionAdvisor.EvalRequest request(String endpoint){
        return new JevDecisionAdvisor.EvalRequest(endpoint,"typesafe-ai/jev",KEY,
                "does this question need retrieval?","cue","CUE",500,1500,8192,65536);
    }
    private static JevGatewayClient gatewayClient(Function<String,String> secrets,
            IntFunction<HttpClient> factory){
        return new JevGatewayClient(false,secrets,factory);
    }
    /** Stub transport with a call counter; the body decides the answer deterministically. */
    static final class StubTransport implements JevDecisionAdvisor.Transport {
        final AtomicInteger calls=new AtomicInteger();
        private final Function<JevDecisionAdvisor.EvalRequest,JevDecisionAdvisor.EvalResponse> body;
        StubTransport(Function<JevDecisionAdvisor.EvalRequest,JevDecisionAdvisor.EvalResponse> body){
            this.body=body;
        }
        @Override public JevDecisionAdvisor.EvalResponse evaluate(JevDecisionAdvisor.EvalRequest req){
            calls.incrementAndGet();
            return body.apply(req);
        }
    }
    static JevDecisionAdvisor.EvalResponse ok(JevDecisionAdvisor.Verdict verdict){
        return new JevDecisionAdvisor.EvalResponse(200,verdict,null,null);
    }

    /** Fixed UTC instant clock that can also step backwards, unlike the forward-only repo helper. */
    static final class StepClock extends Clock {
        final AtomicLong now;
        StepClock(Instant start){now=new AtomicLong(start.toEpochMilli());}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(now.get());}
        void advance(long millis){now.addAndGet(millis);}
        void set(long epochMilli){now.set(epochMilli);}
    }

    private static MockEnvironment modeOn(MockEnvironment env){
        env.withProperty("demo.jev.mode","on").withProperty("demo.jev.allow-paid","true")
                .withProperty("demo.jev.decision-wait-ms","2000");
        return env;
    }
    private static MockEnvironment modeShadow(MockEnvironment env){
        env.withProperty("demo.jev.mode","shadow").withProperty("demo.jev.allow-paid","true");
        return env;
    }
    private static MockEnvironment capped(MockEnvironment env,long cap){
        env.withProperty("demo.jev.budget.enabled","true")
                .withProperty("demo.jev.budget.daily-max-calls",String.valueOf(cap));
        return env;
    }
    private static JevDecisionAdvisor advisor(MockEnvironment env,Clock clock,
            JevDecisionAdvisor.Transport transport){
        return new JevDecisionAdvisor(env,clock,transport,name->KEY.equals(name)?FIXTURE_KEY:null);
    }
    private static JevDecisionAdvisor advisor(MockEnvironment env,Clock clock,
            JevDecisionAdvisor.Transport transport,Function<String,String> secrets){
        return new JevDecisionAdvisor(env,clock,transport,secrets);
    }

    /** Reads the private daily-counter state through the only seam available (reflection). */
    private static Object dailyState(JevDecisionAdvisor advisor){
        return ((AtomicReference<?>)ReflectionTestUtils.getField(advisor,"dailyCalls")).get();
    }
    private static long dayOf(Object state){return (Long)ReflectionTestUtils.invokeMethod(state,"epochDay");}
    private static long countOf(Object state){return (Long)ReflectionTestUtils.invokeMethod(state,"count");}
    private static void refund(JevDecisionAdvisor advisor,Object reservation){
        ReflectionTestUtils.invokeMethod(advisor,"refundDailyCall",reservation);
    }
    private static int permits(JevDecisionAdvisor advisor){
        return ((Semaphore)ReflectionTestUtils.getField(advisor,"inFlight")).availablePermits();
    }
    private static void awaitPermits(JevDecisionAdvisor advisor,int expected)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline&&permits(advisor)!=expected)Thread.sleep(5);
    }
    private static void awaitLines(ListAppender<ILoggingEvent> appender,int expected)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline&&appender.list.size()<expected)Thread.sleep(5);
    }
    // ---- K1-K3: WP-1 model identity -------------------------------------------------------

    /** K1: a reported id that is a strict prefix of the requested id must not be trusted. */
    @Test void k1_prefixLookalikeModelIsRejected()throws Exception{
        List<Hit> hits=new CopyOnWriteArrayList<>();
        HttpServer server=gateway(new Stub(200,"{\"model\":\"typesafe-ai/je\","
                + "\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}",null),hits);
        try{
            var res=gatewayClient(name->FIXTURE_KEY,timeout->HttpClient.newHttpClient())
                    .evaluate(request(endpoint(server)));
            assertEquals(200,res.httpStatus(),"transport answered, so the guard is the parser");
            assertNull(res.verdict(),"a lookalike model must never produce a verdict");
            assertEquals("wrong_model",res.failure());
            assertEquals(1,hits.size());
        }finally{server.stop(0);}
    }

    /** K2: an arbitrary host-side label that merely contains the alias must not be trusted. */
    @Test void k2_proxyLookalikeModelIsRejected()throws Exception{
        List<Hit> hits=new CopyOnWriteArrayList<>();
        HttpServer server=gateway(new Stub(200,"{\"model\":\"evil-jev-proxy\","
                + "\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}",null),hits);
        try{
            var res=gatewayClient(name->FIXTURE_KEY,timeout->HttpClient.newHttpClient())
                    .evaluate(request(endpoint(server)));
            assertNull(res.verdict());
            assertEquals("wrong_model",res.failure());
        }finally{server.stop(0);}
    }

    /** K3: exact id, exact final path component, and case variants are the accepted set. */
    @Test void k3_exactAndAliasModelIdentityIsAccepted()throws Exception{
        for(String reported:List.of("typesafe-ai/jev","jev","TYPESAFE-AI/JEV")){
            List<Hit> hits=new CopyOnWriteArrayList<>();
            HttpServer server=gateway(new Stub(200,"{\"model\":\""+reported+"\","
                    + "\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}",null),hits);
            try{
                var res=gatewayClient(name->FIXTURE_KEY,timeout->HttpClient.newHttpClient())
                        .evaluate(request(endpoint(server)));
                assertEquals(JevDecisionAdvisor.Verdict.WEB,res.verdict(),
                        "accepted identity expected for reported="+reported);
                assertNull(res.failure(),"reported="+reported);
            }finally{server.stop(0);}
        }
    }

    /** K4: no credential means the network is never touched; nothing can leak on that path. */
    @Test void k4_missingCredentialNeverReachesNetwork()throws Exception{
        List<Hit> hits=new CopyOnWriteArrayList<>();
        HttpServer server=gateway(new Stub(200,CONTRACT_200,null),hits);
        try{
            var res=new JevGatewayClient(name->null).evaluate(request(endpoint(server)));
            assertEquals(0,res.httpStatus(),"no transport attempt is allowed without a key");
            assertEquals("jev_not_configured",res.failure());
            assertTrue(hits.isEmpty(),"loopback fixture recorded an unexpected request");
        }finally{server.stop(0);}
    }
    // ---- K5-K6, K12: WP-2 redirect, bound and client reuse --------------------------------

    /** K5: a redirect is a failure, not a hop - the Bearer value must not reach host two. */
    @Test void k5_redirectIsNotFollowedSoCredentialCannotLeak()throws Exception{
        List<Hit> origin=new CopyOnWriteArrayList<>();
        List<Hit> target=new CopyOnWriteArrayList<>();
        HttpServer hop=gateway(new Stub(302,null,null),origin);
        HttpServer leak=gateway(new Stub(200,CONTRACT_200,null),target);
        try{
            var res=gatewayClient(name->FIXTURE_KEY,timeout->HttpClient.newHttpClient())
                    .evaluate(request(endpoint(hop)+"?next="+endpoint(leak)));
            assertEquals(302,res.httpStatus());
            assertEquals("redirect",res.failure());
            assertEquals(1,origin.size(),"exactly one request leaves the client");
            assertTrue(target.isEmpty(),"redirect target must never see the credential");
        }finally{hop.stop(0);leak.stop(0);}
    }

    /** K6: an oversized body is refused before parsing, so memory stays bounded. */
    @Test void k6_oversizedResponseIsBounded()throws Exception{
        List<Hit> hits=new CopyOnWriteArrayList<>();
        String padded=CONTRACT_200.substring(0,CONTRACT_200.length()-1)
                +",\"filler\":\""+"x".repeat(70_000)+"\"}";
        HttpServer server=gateway(new Stub(200,padded,null),hits);
        try{
            var res=gatewayClient(name->FIXTURE_KEY,timeout->HttpClient.newHttpClient())
                    .evaluate(request(endpoint(server)));
            assertEquals(200,res.httpStatus());
            assertNull(res.verdict());
            assertEquals("oversized_response",res.failure());
        }finally{server.stop(0);}
    }

    /** K12: one HttpClient per connect timeout, reused across calls; a new timeout rebuilds. */
    @Test void k12_httpClientIsBuiltOncePerConnectTimeout()throws Exception{
        List<Hit> hits=new CopyOnWriteArrayList<>();
        AtomicInteger builds=new AtomicInteger();
        HttpServer server=gateway(new Stub(200,CONTRACT_200,null),hits);
        try{
            var client=gatewayClient(name->FIXTURE_KEY,timeout->{
                builds.incrementAndGet();
                return HttpClient.newHttpClient();
            });
            var at500=request(endpoint(server));
            client.evaluate(at500);client.evaluate(at500);client.evaluate(at500);
            assertEquals(1,builds.get(),"three calls must share one cached client");
            var at600=new JevDecisionAdvisor.EvalRequest(endpoint(server),"typesafe-ai/jev",KEY,
                    "does this question need retrieval?","cue","CUE",600,1500,8192,65536);
            client.evaluate(at600);
            assertEquals(2,builds.get(),"a different connect timeout is a different cache key");
            assertEquals(4,hits.size(),"each call still issues exactly one request");
        }finally{server.stop(0);}
    }
    // ---- K7-K9: WP-2 permits and credential rotation ---------------------------------------

    /** K7: under contention the in-flight bound holds and every permit comes back. */
    @Test void k7_permitBalanceSurvivesContention()throws Exception{
        MockEnvironment env=modeOn(new MockEnvironment());
        env.withProperty("demo.jev.max-in-flight","2");
        AtomicInteger concurrent=new AtomicInteger();
        AtomicInteger peak=new AtomicInteger();
        StubTransport transport=new StubTransport(req->{
            int live=concurrent.incrementAndGet();
            peak.accumulateAndGet(live,Math::max);
            try{Thread.sleep(20);}catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
            }finally{concurrent.decrementAndGet();}
            return ok(JevDecisionAdvisor.Verdict.WEB);
        });
        JevDecisionAdvisor advisor=advisor(env,new StepClock(OCT_ONE),transport);
        ExecutorService callers=Executors.newFixedThreadPool(8);
        try{
            CountDownLatch gate=new CountDownLatch(1);
            List<String> reasons=new CopyOnWriteArrayList<>();
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int index=0;index<16;index++){
                futures.add(callers.submit(()->{
                    try{gate.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){
                        Thread.currentThread().interrupt();
                    }
                    reasons.add(advisor.advise("cue","does this question need retrieval?","CUE")
                            .reasonCode());
                }));
            }
            gate.countDown();
            for(var future:futures)future.get(5,TimeUnit.SECONDS);
            assertEquals(16,reasons.size(),"every caller must get an answer, ok or defer");
            assertTrue(peak.get()<=2,"observed concurrency "+peak.get()+" exceeded max-in-flight");
            assertEquals(reasons.stream().filter("ok"::equals).count(),transport.calls.get(),
                    "only admitted calls may reach the transport");
            assertEquals(2,permits(advisor),"every permit must return to the semaphore");
        }finally{callers.shutdownNow();advisor.close();}
    }

    /** K8: "busy" is answered before reservation, so it cannot burn daily budget. */
    @Test void k8_busyAnswerDoesNotConsumeDailyBudget()throws Exception{
        MockEnvironment env=modeOn(capped(new MockEnvironment(),3));
        StubTransport transport=new StubTransport(req->ok(JevDecisionAdvisor.Verdict.WEB));
        JevDecisionAdvisor advisor=advisor(env,new StepClock(OCT_ONE),transport);
        try{
            Semaphore gate=(Semaphore)ReflectionTestUtils.getField(advisor,"inFlight");
            gate.acquire();gate.acquire();
            try{
                assertEquals("busy",advisor.advise("cue","contended","CUE").reasonCode());
                assertEquals(0,countOf(dailyState(advisor)),"busy must not reserve a slot");
                assertEquals(0,transport.calls.get(),"busy must not reach the transport");
            }finally{gate.release();gate.release();}
            awaitPermits(advisor,2);
            assertEquals("ok",advisor.advise("cue","admitted","CUE").reasonCode());
            assertEquals(1,countOf(dailyState(advisor)),"only real attempts consume budget");
            assertEquals(1,transport.calls.get());
        }finally{advisor.close();}
    }

    /** K9: the credential is read per call, so a rotation re-opens the gate without a restart. */
    @Test void k9_credentialRotationReopensTheGateWithoutRestart()throws Exception{
        MockEnvironment env=modeOn(new MockEnvironment());
        StubTransport transport=new StubTransport(req->ok(JevDecisionAdvisor.Verdict.HYBRID));
        AtomicReference<String> live=new AtomicReference<>();
        JevDecisionAdvisor advisor=advisor(env,new StepClock(OCT_ONE),transport,
                name->KEY.equals(name)?live.get():null);
        try{
            assertEquals("jev_not_configured",advisor.advise("cue","first","CUE").reasonCode());
            assertEquals(0,transport.calls.get(),"no dispatch before a key exists");
            live.set("rotated-synthetic-fixture-key");
            var opened=advisor.advise("cue","second","CUE");
            assertEquals("ok",opened.reasonCode());
            assertEquals(JevDecisionAdvisor.Verdict.HYBRID,opened.verdict());
            assertEquals(1,transport.calls.get());
        }finally{advisor.close();}
    }
    // ---- K10-K11: WP-3 daily budget counter defects (RED, see ADDENDUM_FINDINGS F5-F6) -----

    /**
     * K10 (RED): a dispatch that never received an HTTP answer must not spend the day's allowance.
     * In the current bytes refundDailyCall is reachable only from the two pool-rejection branches
     * (JevDecisionAdvisor.java:121, :136), so a transport error keeps the reservation and a fully
     * failing day burns its cap on calls that never reached the gateway.
     */
    @Disabled("RED (F5, observed 2026-09-30): expected <0> but was <1> - refundDailyCall is only "
            + "reachable from the pool-rejection branches (JevDecisionAdvisor.java:121,:136), so an "
            + "unanswered dispatch keeps the reservation")
    @Test void k10_rolledBackAttemptLeavesNoCounterResidue()throws Exception{
        MockEnvironment env=modeOn(capped(new MockEnvironment(),1));
        AtomicInteger attempts=new AtomicInteger();
        StubTransport transport=new StubTransport(req->{
            if(attempts.incrementAndGet()==1)throw new IllegalStateException("transport down");
            return ok(JevDecisionAdvisor.Verdict.WEB);
        });
        JevDecisionAdvisor advisor=advisor(env,new StepClock(OCT_ONE),transport);
        try{
            assertEquals("transport_error",advisor.advise("cue","failing","CUE").reasonCode(),
                    "an unanswered dispatch defers with the transport reason");
            Object state=dailyState(advisor);
            assertEquals(OCT_ONE.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay(),
                    dayOf(state),"the day must stay the clock's day");
            assertEquals(1,attempts.get(),"the attempt was dispatched exactly once");
            assertEquals(0,countOf(state),"a call that never answered must leave the counter at zero");
            assertEquals("ok",advisor.advise("cue","retry","CUE").reasonCode());
        }finally{advisor.close();}
    }

    /**
     * K11 (RED): a backward clock step must not hand out a second allowance for a day that is
     * already spent. Any epoch-day difference - backwards included - restarts the count
     * (JevDecisionAdvisor.java:187), so an NTP correction grants a fresh cap per jump.
     */
    @Disabled("RED (F6, observed 2026-09-30): expected <budget_skip> but was <ok> - any epoch-day "
            + "difference restarts the count (JevDecisionAdvisor.java:187), so a backward clock step "
            + "grants a fresh allowance")
    @Test void k11_backwardClockStepGrantsNoSecondAllowance()throws Exception{
        MockEnvironment env=modeOn(capped(new MockEnvironment(),2));
        StepClock clock=new StepClock(OCT_ONE);
        StubTransport transport=new StubTransport(req->ok(JevDecisionAdvisor.Verdict.SCOPED_RAG));
        JevDecisionAdvisor advisor=advisor(env,clock,transport);
        try{
            assertEquals("ok",advisor.advise("cue","one","CUE").reasonCode());
            assertEquals("ok",advisor.advise("cue","two","CUE").reasonCode());
            assertEquals("budget_skip",advisor.advise("cue","three","CUE").reasonCode());
            clock.set(OCT_ONE.toEpochMilli()-DAY_MS);
            assertEquals("budget_skip",advisor.advise("cue","after rollback","CUE").reasonCode(),
                    "a backward jump may not refresh the daily allowance");
        }finally{advisor.close();}
    }

    // ---- K13: WP-4 shadow-mode log never carries the credential ----------------------------

    /** K13: the shadow audit line keeps the verdict auditable and never carries the credential. */
    @Test void k13_shadowLogNeverPrintsCredential()throws Exception{
        MockEnvironment env=modeShadow(new MockEnvironment());
        AtomicInteger calls=new AtomicInteger();
        StubTransport transport=new StubTransport(req->{
            calls.incrementAndGet();
            try{Thread.sleep(20);}catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
            }
            return ok(JevDecisionAdvisor.Verdict.WEB);
        });
        JevDecisionAdvisor advisor=advisor(env,new StepClock(OCT_ONE),transport);
        Logger logger=(Logger)LoggerFactory.getLogger(JevDecisionAdvisor.class);
        Level before=logger.getLevel();
        ListAppender<ILoggingEvent> appender=new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        int permitsBefore=permits(advisor);
        try{
            var outcome=advisor.advise("cue","first","CUE");
            assertNull(outcome.verdict(),"shadow must never steer the caller");
            assertEquals("shadow",outcome.reasonCode(),"shadow defers under its own reason code");
            assertFalse(outcome.usable(),"a shadow answer is not an answer the caller may use");
            awaitLines(appender,1);
            String all=appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("",(left,right)->left+"\n"+right);
            assertFalse(all.contains(FIXTURE_KEY),"shadow log leaked the credential: "+all);
            assertFalse(all.contains("Bearer"),"no Authorization material belongs in a log line");
            String audit=appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(text->text.contains("mode=shadow")).findFirst().orElse("");
            assertTrue(audit.contains("decision=WEB"),"the async verdict must stay auditable: "+audit);
            assertTrue(audit.contains("reasonCode=ok"),"a clean shadow run must not read as an error: "+audit);
            assertTrue(audit.contains("latencyMs=")&&audit.contains("httpStatus="),
                    "the audit line must carry timing and transport outcome: "+audit);
            assertEquals(1,calls.get(),"shadow evaluates exactly once per confirmed question");
            assertEquals(permitsBefore,permits(advisor),"the shadow task owns the permit until it reports");
        }finally{
            logger.detachAppender(appender);
            logger.setLevel(before);
            appender.stop();
            advisor.close();
        }
    }





}
