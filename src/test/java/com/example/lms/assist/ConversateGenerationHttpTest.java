package com.example.lms.assist;

import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ConversateGenerationHttpTest {
    static final String TEXT="고객에게 안내하는 제품 환불 절차에 대한 자세한 설명을 확인하고 관련된 내용을 상담 직원과 함께 검토할 수 있으며 미개봉 제품은 7일 이내 환불이 가능합니다.";
    static final String CARD="미개봉 제품은 7일 이내 환불이 가능합니다.";
    static ServletWebServerApplicationContext context;
    @BeforeAll static void boot()throws Exception{context=Fixture.boot("0");ConversateHttpTest.base="http://127.0.0.1:"+context.getWebServer().getPort();}
    @AfterAll static void close(){if(context!=null)context.close();}
    @Test void authorizedPreparationToStructuredNativeCallToLiveCardAndDuplicateSuppression()throws Exception{
        var c=new ConversateHttpTest.Client("fixture");var s=c.post("/api/assist/sessions","{}");String path="/api/assist/sessions/"+s.path("assistId").asText();
        s=c.post(path+"/materials","{\"epoch\":"+s.path("epoch").asLong()+",\"sessionId\":\"fixture\",\"sourceIds\":[\"fixture-refund\"]}");long epoch=s.path("epoch").asLong();
        String body="{\"epoch\":"+epoch+",\"utterance\":{\"utteranceId\":\"u1\",\"questionId\":\"q1\",\"revision\":1,\"isFinal\":true,\"text\":\"환불 조건은 무엇인가요?\"}}";
        c.post(path+"/utterance",body);com.fasterxml.jackson.databind.JsonNode result=null;long deadline=System.nanoTime()+4_000_000_000L;
        do{result=ConversateHttpTest.JSON.readTree(c.http.send(c.request(path).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).body());if(result.path("metrics").path("samples").asInt()>0)break;Thread.sleep(10);}while(System.nanoTime()<deadline);
        assertEquals("GENERATED",result.path("reason").asText());assertEquals(CARD,result.path("card").path("text").asText());assertEquals("fixture-refund",result.path("card").path("sourceIds").get(0).asText());assertEquals(1,result.path("metrics").path("generationAttempts").asInt());
        var repeated=c.post(path+"/utterance",body);assertEquals(1,repeated.path("metrics").path("generationAttempts").asInt());assertEquals(1,repeated.path("metrics").path("duplicates").asInt());
        assertEquals(404,new ConversateHttpTest.Client("other").getStatus(path));
        c.post(path+"/control","{\"epoch\":"+epoch+",\"action\":\"stop\"}");assertEquals(404,c.getStatus(path));
    }
    @Test void preparedFixtureCannotBeReadByOtherAccount()throws Exception{
        var c=new ConversateHttpTest.Client("other");assertEquals(403,c.getStatus("/api/assist/materials?sessionId=fixture"));
    }
    @Test void dataAccessAndTransactionBoundaryFailuresReturnRedactedNoStoreUnavailable()throws Exception{
        var c=new ConversateHttpTest.Client("fixture");
        for(String session:List.of("unavailable","tx-unavailable")){
            var response=c.http.send(c.request("/api/assist/materials?sessionId="+session).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(503,response.statusCode());assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals("material_index_unavailable",ConversateHttpTest.JSON.readTree(response.body()).path("reason").asText());assertFalse(response.body().contains("synthetic-private-detail"));
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({ConversateHttpTest.Fixture.class,ConversateLocalCardGenerator.class})
    public static class Fixture {
        @Bean PreparedMaterialReader prepared(@org.springframework.beans.factory.annotation.Value("${conversate.fixture.material-delay-ms:0}") long materialDelayMs){return new PreparedMaterialReader(){
            void check(String user,String session){if(!user.equals("fixture"))throw ConversateSessionService.error(org.springframework.http.HttpStatus.FORBIDDEN,"material_owner_denied");if(session.equals("unavailable"))throw new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail");if(session.equals("tx-unavailable"))throw new org.springframework.transaction.CannotCreateTransactionException("synthetic-private-detail");if(!session.equals("fixture"))throw ConversateSessionService.error(org.springframework.http.HttpStatus.FORBIDDEN,"material_owner_denied");}
            public List<Choice> choices(String user,String session){check(user,session);return List.of(new Choice("fixture-refund","합성 환불 준비 자료"));}
            public List<Material> read(String user,String session,List<String> ids){check(user,session);if(!ids.equals(List.of("fixture-refund")))throw ConversateSessionService.error(org.springframework.http.HttpStatus.NOT_FOUND,"material_missing");try{Thread.sleep(Math.max(0,Math.min(10000,materialDelayMs)));}catch(InterruptedException e){Thread.currentThread().interrupt();throw ConversateSessionService.error(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"fixture_interrupted");}return List.of(new Material("fixture-refund",TEXT));}
        };}
        static ServletWebServerApplicationContext boot(String port)throws Exception{return boot(port,"0");}
        static ServletWebServerApplicationContext boot(String port,String materialDelayMs)throws Exception{
            var upstream=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/api/chat",e->{e.getRequestBody().readAllBytes();byte[] body=ConversateHttpTest.JSON.writeValueAsBytes(Map.of("message",Map.of("content",ConversateLocalCardGeneratorTest.reply(CARD,"[\"s0\"]","[]"))));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});upstream.start();
            try{
                var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
                var ctx=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties","--server.address=127.0.0.1","--server.port="+port,"--conversate.enabled=true","--conversate.fixture-enabled=true","--conversate.fixture.material-delay-ms="+Math.max(0,Math.min(10000,Long.parseLong(materialDelayMs))),"--conversate.generation.enabled=true","--conversate.generation.model=qwen3:fixture","--conversate.generation.base-url=http://127.0.0.1:"+upstream.getAddress().getPort(),"--spring.main.banner-mode=off","--logging.level.root=ERROR","--server.servlet.session.persistent=false");
                ctx.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent)upstream.stop(0);});return ctx;
            }catch(Exception failure){upstream.stop(0);throw failure;}
        }
        public static void main(String[] args)throws Exception{boot(args.length==0?"18087":args[0],args.length<2?"0":args[1]);}
    }
    /** Explicit bounded local-only probe; synthetic evidence, categorical/count output only. */
    public static class LocalProbe {
        public static void main(String[] args){
            if(args.length!=2)throw new IllegalArgumentException("expected_loopback_base_and_installed_model");
            var generator=new ConversateLocalCardGenerator(true,args[0],args[1],4000);
            var evidence=List.of(new ConversateCardPrompt.Evidence("e1","synthetic-refund",TEXT));
            for(int n=1;n<=3;n++){var r=generator.generate("환불 조건은 무엇인가요?",evidence,true,System.currentTimeMillis());System.out.println("LOCAL_PROBE sample="+n+" reason="+r.reason()+" attempts="+r.attempts()+" elapsedMs="+r.elapsedMs()+" decision="+r.card().decision()+" cardChars="+r.card().text().codePointCount(0,r.card().text().length())+" sources="+r.card().sourceIds().size());if(!r.reason().equals("GENERATED"))break;}
        }
    }
}
