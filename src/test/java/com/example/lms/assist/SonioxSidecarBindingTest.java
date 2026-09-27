package com.example.lms.assist;

import com.example.lms.service.stt.SonioxSttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SonioxSidecarBindingTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods=false)
    @Import({ConversateAsrBridge.class,ConversateCloudStt.class,ConversateSttBudget.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ConversateSessionService sessions(){return new ConversateSessionService();}
        @Bean com.example.lms.api.PublicChatAdmissionGuard admission(){return mock(com.example.lms.api.PublicChatAdmissionGuard.class);}
        @Bean SonioxSttService soniox(){var service=mock(SonioxSttService.class);when(service.isConfigured()).thenReturn(true);when(service.model()).thenReturn("stt-rt-v5");when(service.disabledReason()).thenReturn("");return service;}
        @Bean SonioxSidecarManager sidecar(){var manager=mock(SonioxSidecarManager.class);when(manager.enabled()).thenReturn(true);when(manager.ready()).thenReturn(true);when(manager.configured()).thenReturn(true);return manager;}
    }
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(
        "conversate.enabled=true","conversate.asr.enabled=true","conversate.asr.provider=soniox","conversate.asr.cloud.routing=fixed",
        "conversate.asr.cloud.enabled=true","conversate.asr.cloud.ledger="+directory.resolve("ledger.jsonl"));}
    static void javaReady(SonioxSttService service){when(service.transcribePcm16Mono(any(),any())).thenAnswer(a->{Runnable connected=a.getArgument(1);return Flux.defer(()->{connected.run();return Flux.never();});});}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"soniox","auto"})
    void cloudAdmissionSelectsNativeAndAProcessFailureReservesExistingJavaFallback(String provider) {
        context().withPropertyValues("conversate.asr.provider="+provider,"conversate.asr.cloud.routing="+(provider.equals("auto")?"auto":"fixed")).run(c->{
            var node=c.getBean(SonioxSidecarManager.class);var nativeStream=new ConversateAsrBridgeTest.FakeTransport();var failed=new AtomicReference<Consumer<String>>();
            doAnswer(a->{nativeStream.events=a.getArgument(0);failed.set(a.getArgument(1));nativeStream.events.accept(new ObjectMapper().createObjectNode().put("type","ready"));return nativeStream;}).when(node).connectAdmittedStream(any(),any());
            var java=c.getBean(SonioxSttService.class);javaReady(java);
            var sessions=c.getBean(ConversateSessionService.class);var bridge=c.getBean(ConversateAsrBridge.class);var cloud=c.getBean(ConversateCloudStt.class);var run=sessions.start("owner");
            try {
                bridge.start("owner",run.assistId(),run.epoch());
                assertEquals(1,cloud.budgetView().reserved().requests());verify(node,times(1)).connectAdmittedStream(any(),any());
                verify(java,never()).transcribePcm16Mono(any(),any());
                failed.get().accept("ASR_SIDECAR_FAILED");
                FailoverAsrTransportTest.await(()->{try{return cloud.budgetView().reserved().requests()==2;}catch(Exception e){return false;}});
                verify(java,timeout(2000).times(1)).transcribePcm16Mono(any(),any());
                assertTrue(nativeStream.closed);assertEquals(1,bridge.activeCount());
            }finally{bridge.close();}
        });
    }
    @Test void deniedBudgetPreventsBothNativeAndJavaConnections() {
        context().withPropertyValues("conversate.asr.cloud.verification-usd=0.0001").run(c->{
            var sessions=c.getBean(ConversateSessionService.class);var bridge=c.getBean(ConversateAsrBridge.class);var run=sessions.start("owner");
            assertThrows(Exception.class,()->bridge.start("owner",run.assistId(),run.epoch()));
            verify(c.getBean(SonioxSidecarManager.class),never()).connectAdmittedStream(any(),any());
            verify(c.getBean(SonioxSttService.class),never()).transcribePcm16Mono(any(),any());
        });
    }
    @Test void anUnreadyNodeUsesJavaInsideTheSameCloudAdmissionBoundary() {
        context().run(c->{
            var node=c.getBean(SonioxSidecarManager.class);when(node.configured()).thenReturn(false);when(node.ready()).thenReturn(false);
            var java=c.getBean(SonioxSttService.class);javaReady(java);
            var sessions=c.getBean(ConversateSessionService.class);var bridge=c.getBean(ConversateAsrBridge.class);var run=sessions.start("owner");
            try {
                bridge.start("owner",run.assistId(),run.epoch());
                verify(node,never()).connectAdmittedStream(any(),any());verify(java,times(1)).transcribePcm16Mono(any(),any());
                assertEquals(1,c.getBean(ConversateCloudStt.class).budgetView().reserved().requests());
            }finally{bridge.close();}
        });
    }
    @Test void nodeReadinessLossBetweenSelectionAndConnectUsesJavaOnce() {
        context().run(c->{
            var node=c.getBean(SonioxSidecarManager.class);
            doThrow(new java.io.IOException("soniox_sidecar_unconfigured")).when(node).connectAdmittedStream(any(),any());
            var service=c.getBean(SonioxSttService.class);javaReady(service);
            var sessions=c.getBean(ConversateSessionService.class);var bridge=c.getBean(ConversateAsrBridge.class);var run=sessions.start("owner");
            try{bridge.start("owner",run.assistId(),run.epoch());verify(service,times(1)).transcribePcm16Mono(any(),any());
                assertEquals(2,c.getBean(ConversateCloudStt.class).budgetView().reserved().requests());}
            finally{bridge.close();}
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ASR_AUTH_FAILED","ASR_QUOTA_EXCEEDED","ASR_AUDIO_FORMAT_INVALID","ASR_PROVIDER_FAILED"})
    void providerFailureDoesNotRetryTheSameSonioxAccountThroughJava(String reason) {
        context().run(c->{
            var node=c.getBean(SonioxSidecarManager.class);var nativeStream=new ConversateAsrBridgeTest.FakeTransport();var failed=new AtomicReference<Consumer<String>>();
            doAnswer(a->{nativeStream.events=a.getArgument(0);failed.set(a.getArgument(1));nativeStream.events.accept(new ObjectMapper().createObjectNode().put("type","ready"));return nativeStream;}).when(node).connectAdmittedStream(any(),any());
            var service=c.getBean(SonioxSttService.class);javaReady(service);
            var failures=new java.util.concurrent.CopyOnWriteArrayList<String>();
            var cloud=c.getBean(ConversateCloudStt.class);
            var stream=cloud.openStream(e->{},failures::add);
            try {
                failed.get().accept(reason);
                FailoverAsrTransportTest.await(()->!failures.isEmpty());
                verify(service,never()).transcribePcm16Mono(any(),any());
                assertEquals(1,cloud.budgetView().reserved().requests());
                assertEquals(java.util.List.of(reason),failures);
            } finally {stream.close();}
        });
    }
}
