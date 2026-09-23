package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SonioxNodeTransportTest {
    @TempDir Path root;
    @Test void actualJavaWebsocketAndNativeSdkCarryAudioAckAndFinalAndReleaseCapacity() throws Exception {
        var json=new ObjectMapper();
        var manager=spy(new SonioxSidecarManager(new MockEnvironment().withProperty("conversate.asr.sidecar.runtime-directory",root.toString()),json,null));
        Process process=null;
        try {
            var prepare=SonioxSidecarManager.class.getDeclaredMethod("prepareBundle");prepare.setAccessible(true);
            Path bundle=(Path)prepare.invoke(manager);
            Files.copy(Path.of("src/test/node/soniox-sidecar-wire-fixture.mjs"),bundle.resolve("wire-fixture.mjs"));
            var builder=new ProcessBuilder("node","wire-fixture.mjs").directory(bundle.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(k->!Set.of("PATH","PATHEXT","SYSTEMROOT","WINDIR","TEMP","TMP","USERPROFILE").contains(k.toUpperCase(Locale.ROOT)));
            String bearer=UUID.randomUUID().toString()+UUID.randomUUID();
            builder.environment().put("AWX_SONIOX_SIDECAR_TOKEN",bearer);
            process=builder.start();var input=process.inputReader();
            String announcement=CompletableFuture.supplyAsync(()->{try{return input.readLine();}catch(Exception e){throw new CompletionException(e);}}).get(10,TimeUnit.SECONDS);
            var announced=json.readTree(announcement);
            var endpoint=new SonioxSidecarManager.Endpoint(announced.path("port").asInt(),bearer);
            int upstreamPort=announced.path("upstreamPort").asInt();
            assertTrue(upstreamPort>0);
            doReturn(endpoint).when(manager).endpoint();doReturn(true).when(manager).configured();
            for(int attempt=0;attempt<2;attempt++) {
                var events=new CopyOnWriteArrayList<JsonNode>();var errors=new CopyOnWriteArrayList<String>();
                var transcript=new CompletableFuture<JsonNode>();
                var transport=manager.connectAdmittedStream(e->{events.add(e);if(e.path("final").asBoolean())transcript.complete(e);},errors::add);
                try {
                    transport.send(json.writeValueAsString(Map.of("seq",0,"pcm",Base64.getEncoder().encodeToString(new byte[640]))));
                    assertEquals("안녕하세요",transcript.get(5,TimeUnit.SECONDS).path("text").asText());
                    assertEquals(1,events.stream().filter(e->e.path("final").asBoolean()).count());
                    assertTrue(events.stream().anyMatch(e->e.path("type").asText().equals("ack")&&e.path("seq").asInt(-1)==0));
                    assertTrue(errors.isEmpty());
                    assertTrue(events.stream().anyMatch(e->"node_sdk".equals(e.path("runtime").path("transport").asText())));
                    transport.finish().get(4,TimeUnit.SECONDS);
                    assertEquals(2,events.stream().filter(e->e.path("final").asBoolean()).count());
                    var last=events.stream().filter(e->e.path("final").asBoolean()).reduce((a,b)->b).orElseThrow();
                    assertEquals("끝",last.path("text").asText());assertEquals("끝",last.path("words").get(0).path("text").asText());
                    assertTrue(errors.isEmpty());
                } finally {transport.close().get(1,TimeUnit.SECONDS);}
                assertFalse(transport.alive());
                // Observe the server's capacity release before opening another stream.
                var http=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);int active=1;
                while(active!=0&&System.nanoTime()<deadline) {
                    var request=java.net.http.HttpRequest.newBuilder(endpoint.httpUri("/health")).header("Authorization","Bearer "+bearer).timeout(Duration.ofSeconds(1)).build();
                    active=json.readTree(http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).body()).path("sessions").asInt(-1);
                    if(active!=0)Thread.sleep(10);
                }
                assertEquals(0,active);
                // Observe the SDK's upstream socket closing before fixture teardown can terminate it.
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);int closed=0;
                while(closed!=attempt+1&&System.nanoTime()<deadline) {
                    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+upstreamPort+"/stats")).timeout(Duration.ofSeconds(1)).build();
                    var stats=json.readTree(http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).body());
                    assertEquals(attempt+1,stats.path("opened").asInt());
                    assertEquals(attempt+1,stats.path("finishRequests").asInt());
                    closed=stats.path("closed").asInt();
                    if(closed!=attempt+1)Thread.sleep(10);
                }
                assertEquals(attempt+1,closed,"SDK must close each upstream socket before fixture teardown");
            }
            String[] reasons={"ASR_AUTH_FAILED","ASR_QUOTA_EXCEEDED","ASR_RATE_LIMITED","ASR_AUDIO_FORMAT_INVALID","ASR_PROVIDER_DISCONNECTED"};
            var http=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
            for(int scenario=1;scenario<=reasons.length;scenario++){
                var failure=new CompletableFuture<String>();var events=new CopyOnWriteArrayList<JsonNode>();
                var transport=manager.connectAdmittedStream(events::add,failure::complete);
                try{
                    byte[] pcm=new byte[640];pcm[0]=(byte)scenario;
                    transport.send(json.writeValueAsString(Map.of("seq",0,"pcm",Base64.getEncoder().encodeToString(pcm))));
                    assertEquals(reasons[scenario-1],failure.get(5,TimeUnit.SECONDS));
                    assertTrue(events.stream().noneMatch(e->e.path("type").asText().equals("finished")),"1000 is not finished evidence");
                }finally{transport.close().get(1,TimeUnit.SECONDS);}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);int active=-1;
                do{
                    var request=java.net.http.HttpRequest.newBuilder(endpoint.httpUri("/health")).header("Authorization","Bearer "+bearer).timeout(Duration.ofSeconds(1)).build();
                    active=json.readTree(http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).body()).path("sessions").asInt(-1);
                    if(active!=0)Thread.sleep(10);
                }while(active!=0&&System.nanoTime()<until);
                assertEquals(0,active);
            }
            // The fallback upstream belongs to this Java test and survives the owned Node failure.
            var fallbackBytes=new java.util.concurrent.atomic.AtomicInteger();var fallbackFrames=new java.util.concurrent.atomic.AtomicInteger();
            var fallbackServer=reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0).route(routes->routes.get("/stt",(request,response)->response.sendWebsocket((in,out)->
                in.receiveFrames().concatMap(frame->{
                    if(frame instanceof io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame binary){fallbackFrames.incrementAndGet();fallbackBytes.addAndGet(binary.content().readableBytes());}
                    if(frame instanceof io.netty.handler.codec.http.websocketx.TextWebSocketFrame text&&text.text().isEmpty())return out.sendString(reactor.core.publisher.Flux.just("{\"tokens\":[{\"text\":\"새 음성\",\"is_final\":true,\"start_ms\":0,\"end_ms\":40},{\"text\":\"<end>\",\"is_final\":true}]}","{\"finished\":true}")).then();
                    return reactor.core.publisher.Mono.empty();
                }).then()))).bindNow();
            try{
                var ctor=com.example.lms.service.stt.SonioxSttService.class.getDeclaredConstructor(ObjectMapper.class,String.class,boolean.class,String.class,String.class,org.springframework.web.reactive.socket.client.WebSocketClient.class,java.net.URI.class);ctor.setAccessible(true);
                var service=ctor.newInstance(json,"synthetic",true,"stt-rt-v5","us",new org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient(),java.net.URI.create("ws://127.0.0.1:"+fallbackServer.port()+"/stt"));
                var events=new CopyOnWriteArrayList<JsonNode>();var errors=new CopyOnWriteArrayList<String>();var legacyReady=new CompletableFuture<Void>();var primaryFinal=new CompletableFuture<Void>();var legacyFinal=new CompletableFuture<JsonNode>();
                var transition=new FailoverAsrTransport(json,manager::connectAdmittedStream,(e,f)->new SonioxAsrTransport(json,service,e,f),e->{
                    events.add(e);if(e.path("type").asText().equals("ready")&&e.path("runtime").path("reason").asText().equals("sidecar_failed_legacy"))legacyReady.complete(null);
                    if(e.path("final").asBoolean()){if(e.path("utteranceId").asText().startsWith("legacy-"))legacyFinal.complete(e);else primaryFinal.complete(null);}
                },errors::add,reason->reason.equals("ASR_SIDECAR_FAILED"));
                try{
                    transition.send(json.writeValueAsString(Map.of("seq",0,"pcm",Base64.getEncoder().encodeToString(new byte[640]))));primaryFinal.get(5,TimeUnit.SECONDS);
                    process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));legacyReady.get(5,TimeUnit.SECONDS);
                    transition.send(json.writeValueAsString(Map.of("seq",1,"pcm",Base64.getEncoder().encodeToString(new byte[1280]))));
                    FailoverAsrTransportTest.await(()->fallbackFrames.get()==1);
                    transition.finish().get(4,TimeUnit.SECONDS);var fin=legacyFinal.get(1,TimeUnit.SECONDS);
                    assertEquals("새 음성",fin.path("text").asText());assertEquals(1,fallbackFrames.get());assertEquals(1280,fallbackBytes.get(),"old Node PCM must not be replayed to Java");
                    assertTrue(fin.path("runtime").path("fallbackGapMs").asLong(-1)>=0);assertTrue(errors.isEmpty());assertFalse(transition.alive());
                }finally{transition.close().get(1,TimeUnit.SECONDS);}
            }finally{fallbackServer.disposeNow();}
        } finally {if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}manager.stop();}
    }
}
