package com.example.lms.assist;

import com.example.lms.service.stt.DeepgramSttService;
import com.example.lms.service.stt.SonioxSttService;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** One bounded STT socket at a time. Reuses the existing Korean Deepgram transport and credential owner. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public final class ConversateCloudStt {
    private final DeepgramSttService service;private final ConversateSttBudget budget;private final ObjectMapper json;
    private final SonioxSttService soniox;private final String provider;private final boolean localFirst;
    private final LongSupplier nanos;private final Duration deadline,cooldown;
    private boolean busy;
    private final ConversateSttRouting routing;
    @org.springframework.beans.factory.annotation.Value("${conversate.asr.cloud.routing:fixed}") private String routingMode="fixed";
    @org.springframework.beans.factory.annotation.Value("${conversate.asr.cloud.deepgram-estimate-micros-per-minute:8000}") private long deepgramRate=8000;
    @org.springframework.beans.factory.annotation.Value("${conversate.asr.cloud.soniox-estimate-micros-per-minute:2000}") private long sonioxRate=2000;
    @Autowired(required=false) private ConversateSttAccounts accounts;
    @Autowired(required=false) private SonioxSidecarManager sidecar;
    @Autowired(required=false) private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;
    @Autowired(required=false) private com.example.lms.agent.GroqFreeTierGuard groqGuard;
    @Autowired(required=false) private com.example.lms.learning.gemini.GeminiGateway gemini;
    @Autowired(required=false) private org.springframework.core.env.Environment environment;
    @Autowired(required=false) private org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;
    @org.springframework.beans.factory.annotation.Value("${conversate.asr.cloud.utterance-provider:legacy}") private String utteranceProvider="legacy";
    @org.springframework.beans.factory.annotation.Value("${soniox.stt.region:${SONIOX_STT_REGION:us}}") private String sonioxRegion="us";
    private volatile String selectedProvider;
    @org.springframework.beans.factory.annotation.Value("${conversate.asr.cloud.stream-seconds:600}") private long streamSeconds=600;
    long renewAfterMs(){return Math.max(5000,(streamSeconds()-10)*1000);}
    private long streamSeconds(){return Math.max(10,Math.min(600,streamSeconds));}
    @Autowired public ConversateCloudStt(ObjectProvider<DeepgramSttService> service,ObjectProvider<SonioxSttService> soniox,ConversateSttBudget budget,ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${conversate.asr.provider:local}") String provider){
        this(service.getIfAvailable(),soniox.getIfAvailable(),provider,budget,json,System::nanoTime,Duration.ofSeconds(12),Duration.ofSeconds(60));
    }
    ConversateCloudStt(DeepgramSttService service,ConversateSttBudget budget,ObjectMapper json,LongSupplier nanos,Duration deadline,Duration cooldown){
        this(service,null,"deepgram",budget,json,nanos,deadline,cooldown);
    }
    ConversateCloudStt(DeepgramSttService service,SonioxSttService soniox,String provider,ConversateSttBudget budget,ObjectMapper json,LongSupplier nanos,Duration deadline,Duration cooldown){
        this.service=service;this.budget=budget;this.json=json;this.nanos=nanos;this.deadline=deadline;this.cooldown=cooldown;
        this.localFirst="local".equals(provider);
        this.soniox=soniox;this.provider=Set.of("soniox","auto","economy").contains(provider)?provider:"deepgram";
        this.selectedProvider="auto".equals(this.provider)?"soniox":"economy".equals(this.provider)?"gemini":this.provider;
        this.routingMode="auto".equals(this.provider)?"auto":"fixed";
        this.routing=new ConversateSttRouting(nanos,cooldown);
    }
    String provider(){return provider;}
    private boolean auto(){return "auto".equals(provider)||"auto".equals(routingMode);}
    private boolean providerConfigured(String name){return name.equals("soniox")?soniox!=null&&soniox.isConfigured():service!=null&&service.isConfigured();}
    boolean configured(){return "economy".equals(provider)||localFirst&&"economy".equals(utteranceProvider)?economyConfigured():(auto()?providerConfigured("soniox")||providerConfigured("deepgram"):providerConfigured(provider))&&budget.configured()&&validRates();}
    private String groqKey(){return environment==null?"":environment.getProperty("GROQ_API_KEY","");}
    private boolean economyConfigured(){return groqGuard!=null&&groqGuard.speechVerified(groqKey())||gemini!=null&&gemini.speechConfigured()&&budget.configured();}
    private boolean validRates(){return deepgramRate>0&&deepgramRate<=8000&&sonioxRate>0&&sonioxRate<=8000;}
    private Map<String,Long> rates(){return Map.of("deepgram",deepgramRate,"soniox",sonioxRate,"groq",0L,"gemini",8000L);}
    private String model(String name){return switch(name){case "groq"->"whisper-large-v3-turbo";case "gemini"->com.example.lms.learning.gemini.GeminiGateway.SPEECH_MODEL;case "soniox"->soniox==null?"not_observed":Objects.toString(soniox.model(),"not_observed");default->"nova-3";};}
    public synchronized Map<String,Object> diagnostics(){
        var view=new LinkedHashMap<String,Object>();String selected=selectedProvider;
        view.put("provider",provider);view.put("selectedProvider",selected);view.put("routing",auto()?"auto":"fixed");
        view.put("model",model(selected));view.put("language",Set.of("groq","gemini").contains(selected)?"original_audio":selected.equals("soniox")?"ko,en":"ko");
        view.put("configured",configured());view.put("state",busy?"IN_FLIGHT":routing.circuit(selected));view.put("errors",routing.errors(selected));view.put("reason",routing.reason(selected));
        view.put("health","not_proven_by_configuration");view.put("disabledReason",configured()?"":"configuration_or_budget");
        var providers=new LinkedHashMap<String,Object>();
        routing.view(rates()).forEach((p,value)->{var row=new LinkedHashMap<String,Object>((Map<String,Object>)value);row.put("model",model(p));row.put("region",p.equals("soniox")&&Set.of("us","eu","jp","in").contains(sonioxRegion)?sonioxRegion:"not_observed");providers.put(p,Map.copyOf(row));});
        view.put("groqFreeOnly",true);view.put("groqQuotaScope","shared_app_reservations_not_organization_balance");
        view.put("groqDisabledReason",groqGuard==null?"groq_free_guard_unavailable":groqGuard.disabledReason("whisper-large-v3-turbo",groqKey()));
        view.put("providers",Map.copyOf(providers));view.put("accounts",accounts==null?Map.of("enabled",false):accounts.diagnostics());
        return Map.copyOf(view);
    }
    public ConversateSttBudget.View budgetView()throws IOException{return budget.view();}
    private synchronized Ticket enter(double seconds)throws IOException {
        if(!configured())throw new IOException("stt_cloud_unavailable");
        if(busy)throw new IOException("stt_cloud_busy");
        if(accounts!=null)accounts.refreshIfDue();
        List<String> candidates=(auto()?List.of("soniox","deepgram"):List.of(provider)).stream()
                .filter(this::providerConfigured).filter(p->accounts==null||!accounts.exhausted(p)).toList();
        candidates=routing.order(candidates,seconds,rates());
        if(candidates.isEmpty())throw new IOException("stt_circuit_or_funds_unavailable");
        busy=true;return new Ticket(candidates);
    }
    private final class Ticket implements AutoCloseable {
        final List<String> candidates;
        final long deadlineNanos=System.nanoTime()+deadline.toNanos();
        private final AtomicBoolean closed=new AtomicBoolean();
        Ticket(List<String> candidates){this.candidates=candidates;}
        synchronized ConversateSttRouting.Attempt attempt(String name,long seconds)throws IOException{
            return attempt(name,seconds,false);
        }
        synchronized ConversateSttRouting.Attempt attempt(String name,long seconds,boolean streaming)throws IOException{
            if(closed.get())throw new IOException("stt_cancelled");
            // Every alternate attempt reserves independently. Unknown charges are never refunded.
            budget.reserve(seconds);selectedProvider=name;return routing.begin(name,rates().get(name),streaming?"pcm_stream":"complete_utterance");
        }
        public synchronized void close(){if(closed.compareAndSet(false,true))synchronized(ConversateCloudStt.this){busy=false;}}
    }
    private static String reason(Throwable error){
        if(error instanceof java.util.concurrent.TimeoutException)return "timeout";
        String value=Objects.toString(error.getMessage(),"");
        if(value.matches("(deepgram|soniox):(http_[0-9]{3}|timeout|transport_error|auth_failed|quota_exceeded|rate_limited|audio_format_invalid|provider_error|unexpected_close)"))return value.substring(value.indexOf(':')+1);
        if(value.matches("ASR_[A-Z_]{1,40}"))return value.substring(4).toLowerCase(Locale.ROOT);
        return "transport_error";
    }
    private static boolean retryable(Throwable error){
        if(error instanceof java.util.concurrent.TimeoutException)return true;
        String value=Objects.toString(error.getMessage(),"");
        return value.matches("(deepgram|soniox):(http_(401|402|403|408|429|5[0-9]{2})|timeout|transport_error|auth_failed|quota_exceeded|rate_limited|provider_error|unexpected_close)");
    }
    ConversateAsrBridge.Transport openStream(Consumer<JsonNode> events,Consumer<String> failure)throws IOException {
        Ticket ticket=enter(streamSeconds());
        try {
            String first=ticket.candidates.get(0);
            var initial=ticket.attempt(first,streamSeconds()+1,true); // A denied first reservation must not enter failover.
            Consumer<JsonNode> receive=event->{
                if(event instanceof com.fasterxml.jackson.databind.node.ObjectNode object&&!ticket.candidates.get(0).equals(selectedProvider)&&"sidecar_failed_legacy".equals(object.path("runtime").path("reason").asText()))object.with("runtime").put("reason","cloud_provider_fallback");
                events.accept(event);
            };
            var notified=new AtomicBoolean();
            Consumer<String> failed=why->{ticket.close();if(notified.compareAndSet(false,true))failure.accept(why);};
            ConversateAsrBridge.Factory primary=(e,f)->openSelectedProvider(ticket,first,initial,e,why->{if(streamRetryable(why))f.accept(why);else failed.accept(why);});
            ConversateAsrBridge.Transport transport;
            if(ticket.candidates.size()==2){
                String second=ticket.candidates.get(1);
                transport=new FailoverAsrTransport(json,primary,(e,f)->{synchronized(ticket){return openSelectedProvider(ticket,second,ticket.attempt(second,streamSeconds()+1,true),e,f);}},receive,failed);
            }else transport=primary.launch(receive,failed);
            var timer=Mono.delay(Duration.ofSeconds(streamSeconds())).subscribe(ignored->{if(!ticket.closed.get()){transport.close().whenComplete((v,e)->ticket.close());failed.accept("ASR_CAPTURE_LIMIT");}});
            return new ConversateAsrBridge.Transport(){
                public void send(String line)throws IOException{transport.send(line);}
                public CompletableFuture<Void> close(){timer.dispose();return transport.close().whenComplete((v,e)->ticket.close());}
                public CompletableFuture<Void> finish(){timer.dispose();return transport.finish().whenComplete((v,e)->ticket.close());}
                public boolean alive(){return transport.alive();}
            };
        }catch(Exception failed){ticket.close();if(failed instanceof IOException io)throw io;throw new IOException("stt_cloud_unavailable");}
    }
    private ConversateAsrBridge.Transport openSelectedProvider(Ticket ticket,String name,ConversateSttRouting.Attempt initial,Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
        if(!name.equals("soniox")||sidecar==null||!sidecar.configured())return openProvider(name,initial,false,events,failure);
        // Node and Java are two transports for the same provider. Both attempts remain under this ticket.
        return new FailoverAsrTransport(json,(e,f)->openProvider(name,initial,true,e,f),(e,f)->{
            synchronized(ticket){
                ConversateSttRouting.Attempt next;
                try{next=ticket.attempt(name,streamSeconds()+1,true);}catch(IOException denied){ticket.close();String reason=Objects.toString(denied.getMessage(),"");failure.accept(reason.matches("stt_budget_(exhausted|unavailable|invalid|busy|ledger_limit)")?"ASR_"+reason.substring(4).toUpperCase(Locale.ROOT):"ASR_BUDGET_UNAVAILABLE");throw denied;}
                return openProvider(name,next,false,e,f);
            }
        },events,failure,why->"ASR_SIDECAR_FAILED".equals(why));
    }
    private static boolean streamRetryable(String reason){
        return reason!=null&&(reason.matches("ASR_(FALLBACK_UNAVAILABLE|STREAM_FAILED|STREAM_ENDED|HTTP_(401|402|403|408|429|5[0-9]{2})|TIMEOUT|TRANSPORT_ERROR|AUTH_FAILED|QUOTA_EXCEEDED|RATE_LIMITED|PROVIDER_ERROR|UNEXPECTED_CLOSE)")||retryable(new IOException(reason)));
    }
    private ConversateAsrBridge.Transport openProvider(String name,ConversateSttRouting.Attempt attempt,boolean nativeSoniox,Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
        try{
            Consumer<JsonNode> receive=event->{
                if("ready".equals(event.path("type").asText()))attempt.connected();
                if("transcript".equals(event.path("type").asText())&&event.path("final").asBoolean()){
                    attempt.success();
                    if(apiFailureRecorder!=null)try{
                        apiFailureRecorder.recordSuccess(name,"soniox".equals(name)?(soniox!=null?soniox.model():"unconfirmed"):"nova-3");
                    }catch(RuntimeException ignored){}
                }
                if(event instanceof com.fasterxml.jackson.databind.node.ObjectNode object)object.set("runtime",json.createObjectNode().put("provider",name).put("device","remote").put("reason","primary").put("transport",nativeSoniox?"node_sdk":"java_ws"));events.accept(event);
            };
            Consumer<String> failed=why->{
                if(apiFailureRecorder!=null&&nativeSoniox){
                    int status=why!=null&&why.matches("ASR_HTTP_[1-5][0-9]{2}")?Integer.parseInt(why.substring(9)):0;
                    if(status>0)apiFailureRecorder.record(name,soniox!=null?soniox.model():"unconfirmed",status,null,null);
                    else apiFailureRecorder.recordSignal(name,soniox!=null?soniox.model():"unconfirmed",why);
                }
                attempt.failure(reason(new IOException(why)));attempt.close();failure.accept(why);
            };
            ConversateAsrBridge.Transport transport=nativeSoniox?sidecar.connectAdmittedStream(receive,failed):name.equals("soniox")?new SonioxAsrTransport(json,soniox,receive,failed):new DeepgramAsrTransport(json,service,receive,failed);
            return new ConversateAsrBridge.Transport(){
                public void send(String line)throws IOException{
                    // Validate/count locally without retaining a second PCM buffer.
                    var frame=json.readTree(line);String encoded=frame.path("pcm").asText();
                    transport.send(line);long count=encoded.length()/4L*3-(encoded.endsWith("==")?2:encoded.endsWith("=")?1:0);attempt.audio(count);
                }
                public CompletableFuture<Void> close(){return transport.close().whenComplete((v,e)->attempt.close());}
                public CompletableFuture<Void> finish(){return transport.finish().whenComplete((v,e)->attempt.close());}
                public boolean alive(){return transport.alive();}
            };
        }catch(Exception error){attempt.failure(reason(error));attempt.close();String why=error.getMessage();
            if(nativeSoniox&&Set.of("soniox_sidecar_unavailable","soniox_sidecar_unconfigured").contains(Objects.toString(why,"")))why="ASR_SIDECAR_FAILED";
            throw new IOException(why!=null&&why.matches("ASR_[A-Z_]{1,40}")?why:"stt_cloud_unavailable");}
    }
    /** Only a complete local utterance is accepted. Partials never cause a billable subscription. */
    Mono<String> transcribe(byte[] pcm) {
        if("economy".equals(provider)||"economy".equals(utteranceProvider))return transcribeEconomy(pcm);
        if(pcm==null||pcm.length<640||pcm.length>320_000||pcm.length%640!=0)return Mono.error(new IOException("stt_audio_limit"));
        return Mono.using(()->enter(pcm.length/32000.0),ticket->{
            Duration perAttempt=ticket.candidates.size()==2?deadline.dividedBy(2):deadline;
            Mono<String> first=transcribeAttempt(ticket,ticket.candidates.get(0),pcm,perAttempt);
            if(ticket.candidates.size()==2)first=first.onErrorResume(e->retryable(e)&&!ticket.closed.get()?transcribeAttempt(ticket,ticket.candidates.get(1),pcm,perAttempt):Mono.error(e));
            return first;
        },Ticket::close,true);
    }
    /** Complete segments only. Admission fallback happens before the first wire request; no audio retry ladder. */
    private Mono<String> transcribeEconomy(byte[] pcm) {
        if(pcm==null||pcm.length<640||pcm.length>512000||pcm.length%640!=0)return Mono.error(new IOException("stt_audio_limit"));
        if(digitalSilence(pcm))return Mono.just("");
        return Mono.defer(()->{
            synchronized(this){if(busy)return Mono.error(new IOException("stt_cloud_busy"));busy=true;}
            String name="gemini";com.example.lms.agent.GroqFreeTierGuard.Reservation reservation=null;
            if(groqGuard!=null&&groqGuard.speechVerified(groqKey())&&webClientBuilder!=null){
                try{reservation=groqGuard.reserve("whisper-large-v3-turbo",groqKey(),0,(pcm.length+31999)/32000);name="groq";}
                catch(IOException unavailable){/* No wire attempt occurred; select the existing paid fallback once. */}
            }
            try{
                if(name.equals("gemini")){
                    if(gemini==null||!gemini.speechConfigured())throw new IOException("stt_economy_unavailable");
                    // Existing budget units are conservative reservation-seconds, not billed audio.
                    // 30 units reserve $0.004: covers <=16s audio + prompt + 1024 output tokens
                    // at official 2026-09-17 Flash-Lite $0.30/$2.50 per million token prices.
                    budget.reserve(30);
                }
                selectedProvider=name;byte[] wav=wav(pcm);String selected=name;
                var attempt=routing.begin(name,name.equals("groq")?0:8000,"complete_utterance");attempt.audio(pcm.length);
                Mono<String> wire=name.equals("groq")?groqSpeech(wav,reservation):gemini.transcribeAudio(wav);
                return wire.doOnSuccess(text->{attempt.connected();attempt.success();
                        if(apiFailureRecorder!=null)try{
                            apiFailureRecorder.recordSuccess(selected,
                                    selected.equals("groq")?"whisper-large-v3-turbo":com.example.lms.learning.gemini.GeminiGateway.SPEECH_MODEL);
                        }catch(RuntimeException ignored){}})
                    .doOnError(error->{attempt.failure("speech_failed");if(apiFailureRecorder!=null)apiFailureRecorder.recordSignal(selected,
                        selected.equals("groq")?"whisper-large-v3-turbo":com.example.lms.learning.gemini.GeminiGateway.SPEECH_MODEL,"speech_failed");})
                    .doFinally(signal->{Arrays.fill(wav,(byte)0);attempt.close();synchronized(this){busy=false;}});
            }catch(Exception denied){synchronized(this){busy=false;}return Mono.error(denied);}
        });
    }
    private Mono<String> groqSpeech(byte[] wav,com.example.lms.agent.GroqFreeTierGuard.Reservation reservation){
        var parts=new org.springframework.http.client.MultipartBodyBuilder();
        parts.part("file",new org.springframework.core.io.ByteArrayResource(wav){@Override public String getFilename(){return "segment.wav";}}).contentType(org.springframework.http.MediaType.parseMediaType("audio/wav"));
        parts.part("model","whisper-large-v3-turbo");parts.part("response_format","verbose_json");parts.part("temperature","0");
        var observed=new AtomicBoolean();
        return webClientBuilder.clone().baseUrl("https://api.groq.com").build().post().uri("/openai/v1/audio/transcriptions")
            .headers(headers->headers.setBearerAuth(groqKey())).header("User-Agent","AWX-Synthetic-STT/1.0")
            .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA).bodyValue(parts.build())
            .exchangeToMono(response->{
                int code=response.statusCode().value();groqGuard.observe(reservation,code,response.headers().asHttpHeaders());observed.set(true);
                if(code!=200)return response.releaseBody().then(Mono.error(new IOException("groq_speech_http_"+code)));
                return response.bodyToMono(JsonNode.class).flatMap(payload->{
                    String text=payload.path("text").asText("").strip();
                    if(text.length()>2048||!payload.path("text").isTextual())return Mono.error(new IOException("groq_speech_invalid_response"));
                    return Mono.just(text);
                }).switchIfEmpty(Mono.error(new IOException("groq_speech_empty_response")));
            }).timeout(Duration.ofSeconds(4)).onErrorMap(error->error instanceof IOException?error:new IOException("groq_speech_transport_error"))
            .doFinally(signal->{if(!observed.get())groqGuard.observe(reservation,0,Map.of());});
    }
    static boolean digitalSilence(byte[] pcm){
        // Only suppress an effectively zero PCM signal; this is not a claim of general noise recognition.
        for(int i=0;i<pcm.length;i+=2)if(Math.abs((short)((pcm[i]&255)|(pcm[i+1]<<8)))>2)return false;
        return true;
    }
    static byte[] wav(byte[] pcm){
        var b=java.nio.ByteBuffer.allocate(pcm.length+44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(pcm.length+36).putInt(0x45564157).putInt(0x20746d66).putInt(16).putShort((short)1)
            .putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(pcm.length).put(pcm);
        return b.array();
    }
    private Mono<String> transcribeAttempt(Ticket ticket,String name,byte[] pcm,Duration limit){
        return Mono.using(()->ticket.attempt(name,Math.max(1,deadline.toSeconds()+1)),attempt->{
            List<byte[]> frames=new ArrayList<>();for(int offset=0;offset<pcm.length;offset+=32_000)frames.add(Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+32_000)));
            Flux<byte[]> audio=Flux.fromIterable(frames).doOnNext(bytes->attempt.audio(bytes.length));
            return Mono.defer(()->{
                if(name.equals("soniox"))return soniox.transcribePcm16Mono(audio,attempt::connected).filter(SonioxSttService.Transcript::isFinal).take(129).collectList().map(results->{
                    String text=results.stream().map(SonioxSttService.Transcript::text).collect(java.util.stream.Collectors.joining(" "));
                    if(results.size()>128||text.isBlank()||text.length()>2048)throw new IllegalStateException("stt_transcript_unavailable");return text;
                });
                return service.transcribePcm16Mono(audio,16000,"ko").take(129).collectList().map(ConversateCloudStt::assemble);
            }).timeout(Duration.ofNanos(Math.max(1,Math.min(limit.toNanos(),ticket.deadlineNanos-System.nanoTime()))))
                    .doOnSuccess(ignored->attempt.success()).doOnError(e->attempt.failure(reason(e)))
                    .doFinally(signal->frames.forEach(bytes->Arrays.fill(bytes,(byte)0)));
        },ConversateSttRouting.Attempt::close,true);
    }
    private static String assemble(List<DeepgramSttService.Transcript> results) {
        if(results.size()>128)throw new IllegalStateException("stt_transcript_limit");
        var timed=new TreeMap<Double,DeepgramSttService.Transcript>();
        for(var result:results){if(!result.isFinal()&&!result.speechFinal()||result.transcript().isBlank())continue;
            if(!Double.isFinite(result.start())||!Double.isFinite(result.duration())||result.start()<0||result.duration()<=0||result.start()+result.duration()>11)throw new IllegalStateException("stt_timing_unavailable");
            var old=timed.putIfAbsent(result.start(),result);if(old!=null&&(!old.transcript().equals(result.transcript())||old.duration()!=result.duration()))throw new IllegalStateException("stt_segment_conflict");}
        var text=new StringJoiner(" ");double end=0;
        for(var result:timed.values()){if(result.start()+0.001<end)throw new IllegalStateException("stt_segment_overlap");text.add(result.transcript());end=result.start()+result.duration();}
        String value=text.toString();if(value.isBlank()||value.length()>2048)throw new IllegalStateException("stt_transcript_unavailable");return value;
    }
}
