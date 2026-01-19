// src/main/java/com/example/lms/trace/LlmTraceAspect.java
package com.example.lms.trace;

import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.TokenChunk;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;




/**
 * Aspect that instruments LLM invocations.  It records both the call
 * parameters and the corresponding responses for blocking and streaming
 * completions.  The advice does not interfere with the reactive
 * nature of the underlying {@link Mono} and {@link Flux} but augments
 * them with side effects to emit trace events.
 */
@Aspect
@Component
public class LlmTraceAspect {

    /**
     * Intercepts non-streaming completion calls on the chat model port.
     * Emits a {@code llm_call} event before invocation and a
     * {@code llm_resp} event when the resulting {@link Mono} produces a
     * value or an error.  The response event includes a preview of the
     * returned text, an approximate output token count and the measured
     * latency.  EBNA detection is triggered on success.
     */
    @Around("execution(* com.acme.aicore.domain.ports.ChatModelPort.complete(..)) && args(prompt, params)")
    public Object aroundComplete(ProceedingJoinPoint pjp, Prompt prompt, GenerationParams params) throws Throwable {
        long t0 = System.currentTimeMillis();
        TraceLogger.emit("llm_call", "llm", Map.of(
                "model", modelName(params),
                "stream", false
        ));
        @SuppressWarnings("unchecked")
        Mono<String> mono = (Mono<String>) pjp.proceed();
        return mono
                .doOnSuccess(resp -> {
                    long dt = System.currentTimeMillis() - t0;
                    int otok = resp == null ? 0 : resp.length() / 4;
                    TraceLogger.emit("llm_resp", "llm", Map.of(
                            "finish", "stop",
                            "otok", otok,
                            "resp_preview", TraceLogger.preview(resp),
                            "lat_ms", dt,
                            "tool_calls", 0
                    ));
                    EbnaDetector.checkAndEmit(resp);
                })
                .doOnError(ex -> {
                    long dt = System.currentTimeMillis() - t0;
                    TraceLogger.emit("llm_resp", "llm", Map.of(
                            "finish", "error",
                            "otok", 0,
                            "resp_preview", TraceLogger.preview(ex.getMessage()),
                            "lat_ms", dt,
                            "tool_calls", 0
                    ));
                });
    }

    /**
     * Intercepts streaming calls on the chat model port.  Emits a
     * {@code llm_call} event immediately, then wraps the returned
     * {@link Flux} to accumulate a preview of the first few tokens and
     * approximate the total output token count.  A {@code llm_resp}
     * event is emitted when the stream terminates or is cancelled.
     */
    @Around("execution(* com.acme.aicore.domain.ports.ChatModelPort.stream(..)) && args(prompt, params)")
    public Object aroundStream(ProceedingJoinPoint pjp, Prompt prompt, GenerationParams params) throws Throwable {
        long t0 = System.currentTimeMillis();
        TraceLogger.emit("llm_call", "llm", Map.of(
                "model", modelName(params),
                "stream", true
        ));
        @SuppressWarnings("unchecked")
        Flux<TokenChunk> original = (Flux<TokenChunk>) pjp.proceed();
        StringBuilder preview = new StringBuilder();
        AtomicInteger tokenCount = new AtomicInteger();
        return original
                .doOnNext(tc -> {
                    String text = tc.text();
                    if (preview.length() < TraceLogger.PREVIEW) {
                        int remaining = TraceLogger.PREVIEW - preview.length();
                        preview.append(text, 0, Math.min(text.length(), remaining));
                    }
                    tokenCount.addAndGet(Math.max(1, text.length() / 4));
                })
                .doFinally(signal -> {
                    long dt = System.currentTimeMillis() - t0;
                    String finish = signal == SignalType.CANCEL ? "cancel" : "stop";
                    TraceLogger.emit("llm_resp", "llm", Map.of(
                            "finish", finish,
                            "otok", tokenCount.get(),
                            "resp_preview", preview.toString(),
                            "lat_ms", dt,
                            "tool_calls", 0
                    ));
                    EbnaDetector.checkAndEmit(preview.toString());
                });
    }

    /**
     * Determine the model name from generation parameters.  As of now
     * {@link GenerationParams} does not expose a model field so this
     * method returns a shim.  Future versions may enrich this
     * abstraction to carry the identifier of the model being invoked.
     */
    private String modelName(GenerationParams params) {
        return "unknown";
    }
}