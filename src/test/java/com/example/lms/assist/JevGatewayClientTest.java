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
        assertTrue(hit.body().contains("\"zeroDataRetention\":true"));
    }
    @Test void authFailureReportsAuthBlocked()throws Exception{
        var response=client.evaluate(req(serve(401,"{\"error\":{\"message\":\"Authentication failed\"}}")));
        assertEquals(401,response.httpStatus());assertNull(response.verdict());assertEquals("auth_blocked",response.failure());
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
}
