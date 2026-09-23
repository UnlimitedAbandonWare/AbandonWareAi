package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ConversateAsrBridgeTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void configuredPathsMustBeAbsoluteAndHaveExpectedShape(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var python=java.nio.file.Files.createFile(dir.resolve("python.exe"));var script=java.nio.file.Files.createFile(dir.resolve("stream.py"));
        var model=java.nio.file.Files.createDirectory(dir.resolve("model"));
        String[][] invalid={{null,script.toString(),model.toString()},{"",script.toString(),model.toString()},
                {"python.exe",script.toString(),model.toString()},{python.toString(),"stream.py",model.toString()},
                {python.toString(),script.toString(),"model"},{dir.resolve("missing").toString(),script.toString(),model.toString()},
                {python.toString(),dir.resolve("missing").toString(),model.toString()},{python.toString(),script.toString(),dir.resolve("missing").toString()},
                {model.toString(),script.toString(),model.toString()},{python.toString(),model.toString(),model.toString()},
                {python.toString(),script.toString(),script.toString()},{"\u0000",script.toString(),model.toString()}};
        try(var sessions=new ConversateSessionService()){
            var costs=new AtomicInteger();var s=sessions.start("owner",costs::incrementAndGet);
            for(String[] paths:invalid){var bridge=new ConversateAsrBridge(sessions,json,true,paths[0],paths[1],paths[2],2);try{
                assertFalse(bridge.available());assertEquals(503,assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch())).getStatusCode().value());
                assertEquals(0,bridge.activeCount());assertEquals(0,costs.get());
            }finally{bridge.close();}}
            for(int threads:new int[]{0,-1,9,Integer.MAX_VALUE}){
                var invalidCpu=new ConversateAsrBridge(sessions,json,true,python.toString(),script.toString(),model.toString(),threads);
                try{assertFalse(invalidCpu.available());assertEquals(503,assertThrows(ResponseStatusException.class,()->invalidCpu.start("owner",s.assistId(),s.epoch())).getStatusCode().value());assertEquals(0,costs.get());assertEquals(0,invalidCpu.activeCount());}finally{invalidCpu.close();}
            }
            var configured=new ConversateAsrBridge(sessions,json,true,python.toString(),script.toString(),model.toString(),8);
            var disabled=new ConversateAsrBridge(sessions,json,false,python.toString(),script.toString(),model.toString(),2);
            try{assertTrue(configured.available());assertFalse(disabled.available());assertEquals(0,configured.activeCount());}finally{configured.close();disabled.close();}
        }
    }
    @Test void importFailureBeforeTransportPublicationReturns503AndCleansOwnedChild() {
        try(var sessions=new ConversateSessionService()){
            var fake=new FakeTransport();var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{failure.accept("ASR_IMPORT_FAILED");return fake;});
            var s=sessions.start("owner");try{
                assertEquals(503,assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch())).getStatusCode().value());
                assertTrue(fake.closed);assertEquals(0,bridge.activeCount());assertEquals("PAUSED",sessions.status("owner",s.assistId()).state());
            }finally{bridge.close();}
        }
    }
    @Test void readyTimeoutReturns503AndCleansOwnedChild() {
        try(var sessions=new ConversateSessionService()){
            var fake=new FakeTransport();var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->fake);var s=sessions.start("owner");
            long start=System.nanoTime();try{
                assertEquals(503,assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch())).getStatusCode().value());
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)>=29);assertTrue(fake.closed);assertEquals(0,bridge.activeCount());
            }finally{bridge.close();}
        }
    }
    @Test void failedChildRetainsCapacityUntilOwnedProcessExitIsObserved() {
        try(var sessions=new ConversateSessionService()){
            var exit=new CompletableFuture<Void>();var alive=new AtomicBoolean(true);
            var transport=new ConversateAsrBridge.Transport(){public void send(String line){}public CompletableFuture<Void> close(){return exit;}public boolean alive(){return alive.get();}};
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{failure.accept("ASR_IMPORT_FAILED");return transport;});var s=sessions.start("owner");
            try{assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch()));assertEquals(1,bridge.activeCount());
                alive.set(false);exit.complete(null);assertEquals(0,bridge.activeCount());
            }finally{alive.set(false);exit.complete(null);bridge.close();}
        }
    }
    @Test void rejectedCostNeverLaunchesChildAndStopDoesNotWaitForBlockedCost() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var starts=new AtomicInteger();
        try(var sessions=new ConversateSessionService()){
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{starts.incrementAndGet();return new FakeTransport();});
            var s=sessions.start("owner",()->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"chat_admission_unavailable");});
            var executor=Executors.newSingleThreadExecutor();
            try{
                var pending=executor.submit(()->assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch())));
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(500),()->sessions.control("owner",s.assistId(),s.epoch(),"stop"));
                release.countDown();assertEquals(503,pending.get(1,TimeUnit.SECONDS).getStatusCode().value());assertEquals(0,starts.get());assertEquals(0,bridge.activeCount());
            }finally{release.countDown();executor.shutdownNow();bridge.close();}
        }
    }
    @Test void actualOwnedChildExitsWithinOneSecondEvenWhileWorkerSleeps() throws Exception {
        String python=System.getenv("CONVERSATE_TEST_PYTHON");org.junit.jupiter.api.Assumptions.assumeTrue(python!=null&&java.nio.file.Files.isRegularFile(java.nio.file.Path.of(python)),"explicit isolated Python required");
        var ready=new CountDownLatch(1);var busy=new CountDownLatch(1);var actualCpu=new AtomicInteger();
        var child=new ConversateAsrBridge.Child(json,python,java.nio.file.Path.of("src/test/python/asr_owned_child.py").toAbsolutePath().toString(),java.nio.file.Path.of("src/test/python").toAbsolutePath().toString(),8,e->{if(e.path("type").asText().equals("ready")){actualCpu.set(e.path("cpuThreads").asInt());ready.countDown();}else busy.countDown();},r->{});
        try{assertTrue(ready.await(3,TimeUnit.SECONDS));assertEquals(8,actualCpu.get());child.send("{}");assertTrue(busy.await(2,TimeUnit.SECONDS));long began=System.nanoTime();child.close().get(1,TimeUnit.SECONDS);assertFalse(child.alive());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1000);}finally{child.close().get(2,TimeUnit.SECONDS);}
    }
    static class FakeTransport implements ConversateAsrBridge.Transport {
        Consumer<JsonNode> events; boolean closed; int writes;
        public void send(String line){writes++;try{var json=new ObjectMapper();events.accept(json.createObjectNode().put("type","ack").put("seq",json.readTree(line).path("seq").asLong()));}catch(Exception e){throw new IllegalStateException();}}
        public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}
        public boolean alive(){return !closed;}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void finishPreservesLastFinalOrUnconfirmedOutcomeAndFencesLateEvents(boolean confirmed){
        try(var sessions=new ConversateSessionService()){
            var fake=new FakeTransport(){@Override public CompletableFuture<Void> finish(){
                if(!confirmed)return CompletableFuture.failedFuture(new java.io.IOException("ASR_FINISH_TIMEOUT"));
                events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","finish-1").put("revision",1).put("final",true).put("text","마지막 말"));
                return CompletableFuture.completedFuture(null);
            }};
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{fake.events=events;events.accept(json.createObjectNode().put("type","ready"));return fake;});
            var s=sessions.start("owner");try{
                bridge.start("owner",s.assistId(),s.epoch());
                var stopped=bridge.finish("owner",s.assistId(),s.epoch());
                assertEquals("STOPPED",stopped.audio().state());assertEquals(confirmed?"finished":"finish_unconfirmed",stopped.audio().runtime().get("stopReason"));
                assertEquals(confirmed?1:0,stopped.audio().finals());assertTrue(fake.closed);assertEquals(0,bridge.activeCount());
                if(confirmed)assertEquals("마지막 말",stopped.caption().text());
                fake.events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","late").put("revision",1).put("final",true).put("text","오래된 말"));
                assertEquals(stopped.audio().finals(),sessions.status("owner",s.assistId()).audio().finals());
                assertFalse(bridge.displayDiagnostics(stopped.audio()).containsKey("cloudStatus"));
            }finally{bridge.close();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ASR_AUTH_FAILED","ASR_QUOTA_EXCEEDED","ASR_AUDIO_FORMAT_INVALID","soniox:auth_failed"})
    void terminalProviderReasonSurvivesHttpChunkFailure(String reason){
        try(var sessions=new ConversateSessionService()){
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{events.accept(json.createObjectNode().put("type","ready"));
                return new FakeTransport(){public void send(String line){failure.accept(reason);throw new IllegalStateException();}};});
            var s=sessions.start("owner");try{
                bridge.start("owner",s.assistId(),s.epoch());
                var failed=assertThrows(ResponseStatusException.class,()->bridge.chunk("owner",s.assistId(),s.epoch(),0,Base64.getEncoder().encodeToString(new byte[640])));
                assertEquals(reason.startsWith("soniox:")?"asr_auth_failed":reason.toLowerCase(java.util.Locale.ROOT),failed.getReason());
                assertEquals(0,bridge.activeCount());
            }finally{bridge.close();}
        }
    }
    @Test void startupFailureRetainsTerminalAuthAndBudgetReasons(){
        for(String reason:List.of("ASR_AUTH_FAILED","stt_budget_exhausted","stt_budget_unavailable","stt_budget_invalid","stt_budget_busy"))try(var sessions=new ConversateSessionService()){
            var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{throw new java.io.IOException(reason);});var s=sessions.start("owner");
            try{var failed=assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch()));
                assertEquals(reason.startsWith("ASR_")?reason.toLowerCase(java.util.Locale.ROOT):reason.replace("stt_","asr_"),failed.getReason());assertEquals(0,bridge.activeCount());}
            finally{bridge.close();}
        }
    }
    @Test void authenticatedEpochOwnsCaptureAndPauseClosesOnlyIt() throws Exception {
        try(var sessions=new ConversateSessionService()) {
            var fake=new FakeTransport();var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{fake.events=events;events.accept(json.createObjectNode().put("type","ready"));return fake;});
            var s=sessions.start("owner");
            assertEquals(404,assertThrows(ResponseStatusException.class,()->bridge.start("other",s.assistId(),s.epoch())).getStatusCode().value());
            assertEquals("READY",bridge.start("owner",s.assistId(),s.epoch()).get("state"));
            assertThrows(ResponseStatusException.class,()->bridge.start("owner",s.assistId(),s.epoch()));
            bridge.chunk("owner",s.assistId(),s.epoch(),0,Base64.getEncoder().encodeToString(new byte[640]));
            assertEquals(1,fake.writes);
            sessions.control("owner",s.assistId(),s.epoch(),"pause");assertTrue(fake.closed);
            assertThrows(ResponseStatusException.class,()->bridge.chunk("owner",s.assistId(),s.epoch(),1,Base64.getEncoder().encodeToString(new byte[640])));
            assertEquals(0,bridge.activeCount());
        }
    }
    @Test void finalTranscriptReusesAnswerPipelineAndLateResultCannotReviveStoppedAssist() throws Exception {
        try(var sessions=new ConversateSessionService()){
            String owner="b".repeat(64);
            var fake=new FakeTransport();var bridge=new ConversateAsrBridge(sessions,json,(events,failure)->{fake.events=events;events.accept(json.createObjectNode().put("type","ready"));return fake;});
            var s=sessions.start(owner);s=sessions.prepare(owner,s.assistId(),s.epoch(),List.of(new PreparedMaterialReader.Material("allowed","보증 기간은 2년입니다.")));
            bridge.start(owner,s.assistId(),s.epoch());
            var event=json.createObjectNode().put("type","transcript").put("utteranceId","a1").put("revision",1).put("final",true).put("text","보증 기간은 얼마인가요?").put("asrMs",14);
            fake.events.accept(event);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(sessions.status(owner,s.assistId()).card()==null&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals("SHOW",sessions.status(owner,s.assistId()).card().decision());
            assertEquals(1,sessions.status(owner,s.assistId()).audio().finals());
            sessions.control(owner,s.assistId(),s.epoch(),"stop");fake.events.accept(event);bridge.close();
            assertEquals(0,sessions.sessionCount());assertTrue(fake.closed);
        }
    }
    @Test void frameLimitSequenceAndPauseByOutputLossFenceCapture() {
        try(var s=new ConversateSessionService()){
            var fake=new FakeTransport();var bridge=new ConversateAsrBridge(s,json,(events,failure)->{fake.events=events;events.accept(json.createObjectNode().put("type","ready"));return fake;});
            var run=s.start("o");bridge.start("o",run.assistId(),run.epoch());
            assertThrows(ResponseStatusException.class,()->bridge.chunk("o",run.assistId(),run.epoch(),0,Base64.getEncoder().encodeToString(new byte[8000])));
            assertThrows(ResponseStatusException.class,()->bridge.chunk("o",run.assistId(),run.epoch(),2,Base64.getEncoder().encodeToString(new byte[640])));
            String silence=Base64.getEncoder().encodeToString(new byte[640]);
            bridge.chunk("o",run.assistId(),run.epoch(),0,silence);bridge.chunk("o",run.assistId(),run.epoch(),0,silence);
            assertEquals(1,fake.writes);
            s.captureFailed("o",run.assistId(),run.epoch(),"ASR_INPUT_LOST");
            assertEquals("PAUSED",s.status("o",run.assistId()).state());assertTrue(fake.closed);
        }
    }
}
