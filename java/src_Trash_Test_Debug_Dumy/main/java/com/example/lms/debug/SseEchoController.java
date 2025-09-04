package com.example.lms.debug;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

@RestController
public class SseEchoController {

    @GetMapping(path="/api/debug/sse-echo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter echo(@RequestParam(value = "n", defaultValue = "10") int n,
                           @RequestParam(value = "intervalMs", defaultValue = "200") long intervalMs) {
        SseEmitter em = new SseEmitter(0L);
        new org.springframework.core.task.SimpleAsyncTaskExecutor("sse-echo-").submit(() -> {
            try {
                for (int i=1; i<=n; i++) {
                    em.send(SseEmitter.event().name("tick").id(String.valueOf(i)).data("tick-"+i+" @ "+ Instant.now()));
                    Thread.sleep(Math.max(1, intervalMs));
                }
                em.send(SseEmitter.event().name("done").data("{}"));
                em.complete();
            } catch (Exception e) {
                try { em.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignore) {}
                em.completeWithError(e);
            }
        });
        return em;
    }
}
