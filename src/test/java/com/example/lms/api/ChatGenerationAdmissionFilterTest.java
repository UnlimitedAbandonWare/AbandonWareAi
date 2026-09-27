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
}
