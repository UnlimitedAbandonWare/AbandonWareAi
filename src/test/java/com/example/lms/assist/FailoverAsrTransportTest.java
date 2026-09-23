package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class FailoverAsrTransportTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void transcriptCallbackCannotBlockConcurrentCaptureCleanup()throws Exception {
        var primary=new Fake();var captureMonitor=new Object();var entered=new CountDownLatch(1);
        var transport=new FailoverAsrTransport(json,primary.factory(),null,event->{
            if(!event.path("type").asText().equals("transcript"))return;
            entered.countDown();synchronized(captureMonitor){ /* capture callback */ }
        },reason->{});
        Thread callback=new Thread(()->primary.events.accept(json.createObjectNode().put("type","transcript")),"fixture-capture-callback");
        callback.setDaemon(true);
        CompletableFuture<Void> cleanup=null;
        try {
            synchronized(captureMonitor){
                callback.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
                cleanup=CompletableFuture.runAsync(()->transport.close().join());
                cleanup.get(500,TimeUnit.MILLISECONDS);
            }
        } finally {
            callback.join(2000);
            if(cleanup!=null)cleanup.get(2,TimeUnit.SECONDS);
            transport.close().get(2,TimeUnit.SECONDS);
        }
    }
    @Test void terminalFallbackCallbackRunsOutsideTransportMonitor()throws Exception {
        var primary=new Fake();
        var reference=new java.util.concurrent.atomic.AtomicReference<FailoverAsrTransport>();
        var result=new CompletableFuture<Boolean>();
        var transport=new FailoverAsrTransport(json,primary.factory(),(e,f)->{throw new IOException("fixture_denied");},e->{},reason->{
            var acquired=new CountDownLatch(1);
            var probe=new Thread(()->{synchronized(reference.get()){acquired.countDown();}},"fixture-monitor-probe");
            probe.setDaemon(true);probe.start();
            try{result.complete(acquired.await(1,TimeUnit.SECONDS));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.complete(false);}
        });
        reference.set(transport);
        try{primary.failure.accept("lost");assertTrue(result.get(3,TimeUnit.SECONDS),"terminal callback must not hold the child monitor");}
        finally{transport.close().get(2,TimeUnit.SECONDS);}
    }
    static class Fake implements ConversateAsrBridge.Transport {
        volatile Consumer<JsonNode> events; Consumer<String> failure; volatile boolean closed; final List<String> sent=new CopyOnWriteArrayList<>();
        public void send(String line){sent.add(line);}
        public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}
        public boolean alive(){return !closed;}
        ConversateAsrBridge.Factory factory(){return (e,f)->{events=e;failure=f;e.accept(new ObjectMapper().createObjectNode().put("type","ready"));return this;};}
    }
    @Test void startupFailureSelectsExistingFactoryOnce()throws Exception {
        var fallback=new Fake();var events=new CopyOnWriteArrayList<JsonNode>();var errors=new CopyOnWriteArrayList<String>();
        var transport=new FailoverAsrTransport(json,(e,f)->{throw new IOException("unavailable");},fallback.factory(),events::add,errors::add);
        try {await(()->fallback.events!=null);transport.send("{\"seq\":0}");await(()->!fallback.sent.isEmpty());assertEquals(List.of("{\"seq\":0}"),fallback.sent);assertTrue(errors.isEmpty());}
        finally{transport.close().get(2,TimeUnit.SECONDS);}assertTrue(fallback.closed);
    }
    @Test void liveFailureFencesOldEventsAndNeverReplaysAlreadySentAudio()throws Exception {
        var primary=new Fake();var fallback=new Fake();var events=new CopyOnWriteArrayList<JsonNode>();var errors=new CopyOnWriteArrayList<String>();
        var transport=new FailoverAsrTransport(json,primary.factory(),fallback.factory(),events::add,errors::add);
        try {
            transport.send("before");primary.failure.accept("lost");await(()->fallback.events!=null);
            primary.events.accept(json.createObjectNode().put("type","transcript").put("text","late").put("utteranceId","a"));
            transport.send("{\"seq\":1}");await(()->!fallback.sent.isEmpty());
            assertEquals(List.of("before"),primary.sent);assertEquals(List.of("{\"seq\":1}"),fallback.sent);assertTrue(primary.closed);
            assertTrue(events.stream().noneMatch(e->e.path("text").asText().equals("late")));
            fallback.failure.accept("also_lost");await(()->!errors.isEmpty());assertEquals(1,errors.size());
        }finally{transport.close().get(2,TimeUnit.SECONDS);}
    }
    @Test void cancellationWhileFallbackLaunchesClosesLateTransport()throws Exception {
        var primary=new Fake();var fallback=new Fake();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var transport=new FailoverAsrTransport(json,primary.factory(),(e,f)->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException x){Thread.currentThread().interrupt();}return fallback;},e->{},e->{});
        primary.failure.accept("lost");assertTrue(entered.await(1,TimeUnit.SECONDS));
        transport.close();release.countDown();await(()->fallback.closed);assertFalse(transport.alive());
    }
    @Test void futureFramesWaitForLegacyReadyAndAckOnlyOnce()throws Exception {
        var primary=new Fake();var fallback=new Fake();var events=new CopyOnWriteArrayList<JsonNode>();
        var transport=new FailoverAsrTransport(json,primary.factory(),(e,f)->{fallback.events=e;fallback.failure=f;return fallback;},events::add,e->{});
        try {
            primary.failure.accept("lost");await(()->fallback.events!=null);
            String frame="{\"seq\":1,\"pcm\":\"AA==\"}";transport.send(frame);
            assertTrue(fallback.sent.isEmpty());assertEquals(1,events.stream().filter(e->e.path("type").asText().equals("ack")).count());
            fallback.events.accept(json.createObjectNode().put("type","ready"));await(()->!fallback.sent.isEmpty());
            assertEquals(List.of(frame),fallback.sent);
            fallback.events.accept(json.createObjectNode().put("type","ack").put("seq",1));
            assertEquals(1,events.stream().filter(e->e.path("type").asText().equals("ack")).count());
        }finally{transport.close();}
    }
    static void await(java.util.function.BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}

    @Test void lostAckIsReleasedAsUncertainAndSubmittedAudioIsNeverReplayed()throws Exception {
        var primary=new Fake();var fallback=new Fake();var events=new CopyOnWriteArrayList<JsonNode>();
        var transport=new FailoverAsrTransport(json,primary.factory(),fallback.factory(),events::add,e->{});
        try {
            transport.send("{\"seq\":0}");primary.failure.accept("process_died_before_ack");
            await(()->fallback.events!=null);
            assertTrue(events.stream().anyMatch(e->e.path("seq").asInt(-1)==0&&e.path("scope").asText().equals("sidecar_delivery_uncertain")));
            transport.send("{\"seq\":1}");await(()->!fallback.sent.isEmpty());
            assertEquals(List.of("{\"seq\":1}"),fallback.sent);
        }finally{transport.close();}
    }
    @Test void synchronousSendFailureStartsLegacyWithoutFailingTheCapture()throws Exception {
        ConversateAsrBridge.Transport primary=new ConversateAsrBridge.Transport(){
            public void send(String line)throws IOException{throw new IOException("fixture_send_failure");}
            public CompletableFuture<Void> close(){return CompletableFuture.completedFuture(null);}
            public boolean alive(){return false;}
        };
        var fallback=new Fake();var errors=new CopyOnWriteArrayList<String>();
        var transport=new FailoverAsrTransport(json,(e,f)->primary,fallback.factory(),e->{},errors::add);
        try{assertDoesNotThrow(()->transport.send("{\"seq\":0}"));await(()->fallback.events!=null);assertTrue(errors.isEmpty());}
        finally{transport.close();}
    }
    @Test void actualBridgeKeepsCaptureActiveAcrossLostAckAndAcceptsNextLegacyChunk()throws Exception {
        var primary=new Fake();var legacy=new ConversateAsrBridgeTest.FakeTransport();
        var executor=Executors.newSingleThreadExecutor();
        try(var sessions=new ConversateSessionService()) {
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->new FailoverAsrTransport(json,primary.factory(),
                (e,f)->{legacy.events=e;e.accept(json.createObjectNode().put("type","ready"));return legacy;},events,failure));
            var run=sessions.start("owner");
            try {
                bridge.start("owner",run.assistId(),run.epoch());
                String pcm=Base64.getEncoder().encodeToString(new byte[640]);
                var pending=executor.submit(()->bridge.chunk("owner",run.assistId(),run.epoch(),0,pcm));
                await(()->!primary.sent.isEmpty());primary.failure.accept("lost_ack");
                assertNotEquals("PAUSED",pending.get(2,TimeUnit.SECONDS).state());
                await(()->legacy.events!=null);
                bridge.chunk("owner",run.assistId(),run.epoch(),1,pcm);
                assertEquals(1,legacy.writes);assertEquals(1,bridge.activeCount());
            }finally{bridge.close();}
        }finally{executor.shutdownNow();}
    }
}
