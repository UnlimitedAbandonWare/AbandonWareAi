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