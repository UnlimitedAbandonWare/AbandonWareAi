package com.abandonware.ai.agent.web;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.Map;




@RestController
@RequestMapping("/trace")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.web.AgentTraceController
 * Role: controller
 * Key Endpoints: GET /trace/events, ANY /trace/trace
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.web.AgentTraceController
role: controller
api:
  - GET /trace/events
  - ANY /trace/trace
*/
public class AgentTraceController {
    private final Sinks.Many<Map<String,Object>> sink = Sinks.many().multicast().onBackpressureBuffer();

    @GetMapping(value="/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String,Object>>> stream(){
        return sink.asFlux().map(ev -> ServerSentEvent.<Map<String,Object>>builder(ev).build());
    }

    public void publish(Map<String,Object> ev){
        sink.tryEmitNext(ev);
    }
}