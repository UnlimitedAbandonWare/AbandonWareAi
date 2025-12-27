package com.abandonware.ai.agent.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;

@RestController
public class StreamController {
    @GetMapping(value = "/api/llm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("prompt") String prompt) throws IOException {
        SseEmitter emitter = new SseEmitter(0L);
        var exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            try {
                // minimal fake stream: emit prompt length then done. Integrators can connect LLM token stream here.
                emitter.send("start");
                emitter.send("prompt_len=" + prompt.length());
                emitter.send("done");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
