package com.example.lms.service.stt;

import com.example.lms.config.DeepgramProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server-side PCM streaming. Each subscription owns one connection, with no automatic retry. */
@Service
public class DeepgramSttService {
    private static final URI LISTEN = URI.create("wss://api.deepgram.com/v1/listen");
    private final DeepgramProperties properties;
    private final ObjectMapper mapper;
    private final WebSocketClient client;
    private final URI endpoint;
    @Autowired(required=false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;

    @Autowired
    public DeepgramSttService(DeepgramProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new ReactorNettyWebSocketClient(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(15))), LISTEN);
    }

    // Package-local transport seam keeps fixture endpoints out of production configuration.
    DeepgramSttService(DeepgramProperties properties, ObjectMapper mapper, WebSocketClient client, URI endpoint) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        this.endpoint = endpoint;
    }

    /**
     * Streams raw signed 16-bit little-endian mono PCM, without a WAV header.
     * Completing audio sends CloseStream and drains final Results. Normal completion requires
     * the terminal Metadata response and a normal close; close code 1000 alone is insufficient.
     * KeepAlive handles input pauses; 30 seconds without a server message fails the session.
     */
    public Flux<Transcript> transcribePcm16Mono(Flux<byte[]> audio, int sampleRate, String language) {
        return transcribePcm16Mono(audio, sampleRate, language, () -> { });
    }

    public boolean isConfigured() { return properties.isConfigured(); }

    /** connected runs only after WebSocket handshake, never on subscription alone. */
    public Flux<Transcript> transcribePcm16Mono(Flux<byte[]> audio, int sampleRate, String language, Runnable connected) {
        return Flux.defer(() -> {
            if (!properties.isConfigured()) return Flux.error(failure("missing_api_key"));
            if (audio == null || sampleRate < 8_000 || sampleRate > 96_000 || language == null
                    || !language.matches("[a-z]{2,5}(-[A-Za-z]{2,4})?")) {
                return Flux.error(failure("invalid_audio_options"));
            }
            URI uri = UriComponentsBuilder.fromUri(endpoint)
                    .queryParam("model", "nova-3").queryParam("encoding", "linear16")
                    .queryParam("sample_rate", sampleRate).queryParam("channels", 1)
                    .queryParam("language", language).queryParam("interim_results", true)
                    .queryParam("smart_format", true).queryParam("endpointing", 600)
                    .queryParam("diarize_model", "v1").queryParam("vad_events", true)
                    .queryParam("utterance_end_ms", 1000)
                    .queryParam("mip_opt_out", true).build().encode().toUri();
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Token " + properties.getApiKey());

            return Flux.<Transcript>create(sink -> {
                var subscription = client.execute(uri, headers, session -> {
                    connected.run();
                    Sinks.One<Void> audioEnded = Sinks.one();
                    var closeRequested = new AtomicBoolean();
                    var terminalMetadata = new AtomicBoolean();
                    Flux<WebSocketMessage> media = audio.map(chunk -> {
                        if (chunk.length == 0 || chunk.length > 65_536 || (chunk.length & 1) != 0) {
                            throw failure("invalid_audio_chunk");
                        }
                        byte[] owned = chunk.clone();
                        return session.binaryMessage(factory -> factory.wrap(owned));
                    }).doFinally(signal -> audioEnded.tryEmitEmpty());
                    Flux<WebSocketMessage> keepAlive = Flux.interval(Duration.ofSeconds(4))
                            .map(tick -> session.textMessage("{\"type\":\"KeepAlive\"}"))
                            .takeUntilOther(audioEnded.asMono());
                    Mono<Void> send = session.send(Flux.merge(1, media, keepAlive)
                            .concatWith(Mono.fromSupplier(() -> {
                                closeRequested.set(true);
                                return session.textMessage("{\"type\":\"CloseStream\"}");
                            })));
                    Mono<Void> receive = session.receive().timeout(Duration.ofSeconds(30))
                            .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                            .doOnNext(message -> {
                                Transcript result = parse(message.getPayloadAsText());
                                if (result != null && "Metadata".equals(result.eventType())) {
                                    if (closeRequested.get()) terminalMetadata.set(true);
                                } else if (result != null) sink.next(result);
                            }).then(session.closeStatus()
                                    .switchIfEmpty(Mono.error(failure("close_status_missing")))
                                    .flatMap(status -> status.getCode() != 1000
                                            ? Mono.error(failure("abnormal_close_" + status.getCode()))
                                            : closeRequested.get() && terminalMetadata.get() ? Mono.empty()
                                            : Mono.error(failure("finish_unconfirmed"))));
                    return Mono.when(send, receive);
                }).onErrorMap(this::sanitize).subscribe(ignored -> { }, sink::error, () -> {
                    if(apiFailureRecorder!=null)try{apiFailureRecorder.recordSuccess("deepgram","nova-3");}catch(RuntimeException ignored){}
                    sink.complete();
                });
                sink.onDispose(subscription);
            }, FluxSink.OverflowStrategy.ERROR);
        });
    }

    private Transcript parse(String message) {
        try {
            var json = mapper.readTree(message);
            if (json == null) throw failure("invalid_response");
            String type = json.path("type").asText();
            if ("Error".equals(type)) throw failure("provider_error");
            if ("Metadata".equals(type)) return new Transcript("",false,false,-1,-1,null,List.of(),type);
            if ("SpeechStarted".equals(type) || "UtteranceEnd".equals(type))
                return new Transcript("", false, false, json.path("last_word_end").asDouble(-1), 0, null, List.of(), type);
            if (!"Results".equals(type)) return null;
            var alternative=json.path("channel").path("alternatives").path(0);
            var words=new ArrayList<Word>();
            for(var word:alternative.path("words")) {
                if(words.size()>=256)throw failure("word_limit");
                words.add(new Word(word.path("punctuated_word").asText(word.path("word").asText("")),
                        word.path("start").asDouble(-1),word.path("end").asDouble(-1),
                        confidence(word.path("confidence")),word.path("speaker").isIntegralNumber()?word.path("speaker").intValue():null));
            }
            return new Transcript(alternative.path("transcript").asText(""),
                    json.path("is_final").asBoolean(), json.path("speech_final").asBoolean(),
                    json.path("start").asDouble(-1), json.path("duration").asDouble(-1),confidence(alternative.path("confidence")),words,"Results");
        } catch (SafeFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw failure("invalid_response");
        }
    }

    private RuntimeException sanitize(Throwable error) {
        if(apiFailureRecorder!=null){
            if(error instanceof WebSocketClientHandshakeException handshake&&handshake.response()!=null)
                apiFailureRecorder.record("deepgram","nova-3",handshake.response().status().code(),null,error);
            else if(error instanceof SafeFailure safe)
                apiFailureRecorder.recordSignal("deepgram","nova-3","ASR_"+safe.getMessage().substring("deepgram:".length()).toUpperCase(java.util.Locale.ROOT));
            else apiFailureRecorder.record("deepgram","nova-3",0,null,error);
        }
        if (error instanceof SafeFailure safe) return safe;
        if (error instanceof WebSocketClientHandshakeException handshake && handshake.response() != null) {
            return failure("http_" + handshake.response().status().code());
        }
        return failure(error instanceof TimeoutException ? "timeout" : "transport_error");
    }

    private static SafeFailure failure(String reason) {
        return new SafeFailure(reason);
    }

    private static final class SafeFailure extends RuntimeException {
        private SafeFailure(String reason) { super("deepgram:" + reason); }
    }

    private static Double confidence(com.fasterxml.jackson.databind.JsonNode node) {
        double value=node.asDouble(-1);return node.isNumber()&&Double.isFinite(value)&&value>=0&&value<=1?value:null;
    }
    public record Word(String text,double start,double end,Double confidence,Integer speaker) {
        public Word {
            if(text==null||text.length()>160||!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start
                    ||(confidence!=null&&(!Double.isFinite(confidence)||confidence<0||confidence>1))
                    ||(speaker!=null&&(speaker<0||speaker>99)))throw new IllegalArgumentException("invalid_word_metadata");
        }
        @Override public String toString(){return "SttWord[redacted]";}
    }
    public record Transcript(String transcript, boolean isFinal, boolean speechFinal, double start, double duration,
                             Double confidence,List<Word> words,String eventType) {
        public Transcript {words=List.copyOf(words);}
        public Transcript(String transcript,boolean isFinal,boolean speechFinal,double start,double duration) {
            this(transcript,isFinal,speechFinal,start,duration,null,List.of(),"Results");
        }
        public Transcript(String transcript, boolean isFinal, boolean speechFinal) {
            this(transcript, isFinal, speechFinal, -1, -1);
        }
        @Override
        public String toString() {
            return "DeepgramTranscript[characters=" + transcript.length() + ", isFinal=" + isFinal + "]";
        }
    }
}
