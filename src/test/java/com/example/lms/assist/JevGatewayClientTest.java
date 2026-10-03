package com.example.lms.assist;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Gateway /v1/evaluate 전송 계약 검증 — 루프백 목 서버로만 시험하고 실제 네트워크·실키는 쓰지 않는다. */
class JevGatewayClientTest {
    record Hit(String method,String authorization,String body){}
    final List<Hit> hits=new CopyOnWriteArrayList<>();
    HttpServer server;

    String serve(int status,String body)throws IOException{return serve(status,body,null);}
    String serve(int status,String body,String retryAfter)throws IOException{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/evaluate",exchange->{
            hits.add(new Hit(exchange.getRequestMethod(),exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            if(retryAfter!=null)exchange.getResponseHeaders().set("Retry-After",retryAfter);
            if(status>=300&&status<400){exchange.getResponseHeaders().set("Location","/elsewhere");exchange.sendResponseHeaders(status,-1);}
            else{byte[] data=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);}
            exchange.close();
        });
        server.start();return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/evaluate";
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    static JevDecisionAdvisor.EvalRequest req(String endpoint){
        return new JevDecisionAdvisor.EvalRequest(endpoint,"typesafe-ai/jev","FIXTURE_ENV","확정 질문","focus","RECENT_ONLY",250,3000,8192,65536);
    }
    final JevGatewayClient client=new JevGatewayClient(name->"FIXTURE_ENV".equals(name)?"synthetic-fixture-secret":null);

