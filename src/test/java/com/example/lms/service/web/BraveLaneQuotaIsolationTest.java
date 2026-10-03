package com.example.lms.service.web;

import ai.abandonware.nova.config.NovaBraveAdaptiveQpsProperties;
import ai.abandonware.nova.orch.aop.BraveOperationalGateAspect;
import ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspect;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import ai.abandonware.nova.orch.web.brave.BraveAdaptiveQpsRestTemplateInterceptor;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BraveLaneQuotaIsolationTest {
    private static final String FREE="synthetic-free-fixture", BASE="synthetic-base-fixture";
    private static final String URL="https://api.search.brave.com/res/v1/web/search";
    private static final byte[] BODY="{\"web\":{\"results\":[]}}".getBytes(StandardCharsets.UTF_8);
    @AfterEach void clear(){TraceStore.clear();}
    record Fixture(BraveSearchService service, BraveRateLimitState state,
                   BraveAdaptiveQpsRestTemplateInterceptor interceptor){}
    Fixture fixture(String free,String base){
        var s=new BraveSearchService(new BraveSearchProperties(true,URL,base,.8,20,1,0,1000));
        ReflectionTestUtils.setField(s,"configEnabled",true);
        ReflectionTestUtils.setField(s,"apiKey",base);ReflectionTestUtils.setField(s,"apiKeyFree",free);
        ReflectionTestUtils.setField(s,"baseUrl",URL);
        ReflectionTestUtils.setField(s,"adaptiveSearchEnabled",false);
        s.init();s.rateLimiter().setRate(2000);
        var p=new NovaBraveAdaptiveQpsProperties();
        ReflectionTestUtils.setField(p,"maxQps",2000d);
        ReflectionTestUtils.setField(p,"perSecondPenaltyEnabled",false);
        var state=new BraveRateLimitState();
        var i=new BraveAdaptiveQpsRestTemplateInterceptor(p,s.rateLimiter(),s.cooldownUntilEpochMs(),
                s.monthlyRemaining(),s,state);
        s.addRestTemplateInterceptorIfAbsent(i);
        return new Fixture(s,state,i);
    }
    MockClientHttpResponse response(HttpStatus status,String limit,String remaining){
        var r=new MockClientHttpResponse(BODY,status);
        r.getHeaders().set("X-RateLimit-Limit",limit);
        r.getHeaders().set("X-RateLimit-Remaining",remaining);
        r.getHeaders().set("X-RateLimit-Reset","1, 3600");
        if(status==HttpStatus.TOO_MANY_REQUESTS)r.getHeaders().set("Retry-After","2");
        return r;
    }
    void receive(Fixture f,String token,MockClientHttpResponse r)throws Exception{
        var req=new MockClientHttpRequest(HttpMethod.GET,URI.create(URL));
        req.getHeaders().set("X-Subscription-Token",token);
        f.interceptor.intercept(req,new byte[0],(request,body)->r);
    }
    void gate(Fixture f){
        var gate=new BraveOperationalGateAspect(null,f.state,new MockEnvironment());
        ReflectionTestUtils.invokeMethod(gate,"apply",f.service);
    }
    @Test void exhaustedFreeLeavesBaseWireAndHybridGateAvailable()throws Exception{
        var f=fixture(FREE,BASE);
        receive(f,FREE,response(HttpStatus.OK,"2, 20","1, 0"));gate(f);
        assertTrue(f.service.isEnabled(),"FREE exhaustion must leave independent BASE enabled");
        assertFalse(f.service.isCoolingDown(),"FREE month latch must not become BASE cooldown");
        AtomicInteger calls=new AtomicInteger();
        var template=(RestTemplate)ReflectionTestUtils.getField(f.service,"restTemplate");
        template.setRequestFactory((uri,method)->new MockClientHttpRequest(method,uri){
            @Override protected org.springframework.http.client.ClientHttpResponse executeInternal(){
                assertEquals(BASE,getHeaders().getFirst("X-Subscription-Token"));calls.incrementAndGet();
                return response(HttpStatus.OK,"2, 0","1, 0");
            }
        });
        assertEquals(BraveSearchResult.Status.OK,f.service.searchWithMeta("base fixture",1).status());
        assertEquals(1,calls.get());
        assertNotEquals(true,TraceStore.get("web.brave.providerDisabled"));
    }
    @Test void baseFiniteQuotaNeverReconcilesFreeCounter()throws Exception{
        var f=fixture(FREE,BASE);
        receive(f,BASE,response(HttpStatus.OK,"2, 100","1, 0"));gate(f);
        assertEquals(20,f.service.monthlyRemaining().get());
        assertFalse(f.service.isQuotaExhausted());assertTrue(f.service.isEnabled());
    }
    @Test void lateFreeResponseKeepsItsOriginalLane()throws Exception{
        var f=fixture(FREE,BASE);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try{
            Future<?> free=pool.submit(()->{
                try{
                    var req=new MockClientHttpRequest(HttpMethod.GET,URI.create(URL));
                    req.getHeaders().set("X-Subscription-Token",FREE);
                    f.interceptor.intercept(req,new byte[0],(r,b)->{
                        arrived.countDown();
                        try{if(!release.await(3,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");}
                        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new java.io.IOException(ex);}
                        return response(HttpStatus.OK,"2, 20","1, 0");
                    });
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
            assertTrue(arrived.await(2,TimeUnit.SECONDS));
            receive(f,BASE,response(HttpStatus.OK,"2, 0","1, 0"));
            release.countDown();free.get(3,TimeUnit.SECONDS);gate(f);
            assertTrue(f.service.isEnabled());assertFalse(f.service.isCoolingDown());
            assertEquals(0,f.service.monthlyRemaining().get());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void unlimitedMonthZeroDoesNotExhaustFreeReservation()throws Exception{
        var f=fixture(FREE,BASE);var reservation=f.service.tryReserveFreeTierQuota();
        var r=response(HttpStatus.OK,"2, 0","1, 0");
        receive(f,FREE,r);f.service.completeFreeTierQuota(reservation,r.getHeaders(),true);
        assertEquals(19,f.service.monthlyRemaining().get());assertFalse(f.service.isQuotaExhausted());
    }
    @Test void free429DoesNotBecomeProviderWideBackoff()throws Throwable{
        var f=fixture(FREE,BASE);receive(f,FREE,response(HttpStatus.TOO_MANY_REQUESTS,"2, 20","0, 19"));
        assertFalse(f.service.isQuotaExhausted());assertFalse(f.service.isCoolingDown());
        var backoff=new RateLimitBackoffCoordinator(new MockEnvironment());
        var aspect=new ProviderRateLimitBackoffAspect(backoff,f.state);
        var pjp=mock(ProceedingJoinPoint.class);when(pjp.getTarget()).thenReturn(f.service);
        when(pjp.proceed()).thenReturn(new BraveSearchResult(java.util.List.of(),
                BraveSearchResult.Status.HTTP_429,429,2000L,"fixture",1L));
        aspect.aroundBraveSearchWithMeta(pjp);
        assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE).shouldSkip());
        assertEquals(19,f.service.monthlyRemaining().get());
    }
    @Test void bothFiniteLanesExhaustedDisableUntilMonthRollover()throws Exception{
        var f=fixture(FREE,BASE);
        receive(f,FREE,response(HttpStatus.OK,"2, 20","1, 0"));
        receive(f,BASE,response(HttpStatus.OK,"2, 100","1, 0"));gate(f);
        assertFalse(f.service.isEnabled());
        f.service.resetQuotaForMonthRollover(LocalDate.now().plusMonths(1));
        assertTrue(f.service.isEnabled());assertEquals(20,f.service.monthlyRemaining().get());
    }
    @Test void identicalKeysDoNotInventIndependentBaseQuota()throws Exception{
        var f=fixture(FREE,FREE);
        receive(f,FREE,response(HttpStatus.OK,"2, 20","1, 0"));gate(f);
        assertFalse(f.service.isEnabled());
    }
    @Test void disabledConfigurationRemainsDisabled()throws Exception{
        var f=fixture(FREE,BASE);ReflectionTestUtils.setField(f.service,"configEnabled",false);f.service.init();
        receive(f,BASE,response(HttpStatus.OK,"2, 100","1, 99"));gate(f);
        assertFalse(f.service.isEnabled());assertEquals("disabled_by_config",f.service.disabledReason());
    }
}

