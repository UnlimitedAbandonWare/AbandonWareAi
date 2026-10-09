package com.example.lms.assist;

import com.example.lms.api.PublicChatAdmissionGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.Clock;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Focus owns its workers and deadlines; normal hint cancellation never reaches them. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class NovaFocusService implements AutoCloseable {
    private final String server=UUID.randomUUID().toString();
    private final NovaFocusHistoryService history;
    private final ObjectProvider<NovaFocusAnswer> answers;
    private final PublicChatAdmissionGuard admission;
    private final Clock clock;
    @Autowired(required=false) private FocusMemoryService memories;
    @Autowired(required=false) private JevDecisionAdvisor jevAdvisor;
    @Autowired(required=false) private com.example.lms.debug.DebugEventStore debugEvents;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Slot> scopes=new ConcurrentHashMap<>(),sessions=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"nova-focus-answer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"nova-focus-clock");t.setDaemon(true);return t;});
    private final ExecutorService diagnosticWriter=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"nova-focus-diagnostics");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicLong diagnosticDropped=new java.util.concurrent.atomic.AtomicLong(),diagnosticFailed=new java.util.concurrent.atomic.AtomicLong();
    private static final class Slot {
        final String owner,channel;
        final NovaFocusState state;
        String assistId;
        long epoch,lastSeen,settingsVersion;
        boolean busy;
        Long room;
        String pendingTurn;
        String preparedRequestId;
        NovaFocusHistoryService.Context preparedContext;
        Map<String,Object> answerDiagnostics=Map.of();
        Observation observation;
        final ArrayDeque<NovaFocusHistoryService.Pair> recent=new ArrayDeque<>();
        final LinkedHashMap<String,com.example.lms.service.ChatConversationContext.Transcript> finalized=new LinkedHashMap<>();
        long contextEpoch;
        Slot(String owner,String channel,String server,NovaFocusSettings settings){this.owner=owner;this.channel=channel;state=new NovaFocusState(server,settings);}
    }
    /** Server-bound opaque identity; never an authorization credential or a transcript hash. */
    private static final class Observation {
        final String ownerHash,sessionHash,serverInstanceHash,activationHash,requestHash;
        final long epoch,confirmedAt,startedNano=System.nanoTime();
        String turnHash;
        long answerNano;
        Map<String,Object> modelDiagnostic=Map.of();
        String failureExceptionClass,failureRootClass;
        boolean terminalRecorded;
        boolean closedRecorded;
        Observation(Slot s,String server,NovaFocusState.Request request,long now){
            ownerHash=hash(s.owner);sessionHash=hash(s.assistId);serverInstanceHash=hash(server);
            activationHash=hash(request.activationId());requestHash=hash(request.requestId());epoch=s.epoch;confirmedAt=now;
        }
    }
    private static String hash(String id){return id==null||id.isBlank()?null:com.example.lms.trace.SafeRedactor.hashValue(id);}
    private Map<String,Object> diagnostic(Slot s,Observation o,String stage,String outcome,String reason){
        if(o==null)return null;
        var row=new LinkedHashMap<String,Object>();
        row.put("stage",stage);row.put("outcome",outcome);
        row.put("reasonCode",reason!=null&&reason.matches("[a-z][a-z0-9_]{0,63}")?reason:"unknown");
        row.put("observedAtMs",stage.equals("focus_question_confirmed")?o.confirmedAt:clock.millis());
        row.put("ownerHash",o.ownerHash);row.put("sessionHash",o.sessionHash);row.put("serverInstanceHash",o.serverInstanceHash);
        row.put("activationHash",o.activationHash);row.put("requestHash",o.requestHash);row.put("turnHash",o.turnHash);
        row.put("epoch",o.epoch);row.put("currentEpoch",Objects.equals(o.sessionHash,hash(s.assistId))?s.epoch:null);
        row.put("answerVersion",s.state.view(clock.millis()).answerVersion());
        row.put("deviceHash",null);row.put("captureRun",null);row.put("connectionGeneration",null);
        row.put("latencyMs",stage.equals("focus_question_confirmed")?null:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-o.startedNano));
        row.put("renderLatencyMs",stage.startsWith("focus_first_")||stage.equals("focus_presentation_done")?
            o.answerNano==0?null:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-o.answerNano):null);
        row.put("clockDomain","server_monotonic");row.put("hardwareRenderedObserved",false);
        row.put("evidenceBoundary",stage.equals("focus_first_visible")||stage.equals("focus_presentation_done")?"dom_callback_only":"server_boundary");
        if(stage.equals("focus_terminal"))row.put("answerModel",o.modelDiagnostic);
        return Collections.unmodifiableMap(row);
    }
    private Map<String,Object> terminalDiagnostic(Slot s,Observation o,String outcome,String reason){
        if(o==null||o.terminalRecorded)return null;
        o.terminalRecorded=true;return diagnostic(s,o,"focus_terminal",outcome,reason);
    }
    private Map<String,Object> closedDiagnostic(Slot s,String reason){
        var o=s.observation;if(o==null||o.closedRecorded)return null;
        o.closedRecorded=true;return diagnostic(s,o,"focus_lifecycle_closed",reason.endsWith("timeout")?"timeout":"closed",reason);
    }
    private void emitDiagnostic(Map<String,Object> row,String where){
        if(row==null||debugEvents==null)return;
        try{
            diagnosticWriter.execute(()->{
                try{
                    var packet=new LinkedHashMap<String,Object>(row);
                    packet.put("diagnosticDropped",diagnosticDropped.get());packet.put("diagnosticFailed",diagnosticFailed.get());
                    String fingerprint=String.valueOf(row.get("serverInstanceHash"))+row.get("ownerHash")+row.get("sessionHash")+
                        row.get("requestHash")+row.get("stage")+row.get("outcome");
                    debugEvents.emit(com.example.lms.debug.DebugProbeType.ORCHESTRATION,com.example.lms.debug.DebugEventLevel.INFO,
                        fingerprint,"[AWX][nova-focus] "+row.get("stage"),where,packet,null);
                }catch(RuntimeException unavailable){diagnosticFailed.incrementAndGet();}
            });
        }catch(RejectedExecutionException full){diagnosticDropped.incrementAndGet();}
    }
    @Autowired public NovaFocusService(NovaFocusHistoryService history,ObjectProvider<NovaFocusAnswer> answers,PublicChatAdmissionGuard admission){
        this(history,answers,admission,monotonicClock());
    }
    private static Clock monotonicClock(){
        final long origin=System.nanoTime();final java.time.Instant started=java.time.Instant.now();
        return new Clock(){
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public Clock withZone(java.time.ZoneId zone){return this;}
            public java.time.Instant instant(){return started.plusNanos(System.nanoTime()-origin);}
        };
    }
    NovaFocusService(NovaFocusHistoryService history,ObjectProvider<NovaFocusAnswer> answers,PublicChatAdmissionGuard admission,Clock clock){
        this.history=history;this.answers=answers;this.admission=admission;this.clock=clock;
    }
    @PostConstruct void start(){timer.scheduleWithFixedDelay(()->{try{maintain();}catch(RuntimeException unavailable){/* A failed DB surface must not kill future ticks. */}},250,250,TimeUnit.MILLISECONDS);}
    /** Called at the existing producer binding, never from token-based lens reads. */
    public synchronized void attach(String owner,String channel,String assistId,long epoch) {
        String scope=NovaFocusHistoryService.scope(owner,channel);Slot s=scopes.get(scope);
        if(s==null){
            if(scopes.size()>=256)throw new IllegalStateException("focus_capacity");
            var settings=history.settings(owner,channel);history.recover(owner,channel);
            s=new Slot(owner,channel,server,settings.settings());s.settingsVersion=settings.settingsVersion();scopes.put(scope,s);
        }
        synchronized(s){
            if(Objects.equals(s.assistId,assistId)&&epoch<s.epoch)return;
            if(!Objects.equals(s.assistId,assistId)||s.epoch!=epoch){
                // The assist binding owns conversation lifetime; an audio segment only fences ASR.
                if(!Objects.equals(s.assistId,assistId)){
                    if(s.assistId!=null)sessions.remove(s.assistId,s);
                    cancel(s,"session_changed");
                    clearTranscript(s);
                }
                s.assistId=assistId;s.epoch=epoch;s.state.sourceNamespace(assistId+":"+epoch);sessions.put(assistId,s);
            }
            s.lastSeen=clock.millis();
        }
    }
    private Slot owned(String owner,String assistId,long epoch){
        var s=sessions.get(assistId);if(s==null||!s.owner.equals(owner)||s.epoch!=epoch)throw new IllegalArgumentException("focus_session_stale");return s;
    }
    public void defaultTarget(String owner,String assistId,long epoch,String target){
        var s=owned(owner,assistId,epoch);synchronized(s){s.state.defaultTarget(target);}
    }
    public NovaFocusHistoryService.Settings settings(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);return history.settings(owner,s.channel);
    }
    /** jev reports the server-owned decision-signal mode; clients display it, never set it. */
    public record LocalStore(String cacheScope,long settingsVersion,NovaFocusSettings settings,Map<String,Object> jev){
        public LocalStore(String cacheScope,long settingsVersion,NovaFocusSettings settings){this(cacheScope,settingsVersion,settings,Map.of());}
    }
    public LocalStore localStore(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);var value=history.settings(owner,s.channel);
        var jev=jevAdvisor==null?Map.<String,Object>of("mode","off","configured",false,"reason","jev_unavailable","callsAllowed",false):jevAdvisor.status();
        return new LocalStore(NovaFocusHistoryService.digest("local-cache:"+NovaFocusHistoryService.scope(owner,s.channel)),value.settingsVersion(),value.settings(),jev);
    }
    public boolean inputAccepted(String owner,String assistId,long epoch,String request,String text){
        var s=owned(owner,assistId,epoch);NovaFocusState.validateManualInput(request,text);
        return history.knownRequest(owner,s.channel,NovaFocusState.typedRequestId(request),text.strip());
    }
    public NovaFocusHistoryService.Settings configure(String owner,String assistId,long epoch,long expected,NovaFocusSettings value){
        var s=owned(owner,assistId,epoch);
        synchronized(s){
            var stored=history.settings(owner,s.channel,expected,value);
            boolean disabled=s.state.settings.enabled()&&!stored.settings().enabled();
            boolean consentChanged=s.state.settings.effectiveRecallEnabled()!=stored.settings().effectiveRecallEnabled()
                ||s.state.settings.effectiveRememberFactsEnabled()!=stored.settings().effectiveRememberFactsEnabled();
            boolean recentDisabled=s.state.settings.recentContextOrDefault().enabled()&&!stored.settings().recentContextOrDefault().enabled();
            s.state.configure(stored.settings(),clock.millis());s.settingsVersion=stored.settingsVersion();
            if(disabled||consentChanged){cancel(s,disabled?"focus_disabled":"memory_consent_changed");clearTranscript(s);}
            else if(recentDisabled)clearTranscript(s); // Already accepted questions keep their immutable input.
            pruneTranscript(s,clock.millis());
            return stored;
        }
    }
    public NovaFocusHistoryService.Page history(String owner,String assistId,long epoch,Long before,int limit){
        var s=owned(owner,assistId,epoch);return history.page(owner,s.channel,before,limit);
    }
    public List<FocusMemoryService.Fact> memoryList(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);if(memories==null)throw new IllegalStateException("focus_memory_unavailable");
        return memories.list(owner,s.channel);
    }
    /** Owner-bound retrieval proof without raw evidence or a chat-generation call. */
    public record MemorySearch(String status,String retrievalMode,int vectorHits,int graphHits,int evidenceCount,String degradationReason){}
    public MemorySearch memorySearch(String owner,String assistId,long epoch,String question){
        var s=owned(owner,assistId,epoch);if(memories==null)throw new IllegalStateException("focus_memory_unavailable");
        if(question==null||question.isBlank()||question.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1200)
            throw new IllegalArgumentException("invalid_memory_query");
        var scope=memories.scope(owner,s.channel);
        var r=memories.retrieve(scope,question,()->{synchronized(s){return sessions.get(assistId)==s&&s.epoch==epoch;}});
        return new MemorySearch(r.status().name(),r.retrievalMode(),r.vectorHits(),r.graphHits(),r.evidence().size(),r.degradationReason());
    }
    public FocusMemoryService.Fact memorySave(String owner,String assistId,long epoch,FocusMemoryService.Edit edit){
        var s=owned(owner,assistId,epoch);if(memories==null)throw new IllegalStateException("focus_memory_unavailable");
        synchronized(s){var result=memories.save(owner,s.channel,edit);cancel(s,"memory_changed");return result;}
    }
    public void memoryDelete(String owner,String assistId,long epoch,String source,long revision,long consent){
        var s=owned(owner,assistId,epoch);if(memories==null)throw new IllegalStateException("focus_memory_unavailable");
        synchronized(s){memories.delete(owner,s.channel,source,revision,consent);cancel(s,"memory_changed");}
    }
    /** Request identity is not known at open; the session-level start row carries only boundary hashes. */
    private Map<String,Object> startDiagnostic(Slot s){
        var row=new LinkedHashMap<String,Object>();
        row.put("stage","focus_started");row.put("outcome","open");row.put("reasonCode","none");
        row.put("observedAtMs",clock.millis());
        row.put("ownerHash",hash(s.owner));row.put("sessionHash",hash(s.assistId));row.put("serverInstanceHash",hash(server));
        row.put("epoch",s.epoch);row.put("currentEpoch",s.epoch);
        row.put("clockDomain","server_monotonic");row.put("hardwareRenderedObserved",false);
        row.put("evidenceBoundary","server_boundary");
        return Collections.unmodifiableMap(row);
    }
    public NovaFocusState.View open(String owner,String assistId,long epoch,String target) {
        var s=owned(owner,assistId,epoch);
        if(answers.getIfAvailable()==null)throw new IllegalStateException("focus_answer_unavailable");
        Long room=history.open(owner,s.channel);
        Map<String,Object> started;NovaFocusState.View view;
        synchronized(s){s.room=room;s.state.open(clock.millis(),target);s.lastSeen=clock.millis();started=startDiagnostic(s);view=s.state.view(clock.millis());}
        emitDiagnostic(started,"NovaFocusService.open");
        return view;
    }
    public void input(String owner,String assistId,long epoch,String request,String text){
        var s=owned(owner,assistId,epoch);synchronized(s){
            s.state.validateTyped(request,text);
            if(history.knownRequest(owner,s.channel,NovaFocusState.typedRequestId(request),text.strip()))s.state.alreadyAccepted();
            else s.state.typed(request,text,clock.millis());
            s.lastSeen=clock.millis();
        }
    }
    /** 확정된 질문의 단발 촬영 명령. 생산자 폴링 응답에만 실리며 구독자에게는 내려가지 않는다. */
    public NovaFocusState.Command snapshotCommand(String owner,String assistId,long epoch,long now){
        var s=sessions.get(assistId);if(s==null||!s.owner.equals(owner)||s.epoch!=epoch)return null;
        synchronized(s){return s.state.pendingCommand(now,s.settingsVersion);}
    }
    public Map<String,Object> snapshotClaim(String owner,String assistId,long epoch,String requestId,String captureId){
        var s=owned(owner,assistId,epoch);
        // 최초 claim만 granted=true — 합류/만료/불일치는 로컬 촬영을 시작하지 않는다.
        synchronized(s){return switch(s.state.claimSnapshot(requestId,captureId,clock.millis())){
            case NovaFocusState.CLAIM_GRANTED -> Map.of("claimed",true,"granted",true);
            case NovaFocusState.CLAIM_JOINED -> Map.of("claimed",true,"granted",false);
            default -> Map.of("claimed",false,"granted",false,"reason","snapshot_stale");};}
    }
    public Map<String,Object> snapshotResult(String owner,String assistId,long epoch,String requestId,String captureId,
                                             String imageBase64,String imageMediaType,String error){
        var s=owned(owner,assistId,epoch);
        synchronized(s){
            if(error!=null&&!error.isBlank()){
                String code=error.matches("[a-z0-9_]{1,40}")?error:"snapshot_failed";
                return Map.of("accepted",false,"failed",s.state.failSnapshot(requestId,captureId,code,clock.millis()));
            }
            return switch(s.state.acceptSnapshot(requestId,captureId,imageBase64,imageMediaType,clock.millis())){
                case NovaFocusState.SNAPSHOT_ACCEPTED -> Map.of("accepted",true,"duplicate",false);
                case NovaFocusState.SNAPSHOT_DUPLICATE -> Map.of("accepted",true,"duplicate",true);
                default -> Map.of("accepted",false,"reason","snapshot_stale");
            };
        }
    }
    /** Hot ASR path: no DB/provider call and no hint-side executor/cancellation. */
    public boolean audio(String owner,String assistId,long epoch,ConversateQuestionPolicy.Utterance utterance){
        var s=sessions.get(assistId);if(s==null)return false;
        synchronized(s){
            if(sessions.get(assistId)!=s||!s.owner.equals(owner)||s.epoch!=epoch)return false;
            long now=clock.millis();s.lastSeen=now;
            boolean consumed=s.state.input(utterance,now);
            rememberFinal(s,utterance,now);
            return consumed;
        }
    }
    private static void clearTranscript(Slot s){s.finalized.clear();s.contextEpoch++;}
    private static void pruneTranscript(Slot s,long now){
        var policy=s.state.settings.recentContextOrDefault();
        if(!policy.enabled()){s.finalized.clear();return;}
        s.finalized.values().removeIf(t->now-t.capturedAt()>=policy.maxAgeSeconds()*1000L);
        while(s.finalized.size()>policy.maxUtterances()||com.example.lms.service.ChatConversationContext.transcriptTokens(s.finalized.values())>policy.tokenBudget())
            s.finalized.remove(s.finalized.keySet().iterator().next());
    }
    private static void rememberFinal(Slot s,ConversateQuestionPolicy.Utterance u,long now){
        pruneTranscript(s,now);
        if(!s.state.settings.recentContextOrDefault().enabled()||!u.isFinal()||u.text().isBlank())return;
        String source=s.state.sourceId(u);var old=s.finalized.get(source);
        if(old!=null&&u.revision()<=old.revision())return;
        // Capture-time ordering and expiry do not slide when a final span is corrected.
        long capturedAt=old==null?now:old.capturedAt();
        var empty=new com.example.lms.service.ChatConversationContext.Transcript(source,u.revision(),capturedAt,s.contextEpoch,"","UNKNOWN");
        int tokenBudget=s.state.settings.recentContextOrDefault().tokenBudget();
        int textBudget=tokenBudget-com.example.lms.service.ChatConversationContext.transcriptTokens(List.of(empty));
        com.example.lms.service.ChatConversationContext.Transcript clipped;
        int encodedSize;
        do{
            clipped=new com.example.lms.service.ChatConversationContext.Transcript(source,u.revision(),
                capturedAt,s.contextEpoch,NovaFocusHistoryService.memoryClip(u.text(),textBudget),"UNKNOWN");
            encodedSize=com.example.lms.service.ChatConversationContext.transcriptTokens(List.of(clipped));
            textBudget=Math.max(0,textBudget-Math.max(1,encodedSize-tokenBudget));
        }while(encodedSize>tokenBudget);
        s.finalized.put(source,clipped);
        pruneTranscript(s,now);
    }
    public boolean active(String assistId){var s=sessions.get(assistId);if(s==null)return false;synchronized(s){return s.state.active();}}
    public Map<String,Object> diagnostics(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);synchronized(s){var result=new LinkedHashMap<>(s.state.diagnostics());result.put("busy",s.busy);
            pruneTranscript(s,clock.millis());result.put("recentFinalCount",s.finalized.size());result.put("contextEpoch",s.contextEpoch);
            result.put("answerModel",s.answerDiagnostics);result.put("diagnosticDropped",diagnosticDropped.get());
            result.put("diagnosticFailed",diagnosticFailed.get());return Map.copyOf(result);}
    }
    public NovaFocusState.View view(String owner,String assistId,long epoch){
        var s=sessions.get(assistId);if(s==null||!s.owner.equals(owner)||s.epoch!=epoch)return null;
        synchronized(s){return s.state.view(clock.millis());}
    }
    public void close(String owner,String assistId,long epoch,String reason){
        var s=owned(owner,assistId,epoch);synchronized(s){cancel(s,reason);clearTranscript(s);}
    }
    /** Explicit conversation reset keeps capture and the producer binding alive. */
    void resetContext(String owner,String assistId,long epoch){
        var s=sessions.get(assistId);if(s==null)return;
        synchronized(s){if(s.owner.equals(owner)&&s.epoch==epoch){cancel(s,"context_reset");clearTranscript(s);}}
    }
    public void detach(String assistId,String reason){
        var s=sessions.remove(assistId);if(s!=null)synchronized(s){cancel(s,reason);clearTranscript(s);}
    }
    private void cancel(Slot s,String reason){
        emitDiagnostic(terminalDiagnostic(s,s.observation,"cancelled",reason),"NovaFocusService.cancel");
        emitDiagnostic(closedDiagnostic(s,reason),"NovaFocusService.cancel");
        s.preparedContext=null;s.preparedRequestId=null;
        s.answerDiagnostics=Map.of();
        s.recent.clear();
        s.state.close(reason);
        if(s.pendingTurn!=null)history.terminal(s.owner,s.channel,s.pendingTurn,"CANCELLED",null);
        var adapter=answers.getIfAvailable();if(adapter!=null&&s.busy&&s.room!=null)adapter.cancel(s.room);
    }
    public record Receipt(String serverInstanceId,String activationId,String turnId,long answerVersion,String renderReceiptTicket,String event){
        @Override public String toString(){return "NovaFocusReceipt[redacted]";}
    }
    /** Receipt ticket grants exactly these two transitions, no owner or input authority. */
    public boolean rendered(Receipt r){
        if(r==null||r.renderReceiptTicket()==null||!r.renderReceiptTicket().matches("[a-f0-9]{64}")||r.event()==null||!Set.of("first_visible","presentation_done").contains(r.event()))return false;
        for(var s:scopes.values()){
            Map<String,Object> event=null;boolean accepted;
            synchronized(s){
                long version=s.state.view(clock.millis()).stateVersion();
                accepted=s.state.receipt(r.serverInstanceId(),r.activationId(),r.turnId(),r.answerVersion(),r.renderReceiptTicket(),r.event(),clock.millis());
                if(accepted&&s.state.view(clock.millis()).stateVersion()!=version)
                    event=diagnostic(s,s.observation,"focus_"+r.event(),"success","none");
            }
            emitDiagnostic(event,"NovaFocusService.rendered");if(accepted)return true;
        }
        return false;
    }
    void maintain(){
        long now=clock.millis();
        for(var s:scopes.values()){
            NovaFocusState.Request request;
            NovaFocusHistoryService.Context context=null;
            Observation observation=null;Map<String,Object> confirmed=null,terminal=null,closed=null;
            synchronized(s){
                boolean was=s.state.active();request=s.state.tick(now,!s.busy,s.settingsVersion);
                if(was&&!s.state.active()){
                    String reason=s.state.view(now).reason();
                    terminal=terminalDiagnostic(s,s.observation,reason.endsWith("timeout")?"timeout":"cancelled",reason);
                    closed=closedDiagnostic(s,reason);
                    cancel(s,reason);
                }
                pruneTranscript(s,now);
                var accepted=request==null?s.state.pendingQuestion():request;
                if(accepted!=null&&!Objects.equals(s.preparedRequestId,accepted.requestId())){
                    s.answerDiagnostics=Map.of();
                    s.observation=new Observation(s,server,accepted,now);
                    confirmed=diagnostic(s,s.observation,"focus_question_confirmed","accepted","none");
                    var sourceIds=accepted.sourceIds();
                    s.preparedContext=new NovaFocusHistoryService.Context(List.copyOf(s.recent),"",List.of(),
                        s.finalized.values().stream().filter(t->!sourceIds.contains(t.sourceId())).toList(),
                        accepted.answerSelection(),accepted.settingsVersion(),accepted.answerLengthChars(),accepted.quickAnswerEnabled(),accepted.webSearchEnabled(),accepted.reasoningPreset(),
                        s.state.settings.effectiveAnswerInstruction(),s.state.settings.effectiveAnswerPreset());
                    s.preparedRequestId=accepted.requestId();
                }
                if(request!=null){
                    s.busy=true;context=s.preparedContext;
                    observation=s.observation;
                    s.preparedContext=null;s.preparedRequestId=null;
                }
            }
            final var acceptedContext=context;
            final var acceptedObservation=observation;
            emitDiagnostic(confirmed,"NovaFocusService.maintain");
            if(request!=null)try{workers.execute(()->generate(s,request,acceptedContext,acceptedObservation));}
            catch(RejectedExecutionException full){synchronized(s){s.busy=false;terminal=terminalDiagnostic(s,observation,"rejected","focus_busy");s.state.failed(request,"focus_busy");}}
            emitDiagnostic(terminal,"NovaFocusService.maintain");
            emitDiagnostic(closed,"NovaFocusService.maintain");
        }
    }
    private void generate(Slot s,NovaFocusState.Request request,NovaFocusHistoryService.Context memory,Observation observation){
        com.example.lms.search.TraceStore.clear();
        String id=null;PublicChatAdmissionGuard.Lease lease=null;Map<String,Object> terminal=null;
        try{
            var adapter=answers.getIfAvailable();if(adapter==null)throw new IllegalStateException("focus_answer_unavailable");
            lease=admission.tryAcquire(s.owner).orElseThrow(PublicChatAdmissionGuard::rejection);
            synchronized(s){if(!s.state.accepts(request)){terminal=terminalDiagnostic(s,observation,"cancelled","focus_request_stale");return;}}
            var accepted=history.accept(s.owner,s.channel,request.activationId(),request.requestId(),request.question());id=accepted.turnId();
            synchronized(s){
                s.room=accepted.chatSessionId();s.pendingTurn=id;
                if(!s.state.accepts(request)){terminal=terminalDiagnostic(s,observation,"cancelled","focus_request_stale");history.terminal(s.owner,s.channel,id,"CANCELLED",null);return;}
                if(!accepted.created()){terminal=terminalDiagnostic(s,observation,"rejected","focus_request_already_accepted");s.state.failed(request,"focus_request_already_accepted");return;}
                s.state.accepted(request,id);
                if(observation!=null)observation.turnHash=hash(id);
            }
            Map<String,Object> started;synchronized(s){started=diagnostic(s,observation,"focus_generation_started","attempted","none");}
            emitDiagnostic(started,"NovaFocusService.generate");
            FocusMemoryScope scope;
            synchronized(s){scope=memories==null?null:memories.scope(s.owner,s.channel);}
            var result=answerWithRetry(s,request,accepted,memory,scope,observation,adapter);
            String answer=result.text();
            synchronized(s){
                if(observation!=null)observation.modelDiagnostic=NovaFocusAnswerService.diagnosticTrace();
                if(!s.state.accepts(request)){terminal=terminalDiagnostic(s,observation,"cancelled","focus_request_stale");history.terminal(s.owner,s.channel,id,"CANCELLED",null);return;}
                if(memories!=null&&!memories.current(scope)){terminal=terminalDiagnostic(s,observation,"cancelled","memory_changed");history.terminal(s.owner,s.channel,id,"CANCELLED",null);s.state.close("memory_changed");return;}
                s.answerDiagnostics=NovaFocusAnswerService.diagnosticTrace();
                if(!s.state.foldPrefixMatches(answer))throw new IllegalStateException("focus_stream_final_mismatch");
                if(history.terminal(s.owner,s.channel,id,"COMPLETED",answer)){
                    if(result.grounding()==null)s.recent.addLast(new NovaFocusHistoryService.Pair(0,id,"COMPLETED",
                        NovaFocusHistoryService.memoryClip(request.question(),350),NovaFocusHistoryService.memoryClip(answer,350)));
                    while(s.recent.size()>2)s.recent.removeFirst();
                    byte[] bytes=new byte[32];random.nextBytes(bytes);
                    s.state.answer(request,id,answer,HexFormat.of().formatHex(bytes),clock.millis(),result.grounding(),result.modelOutcome());
                    var published=s.state.view(clock.millis());
                    boolean answerReady="ANSWER_READY".equals(published.phase())&&Objects.equals(id,published.turnId());
                    if(answerReady&&observation!=null)observation.answerNano=System.nanoTime();
                    terminal=terminalDiagnostic(s,observation,answerReady?"success":"error",answerReady?"none":published.reason());
                }else {terminal=terminalDiagnostic(s,observation,"unknown","focus_outcome_unknown");s.state.failed(request,"focus_outcome_unknown");}
            }
        }catch(RuntimeException failure){
            synchronized(s){
                if(observation!=null){observation.modelDiagnostic=NovaFocusAnswerService.diagnosticTrace();
                    observation.failureExceptionClass=failure.getClass().getSimpleName();
                    observation.failureRootClass=rootCauseClass(failure);}
                if(s.state.accepts(request))s.answerDiagnostics=NovaFocusAnswerService.diagnosticTrace();
                if(id!=null)try{history.terminal(s.owner,s.channel,id,failure instanceof java.util.concurrent.CancellationException?"CANCELLED":"OUTCOME_UNKNOWN",null);}catch(RuntimeException unavailable){}
                String reason=failure instanceof PublicChatAdmissionGuard.Rejection?"focus_busy":"focus_answer_unavailable";
                if(java.util.Set.of("focus_grounding_publication_held","focus_search_off","focus_search_quick","focus_search_image_unsupported",
                        "focus_search_model_required","focus_search_unsupported","focus_search_not_observed","focus_search_model_mismatch",
                        "focus_search_attribution_unavailable","focus_stream_final_mismatch","focus_stream_cancelled").contains(java.util.Objects.toString(failure.getMessage(),"")))reason=failure.getMessage();
                if(request.answerSelection().routing()!=null&&request.answerSelection().routing().executionTarget()==NovaFocusSettings.ExecutionTarget.GEMINI_WEBSEARCH_ONLY
                        &&failure instanceof com.example.lms.llm.ModelSelectionException known)reason="focus_search_"+known.code();
                String diagnosticReason=focusReasonCode(failure,reason);
                String outcome="backend_timeout".equals(diagnosticReason)||"llm_timeout".equals(diagnosticReason)?"timeout":failure instanceof java.util.concurrent.CancellationException?"cancelled":"error";
                terminal=terminalDiagnostic(s,observation,outcome,diagnosticReason);
                if(terminal!=null&&observation!=null&&observation.failureExceptionClass!=null){
                    var packet=new LinkedHashMap<String,Object>(terminal);
                    packet.put("exceptionClass",observation.failureExceptionClass);
                    packet.put("exceptionRootClass",observation.failureRootClass);
                    terminal=Collections.unmodifiableMap(packet);
                }
                s.state.failed(request,reason);
                if(!s.state.active())s.recent.clear();
            }
        }finally{com.example.lms.search.TraceStore.clear();if(lease!=null)lease.close();synchronized(s){s.pendingTurn=null;s.busy=false;}
            emitDiagnostic(terminal,"NovaFocusService.generate");}
    }
    /**
     * One bounded second attempt so a single provider/runtime failure cannot close Focus
     * before the configured fallback chain has run. Each attempt re-enters the adapter
     * (fresh run + request time budget); {@code accepts()} is the live generationUntil
     * guard and a published partial pins the first stream, so no retry is allowed then.
     */
    private NovaFocusAnswer.Result answerWithRetry(Slot s,NovaFocusState.Request request,NovaFocusHistoryService.Accepted accepted,
            NovaFocusHistoryService.Context memory,FocusMemoryScope scope,Observation observation,NovaFocusAnswer adapter){
        for(int attempt=1;;attempt++){
            Map<String,Object> row;
            synchronized(s){
                if(!s.state.accepts(request))throw new java.util.concurrent.CancellationException("focus_request_stale");
                row=diagnostic(s,observation,"focus_model_attempt","attempted","none");
                if(row!=null){var packet=new LinkedHashMap<String,Object>(row);packet.put("attempt",attempt);row=Collections.unmodifiableMap(packet);}
            }
            emitDiagnostic(row,"NovaFocusService.generate");
            try{
                return adapter.answerResult(accepted.chatSessionId(),request.question(),request.imageBase64(),request.imageMediaType(),memory,scope,()->{synchronized(s){return s.state.accepts(request);}},text->{
                    synchronized(s){
                        if(!s.state.accepts(request)||(memories!=null&&!memories.current(scope)))
                            throw new java.util.concurrent.CancellationException("focus_stream_cancelled");
                        s.state.foldPartial(request,accepted.turnId(),text);
                    }
                });
            }catch(RuntimeException failure){
                synchronized(s){if(observation!=null)observation.modelDiagnostic=NovaFocusAnswerService.diagnosticTrace();}
                Map<String,Object> failed;
                synchronized(s){
                    failed=diagnostic(s,observation,"focus_model_attempt","failed",focusReasonCode(failure,null));
                    if(failed!=null){var packet=new LinkedHashMap<String,Object>(failed);packet.put("attempt",attempt);packet.put("exceptionClass",failure.getClass().getSimpleName());failed=Collections.unmodifiableMap(packet);}
                }
                emitDiagnostic(failed,"NovaFocusService.generate");
                boolean partialPublished,memoriesCurrent;
                synchronized(s){partialPublished=s.state.view(clock.millis()).answerVersion()>0;memoriesCurrent=memories==null||memories.current(scope);}
                if(attempt>=2||partialPublished||!memoriesCurrent||!focusRetryable(failure))throw failure;
            }
        }
    }
    /** Deterministic Focus outcomes and cancelled/non-retryable selections never gain a second attempt. */
    private static boolean focusRetryable(Throwable failure){
        if(failure instanceof PublicChatAdmissionGuard.Rejection||failure instanceof java.util.concurrent.CancellationException)return false;
        String message=Objects.toString(failure.getMessage(),"");
        if(message.startsWith("focus_"))return false;
        if(failure instanceof com.example.lms.llm.ModelSelectionException known)
            return Set.of("backend_timeout","backend_unavailable").contains(known.code());
        return com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable();
    }
    /** Safe reason code for the terminal diagnostic; raw exception messages stay out. */
    private static String focusReasonCode(Throwable failure,String fallback){
        if(failure instanceof PublicChatAdmissionGuard.Rejection)return fallback!=null?fallback:"focus_busy";
        if(failure instanceof com.example.lms.llm.ModelSelectionException known)return known.code();
        String message=Objects.toString(failure.getMessage(),"");
        if(message.matches("[a-z][a-z0-9_]{0,63}")&&message.startsWith("focus_"))return message;
        return switch(com.example.lms.llm.LlmErrorClassifier.classify(failure).code()){
            case "TIMEOUT"->"llm_timeout";
            case "RATE_LIMIT"->"llm_rate_limited";
            case "UPSTREAM_5XX"->"llm_upstream_5xx";
            case "BLANK_RESPONSE"->"evidence_empty";
            case "AUTH"->"llm_auth_failed";
            case "MODEL_NOT_FOUND","MODEL_REQUIRED","HTTP_4XX"->"no_model_allowed";
            case "NON_REPLAYABLE"->"llm_non_replayable";
            case "CANCELLED","INTERRUPTED"->"focus_cancelled";
            default->"llm_failed_unknown";
        };
    }
    private static String rootCauseClass(Throwable failure){
        Throwable root=failure;int hops=0;
        while(root.getCause()!=null&&root.getCause()!=root&&hops++<8)root=root.getCause();
        return root.getClass().getSimpleName();
    }
    @Override @PreDestroy public void close(){
        timer.shutdownNow();workers.shutdownNow();
        diagnosticWriter.shutdown();
        for(var s:scopes.values())synchronized(s){s.recent.clear();clearTranscript(s);s.state.close("server_shutdown");}
        sessions.clear();scopes.clear();
    }
}
