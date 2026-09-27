package com.example.lms.debug;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods=false)
public class ApiFailureWebClientConfiguration {
    @Bean
    WebClientCustomizer apiFailureWebClientCustomizer(ApiFailureRecorder recorder) {
        return builder->builder.filter(filter(recorder));
    }
    static ExchangeFilterFunction filter(ApiFailureRecorder recorder) {
        return (request,next)->Mono.defer(()-> {
            String url=request.url().toString();
            String model=request.attribute("api.failure.model").map(Object::toString).orElseGet(()-> {
                String path=request.url().getPath();int start=path.indexOf("/models/");
                if(start>=0){String rest=path.substring(start+8);int end=rest.indexOf(':');return end>=0?rest.substring(0,end):rest;}
                return "unconfirmed";
            });
            return next.exchange(request).map(response->{
                int status=response.statusCode().value();
                if(status<400)return response;
                var observation=recorder.recordHttp(url,model,status,null,null);
                var prefix=new ByteArrayOutputStream();var recorded=new AtomicBoolean();
                Runnable record=()->{if(recorded.compareAndSet(false,true))recorder.refineHttp(observation,prefix.toString(StandardCharsets.UTF_8));};
                // Tap a bounded prefix without consuming/replacing buffers; preserve downstream parsing and streaming.
                return response.mutate().body(body->body.doOnNext(buffer->{
                    var bytes=buffer.asByteBuffer().asReadOnlyBuffer();int size=Math.min(bytes.remaining(),16_384-prefix.size());
                    if(size>0){byte[] part=new byte[size];bytes.get(part);prefix.writeBytes(part);}
                }).doOnComplete(record).doOnError(ignored->record.run()).doFinally(ignored->record.run())).build();
            }).doOnError(error->recorder.recordException(url,model,error));
        });
    }
}
