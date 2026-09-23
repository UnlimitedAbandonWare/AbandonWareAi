package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static com.example.lms.assist.ConversateSessionService.*;

/** Authenticated volatile PCM to an owned local child or explicitly selected ASR service. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateAsrBridge {
    interface Transport {void send(String line)throws IOException;CompletableFuture<Void> close();default CompletableFuture<Void> finish(){return close().thenCompose(v->CompletableFuture.failedFuture(new IOException("ASR_FINISH_UNSUPPORTED")));}boolean alive();}
    interface Factory {Transport launch(Consumer<JsonNode> events,Consumer<String> failure)throws IOException;}
    private final ConversateSessionService sessions;private final ObjectMapper json;private final Factory factory;private final ConversateCloudStt cloud;
    private SonioxSidecarManager sidecar;
    private final ConcurrentMap<String,Capture> captures=new ConcurrentHashMap<>();private final Semaphore capacity=new Semaphore(1);
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(null,r,"assist-asr-watch",0,false);t.setDaemon(true);return t;});
    @Autowired
    public ConversateAsrBridge(ConversateSessionService sessions,ObjectMapper json,
            @Value("${conversate.asr.enabled:false}") boolean enabled,@Value("${conversate.asr.python:}") String python,
            @Value("${conversate.asr.script:}") String script,@Value("${conversate.asr.model:}") String model,
            @Value("${conversate.asr.cpu-threads:2}") int cpuThreads,
            @Value("${conversate.asr.provider:local}") String provider,
            org.springframework.beans.factory.ObjectProvider<com.example.lms.service.stt.DeepgramSttService> deepgram,
            org.springframework.beans.factory.ObjectProvider<ConversateCloudStt> cloud,
            org.springframework.beans.factory.ObjectProvider<SonioxSidecarManager> sidecar){
        this(sessions,json,selectFactory(json,enabled,python,script,model,cpuThreads,provider,cloud.getIfAvailable()),cloud.getIfAvailable());
        this.sidecar=sidecar.getIfAvailable();
    }
    public ConversateAsrBridge(ConversateSessionService sessions,ObjectMapper json,boolean enabled,String python,String script,String model,int cpuThreads){
        this(sessions,json,selectFactory(json,enabled,python,script,model,cpuThreads,"local",(ConversateCloudStt)null));
    }
    static Factory selectFactory(ObjectMapper json,boolean enabled,String python,String script,String model,int cpuThreads,String provider,com.example.lms.service.stt.DeepgramSttService deepgram){
        return selectFactory(json,enabled,python,script,model,cpuThreads,provider,(ConversateCloudStt)null);
    }
    static Factory selectFactory(ObjectMapper json,boolean enabled,String python,String script,String model,int cpuThreads,String provider,ConversateCloudStt cloud){
        if(!enabled)return null;
        if(Set.of("deepgram","soniox","auto").contains(provider))return cloud!=null&&provider.equals(cloud.provider())&&cloud.configured()?cloud::openStream:null;
        if(!"local".equals(provider))return null;
        return cpuThreads>=1&&cpuThreads<=8&&configuredPaths(python,script,model)?(events,failure)->new Child(json,python,script,model,cpuThreads,cloud!=null&&cloud.configured(),events,failure):null;
    }
    ConversateAsrBridge(ConversateSessionService sessions,ObjectMapper json,Factory factory){this(sessions,json,factory,null);}
    ConversateAsrBridge(ConversateSessionService sessions,ObjectMapper json,Factory factory,ConversateCloudStt cloud){this.sessions=sessions;this.json=json;this.factory=factory;this.cloud=cloud;monitor.scheduleWithFixedDelay(()->{for(var c:captures.values()){if(c.closed.get()){if(c.transport!=null&&!c.transport.alive())c.release();}else if(c.ready.isDone()&&System.nanoTime()-c.lastInput>TimeUnit.SECONDS.toNanos(5))c.fail("ASR_INPUT_LOST");}},1,1,TimeUnit.SECONDS);}
    long renewAfterMs(){return cloud==null?540000:cloud.renewAfterMs();}
    public boolean available(){return factory!=null;}
    /** Count-only, allowlisted diagnostics for the existing anonymous Display view. */
    Map<String,Object> displayDiagnostics(AudioMetrics audio){
        var safe=new LinkedHashMap<String,Object>();var runtime=audio.runtime();
        for(String key:List.of("diagnosticRunId","provider","model","transport","device","reason","failureReason","processedMs","providerResponse","firstPartialMs","finalAfterStopMs","fallbackGapMs","shutdownMs","stopReason"))if(runtime.containsKey(key))safe.put(key,runtime.get(key));
        safe.putIfAbsent("model","not_observed");
        if(sidecar!=null){var node=sidecar.diagnostics();safe.put("nodeReady",node.get("localReady"));safe.put("nodeConfigured",node.get("configured"));safe.put("nodeState",node.get("state"));}
        if(cloud!=null){safe.put("renewAfterMs",cloud.renewAfterMs());var info=cloud.diagnostics();safe.put("configuredProvider",info.get("provider"));
            String configuredModel=Objects.toString(info.get("model"),"");
            safe.put("configuredCloudModel",Set.of("stt-rt-v4","stt-rt-v5","nova-3","whisper-large-v3-turbo","gemini-3.5-flash-lite").contains(configuredModel)?configuredModel:"not_observed");
            safe.put("routing",info.get("routing"));safe.put("providerState",info.get("state"));
            if(info.get("providers") instanceof Map<?,?> providers&&providers.get("soniox") instanceof Map<?,?> row){
                for(String key:List.of("attempts","handshakes","successes","failures","cancelled","connectedWallMs","acceptedAudioMs","lastClosedAt"))if(row.containsKey(key))safe.put("soniox_"+key,row.get(key));
            }
        }
        return Map.copyOf(safe);
    }
    /** Developer-only standalone projection; no account balances or identifiers. */
    Map<String,Object> displayUsage(){
        if(cloud==null)return Map.of("state","not_observed");
        var usage=new LinkedHashMap<String,Object>();usage.put("scope","server_process");usage.put("currency","USD");
        usage.put("costEvidence","configured_rate_estimate_not_invoice");usage.put("billedCostUsd","not_observed");
        var rows=new LinkedHashMap<String,Object>();
        if(cloud.diagnostics().get("providers") instanceof Map<?,?> providers)for(String name:List.of("soniox","deepgram","groq","gemini")){
            if(providers.get(name) instanceof Map<?,?> row){var safe=new LinkedHashMap<String,Object>();
                for(String key:List.of("attempts","handshakes","successes","failures","cancelled","acceptedAudioMs","connectedWallMs"))if(row.get(key) instanceof Number value)safe.put(key,value);
                if(row.get("estimatedMicros") instanceof Number value)safe.put("estimatedCostUsd",value.doubleValue()/1_000_000d);
                rows.put(name,Map.copyOf(safe));
            }
        }
        usage.put("providers",Map.copyOf(rows));
        try{var budget=cloud.budgetView();usage.put("costLimitsEnforced",budget.enforced());usage.put("accountingState",budget.ledgerState());
            usage.put("reservationScope","recorded".equals(budget.ledgerState())?"persistent_ledger":"server_process");
            usage.put("reservationEstimateUsd",budget.reserved().totalMicros()/1_000_000d);
            usage.put("reservedRequests",budget.reserved().requests());usage.put("reservedSeconds",budget.reserved().reservedSeconds());
        }catch(IOException unavailable){usage.put("accountingState","not_observed");}
        return Map.copyOf(usage);
    }
    public Map<String,Object> diagnostics(){
        if(cloud==null)return Map.of("state","unavailable","health","not_observed","observedAt",System.currentTimeMillis());
        var result=new LinkedHashMap<String,Object>();result.put("cloud",cloud.diagnostics());result.put("observedAt",System.currentTimeMillis());
        if(sidecar!=null)result.put("sidecar",sidecar.diagnostics());
        try{var view=cloud.budgetView();if(view!=null){result.put("budget",view);result.put("state","observed");}else result.put("state","unavailable");}
        catch(IOException unavailable){result.put("state","unavailable");}
        return Map.copyOf(result);
    }
    private static boolean configuredPaths(String python,String script,String model){
        if(python==null||script==null||model==null||python.isBlank()||script.isBlank()||model.isBlank())return false;
        try{var executable=Path.of(python);var program=Path.of(script);var directory=Path.of(model);
            return executable.isAbsolute()&&program.isAbsolute()&&directory.isAbsolute()&&Files.isRegularFile(executable)&&Files.isRegularFile(program)&&Files.isDirectory(directory);
        }catch(InvalidPathException|SecurityException invalid){return false;}
    }
    public Map<String,Object> start(String owner,String id,long epoch){
        check(owner,id,epoch);if(!available())throw error(HttpStatus.SERVICE_UNAVAILABLE,"asr_disabled");
        if(!capacity.tryAcquire())throw error(HttpStatus.TOO_MANY_REQUESTS,"asr_capacity");
        var c=new Capture(owner,id,epoch);
        try{
            if(captures.putIfAbsent(id,c)!=null)throw error(HttpStatus.CONFLICT,"capture_active");
            sessions.registerCapture(owner,id,epoch,c);
            sessions.checkCost(owner,id,epoch);
            if(c.closed.get())throw error(HttpStatus.CONFLICT,"stale_epoch");
            c.transport=factory.launch(c::event,c::fail);
            if(c.closed.get()){c.terminate();throw c.failed.get()?error(HttpStatus.SERVICE_UNAVAILABLE,"asr_unavailable"):error(HttpStatus.CONFLICT,"capture_closed");}
            c.ready.get(30,TimeUnit.SECONDS);c.lastInput=System.nanoTime();
            check(owner,id,epoch);return Map.of("state","READY","sampleRate",16000,"maxChunkBytes",7680,"storage","volatile");
        }catch(Exception failure){String why=Objects.toString(failure.getMessage(),"");c.fail(why.matches("stt_budget_(exhausted|unavailable|invalid|busy|ledger_limit)")?"ASR_"+why.substring(4).toUpperCase(Locale.ROOT):why.matches("ASR_[A-Z_]{1,40}")?why:"ASR_UNAVAILABLE");if(c.transport==null)c.release();throw failure instanceof org.springframework.web.server.ResponseStatusException r?r:error(HttpStatus.SERVICE_UNAVAILABLE,c.clientFailure("asr_unavailable"));}
    }
    public Snapshot chunk(String owner,String id,long epoch,long sequence,String pcm){
        check(owner,id,epoch);var current=sessions.status(owner,id);if("API_PAUSED".equals(current.audio().state()))throw error(HttpStatus.TOO_MANY_REQUESTS,current.reason().toLowerCase(Locale.ROOT));var c=captures.get(id);if(c==null||c.epoch!=epoch||!c.owner.equals(owner)||c.closed.get()||c.finishing)throw error(HttpStatus.CONFLICT,"capture_not_active");
        byte[] bytes;try{if(pcm==null||pcm.length()>10240)throw new IllegalArgumentException();bytes=Base64.getDecoder().decode(pcm);}catch(IllegalArgumentException invalid){throw error(HttpStatus.BAD_REQUEST,"invalid_audio");}
        if(bytes.length==0||bytes.length>7680||bytes.length%640!=0)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"audio_chunk_limit");
        String digest=org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes);Arrays.fill(bytes,(byte)0);
        synchronized(c){
            if(c.closed.get()||c.finishing)throw error(HttpStatus.CONFLICT,"capture_not_active");
            String old=c.seen.get(sequence);if(old!=null){if(!old.equals(digest))throw error(HttpStatus.CONFLICT,"sequence_conflict");c.duplicates++;c.metrics();return sessions.status(owner,id);}
            if(sequence!=c.nextSeq)throw error(HttpStatus.CONFLICT,"sequence_gap");
            c.ack=new CompletableFuture<>();c.ackSeq=sequence;
            try{if(c.firstAudioNanos==0)c.firstAudioNanos=System.nanoTime();c.transport.send(json.writeValueAsString(Map.of("seq",sequence,"pcm",pcm)));c.ack.get(2,TimeUnit.SECONDS);}
            catch(Exception failure){c.fail("ASR_INPUT_UNAVAILABLE");throw error(HttpStatus.SERVICE_UNAVAILABLE,c.clientFailure("asr_input_unavailable"));}
            c.seen.put(sequence,digest);while(c.seen.size()>64)c.seen.remove(c.seen.keySet().iterator().next());c.nextSeq++;c.chunks++;c.lastInput=System.nanoTime();c.metrics();
        }return sessions.status(owner,id);
    }
    private void check(String owner,String id,long epoch){var s=sessions.status(owner,id);if(s.epoch()!=epoch)throw error(HttpStatus.CONFLICT,"stale_epoch");if(!s.state().equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");}
    public Snapshot finish(String owner,String id,long epoch){
        check(owner,id,epoch);var c=captures.get(id);
        if(c==null)return sessions.status(owner,id);
        if(c.epoch!=epoch||!c.owner.equals(owner))throw error(HttpStatus.CONFLICT,"stale_epoch");
        long began=System.nanoTime();synchronized(c){if(c.finishing)return sessions.status(owner,id);c.finishing=true;c.finishBegan=began;}
        try{long deadline=began+TimeUnit.MILLISECONDS.toNanos(4500);c.transport.finish().get(4500,TimeUnit.MILLISECONDS);
            c.remoteFinished.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(c.failed.get())throw new IOException("ASR_FINISH_FAILED");c.runtime.put("stopReason","finished");}
        catch(Exception failure){c.runtime.put("stopReason","finish_unconfirmed");}
        finally{c.runtime.put("shutdownMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));c.metrics();c.close();sessions.captureFinished(owner,id,epoch,c);}
        return sessions.status(owner,id);
    }
    int activeCount(){return captures.size();}
    @PreDestroy public void close(){captures.values().forEach(Capture::close);monitor.shutdownNow();}
    private final class Capture implements AutoCloseable {
        final String owner,id;final long epoch;final AtomicBoolean closed=new AtomicBoolean(),released=new AtomicBoolean(),failed=new AtomicBoolean();
        final CompletableFuture<Void> ready=new CompletableFuture<>();final Map<Long,String> seen=new LinkedHashMap<>();
        volatile Transport transport;volatile CompletableFuture<Void> ack;volatile long ackSeq=-1,lastInput=System.nanoTime();
        volatile long chunks,partials,finals,duplicates,lastAsrMs,firstAudioNanos,finishBegan;long nextSeq;volatile boolean finishing;
        private final reactor.core.Disposable.Swap remote=reactor.core.Disposables.swap();
        private final AtomicBoolean remotePending=new AtomicBoolean();private volatile long handedOffThrough;
        private volatile CompletableFuture<Void> remoteFinished=CompletableFuture.completedFuture(null);
        private final Map<Long,LocalGroup> localGroups=new LinkedHashMap<>();private long completedThrough;
        private final Map<String,Object> runtime=new ConcurrentHashMap<>();
        private volatile Map<String,Object> cloudStatus=diagnostics();
        Capture(String owner,String id,long epoch){this.owner=owner;this.id=id;this.epoch=epoch;runtime.put("diagnosticRunId",UUID.randomUUID().toString());}
        void event(JsonNode event){
            if(closed.get())return;
            switch(event.path("type").asText()){
                case "ready" -> {runtime(event);cloudStatus=diagnostics();ready.complete(null);metrics();}
                case "ack" -> {boundedNumber(event,"queueLength",2);var waiting=ack;if(waiting!=null&&event.path("seq").asLong(-1)==ackSeq)waiting.complete(null);}
                case "progress" -> {runtime(event);boundedNumber(event,"processedMs",600000);runtime.put("providerResponse","observed");metrics();}
                case "transcript" -> transcript(event,false);
                case "fallback" -> fallback(event);
                case "error" -> {runtime(event);fail("ASR_PROCESSING_FAILED");}
                default -> fail("ASR_PROTOCOL_FAILED");
            }
        }
        synchronized void transcript(JsonNode event,boolean recovered){
            if(closed.get())return;
            try{String text=event.path("text").asText(),utterance=event.path("utteranceId").asText();boolean grouped=event.has("groupId");
                if((text.isBlank()&&!grouped)||text.length()>(grouped?2048:8192))throw new IllegalArgumentException();
                if(!recovered&&!text.isEmpty()&&utterance.matches("asr-[1-9][0-9]{0,9}")&&Long.parseLong(utterance.substring(4))<=handedOffThrough)return;
                if(!recovered)runtime(event);
                boolean fin=event.path("final").asBoolean();long revision=event.path("revision").asLong();
                if(!event.path("revision").isIntegralNumber()||revision<0||revision>1_000_000)throw new IllegalArgumentException();
                var words=List.<com.example.lms.service.stt.DeepgramSttService.Word>of();
                if(event.has("words")){
                    if(!event.path("words").isArray()||event.path("words").size()>1024)throw new IllegalArgumentException();
                    words=json.convertValue(event.path("words"),json.getTypeFactory().constructCollectionType(List.class,com.example.lms.service.stt.DeepgramSttService.Word.class));
                }
                Double confidence=null;
                if(event.hasNonNull("confidence")){if(!event.path("confidence").isNumber())throw new IllegalArgumentException();confidence=event.path("confidence").asDouble();}
                var accepted=new ConversateQuestionPolicy.Utterance(utterance,utterance,(int)revision,fin,text,confidence,words);
                if(grouped){accepted=localUtterance(event,text,fin,(int)revision);if(accepted==null){duplicates++;metrics();return;}fin=accepted.isFinal();}
                sessions.submit(owner,id,epoch,accepted,"phone_voice",null);
                runtime.put("providerResponse","observed");
                if(!fin&&partials==0&&firstAudioNanos>0)runtime.put("firstPartialMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-firstAudioNanos));
                if(fin&&finishing)runtime.put("finalAfterStopMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-finishBegan));
                if(fin)finals++;else partials++;lastAsrMs=Math.max(0,event.path("asrMs").asLong());metrics();
            }catch(Exception invalid){if(!closed.get())fail("ASR_TRANSCRIPT_REJECTED");}
        }
        private ConversateQuestionPolicy.Utterance localUtterance(JsonNode event,String text,boolean finalized,int revision){
            String segment=event.path("utteranceId").asText(),group=event.path("groupId").asText();
            if(!segment.matches("asr-[1-9][0-9]{0,9}")||!group.matches("asr-[1-9][0-9]{0,9}")||!event.path("utteranceEnd").isBoolean())throw new IllegalArgumentException();
            long first=Long.parseLong(group.substring(4)),number=Long.parseLong(segment.substring(4));boolean end=event.path("utteranceEnd").asBoolean();
            if(number<first||number-first>=64||end&&!finalized)throw new IllegalArgumentException();
            if(first<=completedThrough)return null;
            if(!localGroups.containsKey(first)&&localGroups.size()>=4)throw new IllegalArgumentException();
            var pending=localGroups.computeIfAbsent(first,key->new LocalGroup(first));
            var prior=pending.segments.get(number);
            if(prior!=null&&revision<prior.revision())return null;
            if(!text.isEmpty()){
                if(prior!=null&&prior.finalized()&&!prior.text().equals(text))throw new IllegalArgumentException();
                if(prior!=null&&revision==prior.revision()&&!prior.text().equals(text))throw new IllegalArgumentException();
                pending.segments.put(number,new LocalSegment(text,revision,finalized));
            }
            if(end)pending.last=number;
            var combined=new StringBuilder();boolean complete=pending.last>=first;
            long through=pending.last>=first?pending.last:number;
            for(long index=first;index<=through;index++){
                var item=pending.segments.get(index);if(item==null){complete=false;break;}
                if(!combined.isEmpty())combined.append(' ');combined.append(item.text());
                if(!item.finalized()){complete=false;break;}
            }
            if(combined.length()>8192)throw new IllegalArgumentException();
            String joined=combined.toString();
            if(joined.isEmpty()||joined.equals(pending.published)&&!complete)return null;
            pending.published=joined;
            if(complete){completedThrough=pending.last;localGroups.remove(first);}
            return new ConversateQuestionPolicy.Utterance(group,group,++pending.revision,complete,joined);
        }
        void fallback(JsonNode event){
            try{
                String utterance=event.path("utteranceId").asText(),encoded=event.path("pcm").asText();int revision=event.path("revision").asInt(-1);
                if(!utterance.matches("asr-[1-9][0-9]{0,9}")||!event.path("final").asBoolean()||revision<1||revision>10000||encoded.length()>682668)throw new IOException("asr_fallback_invalid");
                long number=Long.parseLong(utterance.substring(4));if(number<=handedOffThrough){duplicates++;metrics();return;}
                if(cloud==null||!remotePending.compareAndSet(false,true))throw new IOException("asr_fallback_unavailable");
                byte[] pcm=Base64.getDecoder().decode(encoded);
                if(pcm.length<640||pcm.length>512000||pcm.length%640!=0){Arrays.fill(pcm,(byte)0);throw new IOException("asr_fallback_invalid");}
                handedOffThrough=number;long began=System.nanoTime();var completion=new CompletableFuture<Void>();remoteFinished=completion;
                runtime.clear();runtime.put("device","remote");runtime.put("reason","local_failed_cloud_candidate");
                // Fence before subscribing; one final result replaces this utterance's partial.
                remote.update(cloud.transcribe(pcm).doFinally(signal->{Arrays.fill(pcm,(byte)0);remotePending.set(false);}).subscribe(text->{
                    if(!closed.get()&&(!text.isBlank()||event.has("groupId"))){
                        cloudStatus=diagnostics();var cloudInfo=cloud.diagnostics();
                        String observedProvider=Objects.toString(cloudInfo.get("selectedProvider"),"");
                        String observedModel=Objects.toString(cloudInfo.get("model"),"");
                        if(Set.of("deepgram","soniox","groq","gemini").contains(observedProvider))runtime.put("provider",observedProvider);
                        if(Set.of("nova-3","stt-rt-v4","stt-rt-v5","whisper-large-v3-turbo","gemini-3.5-flash-lite").contains(observedModel))runtime.put("model",observedModel);
                        runtime.put("reason","local_failed_cloud_recovered");var result=json.createObjectNode().put("text",text).put("utteranceId",utterance).put("revision",revision).put("final",true).put("asrMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));
                        if(event.has("groupId")){result.set("groupId",event.path("groupId"));result.set("utteranceEnd",event.path("utteranceEnd"));}transcript(result,true);}
                    completion.complete(null);
                },failure->{completion.completeExceptionally(failure);if(!closed.get()){cloudStatus=diagnostics();String reason=failure.getMessage();runtime.put("reason",Set.of("stt_budget_exhausted","stt_budget_unavailable","stt_budget_invalid","stt_cloud_busy","stt_circuit_open").contains(reason==null?"":reason)?reason:"cloud_failed");fail("ASR_FALLBACK_UNAVAILABLE");}}));
                cloudStatus=diagnostics();metrics();
            }catch(Exception invalid){if(!closed.get())fail("ASR_FALLBACK_UNAVAILABLE");}
        }
        void boundedNumber(JsonNode node,String key,long maximum){var value=node.path(key);if(value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>=0&&value.asLong()<=maximum)runtime.put(key,value.asLong());}
        void runtime(JsonNode event){
            var node=event.path("runtime");if(!node.isObject())return;
            String model=node.path("model").asText();if(Set.of("tiny","tiny.en","base","base.en","small","small.en","medium","medium.en","large-v1","large-v2","large-v3","large-v3-turbo","turbo","not_observed").contains(model))runtime.put("model",model);
            boundedNumber(node,"fallbackGapMs",600000);
            for(String key:List.of("provider","device","reason","transport")){String value=node.path(key).asText();
                if((key.equals("provider")&&Set.of("whisper","deepgram","soniox").contains(value))||(key.equals("device")&&Set.of("cpu","cuda","remote").contains(value))||(key.equals("transport")&&Set.of("node_sdk","java_ws","local_process").contains(value))||(key.equals("reason")&&Set.of("primary","starting","local_ready","cloud_provider_fallback","sidecar_failed_legacy","gpu_unavailable","gpu_load_failed","gpu_decode_failed","gpu_timeout_cpu_fallback","gpu_worker_cpu_fallback","asr_startup_failed","asr_native_failed").contains(value)))runtime.put(key,value);
            }
            runtime.remove("gpu");runtime.remove("vramFreeMiB");runtime.remove("vramObservedAt");
            if("cuda".equals(node.path("device").asText())){String uuid=node.path("gpuUuid").asText();if(uuid.matches("GPU-[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")){runtime.put("gpu","RTX3060_"+uuid.substring(uuid.length()-8));boundedNumber(node,"vramFreeMiB",131072);boundedNumber(node,"vramObservedAt",4102444800000L);}}
            boundedNumber(node,"queueLength",2);boundedNumber(node,"errors",1000000);
        }
        void metrics(){var safe=new LinkedHashMap<>(runtime);safe.put("cloudStatus",cloudStatus);sessions.audioMetrics(owner,id,epoch,new AudioMetrics(chunks,partials,finals,duplicates,lastAsrMs,finishing?(runtime.containsKey("stopReason")?"STOPPED":"FINISHING"):chunks>0?"CAPTURING":ready.isDone()?"READY":"STARTING",safe));}
        String clientFailure(String fallback){String reason=Objects.toString(runtime.get("failureReason"),"");return Set.of("asr_auth_failed","asr_quota_exceeded","asr_rate_limited","asr_audio_format_invalid","asr_provider_failed","asr_provider_disconnected","asr_budget_unavailable","asr_budget_exhausted","asr_budget_invalid","asr_budget_busy","asr_budget_ledger_limit").contains(reason)?reason:fallback;}
        void fail(String reason){
            if(reason!=null&&reason.matches("soniox:(auth_failed|quota_exceeded|rate_limited|audio_format_invalid|provider_error)"))reason="ASR_"+reason.substring(7).toUpperCase(Locale.ROOT).replace("PROVIDER_ERROR","PROVIDER_FAILED");
            if(reason==null||!reason.matches("ASR_[A-Z_]{1,40}"))reason="ASR_PROCESSING_FAILED";
            if(failed.compareAndSet(false,true)){runtime.putIfAbsent("reason",reason.toLowerCase(Locale.ROOT));runtime.put("failureReason",reason.toLowerCase(Locale.ROOT));if(finishing)runtime.put("stopReason","finish_unconfirmed");metrics();if(!finishing)sessions.captureFailed(owner,id,epoch,reason);}close();
        }
        public void close(){if(closed.compareAndSet(false,true)){remote.dispose();ready.completeExceptionally(new IOException("capture_closed"));var waiting=ack;if(waiting!=null)waiting.completeExceptionally(new IOException("capture_closed"));terminate();}}
        void terminate(){var t=transport;if(t!=null)try{t.close().whenComplete((v,e)->{if(!t.alive())release();});}catch(RuntimeException failure){if(!t.alive())release();}}
        void release(){if(released.compareAndSet(false,true)){captures.remove(id,this);capacity.release();}}
    }
    private record LocalSegment(String text,int revision,boolean finalized) {}
    private static final class LocalGroup {
        final long first;final Map<Long,LocalSegment> segments=new TreeMap<>();long last=-1;int revision;String published="";
        LocalGroup(long first){this.first=first;}
    }
    /** Only this Process object can be terminated; no PID/name scan, shared model or shell command. */
    static final class Child implements Transport {
        private final Process process;private final BufferedWriter input;private final CompletableFuture<Void> exited;
        private final CompletableFuture<Void> finished=new CompletableFuture<>();private boolean finishing;
        private final AtomicBoolean closing=new AtomicBoolean();
        Child(ObjectMapper json,String python,String script,String model,int cpuThreads,Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
            this(json,python,script,model,cpuThreads,false,events,failure);
        }
        Child(ObjectMapper json,String python,String script,String model,int cpuThreads,boolean allowFallback,Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
            if(!configuredPaths(python,script,model))throw new IOException("asr_paths_unavailable");
            if(cpuThreads<1||cpuThreads>8)throw new IOException("asr_cpu_threads_invalid");
            var builder=new ProcessBuilder(python,"-B","-u",script,model,Integer.toString(cpuThreads)).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).matches(".*(API_KEY|TOKEN|SECRET|PASSWORD).*"));
            builder.environment().put("PYTHONDONTWRITEBYTECODE","1");builder.environment().put("HF_HUB_OFFLINE","1");
            builder.environment().put("CONVERSATE_ASR_FALLBACK",allowFallback?"1":"0");
            process=builder.start();input=process.outputWriter(StandardCharsets.UTF_8);exited=process.onExit().thenApply(p->null);
            var reader=new Thread(null,()->{try(var output=process.inputReader(StandardCharsets.UTF_8)){String line;while((line=boundedLine(output))!=null){
                var event=json.readTree(line);if("finished".equals(event.path("type").asText())){finished.complete(null);break;}events.accept(event);
            }}catch(Exception ignored){}finally{if(!finished.isDone()){finished.completeExceptionally(new IOException("ASR_PROCESS_EXITED"));if(!closing.get())failure.accept("ASR_PROCESS_EXITED");}}},"assist-asr-protocol",0,false);reader.setDaemon(true);reader.start();
        }
        private static String boundedLine(Reader reader)throws IOException{var line=new StringBuilder();int c;while((c=reader.read())!=-1&&c!='\n'){if(line.length()>=524288)throw new IOException("asr_protocol_limit");line.append((char)c);}return c==-1&&line.isEmpty()?null:line.toString();}
        public synchronized void send(String line)throws IOException{if(finishing||!process.isAlive())throw new IOException("asr_process_exited");input.write(line);input.newLine();input.flush();}
        public synchronized CompletableFuture<Void> finish(){if(!finishing){try{send("{\"type\":\"finish\"}");finishing=true;}catch(IOException failure){finished.completeExceptionally(failure);}}return finished;}
        public CompletableFuture<Void> close(){closing.set(true);process.destroyForcibly();return exited;}
        public boolean alive(){return process.isAlive();}
    }
}
