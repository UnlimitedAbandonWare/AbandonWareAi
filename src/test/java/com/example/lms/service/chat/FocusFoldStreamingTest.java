package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class FocusFoldStreamingTest {
    @SuppressWarnings("unchecked")
    private Consumer<String> emitter() {
        return ReflectionTestUtils.invokeMethod(ChatRunExecutionContext.class, "providerTextConsumer");
    }
    private AutoCloseable stage(String name) {
        return ReflectionTestUtils.invokeMethod(ChatRunExecutionContext.class, "bindProviderTextStage", name);
    }
    private AutoCloseable permit(ChatRunExecutionContext run, boolean allowed) {
        return ReflectionTestUtils.invokeMethod(run, "permitFoldStreaming", allowed);
    }
    @Test void onlyOwnedPrimaryDraftMayPublishAndCancellationStopsCapturedEmitter() throws Exception {
        var registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        var run = registry.beginOrJoin(9751L).context();
        var seen = new ArrayList<String>();
        try (var binding = ChatRunExecutionContext.bind(run)) {
            assertNull(emitter());
            ReflectionTestUtils.invokeMethod(run, "installFoldTextConsumer", (Consumer<String>) seen::add);
            try (var permit = permit(run, true); var aux = stage("judge")) { assertNull(emitter()); }
            Consumer<String> captured;
            try (var permit = permit(run, true); var primary = stage("chat_draft")) {
                captured = emitter(); assertNotNull(captured);
                captured.accept("A useful first sentence. "+"x".repeat(40));
                assertEquals(java.util.List.of("A useful first sentence."), seen);
                registry.cancelExact(9751L, run.clientToken());
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> captured.accept(" More useful text. "+"x".repeat(40)));
                assertEquals(1, seen.size());
            }
        } finally { registry.shutdown(); }
    }
    @Test void splitDiagnosticsCannotEscapeAndFinalRevisionFailsBeforeSuccess() throws Exception {
        var registry = new ChatRunRegistry(); registry.replayCapacity=16; var run = registry.beginOrJoin(9752L).context();
        var seen = new ArrayList<String>();
        try (var binding=ChatRunExecutionContext.bind(run)) {
            ReflectionTestUtils.invokeMethod(run,"installFoldTextConsumer",(Consumer<String>)seen::add);
            try(var permit=permit(run,true);var primary=stage("chat_draft")) {
                var output=emitter();
                output.accept("\n A useful first sentence.\nTRACE_");
                output.accept("JSON:{\"secret\":\"SYNTHETIC_DIAGNOSTIC\"}. "+"x".repeat(50));
                assertEquals(java.util.List.of("A useful first sentence."),seen);
                output.accept(" TRACE_JSON ordinary discussion.");
                assertEquals(1,seen.size());
            }
            ReflectionTestUtils.invokeMethod(run,"requireFoldPrefix","A useful first sentence.");
            ReflectionTestUtils.invokeMethod(run,"requireFoldPrefix","\n A useful first sentence.");
            assertThrows(IllegalStateException.class,
                    ()->ReflectionTestUtils.invokeMethod(run,"requireFoldPrefix","A changed final sentence."));
        } finally { registry.shutdown(); }
    }
    @Test void disabledGateNeverExposesConsumerOrSanitizerFallback() throws Exception {
        var registry=new ChatRunRegistry();registry.replayCapacity=16;var run=registry.beginOrJoin(9753L).context();
        var seen=new ArrayList<String>();
        try(var binding=ChatRunExecutionContext.bind(run)) {
            ReflectionTestUtils.invokeMethod(run,"installFoldTextConsumer",(Consumer<String>)seen::add);
            try(var permit=permit(run,false);var primary=stage("chat_draft")){assertNull(emitter());}
            try(var permit=permit(run,true);var primary=stage("chat_draft")){
                emitter().accept("<!-- NOVA_TRACE_INJECTED -->hidden. "+"x".repeat(80));
                assertTrue(seen.isEmpty());
            }
        } finally {registry.shutdown();}
    }
}
