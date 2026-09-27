package com.example.lms.service.chat;

import com.example.lms.api.ChatRunClusterController;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatRunClusterHttpTest {
    static final String KEY="synthetic-fixture-key-only-32-characters";
    static DataSource source;
    static ServletWebServerApplicationContext nodeA,nodeB;
    static ChatRunRegistry a,b;
    static ChatRunCluster ca,cb;
    static int portA,portB;
    static final ObjectMapper JSON=new ObjectMapper();
    static final java.net.http.HttpClient HTTP=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @BeforeAll static void boot() throws Exception {
        source=new DriverManagerDataSource("jdbc:h2:mem:cluster"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20260912_05__chat_run_owners.sql")).execute(source);
        portA=freePort();portB=freePort();
        String peers="node-a=http://127.0.0.1:"+portA+";node-b=http://127.0.0.1:"+portB;
        nodeA=Fixture.boot(portA,"node-a",peers);nodeB=Fixture.boot(portB,"node-b",peers);
        a=nodeA.getBean(ChatRunRegistry.class);b=nodeB.getBean(ChatRunRegistry.class);
        ca=nodeA.getBean(ChatRunCluster.class);cb=nodeB.getBean(ChatRunCluster.class);
    }
    static int freePort() throws Exception {try(var s=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
    @AfterAll static void close(){if(nodeB!=null)nodeB.close();if(nodeA!=null)nodeA.close();}
    static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test @Order(1) void wrongNodeCannotBeginSecondOwnerAndAuthorizedAckAndStateReachOwner() {
        var run=a.beginOrJoin(201L).context();
        var conflict=assertThrows(ResponseStatusException.class,()->b.beginOrJoin(201L));
        assertEquals(409,conflict.getStatusCode().value());
        assertEquals(run.clientToken(),b.currentRunToken(201L).orElseThrow());
        assertTrue(b.acknowledgeExact(201L,run.clientToken()));assertTrue(run.awaitClientAcknowledgement(10));
        assertEquals(ChatRunRegistry.Status.RUNNING,b.describeExact(201L,run.clientToken()).orElseThrow().status());
        assertFalse(b.acknowledgeExact(999L,run.clientToken()));
        assertTrue(b.cancelExact(201L,run.clientToken())); assertFalse(b.cancelExact(201L,run.clientToken()));
        verify(nodeA.getBean(ChatHistoryService.class),times(1)).appendMessage(201L,"assistant","Response stopped");
        verifyNoInteractions(nodeB.getBean(ChatHistoryService.class));
    }
    @Test @Order(2) void remoteAndInitialExternalViewerShareOwnerCountWithoutCountingInternalBridge() throws Exception {
        var run=a.beginOrJoin(202L).context();var stopped=new CountDownLatch(1);run.registerCancellationHandle(stopped::countDown);
        var internal=a.attach(run).subscribe(); // Must never keep the external execution alive.
        var initial=a.interactiveClient();initial.bind(run);
        var first=a.interactiveSource(initial,a.attach(run)).subscribe();
        var connected=new CountDownLatch(1);
        a.emit(run,ServerSentEvent.builder(ChatStreamEvent.token("fixture")).build());
        var second=b.attachInteractiveExact(202L,run.clientToken()).orElseThrow().subscribe(e->{if(e.data()!=null)connected.countDown();});
        try {
            assertTrue(connected.await(2,TimeUnit.SECONDS));first.dispose();Thread.sleep(100);
            assertTrue(a.isRunning(202L));assertEquals(1,stopped.getCount());
            long start=System.nanoTime();second.dispose();assertTrue(stopped.await(2,TimeUnit.SECONDS));
            System.out.println("B4_REMOTE_CANCEL_HANDLER_OBSERVED_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            until(()->a.isCancelled(202L) && internal.isDisposed());
        } finally {first.dispose();second.dispose();internal.dispose();}
    }
    @Test @Order(3) void completedReplayRetainsOldTokenAndFinalAckWhileCurrentPointerMovesToOtherNode() {
        var first=a.beginOrJoin(203L).context();
        assertTrue(first.markGenerationSucceeded());assertTrue(first.tryBeginTranscriptCommit());assertTrue(first.markPersisted());
        assertTrue(first.claimTerminalEvent("completed"));assertTrue(first.recordFinalEmit("ok",false));
        a.emit(first,ServerSentEvent.builder(ChatStreamEvent.token("old fixture answer")).build());assertTrue(a.markDone(first));
        var next=b.beginOrJoin(203L).context();
        assertEquals(next.clientToken(),a.currentRunToken(203L).orElseThrow());
        assertFalse(a.describeExact(203L,first.clientToken()).orElseThrow().current());
        assertTrue(b.acknowledgeFinalDeliveryExact(203L,first.clientToken()));
        var replay=b.attachInteractiveExact(203L,first.clientToken()).orElseThrow().collectList().block(Duration.ofSeconds(2));
        assertNotNull(replay);assertEquals(1,replay.stream().filter(e->e.data()!=null).count());
        assertTrue(b.isRunning(203L));b.cancelExact(203L,next.clientToken());
    }
    @Test @Order(4) void unsignedAlteredAndReplayedPeerRequestsCannotReadOrCancel() throws Exception {
        var run=a.beginOrJoin(204L).context();var command=new ChatRunCluster.Command(204L,run.clientToken());
        var unsigned=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+portA+"/api/chat/cluster/state"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(command))).build();
        assertEquals(403,HTTP.send(unsigned,HttpResponse.BodyHandlers.discarding()).statusCode());
        var headers=cb.sign("state",command);ca.authorizePeer("state",command,headers);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->ca.authorizePeer("state",command,headers)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->ca.authorizePeer("cancel",command,cb.sign("state",command))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->ca.authorizePeer("state",new ChatRunCluster.Command(999L,run.clientToken()),cb.sign("state",command))).getStatusCode().value());
        assertTrue(a.isRunning(204L));a.cancelExact(204L,run.clientToken());
    }
    @Test @Order(5) void peerUrlsMustBeOperatorAllowlistedHttpsOrLiteralLoopbackAndNoRedirectOrUserinfo() {
        for(String peer:List.of("http://example.test","https://user:pass@example.test","https://example.test/path","https://example.test/?next=elsewhere"))
            assertThrows(IllegalArgumentException.class,()->new ChatRunCluster(source,"node-a","node-a="+peer,KEY));
        assertThrows(IllegalArgumentException.class,()->new ChatRunCluster(source,"node-a","node-a=https://example.test","short"));
    }
    @Test @Order(6) void leaseLossCancelsExactRunAndCannotStartOrCommitAgain() {
        var run=a.beginOrJoin(205L).context();var stop=new AtomicInteger();run.registerCancellationHandle(stop::incrementAndGet);
        new org.springframework.jdbc.core.JdbcTemplate(source).update("update awx_chat_run_owners set lease_until=TIMESTAMP '2000-01-01 00:00:00' where run_token=?",run.clientToken());
        a.renewOwnerLeases();assertEquals(1,stop.get());assertFalse(run.admitCall(()->fail("late inference")));
        assertFalse(run.tryBeginTranscriptCommit());assertFalse(run.markPersisted());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->b.beginOrJoin(205L)).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->b.attachInteractiveExact(205L,run.clientToken())).getStatusCode().value());
    }
    @Test @Order(7) void sessionDeletionOnOtherNodeFencesOwnerAndPreventsAnyReplacement() {
        var run=a.beginOrJoin(207L).context();var stopped=new AtomicInteger();run.registerCancellationHandle(stopped::incrementAndGet);
        assertTrue(run.tryBeginTranscriptCommit());
        b.cancelSessionForDeletion(207L);
        assertEquals(1,stopped.get());assertFalse(run.runTerminalSideEffect(()->fail("deleted transcript resurrected")));
        assertFalse(a.beginOrJoin(207L).owner());assertFalse(b.beginOrJoin(207L).owner());
        var thirdCluster=new ChatRunCluster(source,"node-b","node-b=http://127.0.0.1:"+portB,KEY);
        var third=new ChatRunRegistry();third.replayCapacity=16;third.setCluster(thirdCluster);
        try {assertEquals(409,assertThrows(ResponseStatusException.class,()->third.beginOrJoin(207L)).getStatusCode().value());}
        finally {third.shutdown();thirdCluster.close();}
    }
    @Test @Order(8) void saturatedStreamPoolDoesNotStarveAcknowledgementOrCancellation() throws Exception {
        var relay=new ChatRunCluster(source,"node-b","node-a=http://127.0.0.1:"+portA+";node-b=http://127.0.0.1:"+portB,KEY,1);
        var run=a.beginOrJoin(208L).context();a.emit(run,ServerSentEvent.builder(ChatStreamEvent.token("fixture")).build());
        var attached=new CountDownLatch(1);var stream=relay.attach(208L,run.clientToken()).orElseThrow().subscribe(e->{if(e.data()!=null)attached.countDown();});
        try {
            assertTrue(attached.await(2,TimeUnit.SECONDS));
            assertTrue(relay.control("ready",208L,run.clientToken()));
            assertTrue(relay.control("cancel",208L,run.clientToken()));
            until(()->a.isCancelled(208L));
        } finally {stream.dispose();relay.close();}
    }
    @Test @Order(9) void directoryOutageFailsClosedForNewWorkAndStopsExistingLocalRun() {
        var failed=new AtomicBoolean();
        DataSource isolated=new AbstractDataSource(){
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {if(failed.get())throw new java.sql.SQLException("synthetic_unavailable");return source.getConnection();}
            @Override public java.sql.Connection getConnection(String u,String p) throws java.sql.SQLException{return getConnection();}
        };
        var cluster=new ChatRunCluster(isolated,"node-a","node-a=http://127.0.0.1:"+portA,KEY);
        var registry=new ChatRunRegistry();registry.replayCapacity=16;registry.setCluster(cluster);
        try {
            var run=registry.beginOrJoin(209L).context();var count=new AtomicInteger();run.registerCancellationHandle(count::incrementAndGet);
            failed.set(true);registry.renewOwnerLeases();assertEquals(1,count.get());assertTrue(run.isCancellationRequested());
            assertEquals(503,assertThrows(ResponseStatusException.class,()->registry.beginOrJoin(210L)).getStatusCode().value());
            assertFalse(run.admitCall(()->fail("outage starts inference")));
        } finally {failed.set(false);registry.shutdown();cluster.close();}
    }
    @Test @Order(100) void relayHttpServerLossClosesUpstreamViewerAndCancelsOwner() throws Exception {
        var run=a.beginOrJoin(206L).context();var stop=new CountDownLatch(1);run.registerCancellationHandle(stop::countDown);
        a.emit(run,ServerSentEvent.builder(ChatStreamEvent.token("fixture")).build());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+portB+"/fixture/viewer?session=206&token="+run.clientToken())).GET().build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());
        try(var body=response.body()) {
            assertTrue(body.readNBytes(5).length>0);assertTrue(a.isRunning(206L));
            nodeB.close();nodeB=null;
            assertTrue(stop.await(2,TimeUnit.SECONDS),"relay server shutdown must disconnect its owner subscription");
            assertTrue(a.isCancelled(206L));
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({ChatRunClusterController.class,Viewer.class})
    @ImportAutoConfiguration({org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,JacksonAutoConfiguration.class})
    public static class Fixture {
        @Bean ChatRunCluster cluster(org.springframework.core.env.Environment env){return new ChatRunCluster(source,env.getRequiredProperty("fixture.instance"),env.getRequiredProperty("fixture.peers"),KEY);}
        @Bean ChatRunRegistry registry(){return new ChatRunRegistry();}
        @Bean ChatHistoryService history(){return mock(ChatHistoryService.class);}
        static ServletWebServerApplicationContext boot(int port,String instance,String peers) {
            var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
            return (ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/cluster-fixture-empty.properties","--server.address=127.0.0.1","--server.port="+port,"--chat.cluster.enabled=true","--fixture.instance="+instance,"--fixture.peers="+peers,"--spring.main.banner-mode=off","--logging.level.root=ERROR");
        }
    }
    /** Synthetic data only: represents an already authorized public viewer boundary. */
    @RestController static class Viewer {
        private final ChatRunRegistry registry; Viewer(ChatRunRegistry registry){this.registry=registry;}
        @GetMapping(value="/fixture/viewer",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<ServerSentEvent<ChatStreamEvent>> view(@RequestParam long session,@RequestParam String token){return registry.attachInteractiveExact(session,token).orElseThrow();}
    }
}
