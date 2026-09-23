package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/harmony")
public class HarmonyScoreController {

    private final HarmonyScoreEngine engine;
    private final HarmonySseRuntime streamRuntime;

    @Autowired
    public HarmonyScoreController(HarmonyScoreEngine engine, HarmonySseRuntime streamRuntime) {
        this.engine = engine;
        this.streamRuntime = streamRuntime;
    }

    @GetMapping(value = "/score", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HarmonyScoreSnapshot> getScore() {
        return ResponseEntity.ok(computeSnapshot());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = createEmitter(300_000L);
        HarmonySseRuntime.StreamLease lease = streamRuntime.open(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("harmony")
                        .data(computeSnapshot(), MediaType.APPLICATION_JSON));
            } catch (IOException error) {
                TraceStore.put("harmony.score.stream.io.catchObserved", Boolean.TRUE);
                recordStreamFailure("io", error);
                emitter.completeWithError(error);
                throw new StreamTickTerminated(error);
            } catch (RuntimeException error) {
                TraceStore.put("harmony.score.stream.runtime.catchObserved", Boolean.TRUE);
                recordStreamFailure("runtime", error);
                emitter.completeWithError(error);
                throw new StreamTickTerminated(error);
            }
        }).orElseThrow(() -> capacityRejection());

        Runnable close = lease::close;
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        return emitter;
    }

    SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    @GetMapping(value = "/push", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter push() {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(SseEmitter.event()
                    .name("harmony")
                    .data(computeSnapshot(), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException error) {
            TraceStore.put("harmony.score.push.io.catchObserved", Boolean.TRUE);
            recordStreamFailure("io", error);
            emitter.completeWithError(error);
        } catch (RuntimeException error) {
            TraceStore.put("harmony.score.push.runtime.catchObserved", Boolean.TRUE);
            recordStreamFailure("runtime", error);
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private HarmonyScoreSnapshot computeSnapshot() {
        return engine.compute();
    }

    private static void recordStreamFailure(String failureClass, Throwable error) {
        TraceStore.put("harmony.score.stream.sendFailed", Boolean.TRUE);
        TraceStore.put("harmony.score.stream.failureClass", failureClass);
        TraceStore.put("harmony.score.stream.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }

    private static ResponseStatusException capacityRejection() {
        TraceStore.put("harmony.score.stream.rejected", Boolean.TRUE);
        TraceStore.put("harmony.score.stream.rejectReason", "harmony_sse_capacity");
        TraceStore.inc("harmony.score.stream.rejected.count");
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "harmony_sse_capacity");
    }

    private static final class StreamTickTerminated extends RuntimeException {
        private StreamTickTerminated(Throwable cause) {
            super("harmony_sse_terminal", cause);
        }
    }
}
