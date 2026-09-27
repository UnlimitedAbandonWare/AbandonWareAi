package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SonioxSidecarTest {
    @TempDir Path directory;
    MockEnvironment environment() {
        return new MockEnvironment().withProperty("conversate.asr.sidecar.runtime-directory",directory.toString())
                .withProperty("conversate.asr.enabled","true")
                .withProperty("conversate.asr.provider","soniox")
                .withProperty("soniox.stt.enabled","true")
                .withProperty("soniox.api-key","synthetic-local-health-only")
                .withProperty("conversate.asr.sidecar.health-interval-ms","100")
                .withProperty("conversate.asr.sidecar.restart-delay-ms","100")
                .withProperty("conversate.asr.sidecar.bootstrap-timeout-ms","30000");
    }
    ConversateSttBudget budget(){return new ConversateSttBudget(new ObjectMapper(),true,directory.resolve("budget.jsonl").toString(),"verification","1","5");}
    @Test void springLifecycleStartsActualNodeAuthenticatesHealthRestartsAndStopsOnlyOwnedChild() throws Exception {
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        var manager=new SonioxSidecarManager(environment(),new ObjectMapper(),budget());
        context.setEnvironment(environment().withProperty("conversate.enabled","true"));
        context.registerBean(SonioxSidecarManager.class,()->manager);
        long oldPid=0,newPid=0;
        try {
            context.refresh(); awaitReady(manager);
            var first=manager.endpoint(); oldPid=manager.childPid();
            assertTrue(ProcessHandle.of(oldPid).orElseThrow().isAlive());
            assertTrue(manager.configured()); // Synthetic key permits local health, not provider authentication.
            assertEquals("not_observed",manager.diagnostics().get("providerAttempt"));
            assertFalse(java.nio.file.Files.exists(directory.resolve("budget.jsonl")));
            var response=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(first.httpUri("/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode());
            assertFalse(response.body().contains(first.bearer()));
            ProcessHandle.of(oldPid).orElseThrow().destroyForcibly(); // Exact process created by this test.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline && (manager.childPid()==oldPid||!manager.ready()))Thread.sleep(25);
            assertTrue(manager.ready(),manager.diagnostics().toString());
            newPid=manager.childPid(); assertNotEquals(oldPid,newPid);
            assertNotEquals(first.bearer(),manager.endpoint().bearer());
        } finally { context.close(); }
        assertFalse(manager.isRunning()); assertFalse(manager.ready());
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        if(newPid!=0)assertFalse(ProcessHandle.of(newPid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void missingNodeFailsSoftWithinDeadlineAndStopIsIdempotent() throws Exception {
        var env=environment().withProperty("conversate.asr.sidecar.node",directory.resolve("absent-node").toString());
        var manager=new SonioxSidecarManager(env,new ObjectMapper(),budget());
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1),manager::start);
            Thread.sleep(500); assertFalse(manager.ready()); assertFalse(manager.configured());
            assertFalse(manager.diagnostics().toString().contains(directory.toString()));
        } finally {manager.stop();manager.stop();}
    }
    @Test void disabledManagerCreatesNoChildOrRuntimeFiles() throws Exception {
        var manager=new SonioxSidecarManager(environment().withProperty("conversate.asr.sidecar.enabled","false"),new ObjectMapper(),null);
        try {manager.start();assertFalse(manager.ready());assertEquals(0,manager.childPid());
            try(var files=java.nio.file.Files.list(directory)){assertEquals(0,files.count());}
        }finally{manager.stop();}
    }
    @Test void healthDeadlineIncludesAResponseThatSendsHeadersAndHangs()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/health",x->{x.sendResponseHeaders(200,100);try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{x.close();}});
        server.start();var manager=new SonioxSidecarManager(environment().withProperty("conversate.asr.sidecar.health-timeout-ms","100"),new ObjectMapper(),null);
        try {assertTimeoutPreemptively(Duration.ofMillis(700),()->assertFalse(manager.probe(new SonioxSidecarManager.Endpoint(server.getAddress().getPort(),"a".repeat(64)))));}
        finally{release.countDown();server.stop(0);manager.stop();}
    }
    static void awaitReady(SonioxSidecarManager manager)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(!manager.ready()&&System.nanoTime()<deadline)Thread.sleep(30);
        assertTrue(manager.ready(),manager.diagnostics().toString());
    }
}
