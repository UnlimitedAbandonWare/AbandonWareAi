package com.example.lms.api;

import com.example.lms.infra.upstash.UpstashRedisClient;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.*;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGenerationAdmissionFilterTest {
    @TempDir Path directory;
    DriverManagerDataSource ds;
    UpstashRedisClient redis;
    ClientOwnerKeyResolver owners;
    @BeforeEach void setUp() {
        ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("admission")+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa","");
        new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912_02__chat_requests.sql"),new FileSystemResource("main/resources/db/migration/V20260912_04__chat_request_results.sql")).execute(ds);
        redis=mock(UpstashRedisClient.class);owners=mock(ClientOwnerKeyResolver.class);
        when(redis.enabled()).thenReturn(true);
        when(redis.eval(anyString(),anyList(),anyList())).thenReturn(Mono.just(List.of(1L,0L)));
        when(owners.ownerKey()).thenReturn("fixture-owner");
        when(owners.clientIpHash(any())).thenReturn("f".repeat(64));
    }
    private MockHttpServletRequest request(String path,String key,String body) {
        var req=new MockHttpServletRequest("POST",path);req.setContentType("application/json");req.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        req.setAttribute("chat.admission.bodySha256",org.apache.commons.codec.digest.DigestUtils.sha256Hex(body));
        if(key!=null)req.addHeader("Idempotency-Key",key);
        return req;
    }
    @Test void fiveStaggeredRequestsAcrossInstancesInvokeWorkOnceEvenIfRedisLosesKey() throws Exception {
        var a=new ChatGenerationAdmissionFilter(redis,ds,owners);var b=new ChatGenerationAdmissionFilter(redis,ds,owners);
        var calls=new AtomicInteger();var pool=Executors.newScheduledThreadPool(5);var responses=new CopyOnWriteArrayList<Integer>();
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<5;i++){final int index=i;futures.add(pool.schedule(()->{
                var response=new MockHttpServletResponse();
                try {(index%2==0?a:b).doFilter(request("/api/chat/sync","fixture-key","{}"),response,(rq,rs)->{
                    calls.incrementAndGet();try{Thread.sleep(600);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                });responses.add(response.getStatus());}catch(Exception e){throw new RuntimeException(e);}
            },i*100L,TimeUnit.MILLISECONDS));}
            for(var f:futures)f.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertEquals(1,Collections.frequency(responses,200));assertEquals(4,Collections.frequency(responses,409));
        }finally{pool.shutdownNow();}
    }
    @Test void reusedKeyWithChangedBodyIsRejectedAndCompletedRecordExpiresAfter24Hours() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);
        filter.doFilter(request("/api/chat","fixture-key","{}"),new MockHttpServletResponse(),(a,b)->ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)a).accept(new com.example.lms.dto.ChatResponseDto("answer",1L,"fixture",false)));
        var response=new MockHttpServletResponse();
        filter.doFilter(request("/api/chat","fixture-key","{\"message\":\"different\"}"),response,(a,b)->fail("must not execute"));
        assertEquals(409,response.getStatus());
        var jdbc=new JdbcTemplate(ds);
        assertEquals(86_400_000L,jdbc.queryForObject("SELECT expires_at-completed_at FROM awx_chat_requests",Long.class));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT state FROM awx_chat_requests",String.class));
    }
    @Test void rateLimitHasRetryAfterAndUnavailableRedisFailsClosed() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);
        when(redis.eval(anyString(),anyList(),anyList())).thenReturn(Mono.just(List.of(0L,1500L)));
        var limited=new MockHttpServletResponse();filter.doFilter(request("/api/chat/stream",null,"{}"),limited,(a,b)->fail("must not execute"));
        assertEquals(429,limited.getStatus());assertEquals("2",limited.getHeader("Retry-After"));
        when(redis.eval(anyString(),anyList(),anyList())).thenReturn(Mono.error(new IllegalStateException("fixture")));
        var unavailable=new MockHttpServletResponse();filter.doFilter(request("/api/chat",null,"{}"),unavailable,(a,b)->fail("must not execute"));
        assertEquals(503,unavailable.getStatus());assertFalse(unavailable.getContentAsString().contains("fixture"));
    }
    @Test void attachAndControlRequestsDoNotConsumeGenerationCapacity() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);var calls=new AtomicInteger();
        var attach=request("/api/chat/stream",null,"{}");attach.setParameter("attach","true");
        for(var req:List.of(attach,request("/api/chat/cancel",null,"{}"),request("/api/chat/ack",null,"{}")))
            filter.doFilter(req,new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
        assertEquals(3,calls.get());verifyNoInteractions(redis);
    }
    @Test void asyncClaimIsNotCompletedAt202AndTimeoutRemainsUncertain() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);var req=request("/api/chat/stream","async-key","{}");req.setAsyncSupported(true);
        filter.doFilter(req,new MockHttpServletResponse(),(a,b)->a.startAsync(a,b));
        var jdbc=new JdbcTemplate(ds);
        assertEquals("IN_PROGRESS",jdbc.queryForObject("SELECT state FROM awx_chat_requests",String.class));
        assertNull(jdbc.queryForObject("SELECT expires_at FROM awx_chat_requests",Long.class));
        var context=(MockAsyncContext)req.getAsyncContext();
        for(var listener:context.getListeners())listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
        for(var listener:context.getListeners())listener.onComplete(new jakarta.servlet.AsyncEvent(context));
        assertEquals("OUTCOME_UNKNOWN",jdbc.queryForObject("SELECT state FROM awx_chat_requests",String.class));
        assertNull(jdbc.queryForObject("SELECT expires_at FROM awx_chat_requests",Long.class));
    }
    @Test void ownerNamespaceAndExpiryDoNotCrossOrRetainCompletedClaimsForever() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);var calls=new AtomicInteger();
        filter.doFilter(request("/api/chat","fixture-key","{}"),new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
        when(owners.ownerKey()).thenReturn("another-owner");
        filter.doFilter(request("/api/chat","fixture-key","{}"),new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
        assertEquals(2,calls.get());var jdbc=new JdbcTemplate(ds);
        jdbc.update("UPDATE awx_chat_requests SET expires_at=?",System.currentTimeMillis()-1);filter.purgeExpired();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM awx_chat_requests",Integer.class));
    }
    @Test void failedOrDisconnectedExecutionDoesNotExpireItsUncertainClaim() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);
        assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(request("/api/chat","fixture-key","{}"),new MockHttpServletResponse(),(a,b)->{throw new jakarta.servlet.ServletException("fixture");}));
        var jdbc=new JdbcTemplate(ds);
        assertEquals("OUTCOME_UNKNOWN",jdbc.queryForObject("SELECT state FROM awx_chat_requests",String.class));
        assertNull(jdbc.queryForObject("SELECT expires_at FROM awx_chat_requests",Long.class));
    }
    @Test void existingBoundedBodyReaderSuppliesFingerprintWithoutConsumingControllerBody() throws Exception {
        var req=request("/api/chat/sync","fixture-key","{}");req.removeAttribute("chat.admission.bodySha256");
        var budget=new PublicRequestBudgetGuard();var admission=new ChatGenerationAdmissionFilter(redis,ds,owners);
        try {budget.doFilter(req,new MockHttpServletResponse(),(rq,rs)->admission.doFilter(rq,rs,(body,response)->{
            assertEquals("{}",new String(body.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }));}finally{org.springframework.test.util.ReflectionTestUtils.invokeMethod(budget,"shutdownBodyReadExecutor");}
    }
    @Test void semanticOrderingIsDuplicateButOperationAndEffectiveSessionAreDistinct() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);var budget=new PublicRequestBudgetGuard();var calls=new AtomicInteger();
        try {
            var first=request("/api/chat","semantic","{\"message\":\"질문\",\"useRag\":false}");
            budget.doFilter(first,new MockHttpServletResponse(),(rq,rs)->filter.doFilter(rq,rs,(a,b)->calls.incrementAndGet()));
            var reordered=request("/api/chat","semantic","{ \"useRag\":false, \"message\":\"질문\" }");
            var duplicate=new MockHttpServletResponse();budget.doFilter(reordered,duplicate,(rq,rs)->filter.doFilter(rq,rs,(a,b)->fail("duplicate")));
            assertEquals(409,duplicate.getStatus());assertTrue(duplicate.getContentAsString().contains("idempotency_duplicate"));
            budget.doFilter(request("/api/chat/sync","semantic","{\"message\":\"질문\",\"useRag\":false}"),new MockHttpServletResponse(),(rq,rs)->filter.doFilter(rq,rs,(a,b)->calls.incrementAndGet()));
            assertEquals(2,calls.get());
            var changedSession=request("/api/chat","semantic","{\"message\":\"질문\",\"useRag\":false}");changedSession.addHeader("X-Session-Id","42");
            var conflict=new MockHttpServletResponse();budget.doFilter(changedSession,conflict,(rq,rs)->filter.doFilter(rq,rs,(a,b)->fail("different session")));
            assertEquals(409,conflict.getStatus());assertTrue(conflict.getContentAsString().contains("idempotency_payload_mismatch"));
        } finally {org.springframework.test.util.ReflectionTestUtils.invokeMethod(budget,"shutdownBodyReadExecutor");}
    }
    @Test void exactCompletedResultReplaysAcrossInstancesAfterAnotherTurnAndWithoutRedis() throws Exception {
        var first=new ChatGenerationAdmissionFilter(redis,ds,owners);var second=new ChatGenerationAdmissionFilter(redis,ds,owners);var calls=new AtomicInteger();
        first.doFilter(request("/api/chat","first","{}"),new MockHttpServletResponse(),(a,b)->{calls.incrementAndGet();ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)a).accept(new com.example.lms.dto.ChatResponseDto("original answer",1L,"fixture",false));});
        first.doFilter(request("/api/chat","second","{}"),new MockHttpServletResponse(),(a,b)->{calls.incrementAndGet();ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)a).accept(new com.example.lms.dto.ChatResponseDto("later answer",1L,"fixture",false));});
        when(redis.enabled()).thenReturn(false);var replay=new MockHttpServletResponse();
        second.doFilter(request("/api/chat","first","{}"),replay,(a,b)->fail("must not infer"));
        assertEquals(200,replay.getStatus());assertTrue(replay.getContentAsString().contains("original answer"));assertFalse(replay.getContentAsString().contains("later answer"));assertEquals("true",replay.getHeader("X-Idempotent-Replay"));assertEquals(2,calls.get());
        when(owners.ownerKey()).thenReturn("other");var denied=new MockHttpServletResponse();second.doFilter(request("/api/chat","first","{}"),denied,(a,b)->fail("Redis down"));assertEquals(503,denied.getStatus());assertFalse(denied.getContentAsString().contains("original answer"));
    }
    @Test void transportCompletionAloneCannotCertifyResultAndLateCompletionCannotReviveUnknown() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);var req=request("/api/chat/stream","lost","{}");req.setAsyncSupported(true);
        var receipt=new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Object>>();
        filter.doFilter(req,new MockHttpServletResponse(),(a,b)->{receipt.set(ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)a));a.startAsync(a,b);});
        var context=(MockAsyncContext)req.getAsyncContext();for(var listener:context.getListeners())listener.onComplete(new jakarta.servlet.AsyncEvent(context));
        assertEquals("OUTCOME_UNKNOWN",new JdbcTemplate(ds).queryForObject("SELECT state FROM awx_chat_requests",String.class));
        receipt.get().accept(new com.example.lms.dto.ChatResponseDto("late",1L,"fixture",false));assertEquals(0,new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM awx_chat_request_results",Integer.class));
    }
    @Test void simultaneousFiveRequestsAcrossTwoInstancesExecuteOnce() throws Exception {
        var a=new ChatGenerationAdmissionFilter(redis,ds,owners);var b=new ChatGenerationAdmissionFilter(redis,ds,owners);
        var barrier=new CyclicBarrier(5);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rejected=new CountDownLatch(4);var calls=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(5);var work=new ArrayList<Future<Integer>>();
        try{
            for(int i=0;i<5;i++){final int index=i;work.add(pool.submit(()->{barrier.await();var response=new MockHttpServletResponse();
                (index%2==0?a:b).doFilter(request("/api/chat/sync","concurrent","{}"),response,(rq,rs)->{calls.incrementAndGet();entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)rq).accept(new com.example.lms.dto.ChatResponseDto("once",1L,"fixture",false));});
                if(response.getStatus()==409)rejected.countDown();return response.getStatus();}));}
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(rejected.await(3,TimeUnit.SECONDS));release.countDown();
            var statuses=new ArrayList<Integer>();for(var f:work)statuses.add(f.get(3,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1,Collections.frequency(statuses,200));assertEquals(4,Collections.frequency(statuses,409));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void persistedFinalReplaysAsOneSseFinalAndExpiresWithItsParent()throws Exception{
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);
        var event=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"type\":\"final\",\"data\":\"원래 답변\",\"sessionId\":1}",com.example.lms.dto.ChatStreamEvent.class);
        filter.doFilter(request("/api/chat/stream","stream-final","{}"),new MockHttpServletResponse(),(rq,rs)->ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest)rq).accept(event));
        var response=new MockHttpServletResponse();filter.doFilter(request("/api/chat/stream","stream-final","{}"),response,(rq,rs)->fail("replay"));
        assertEquals(200,response.getStatus());assertTrue(response.getContentType().startsWith("text/event-stream"));assertTrue(response.getContentAsString().startsWith("event: final\ndata: "));assertTrue(response.getContentAsString().contains("원래 답변"));
        var jdbc=new JdbcTemplate(ds);jdbc.update("UPDATE awx_chat_requests SET expires_at=?",System.currentTimeMillis()-1);filter.purgeExpired();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM awx_chat_request_results",Integer.class));
    }
    @Test void existingFenceIsReadableWithRedisDisabledAndCostHookCapturesOnlyHashedIdentity() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(redis,ds,owners);
        filter.doFilter(request("/api/chat","retained","{}"),new MockHttpServletResponse(),(a,b)->{});
        when(redis.enabled()).thenReturn(false);
        var replay=new MockHttpServletResponse();filter.doFilter(request("/api/chat","retained","{}"),replay,(a,b)->fail("duplicate"));assertEquals(409,replay.getStatus());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("synthetic-user",null,List.of()));
        try {
            var cost=filter.costCheck(request("/api/assist/sessions",null,"{}"));
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,cost::run).getStatusCode().value());
            when(redis.enabled()).thenReturn(true);cost.run();
            verify(redis).eval(anyString(),eq(List.of("chat:{admission}:user:"+org.apache.commons.codec.digest.DigestUtils.sha256Hex("user:synthetic-user"),"chat:{admission}:ip:"+"f".repeat(64),"chat:{admission}:key:unused")),argThat(args->args.get(6).equals("0")));
            when(redis.eval(anyString(),anyList(),anyList())).thenReturn(Mono.just(List.of(0L,1500L)));
            var limited=assertThrows(org.springframework.web.server.ResponseStatusException.class,cost::run);assertEquals(429,limited.getStatusCode().value());assertEquals("2",limited.getHeaders().getFirst("Retry-After"));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    @Test void syntheticPrincipalsCannotReplayAnotherOwnersCompletedAnswer() throws Exception {
        var filter = new ChatGenerationAdmissionFilter(redis, ds, owners);
        var calls = new AtomicInteger();
        try {
            for (String name : List.of("proto-open", "admin-token")) {
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name, null, List.of()));
                for (String owner : List.of("owner-a", "owner-b")) {
                    when(owners.ownerKey()).thenReturn(owner);
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request("/api/chat", "shared-" + name, "{}"), response, (rq, rs) -> {
                        calls.incrementAndGet();
                        ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest) rq).accept(
                                new com.example.lms.dto.ChatResponseDto("answer-" + owner, 1L, "fixture", false));
                    });
                    assertEquals(200, response.getStatus());
                    assertNull(response.getHeader("X-Idempotent-Replay"));
                    assertFalse(response.getContentAsString().contains("answer-owner-a"));
                }
            }
            assertEquals(4, calls.get());
            assertEquals(2, new JdbcTemplate(ds).queryForObject("SELECT COUNT(DISTINCT owner_hash) FROM awx_chat_requests", Integer.class));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test void syntheticCostHooksCaptureSeparateOwnerIdentityBeforeContextLeaves() {
        var filter = new ChatGenerationAdmissionFilter(redis, ds, owners);
        try {
            for (String name : List.of("proto-open", "admin-token")) {
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name, null, List.of()));
                var captured = new ArrayList<Runnable>();
                for (String owner : List.of("cost-a", "cost-b")) {
                    when(owners.ownerKey()).thenReturn(owner);
                    captured.add(filter.costCheck(request("/api/tasks", null, "{}")));
                }
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                captured.forEach(Runnable::run);
            }
            for (String owner : List.of("cost-a", "cost-b"))
                verify(redis, times(2)).eval(anyString(), eq(List.of(
                        "chat:{admission}:user:" + org.apache.commons.codec.digest.DigestUtils.sha256Hex("owner:" + owner),
                        "chat:{admission}:ip:" + "f".repeat(64), "chat:{admission}:key:unused")), anyList());
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test void authenticatedPrincipalKeepsOneReplayNamespaceAcrossOwnerCookies() throws Exception {
        var filter = new ChatGenerationAdmissionFilter(redis, ds, owners);
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("real-fixture", null, List.of()));
            filter.doFilter(request("/api/chat", "same-user", "{}"), new MockHttpServletResponse(), (rq, rs) ->
                    ChatGenerationAdmissionFilter.completion((jakarta.servlet.http.HttpServletRequest) rq).accept(
                            new com.example.lms.dto.ChatResponseDto("own-answer", 1L, "fixture", false)));
            when(owners.ownerKey()).thenReturn("second-device");
            var replay = new MockHttpServletResponse();
            filter.doFilter(request("/api/chat", "same-user", "{}"), replay, (rq, rs) -> fail("same authenticated owner must replay"));
            assertEquals("true", replay.getHeader("X-Idempotent-Replay"));
            assertTrue(replay.getContentAsString().contains("own-answer"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Nested class DemoRollingAdmission {
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        ChatGenerationAdmissionFilter filter;
        int nextKey;
        @BeforeEach void demo() {
            filter = new ChatGenerationAdmissionFilter(redis, ds, owners);
            org.springframework.test.util.ReflectionTestUtils.setField(filter, "demoMode", true);
            if (org.springframework.util.ReflectionUtils.findField(filter.getClass(), "turnClock") != null)
                org.springframework.test.util.ReflectionTestUtils.setField(filter, "turnClock", (java.util.function.LongSupplier) clock::get);
        }
        @AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        MockHttpServletResponse send(String key) throws Exception {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("/api/chat/sync", key, "{}"), response, (rq, rs) -> {});
            return response;
        }
        void fill(int count) throws Exception {
            for (int i = 0; i < count; i++) assertEquals(200, send("q" + nextKey++).getStatus());
        }
        void hourly(boolean enabled) {
            if (org.springframework.util.ReflectionUtils.findField(filter.getClass(), "turnHourlyEnabled") != null)
                org.springframework.test.util.ReflectionTestUtils.setField(filter, "turnHourlyEnabled", enabled);
        }
        void principal(String name) {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name, "synthetic", List.of()));
        }
        @Test void T1_eleventhNewQuestionIs429WithNoDownstreamCall() throws Exception {
            fill(10);
            var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
            filter.doFilter(request("/api/chat/sync", "eleven", "{}"), response, (rq, rs) -> calls.incrementAndGet());
            assertEquals(429, response.getStatus()); assertEquals(0, calls.get());
            assertTrue(Long.parseLong(response.getHeader("Retry-After")) >= 1);
            assertEquals("minute", response.getHeader("X-RateLimit-Scope"));
            assertEquals("{\"error\":\"chat_rate_limited\"}", response.getContentAsString());
            verifyNoInteractions(redis);
        }
        @Test void T2_exactMinuteBoundaryAndBackwardClockRemainSafe() throws Exception {
            fill(10); clock.set(59_999); assertEquals(429, send("blocked").getStatus());
            clock.set(60_000); assertEquals(200, send("boundary").getStatus());
            for (int i = 0; i < 9; i++) assertEquals(200, send("later" + i).getStatus());
            clock.set(0); assertEquals(429, send("backward").getStatus());
        }
        @Test void T3_syntheticPrincipalsUseSeparateOwnersRealPrincipalsAggregate() throws Exception {
            for (String name : List.of("proto-open", "admin-token")) {
                principal(name); clock.addAndGet(60_000);
                when(owners.ownerKey()).thenReturn("owner-a"); fill(10);
                when(owners.ownerKey()).thenReturn("owner-b"); fill(10);
                assertEquals(429, send("eleven-b").getStatus());
            }
            clock.addAndGet(60_000); principal("fixture-real-user");
            when(owners.ownerKey()).thenReturn("device-a"); fill(5);
            when(owners.ownerKey()).thenReturn("device-b");
            for (int i = 5; i < 10; i++) assertEquals(200, send("q" + i).getStatus());
            assertEquals(429, send("real-eleven").getStatus());
        }
        @Test void T4_idempotentReplayCostsZeroAndExpiresAtOneHour() throws Exception {
            fill(10);
            var calls = new AtomicInteger();
            for (int i = 0; i < 15; i++) {
                var duplicate = new MockHttpServletResponse();
                filter.doFilter(request("/api/chat/sync", "q0", "{}"), duplicate,
                        (rq, rs) -> calls.incrementAndGet());
                assertEquals(409, duplicate.getStatus());
                assertTrue(duplicate.getContentAsString().contains("idempotency_duplicate"));
            }
            assertEquals(0, calls.get());
            assertEquals(429, send("new-key").getStatus());
            clock.set(60_000); fill(10);
            clock.set(3_599_999); fill(10); clock.set(3_600_000);
            assertEquals(429, send("q0").getStatus());
        }
        @Test void T5_lastSlotCompetitionAcceptsExactlyOneNewKey() throws Exception {
            fill(9); var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var futures = new ArrayList<Future<Integer>>();
                for (int i = 0; i < 2; i++) { final String key = "race" + i;
                    futures.add(pool.submit(() -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return send(key).getStatus(); }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
                var statuses = List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS));
                assertEquals(1, Collections.frequency(statuses, 200)); assertEquals(1, Collections.frequency(statuses, 429));
            } finally { go.countDown(); pool.shutdownNow(); }
        }
        @Test void T6_attachGetAndSettingsDoNotConsumeTurns() throws Exception {
            for (int i = 0; i < 12; i++) {
                var attach = request("/api/chat/stream", null, "{}"); attach.setParameter("attach", "true");
                var get = new MockHttpServletRequest("GET", "/api/chat/sync");
                var settings = request("/api/settings", null, "{}");
                for (var req : List.of(attach, get, settings))
                    filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
            }
            fill(10); assertEquals(429, send("eleven").getStatus());
        }
        @Test void T7_onlyExplicitNeverDispatchedMarkerRefunds() throws Exception {
            fill(9);
            var req = request("/api/chat/sync", "not-sent", "{}");
            filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
                rq.setAttribute("chat.admission.neverDispatched", true); ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(400);
            });
            assertEquals(200, send("not-sent").getStatus());
            assertEquals(429, send("eleven").getStatus());
            for (int status : List.of(400, 500)) {
                clock.addAndGet(60_000); fill(9);
                filter.doFilter(request("/api/chat/sync", "http" + status, "{}"), new MockHttpServletResponse(),
                        (rq, rs) -> ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(status));
                assertEquals(429, send("after-http" + status).getStatus());
            }
            clock.addAndGet(60_000); fill(9);
            assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request("/api/chat/sync", "timeout", "{}"),
                    new MockHttpServletResponse(), (rq, rs) -> { throw new jakarta.servlet.ServletException("synthetic-timeout"); }));
            assertEquals(429, send("after-timeout").getStatus());
            clock.addAndGet(60_000); fill(9);
            var stream = request("/api/chat/stream", "cancel", "{}"); stream.setAsyncSupported(true);
            filter.doFilter(stream, new MockHttpServletResponse(), (rq, rs) -> rq.startAsync(rq, rs));
            stream.getAsyncContext().complete();
            assertEquals(429, send("after-cancel").getStatus());
        }
        @Test void T8_hourlyDefaultOffAndToggleUsesRecordedHistory() throws Exception {
            for (int i = 0; i < 11; i++) { clock.set(i * 60_000L); assertEquals(200, send("hour" + i).getStatus()); }
            hourly(true); clock.set(660_000);
            var denied = send("hour-on"); assertEquals(429, denied.getStatus());
            assertEquals("hour", denied.getHeader("X-RateLimit-Scope"));
            hourly(false); assertEquals(200, send("hour-off").getStatus());
            hourly(true); clock.set(720_000); assertEquals(429, send("hour-on-again").getStatus());
        }
        @Test void T9_internalCostChecksDoNotConsumeLogicalTurns() throws Exception {
            for (int i = 0; i < 20; i++) {
                filter.costCheck(request("/api/tasks", null, "{}")).run(); filter.costCheckCurrentRequest().run();
            }
            fill(10); assertEquals(429, send("eleven").getStatus()); verifyNoInteractions(redis);
        }
        @Test void T10_sameKeyChangedPayloadRejectsBeforeGenerationOrAnotherTurnCharge() throws Exception {
            var calls = new AtomicInteger();
            var first = new MockHttpServletResponse();
            filter.doFilter(request("/api/chat/sync", "payload-key", "{\"message\":\"first\"}"), first,
                    (rq, rs) -> calls.incrementAndGet());
            assertEquals(200, first.getStatus());
            var changed = new MockHttpServletResponse();
            filter.doFilter(request("/api/chat/sync", "payload-key", "{\"message\":\"different\"}"), changed,
                    (rq, rs) -> calls.incrementAndGet());
            assertEquals(409, changed.getStatus());
            assertTrue(changed.getContentAsString().contains("idempotency_payload_mismatch"));
            assertEquals(1, calls.get());
            fill(9); assertEquals(429, send("eleven-after-mismatch").getStatus());
            verifyNoInteractions(redis);
        }
        @Test void T11_concurrentDuplicateHasOneGenerationAndNoSecondTurnCharge() throws Exception {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            var firstEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var calls = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
            var completed = new ExecutorCompletionService<Integer>(pool);
            try {
                for (int i = 0; i < 2; i++) completed.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request("/api/chat/sync", "concurrent-key", "{}"), response, (rq, rs) -> {
                        calls.incrementAndGet(); firstEntered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                    });
                    return response.getStatus();
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                var duplicate = completed.poll(2, TimeUnit.SECONDS);
                assertNotNull(duplicate, "duplicate must reject while the original generation remains in flight");
                assertEquals(409, duplicate.get(5, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
                release.countDown();
                var original = completed.poll(5, TimeUnit.SECONDS);
                assertNotNull(original, "released original must finish within the fixture deadline");
                assertEquals(200, original.get(5, TimeUnit.SECONDS));
                fill(9); assertEquals(429, send("eleven-after-duplicate").getStatus());
                verifyNoInteractions(redis);
            } finally { go.countDown(); release.countDown(); pool.shutdownNow(); }
        }
    }
}
