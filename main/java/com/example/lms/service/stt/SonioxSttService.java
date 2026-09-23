package com.example.lms.service.stt;

import com.example.lms.config.ConfigValueGuards;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.*;
import reactor.netty.http.client.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

/** Server-only Soniox PCM owner. One subscription, one socket, no automatic retry. */
@Service
public class SonioxSttService {
    private final String credential, model, disabledReason;
    private final ObjectMapper json;
    private final WebSocketClient client;
    private final URI endpoint;
    @Autowired(required=false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;

    @Autowired
    public SonioxSttService(ObjectMapper json,
            @Value("${soniox.api-key:${SONIOX_API_KEY:}}") String credential,
            @Value("${soniox.stt.enabled:${SONIOX_STT_ENABLED:false}}") boolean enabled,
            @Value("${soniox.stt.model:${SONIOX_STT_MODEL:stt-rt-v5}}") String model,
            @Value("${soniox.stt.region:${SONIOX_STT_REGION:us}}") String region) {
        this(json,credential,enabled,model,region,new ReactorNettyWebSocketClient(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,3000).responseTimeout(Duration.ofSeconds(5))),null);
    }

    // Loopback injection is test-only; production endpoints come from the region allowlist.
    SonioxSttService(ObjectMapper json,String credential,boolean enabled,String model,String region,WebSocketClient client,URI fixture) {
        this.json=json;this.client=client;this.model=model;
        this.credential=ConfigValueGuards.isMissing(credential)?"":credential.trim();
        String host=switch(Objects.toString(region,"")){case "us"->"stt-rt.soniox.com";case "eu"->"stt-rt.eu.soniox.com";case "jp"->"stt-rt.jp.soniox.com";case "in"->"stt-rt.in.soniox.com";default->"";};
        this.disabledReason=!enabled?"disabled":this.credential.isBlank()?"missing_key":host.isBlank()?"invalid_region":!Set.of("stt-rt-v5","stt-rt-v4").contains(Objects.toString(model,""))?"invalid_model":"";
        this.endpoint=fixture!=null?fixture:host.isBlank()?null:URI.create("wss://"+host+"/transcribe-websocket");
    }
    public boolean isConfigured(){return disabledReason.isEmpty();}
    public String disabledReason(){return disabledReason;}
    public String model(){return model;}
    @Override public String toString(){return "SonioxSttService[configured="+isConfigured()+"]";}

    /** Connected means handshake/config send, not authentication or recognized speech. */
    public Flux<Transcript> transcribePcm16Mono(Flux<byte[]> audio,Runnable connected) {
        return Flux.defer(()->{
            if(!isConfigured())return Flux.error(failure(disabledReason));
            if(audio==null||connected==null)return Flux.error(failure("invalid_audio_options"));
            return Flux.<Transcript>create(sink->{
                var decoder=new Decoder(json);
                var subscription=client.execute(endpoint,session->{
                    Sinks.One<Void> ended=Sinks.one(),finished=Sinks.one();
                    String config;
                    try {config=json.writeValueAsString(Map.of("api_key",credential,"model",model,"audio_format","pcm_s16le",
                            "sample_rate",16000,"num_channels",1,"language_hints",List.of("ko","en"),"enable_endpoint_detection",true));}
                    catch(Exception invalid){return Mono.error(failure("invalid_configuration"));}
                    Flux<WebSocketMessage> media=audio.map(bytes->{
                        if(bytes==null||bytes.length==0||bytes.length>65536||(bytes.length&1)!=0)throw failure("invalid_audio_chunk");
                        return session.binaryMessage(factory->factory.wrap(bytes.clone()));
                    }).doFinally(signal->ended.tryEmitEmpty());
                    Flux<WebSocketMessage> keepalive=Flux.interval(Duration.ofSeconds(4))
                            .map(tick->session.textMessage("{\"type\":\"keepalive\"}")).takeUntilOther(ended.asMono());
                    Flux<WebSocketMessage> outbound=Flux.concat(Mono.just(session.textMessage(config)),
                            Flux.defer(()->{connected.run();return Flux.merge(1,media,keepalive);}),
                            Mono.fromSupplier(()->session.textMessage(""))).takeUntilOther(finished.asMono());
                    Mono<Void> send=session.send(outbound);
                    Mono<Void> receive=session.receive().timeout(Duration.ofSeconds(15))
                            .filter(message->message.getType()==WebSocketMessage.Type.TEXT)
                            .doOnNext(message->{decoder.accept(message.getPayloadAsText()).forEach(sink::next);if(decoder.finished)finished.tryEmitEmpty();})
                            .takeUntil(message->decoder.finished).then(Mono.defer(()->decoder.finished?session.close():Mono.error(failure("unexpected_close"))));
                    return Mono.when(send,receive);
                }).onErrorMap(this::sanitize).subscribe(ignored->{},sink::error,()->{
                    if(apiFailureRecorder!=null)try{apiFailureRecorder.recordSuccess("soniox",model);}catch(RuntimeException ignored){}
                    sink.complete();
                });
                sink.onDispose(subscription);
            },FluxSink.OverflowStrategy.ERROR);
        });
    }

