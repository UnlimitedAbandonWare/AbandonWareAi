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
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Slot> scopes=new ConcurrentHashMap<>(),sessions=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"nova-focus-answer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"nova-focus-clock");t.setDaemon(true);return t;});
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
        final ArrayDeque<NovaFocusHistoryService.Pair> recent=new ArrayDeque<>();
        final LinkedHashMap<String,com.example.lms.service.ChatConversationContext.Transcript> finalized=new LinkedHashMap<>();
        long contextEpoch;
        Slot(String owner,String channel,String server,NovaFocusSettings settings){this.owner=owner;this.channel=channel;state=new NovaFocusState(server,settings);}
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
    public NovaFocusState.View open(String owner,String assistId,long epoch,String target) {
        var s=owned(owner,assistId,epoch);
        if(answers.getIfAvailable()==null)throw new IllegalStateException("focus_answer_unavailable");
        Long room=history.open(owner,s.channel);
        synchronized(s){s.room=room;s.state.open(clock.millis(),target);s.lastSeen=clock.millis();return s.state.view(clock.millis());}
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
            result.put("answerModel",s.answerDiagnostics);return Map.copyOf(result);}
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
        for(var s:scopes.values())synchronized(s){
            if(s.state.receipt(r.serverInstanceId(),r.activationId(),r.turnId(),r.answerVersion(),r.renderReceiptTicket(),r.event(),clock.millis()))return true;
        }
        return false;
    }
    void maintain(){
        long now=clock.millis();
        for(var s:scopes.values()){
            NovaFocusState.Request request;
            NovaFocusHistoryService.Context context=null;
            synchronized(s){
                boolean was=s.state.active();request=s.state.tick(now,!s.busy,s.settingsVersion);
                if(was&&!s.state.active())cancel(s,s.state.view(now).reason());
                pruneTranscript(s,now);
                var accepted=request==null?s.state.pendingQuestion():request;
                if(accepted!=null&&!Objects.equals(s.preparedRequestId,accepted.requestId())){
                    s.answerDiagnostics=Map.of();
                    var sourceIds=accepted.sourceIds();
                    s.preparedContext=new NovaFocusHistoryService.Context(List.copyOf(s.recent),"",List.of(),
                        s.finalized.values().stream().filter(t->!sourceIds.contains(t.sourceId())).toList(),
                        accepted.answerSelection(),accepted.settingsVersion(),accepted.answerLengthChars(),accepted.quickAnswerEnabled(),accepted.webSearchEnabled());
                    s.preparedRequestId=accepted.requestId();
                }
                if(request!=null){
                    s.busy=true;context=s.preparedContext;
                    s.preparedContext=null;s.preparedRequestId=null;
                }
            }
            final var acceptedContext=context;
            if(request!=null)try{workers.execute(()->generate(s,request,acceptedContext));}
            catch(RejectedExecutionException full){synchronized(s){s.busy=false;s.state.failed(request,"focus_busy");}}
        }
    }
    private void generate(Slot s,NovaFocusState.Request request,NovaFocusHistoryService.Context memory){
        com.example.lms.search.TraceStore.clear();
        String id=null;PublicChatAdmissionGuard.Lease lease=null;
        try{
            var adapter=answers.getIfAvailable();if(adapter==null)throw new IllegalStateException("focus_answer_unavailable");
            lease=admission.tryAcquire(s.owner).orElseThrow(PublicChatAdmissionGuard::rejection);
            synchronized(s){if(!s.state.accepts(request))return;}
            var accepted=history.accept(s.owner,s.channel,request.activationId(),request.requestId(),request.question());id=accepted.turnId();
            synchronized(s){
                s.room=accepted.chatSessionId();s.pendingTurn=id;
                if(!s.state.accepts(request)){history.terminal(s.owner,s.channel,id,"CANCELLED",null);return;}
                if(!accepted.created()){s.state.failed(request,"focus_request_already_accepted");return;}
                s.state.accepted(request,id);
            }
            FocusMemoryScope scope;
            synchronized(s){scope=memories==null?null:memories.scope(s.owner,s.channel);}
            var result=adapter.answerResult(accepted.chatSessionId(),request.question(),request.imageBase64(),request.imageMediaType(),memory,scope,()->{synchronized(s){return s.state.accepts(request);}},text->{
                synchronized(s){
                    if(!s.state.accepts(request)||s.settingsVersion!=request.settingsVersion()||(memories!=null&&!memories.current(scope)))
                        throw new java.util.concurrent.CancellationException("focus_stream_cancelled");
                    s.state.foldPartial(request,accepted.turnId(),text);
                }
            });
            String answer=result.text();
            synchronized(s){
                if(!s.state.accepts(request)||(memories!=null&&!memories.current(scope))){history.terminal(s.owner,s.channel,id,"CANCELLED",null);s.state.close("memory_changed");return;}
                s.answerDiagnostics=NovaFocusAnswerService.diagnosticTrace();
                if(!s.state.foldPrefixMatches(answer))throw new IllegalStateException("focus_stream_final_mismatch");
                if(history.terminal(s.owner,s.channel,id,"COMPLETED",answer)){
                    if(result.grounding()==null)s.recent.addLast(new NovaFocusHistoryService.Pair(0,id,"COMPLETED",
                        NovaFocusHistoryService.memoryClip(request.question(),350),NovaFocusHistoryService.memoryClip(answer,350)));
                    while(s.recent.size()>2)s.recent.removeFirst();
                    byte[] bytes=new byte[32];random.nextBytes(bytes);
                    s.state.answer(request,id,answer,HexFormat.of().formatHex(bytes),clock.millis(),result.grounding());
                }else s.state.failed(request,"focus_outcome_unknown");
            }
        }catch(RuntimeException failure){
            synchronized(s){
                if(s.state.accepts(request))s.answerDiagnostics=NovaFocusAnswerService.diagnosticTrace();
                if(id!=null)try{history.terminal(s.owner,s.channel,id,"OUTCOME_UNKNOWN",null);}catch(RuntimeException unavailable){}
                String reason=failure instanceof PublicChatAdmissionGuard.Rejection?"focus_busy":"focus_answer_unavailable";
                if(java.util.Set.of("focus_grounding_publication_held","focus_search_off","focus_search_quick","focus_search_image_unsupported",
                        "focus_search_model_required","focus_search_unsupported","focus_search_not_observed","focus_search_model_mismatch",
                        "focus_search_attribution_unavailable","focus_stream_final_mismatch","focus_stream_cancelled").contains(java.util.Objects.toString(failure.getMessage(),"")))reason=failure.getMessage();
                if(request.answerSelection().routing()!=null&&request.answerSelection().routing().executionTarget()==NovaFocusSettings.ExecutionTarget.GEMINI_WEBSEARCH_ONLY
                        &&failure instanceof com.example.lms.llm.ModelSelectionException known)reason="focus_search_"+known.code();
                s.state.failed(request,reason);
                if(!s.state.active())s.recent.clear();
            }
        }finally{com.example.lms.search.TraceStore.clear();if(lease!=null)lease.close();synchronized(s){s.pendingTurn=null;s.busy=false;}}
    }
    @Override @PreDestroy public void close(){
        timer.shutdownNow();workers.shutdownNow();
        for(var s:scopes.values())synchronized(s){s.recent.clear();clearTranscript(s);s.state.close("server_shutdown");}
        sessions.clear();scopes.clear();
    }
}