    @Test void postsEvaluateContractAndParsesChoice()throws Exception{
        var response=client.evaluate(req(serve(200,"{\"model\":\"typesafe-ai/jev\",\"answers\":{\"routeDecision\":{\"choice\":\"HYBRID\"}}}")));
        assertEquals(200,response.httpStatus());assertEquals(JevDecisionAdvisor.Verdict.HYBRID,response.verdict());assertNull(response.failure());
        assertEquals(1,hits.size());var hit=hits.get(0);
        assertEquals("POST",hit.method());assertEquals("Bearer synthetic-fixture-secret",hit.authorization());
        assertTrue(hit.body().contains("\"model\":\"typesafe-ai/jev\""));assertTrue(hit.body().contains("routeDecision"));
        assertTrue(hit.body().contains("\"only\":[\"typesafe-ai\"]"));
        assertFalse(hit.body().contains("\"zeroDataRetention\""),"default request must omit the Pro-only option");
    }
    @Test void explicitZdrOptInIncludesRequestOption()throws Exception{
        var optedIn=new JevGatewayClient(true,name->"FIXTURE_ENV".equals(name)?"synthetic-fixture-secret":null);
        var response=optedIn.evaluate(req(serve(200,
                "{\"model\":\"typesafe-ai/jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}")));
        assertEquals(JevDecisionAdvisor.Verdict.WEB,response.verdict());
        assertEquals(1,hits.size());
        assertTrue(hits.get(0).body().contains("\"zeroDataRetention\":true"));
        assertTrue(hits.get(0).body().contains("\"only\":[\"typesafe-ai\"]"));
    }
    @Test void key401ReportsAuthInvalid()throws Exception{
        var response=client.evaluate(req(serve(401,"{\"error\":{\"message\":\"Authentication failed\"}}")));
        assertEquals(401,response.httpStatus());assertNull(response.verdict());assertEquals("auth_invalid",response.failure());
    }
    @Test void planRelated403ReportsPlanGate()throws Exception{
        var response=client.evaluate(req(serve(403,"{\"error\":{\"message\":\"Zero Data Retention requires a Pro plan\"}}")));
        assertEquals(403,response.httpStatus());assertNull(response.verdict());assertEquals("plan_gate",response.failure());
    }
    @Test void unrelated403ReportsPermissionDenied()throws Exception{
        var response=client.evaluate(req(serve(403,"{\"error\":{\"message\":\"Access denied\"}}")));
        assertEquals(403,response.httpStatus());assertNull(response.verdict());assertEquals("permission_denied",response.failure());
    }
    @Test void billing402UsesRoutingReason()throws Exception{
        var response=client.evaluate(req(serve(402,"{\"error\":{\"message\":\"Billing blocked\"}}")));
        assertEquals(402,response.httpStatus());assertNull(response.verdict());assertEquals("billing-blocked",response.failure());
    }
    @Test void server5xxUsesRoutingReason()throws Exception{
        var response=client.evaluate(req(serve(503,"{\"error\":{\"message\":\"Unavailable\"}}")));
        assertEquals(503,response.httpStatus());assertNull(response.verdict());assertEquals("upstream_error",response.failure());
    }
    @Test void rateLimitCarriesRetryAfter()throws Exception{
        var response=client.evaluate(req(serve(429,"{}",  "7")));
        assertEquals("rate_limited",response.failure());assertEquals(7000L,response.retryAfterMs());
    }
    @Test void redirectIsNotFollowed()throws Exception{
        var response=client.evaluate(req(serve(302,"")));
        assertEquals("redirect",response.failure());assertEquals(1,hits.size(),"Authorization must never follow a redirect");
    }
    @Test void malformedBodyIsInvalidResponse()throws Exception{
        assertEquals("invalid_response",client.evaluate(req(serve(200,"not json"))).failure());
    }
    @Test void missingModelIsUnverified()throws Exception{
        assertEquals("model_unverified",client.evaluate(req(serve(200,
                "{\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"))).failure());
    }
    @Test void nonJevModelIsRejected()throws Exception{
        assertEquals("wrong_model",client.evaluate(req(serve(200,
                "{\"model\":\"other-provider/other-model\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"))).failure());
    }
    @Test void unknownChoiceIsInvalidResponse()throws Exception{
        assertEquals("invalid_response",client.evaluate(req(serve(200,
                "{\"model\":\"typesafe-ai/jev\",\"answers\":{\"routeDecision\":{\"choice\":\"MAGIC\"}}}"))).failure());
    }
    @Test void substringJevModelIsRejected()throws Exception{
        var response=client.evaluate(req(serve(200,
                "{\"model\":\"evil-jev-proxy\",\"answers\":{\"routeDecision\":{\"choice\":\"HYBRID\"}}}")));
        assertEquals("wrong_model",response.failure());assertNull(response.verdict());
    }
    @Test void bareModelNameAliasAccepted()throws Exception{
        var response=client.evaluate(req(serve(200,
                "{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"HYBRID\"}}}")));
        assertEquals(JevDecisionAdvisor.Verdict.HYBRID,response.verdict());assertNull(response.failure());
    }
    @Test void slashJevFromOtherVendorIsRejected()throws Exception{
        for(String model:List.of("other-vendor/jev","evil/jev","typesafe-ai/jev-evil","typesafe-ai/jevx")){
            try{
                var response=client.evaluate(req(serve(200,"{\"model\":\""+model+"\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}")));
                assertEquals("wrong_model",response.failure(),model);assertNull(response.verdict());
            }finally{stop();}
        }
    }
    @Test void serializedBodyMatchesContract()throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=req(serve(200,"{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"));
        for(boolean zdr:List.of(false,true)){
            var transport=new JevGatewayClient(zdr,name->"synthetic-fixture-key");
            assertNotNull(transport.evaluate(request).verdict());
            var body=mapper.readTree(hits.get(hits.size()-1).body());
            assertEquals(mapper.valueToTree("typesafe-ai/jev"),body.get("model"));
            assertEquals(mapper.valueToTree("choice"),body.at("/questions/routeDecision/type"));
            var criteria=body.at("/questions/routeDecision/criteria");
            assertTrue(criteria.isObject());
            var criteriaKeys=new java.util.HashSet<String>();criteria.fieldNames().forEachRemaining(criteriaKeys::add);
            assertEquals(java.util.Set.of("RECENT_ONLY","SCOPED_RAG","WEB","HYBRID","CLARIFY"),criteriaKeys);
            var gateway=body.at("/providerOptions/gateway");
            assertEquals(mapper.valueToTree(List.of("typesafe-ai")),gateway.get("only"));
            if(zdr)assertEquals(mapper.valueToTree(true),gateway.get("zeroDataRetention"));
            else assertFalse(gateway.has("zeroDataRetention"));
            assertEquals(mapper.valueToTree(java.util.Map.of("query",request.question(),"surface",request.surface(),
                    "baselineRoute",request.baseline(),"externalDecisionAllowed",true)),body.get("state"));
        }
        assertEquals(2,hits.size());
    }
    @Test void wrongTypedChoiceIsInvalid()throws Exception{
        for(String answers:List.of("{\"routeDecision\":{\"choice\":1}}","{\"routeDecision\":{\"choice\":{}}}",
                "{\"routeDecision\":{\"choice\":[]}}","{}","[]","1")){
            try{
                var response=client.evaluate(req(serve(200,"{\"model\":\"typesafe-ai/jev\",\"answers\":"+answers+"}")));
                assertEquals("invalid_response",response.failure(),answers);assertNull(response.verdict());
            }finally{stop();}
        }
    }
    @Test void provider403IsNotPlanGate()throws Exception{
        var response=client.evaluate(req(serve(403,
                "{\"error\":{\"message\":\"Forbidden by provider project policy\"}}")));
        assertEquals("permission_denied",response.failure());assertNull(response.verdict());
    }
    @Test void missingKeyNeverCallsServer()throws Exception{
        var unconfigured=new JevGatewayClient(name->null);
        var response=unconfigured.evaluate(req(serve(200,"{}")));
        assertEquals("jev_not_configured",response.failure());assertEquals(0,hits.size());
    }
    @Test void foreignEndpointIsRefusedBeforeNetwork(){
        var response=client.evaluate(req("https://example.com/v1/evaluate"));
        assertEquals("endpoint_not_allowed",response.failure());assertEquals(0,hits.size());
    }
    @Test void httpNonLoopbackIsRefused(){
        var response=client.evaluate(req("http://example.com/v1/evaluate"));
        assertEquals("endpoint_not_allowed",response.failure());assertEquals(0,hits.size());
    }
    @Test void oversizedResponseIsRefused()throws Exception{
        var big=serve(200,"{\"model\":\"typesafe-ai/jev\",\"pad\":\""+"x".repeat(4096)+"\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}");
        var request=new JevDecisionAdvisor.EvalRequest(big,"typesafe-ai/jev","FIXTURE_ENV","q","focus","WEB",250,3000,8192,128);
        assertEquals("oversized_response",client.evaluate(request).failure());
    }
    JevGatewayClient countingClient(java.util.concurrent.atomic.AtomicInteger creations){
        return new JevGatewayClient(false,name->"synthetic-fixture-secret",timeout->{
            creations.incrementAndGet();
            return java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeout))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        });
    }
    @Test void reusesHttpClientAcrossCalls()throws Exception{
        var creations=new java.util.concurrent.atomic.AtomicInteger();
        var cached=countingClient(creations);
        var request=req(serve(200,"{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"HYBRID\"}}}"));
        assertNotNull(cached.evaluate(request).verdict());assertNotNull(cached.evaluate(request).verdict());
        assertEquals(1,creations.get());assertEquals(2,hits.size());
        assertTrue(hits.stream().allMatch(hit->"Bearer synthetic-fixture-secret".equals(hit.authorization())));
    }
    @Test void connectTimeoutChangeRebuildsClient()throws Exception{
        var creations=new java.util.concurrent.atomic.AtomicInteger();
        var cached=countingClient(creations);
        var first=req(serve(200,"{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"));
        var second=new JevDecisionAdvisor.EvalRequest(first.endpoint(),first.model(),first.credentialEnv(),
                first.question(),first.surface(),first.baseline(),300,first.requestTimeoutMs(),first.maxRequestBytes(),first.maxResponseBytes());
        assertNotNull(cached.evaluate(first).verdict());assertNotNull(cached.evaluate(second).verdict());
        assertEquals(2,creations.get());assertEquals(2,hits.size());
    }
    @Test void concurrentFirstCallsCreateOneClient()throws Exception{
        var creations=new java.util.concurrent.atomic.AtomicInteger();
        var cached=countingClient(creations);
        var request=req(serve(200,"{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"));
        var ready=new java.util.concurrent.CountDownLatch(8);
        var start=new java.util.concurrent.CountDownLatch(1);
        var callers=java.util.concurrent.Executors.newFixedThreadPool(8);
        var results=new java.util.ArrayList<java.util.concurrent.Future<JevDecisionAdvisor.EvalResponse>>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try{
            for(int i=0;i<8;i++)results.add(callers.submit(()->{
                ready.countDown();
                assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return cached.evaluate(request);
            }));
            assertTrue(ready.await(Math.max(0,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS));
            start.countDown();
            for(var result:results)assertNotNull(result.get(Math.max(0,deadline-System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS).verdict());
            assertEquals(1,creations.get());assertEquals(8,hits.size());
        }finally{start.countDown();callers.shutdownNow();}
    }
    @Test void credentialSwapUsesNewKeyOnNextRequest()throws Exception{
        var creations=new java.util.concurrent.atomic.AtomicInteger();
        var key=new java.util.concurrent.atomic.AtomicReference<>("synthetic-fixture-A");
        var cached=new JevGatewayClient(false,name->key.get(),timeout->{
            creations.incrementAndGet();
            return java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeout))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        });
        var request=req(serve(200,"{\"model\":\"jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}"));
        assertNotNull(cached.evaluate(request).verdict());
        key.set("synthetic-fixture-B");
        assertNotNull(cached.evaluate(request).verdict());
        assertEquals(2,hits.size());assertEquals(1,creations.get());
        assertEquals("Bearer synthetic-fixture-A",hits.get(0).authorization());
        assertEquals("Bearer synthetic-fixture-B",hits.get(1).authorization());
    }
}