    /** A final token is stable text; only <end>, <fin>, or finished closes an utterance. */
    static final class Decoder {
        private final ObjectMapper json;private final StringBuilder committed=new StringBuilder();
        private final Set<String> seen=new LinkedHashSet<>();private String lastPartial="";private long utterance;
        boolean finished;
        Decoder(ObjectMapper json){this.json=json;}
        List<Transcript> accept(String message){
            if(finished)return List.of();
            if(message==null||message.length()>65536)throw failure("response_limit");
            try {
                JsonNode root=json.readTree(message);if(root==null||!root.isObject())throw failure("invalid_response");
                if(root.has("error_code")||root.has("error_type"))throw failure(switch(root.path("error_type").asText()){
                    case "unauthorized","authentication_error","invalid_api_key"->"auth_failed";
                    case "insufficient_funds","quota_exceeded"->"quota_exceeded";
                    case "rate_limit_exceeded"->"rate_limited";
                    case "invalid_request","invalid_model","unsupported_model","model_not_available"->"audio_format_invalid";
                    default->switch(root.path("error_code").asInt()){
                        case 401,403->"auth_failed";case 402->"quota_exceeded";case 429->"rate_limited";case 408->"timeout";case 400->"audio_format_invalid";default->"provider_error";};});
                var result=new ArrayList<Transcript>();var partial=new StringBuilder();JsonNode tokens=root.path("tokens");
                if(!tokens.isMissingNode()&&(!tokens.isArray()||tokens.size()>512))throw failure("response_limit");
                for(JsonNode segment:tokens){
                    String text=segment.path("text").asText();boolean stable=segment.path("is_final").asBoolean();
                    if(text.equals("<end>")||text.equals("<fin>")){if(stable)complete(result);continue;}
                    if(text.startsWith("<")&&text.endsWith(">"))continue;
                    if(stable){
                        if(!segment.path("start_ms").isIntegralNumber()||!segment.path("end_ms").isIntegralNumber()
                                ||segment.path("start_ms").asLong()<0||segment.path("end_ms").asLong()<segment.path("start_ms").asLong())throw failure("invalid_response");
                        String identity=segment.path("start_ms").asLong()+":"+segment.path("end_ms").asLong()+":"+text;
                        if(seen.add(identity))committed.append(text);
                        while(seen.size()>1024)seen.remove(seen.iterator().next());
                    }else partial.append(text);
                    if(committed.length()+partial.length()>2048)throw failure("transcript_limit");
                }
                if(root.path("finished").asBoolean()){complete(result);finished=true;}
                else {String text=committed.toString()+partial;if(!text.isBlank()&&!text.equals(lastPartial)){result.add(new Transcript(text,false,utterance));lastPartial=text;}}
                return result;
            }catch(SafeFailure safe){throw safe;}catch(Exception invalid){throw failure("invalid_response");}
        }
        private void complete(List<Transcript> out){
            if(!committed.toString().isBlank())out.add(new Transcript(committed.toString(),true,utterance++));
            committed.setLength(0);lastPartial="";
        }
    }
    private RuntimeException sanitize(Throwable error){
        if(apiFailureRecorder!=null){
            if(error instanceof WebSocketClientHandshakeException handshake&&handshake.response()!=null)
                apiFailureRecorder.record("soniox",model,handshake.response().status().code(),null,error);
            else if(error instanceof SafeFailure safe)
                apiFailureRecorder.recordSignal("soniox",model,"ASR_"+safe.getMessage().substring("soniox:".length()).toUpperCase(java.util.Locale.ROOT));
            else apiFailureRecorder.record("soniox",model,0,null,error);
        }
        if(error instanceof SafeFailure safe)return safe;
        if(error instanceof WebSocketClientHandshakeException handshake&&handshake.response()!=null){
            int code=handshake.response().status().code();return failure(code==401||code==403?"auth_failed":code==402?"quota_exceeded":code==429?"rate_limited":"transport_error");
        }
        return failure(error instanceof TimeoutException?"timeout":"transport_error");
    }
    private static SafeFailure failure(String reason){return new SafeFailure(reason);}
    private static final class SafeFailure extends RuntimeException {SafeFailure(String reason){super("soniox:"+reason);}}
    public record Transcript(String text,boolean isFinal,long utterance){
        @Override public String toString(){return "SonioxTranscript[characters="+text.length()+", final="+isFinal+"]";}
    }
}
