package com.example.lms.assist;

import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversateHttpTest {
    static ServletWebServerApplicationContext context;
    static String base;
    static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void boot() {
        context=Fixture.boot("0");base="http://127.0.0.1:"+context.getWebServer().getPort();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @Test void realAuthenticatedSseDisplaysTwoFixtureCardsAndRejectsOtherOwner() throws Exception {
        var client=new Client("fixture");var s=client.post("/api/assist/sessions","{}");
        String id=s.path("assistId").asText();long epoch=s.path("epoch").asLong();
        var request=client.request("/api/assist/sessions/"+id+"/output?epoch="+epoch).GET().build();
        var response=client.http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
        try(var input=response.body()) {
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,StandardCharsets.UTF_8));
            client.post("/api/assist/sessions/"+id+"/fixture","{\"epoch\":"+epoch+"}");
            var seen=new ArrayList<String>();
            var future=java.util.concurrent.CompletableFuture.runAsync(()->{
                try {String line;while((line=reader.readLine())!=null){if(line.startsWith("data:")){var card=JSON.readTree(line.substring(5)).path("card");String body=card.path("text").asText();if(body.startsWith("테스트 답변")&&!seen.contains(body))seen.add(body);if(seen.size()==2)return;}}}
                catch(Exception e){throw new RuntimeException("fixture_sse_read_failed");}
            });future.get(6,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("테스트 답변 1\n자동 표시 연결을 확인합니다.","테스트 답변 2\n추가 동작 없이 갱신되었습니다."),seen);
            var other=new Client("other");assertEquals(404,other.getStatus("/api/assist/sessions/"+id));
            client.post("/api/assist/sessions/"+id+"/control","{\"epoch\":"+epoch+",\"action\":\"stop\"}");
            assertEquals(404,client.getStatus("/api/assist/sessions/"+id));
        }
    }
    @Test void authenticatedShellAssetsUseSameOriginAndExpectedMediaTypes() throws Exception {
        var client=new Client("fixture");
        var page=client.http.send(client.request("/conversate").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,page.statusCode());
        assertTrue(page.body().contains("href=\"/conversate/manifest.webmanifest\""));
        assertTrue(page.body().contains("href=\"/conversate/favicon.png\""));
        var manifest=client.http.send(client.request("/conversate/manifest.webmanifest").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,manifest.statusCode());
        assertEquals("application/manifest+json",manifest.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        assertEquals("/conversate?display",JSON.readTree(manifest.body()).path("start_url").asText());
        var icon=client.http.send(client.request("/conversate/favicon.png").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,icon.statusCode());assertEquals("image/png",icon.headers().firstValue("Content-Type").orElse(""));
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(icon.body()));
        assertNotNull(image);assertEquals(128,image.getWidth());assertEquals(128,image.getHeight());
        var anonymous=HttpClient.newHttpClient();
        assertEquals(401,anonymous.send(HttpRequest.newBuilder(URI.create(base+"/conversate/manifest.webmanifest")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test void missingAuthenticationAndCsrfCannotStartAssist() throws Exception {
        var http=HttpClient.newHttpClient();
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/assist/bootstrap")).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(401,response.statusCode());
        var client=new Client("fixture");
        assertEquals(403,client.http.send(client.request("/api/assist/sessions").POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test void preparedTextAndBackchannelUseSameLiveStreamWithoutAChatSession() throws Exception {
        var c=new Client("fixture");var s=c.post("/api/assist/sessions","{}");String path="/api/assist/sessions/"+s.path("assistId").asText();
        s=c.post(path+"/fixture-materials","{\"epoch\":"+s.path("epoch").asLong()+"}");long epoch=s.path("epoch").asLong();
        c.post(path+"/utterance","{\"epoch\":"+epoch+",\"utterance\":{\"utteranceId\":\"u1\",\"questionId\":\"q1\",\"revision\":1,\"isFinal\":true,\"text\":\"보증 기간은 얼마인가요?\"}}");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);JsonNode result;
        do{result=JSON.readTree(c.http.send(c.request(path).GET().build(),HttpResponse.BodyHandlers.ofString()).body());if(result.path("card").isObject())break;Thread.sleep(10);}while(System.nanoTime()<deadline);
        assertEquals("SHOW",result.path("card").path("decision").asText());assertEquals("fixture-warranty",result.path("card").path("sourceIds").get(0).asText());
        var back=c.post(path+"/utterance","{\"epoch\":"+epoch+",\"utterance\":{\"utteranceId\":\"u2\",\"questionId\":\"q2\",\"revision\":1,\"isFinal\":true,\"text\":\"아, 네\"}}");
        assertEquals(1,back.path("metrics").path("started").asInt());assertEquals(result.path("card"),back.path("card"));
        c.post(path+"/control","{\"epoch\":"+epoch+",\"action\":\"stop\"}");
    }
    @Test void liveJsonBodyIsBoundedBeforeDeserialization() throws Exception {
        var c=new Client("fixture");var response=c.http.send(c.request("/api/assist/sessions/unknown/utterance").header(c.header,c.csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("x".repeat(16385))).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(413,response.statusCode());
    }
    @Test void bootstrapReadinessUsesOnlyAllowlistedManagerObservations() {
        try(var sessions=new ConversateSessionService()){
            var controller=new ConversateController(sessions);var request=new org.springframework.mock.web.MockHttpServletRequest();
            var auth=new org.springframework.security.authentication.TestingAuthenticationToken("fixture","unused","ROLE_USER");
            var absent=JSON.valueToTree(controller.bootstrap(auth,request).getBody());assertFalse(absent.path("ollama").path("observed").asBoolean(true));
            var manager=org.mockito.Mockito.mock(com.example.lms.config.LocalLlmProcessManager.class);
            org.mockito.Mockito.when(manager.diagnostics()).thenReturn(Map.of("state","READY","modelReady",true,"serviceResponding",true,"host","private-host","token","private-token"));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",manager);
            var observed=JSON.valueToTree(controller.bootstrap(auth,request).getBody()).path("ollama");
            assertTrue(observed.path("observed").asBoolean());assertTrue(observed.path("modelReady").asBoolean());assertEquals("READY",observed.path("state").asText());
            assertFalse(observed.has("host"));assertFalse(observed.has("token"));assertFalse(observed.toString().contains("private"));
        }
    }
    @Test void receiptRequiresSameOwnerCsrfAndCurrentEpoch() throws Exception {
        var c=new Client("fixture");var s=c.post("/api/assist/sessions","{}");String path="/api/assist/sessions/"+s.path("assistId").asText();
        try(var output=c.http.send(c.request(path+"/output?epoch=1").GET().build(),HttpResponse.BodyHandlers.ofInputStream()).body()){
            String body="{\"epoch\":1,\"version\":1}";
            assertEquals(403,c.http.send(c.request(path+"/ack").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            var other=new Client("other");assertEquals(404,other.http.send(other.request(path+"/ack").header(other.header,other.csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(1,c.post(path+"/ack",body).path("metrics").path("outputAcks").asInt());
            c.post(path+"/control","{\"epoch\":1,\"action\":\"stop\"}");
        }
    }
    static class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        final String user;String csrf,header;
        Client(String user)throws Exception{this.user=user;var b=JSON.readTree(http.send(request("/api/assist/bootstrap").GET().build(),HttpResponse.BodyHandlers.ofString()).body());csrf=b.path("csrfToken").asText();header=b.path("csrfHeader").asText();}
        HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":fixture-only").getBytes(StandardCharsets.UTF_8)));}
        JsonNode post(String path,String body)throws Exception{var r=http.send(request(path).header(header,csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());return JSON.readTree(r.body());}
        int getStatus(String path)throws Exception{return http.send(request(path).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    }
    /** Test-only loopback development host: production assist beans, synthetic users, no DB/provider configuration. */
    @Configuration(proxyBeanMethods=false)
    @EnableWebSecurity
    @Import({ConversateSessionService.class,ConversateController.class,ConversateAsrBridge.class,com.example.lms.api.PublicChatAdmissionGuard.class,com.example.lms.api.PublicRequestBudgetGuard.class,com.example.lms.config.WebMvcConfig.class})
    @ImportAutoConfiguration({org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,JacksonAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class})
    public static class Fixture {
        @Bean com.example.lms.common.ReqLogInterceptor reqLogInterceptor(){return new com.example.lms.common.ReqLogInterceptor();}
        @Bean UserDetailsService users(){return new InMemoryUserDetailsManager(User.withUsername("fixture").password("{noop}fixture-only").roles("USER").build(),User.withUsername("other").password("{noop}fixture-only").roles("USER").build());}
        @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
            var handler=new CsrfTokenRequestAttributeHandler();handler.setCsrfRequestAttributeName("_csrf");
            return http.authorizeHttpRequests(a->a.anyRequest().authenticated()).httpBasic(b->{}).formLogin(f->f.defaultSuccessUrl("/conversate",true)).csrf(c->c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(handler)).build();
        }
        static ServletWebServerApplicationContext boot(String port) {
            var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
            return (ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties","--server.address=127.0.0.1","--server.port="+port,"--conversate.enabled=true","--conversate.fixture-enabled=true","--spring.main.banner-mode=off","--logging.level.root=ERROR","--server.servlet.session.persistent=false");
        }
        public static void main(String[] args){boot(args.length==0?"18086":args[0]);}
    }
}
