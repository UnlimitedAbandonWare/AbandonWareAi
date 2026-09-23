package com.example.lms.assist;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.api.PublicChatAdmissionGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live content deliberately has no repository, Redis, history, logging or browser persistence adapter. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateSessionService implements AutoCloseable {
    public record Card(String decision,String kind,String text,List<String> sourceIds,long expiresAt,String requestId,List<String> sourceTitles,List<String> detailPages) {
        public Card(String decision,String kind,String text,List<String> sourceIds,long expiresAt,String requestId,List<String> sourceTitles){this(decision,kind,text,sourceIds,expiresAt,requestId,sourceTitles,List.of());}
        public Card(String decision,String kind,String text,List<String> sourceIds,long expiresAt){this(decision,kind,text,sourceIds,expiresAt,null,List.of());}
        public Card {
            detailPages=detailPages==null?List.of():List.copyOf(detailPages);
            if(detailPages.size()>128||detailPages.stream().anyMatch(page->page==null||page.codePointCount(0,page.length())>120))throw error(HttpStatus.BAD_REQUEST,"invalid_card_pages");
            sourceIds=List.copyOf(sourceIds);
            if(sourceTitles!=null&&sourceTitles.stream().anyMatch(Objects::isNull))throw error(HttpStatus.BAD_REQUEST,"invalid_card");
            sourceTitles=sourceTitles==null?List.of():List.copyOf(sourceTitles);
            if(text==null||text.codePointCount(0,text.length())>HINT_TEXT_MAX||sourceIds.size()>4||sourceTitles.size()>4
                    ||(requestId!=null&&!requestId.matches("[A-Za-z0-9._:-]{1,128}"))
                    ||(requestId==null&&!sourceTitles.isEmpty())
                    ||sourceTitles.stream().anyMatch(title->title.isBlank()||title.codePointCount(0,title.length())>120||title.chars().anyMatch(Character::isISOControl)))
                throw error(HttpStatus.BAD_REQUEST,"invalid_card");
        }
        @Override public String toString(){return "Card[redacted]";}
    }
    public record Metrics(long started,long suppressed,long duplicates,long dropped,int queueLength,int inFlight,int preparedSources,long samples,long lastProcessingMs,long generationAttempts,long complexJudgments,long verificationHolds,long lastGenerationMs,long searchAttempts,long queryRefinements,int contextTurns,long cancelled,long expired,long outputAcks,long lastOutputAckEpoch,long lastOutputAckVersion,long lastOutputAckAt,ConversateAnswerPipeline.Stages stages,long fixtureRuns){}
    public record AudioMetrics(long chunks,long partials,long finals,long duplicates,long lastAsrMs,String state,Map<String,Object> runtime){
        public AudioMetrics(long chunks,long partials,long finals,long duplicates,long lastAsrMs,String state){this(chunks,partials,finals,duplicates,lastAsrMs,state,Map.of());}
        public AudioMetrics{runtime=Map.copyOf(runtime);}
    }
    public record Caption(String utteranceId,int revision,boolean isFinal,String text,Double confidence,
                          List<com.example.lms.service.stt.DeepgramSttService.Word> words,long receivedAt,long expiresAt){
        public Caption {words=List.copyOf(words);}
        @Override public String toString(){return "Caption[redacted]";}
    }
    public record WearDiagnostics(String inputPath,String requestId,String inputDecision,long lastFinalAt,Long sinceFinalMs,String cueId,long renderedVersion,long renderedCount,String lensVerification,
                                  Long firstTranscriptAt,Long finalTranscriptAt,Long hintCompletedAt,Long captionRenderedAt,Long hintRenderedAt,Long hintForFinalAt){}
    public record Snapshot(String assistId,long epoch,String state,Card card,long version,int outputConnections,String reason,Metrics metrics,AudioMetrics audio,WearDiagnostics diagnostics,Caption caption,NovaFocusState.View focus){
        public Snapshot(String assistId,long epoch,String state,Card card,long version,int outputConnections,String reason,Metrics metrics,AudioMetrics audio,WearDiagnostics diagnostics,Caption caption){this(assistId,epoch,state,card,version,outputConnections,reason,metrics,audio,diagnostics,caption,null);}
    }
    @Autowired(required=false) private NovaFocusService novaFocus;
    private static final long GRACE_MS=5_000,DISCONNECTED_RETENTION_MS=3_600_000;
    static final long ROLLING_EXPIRY=9_007_199_254_740_991L;
    /** Single source for the display hint hard cap (chars/lines); generation targets stay configurable below it. */
    public static final int HINT_TEXT_MAX=1180,HINT_LINE_MAX=24;
    @org.springframework.beans.factory.annotation.Value("${conversate.transcript.rolling-enabled:false}") private boolean rollingEnabled;
    @org.springframework.beans.factory.annotation.Value("${conversate.transcript.visible-chars:200}") private int visibleChars=200;
    @org.springframework.beans.factory.annotation.Value("${conversate.transcript.context-chars:2000}") private int contextChars=2000;
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.cooldown-ms:10000}") private long cueCooldownMs=10000;
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.force-after-ms:180000}") private long forceAfterMs=180000;
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.force-min-delta-chars:50}") private int forceMinDeltaChars=50;
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.trigger-min-delta-chars:120}") private int triggerMinDeltaChars=120;
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.trigger-quiet-ms:2500}") private long triggerQuietMs=2500;
    private final Clock clock;
    private ConversateAnswerPipeline pipeline;
    private PublicChatAdmissionGuard admission=new PublicChatAdmissionGuard();
    @org.springframework.beans.factory.annotation.Value("${conversate.display-ttl-ms:15000}") private long displayTtlMs=15000;
    private volatile java.util.function.Function<String,LensDisplayPrefs> displayPrefs;
    public void displayPrefs(java.util.function.Function<String,LensDisplayPrefs> lookup){displayPrefs=lookup;}
    private LensDisplayPrefs lensPrefs(String owner){var lookup=displayPrefs;return lookup==null?null:lookup.apply(owner);}
    private int hintTargetChars(String owner){var prefs=lensPrefs(owner);return prefs==null?0:prefs.hintTargetChars();}
    @org.springframework.beans.factory.annotation.Value("${conversate.context-ttl-ms:120000}") private long contextTtlMs=120000;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(null,r,"conversate-answer",0,false);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"conversate-lifecycle");t.setDaemon(true);return t;});
    public ConversateSessionService(){this(Clock.systemUTC());}
    ConversateSessionService(Clock clock){this(clock,new ConversateAnswerPipeline());}
    ConversateSessionService(Clock clock,ConversateAnswerPipeline pipeline){this.clock=clock;this.pipeline=pipeline;scheduler.setRemoveOnCancelPolicy(true);}
    @Autowired void sharedAdmission(PublicChatAdmissionGuard admission){this.admission=admission;}
    @Autowired(required=false) void localGeneration(ConversateLocalCardGenerator generator){this.pipeline=new ConversateAnswerPipeline(generator);}
    @Autowired(required=false) private com.example.lms.service.ChatService sharedRag;
    @Autowired(required=false) private ConversateApiCueService apiCues;
    @Autowired(required=false) private com.example.lms.util.TokenCounter tokens;
    private int estTokens(String text){if(text==null||text.isEmpty())return 0;return tokens!=null?Math.max(1,tokens.count(text)):(text.length()+1)/2;}
    @PostConstruct void initialize(){if(sharedRag!=null)pipeline.sharedRag(sharedRag);if(apiCues!=null)pipeline.apiCues(apiCues);scheduler.scheduleWithFixedDelay(this::maintain,250,250,TimeUnit.MILLISECONDS);}
    public Snapshot start(String owner) {return start(owner,()->{});}
    public synchronized Snapshot start(String owner,Runnable costCheck) {
        return start(owner,costCheck,false);
    }
    public synchronized Snapshot startPublicDisplay(String owner){return start(owner,()->{},true);}
    private Snapshot start(String owner,Runnable costCheck,boolean publicDisplay) {
        if(owner==null||owner.isBlank())throw error(HttpStatus.UNAUTHORIZED,"authentication_required");
        if(sessions.size()>=64||sessions.values().stream().filter(s->s.owner.equals(owner)).count()>=2)throw error(HttpStatus.TOO_MANY_REQUESTS,"assist_capacity");
        var s=new Session(owner,clock.millis(),Objects.requireNonNull(costCheck),publicDisplay);sessions.put(s.id,s);return snapshot(s);
    }
    void checkCost(String owner,String id,long epoch){var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");}s.costCheck.run();synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");}}
    public Snapshot status(String owner,String id){var s=owned(owner,id);synchronized(s){return snapshot(s);}}
    /** Short-lived output presence for HTTPS transports that do not carry SSE. */
    public Snapshot pollOutput(String owner,String id,long epoch,String client){
        if(client==null||!client.matches("[a-f0-9]{32}"))throw error(HttpStatus.BAD_REQUEST,"invalid_output_client");
        var s=owned(owner,id);synchronized(s){
            checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");
            if(!s.pollOutputs.containsKey(client)){if(outputCount(s)>=4)throw error(HttpStatus.TOO_MANY_REQUESTS,"output_capacity");s.version++;}
            s.pollOutputs.put(client,clock.millis());s.disconnectedAt=-1;return snapshot(s);
        }
    }
    private static int outputCount(Session s){return s.outputs+s.pollOutputs.size();}
    boolean hintsEnabled(String owner,String id){var s=owned(owner,id);synchronized(s){return s.hintsEnabled;}}
    boolean usesApiCues(){return pipeline.usesApiCues();}
    /** Client-declared receipt only: does not prove hardware visibility or human reading. */
    public Snapshot acknowledge(String owner,String id,long epoch,long version){var s=owned(owner,id);synchronized(s){
        checkEpoch(s,epoch);if(version<1||version>s.version||outputCount(s)==0)throw error(HttpStatus.CONFLICT,"invalid_output_ack");
        if(s.lastOutputAckEpoch!=epoch||version>s.lastOutputAckVersion){s.lastOutputAckEpoch=epoch;s.lastOutputAckVersion=version;s.lastOutputAckAt=clock.millis();s.outputAcks++;}
        // No version increment: acknowledgements must never create a self-sustaining SSE/ACK loop.
        return snapshot(s);
    }}
    /** Rendering remains client-declared; neither phase proves physical lens visibility. */
    public Snapshot acknowledge(String owner,String id,long epoch,long version,String phase){
        if(phase==null||phase.equals("received"))return acknowledge(owner,id,epoch,version);
        if(phase.equals("caption_rendered")){
            var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
                if(!s.state.equals("RUNNING")||outputCount(s)==0||s.caption==null||s.caption.expiresAt()<=clock.millis()
                        ||version<s.captionVersion||version>s.version)throw error(HttpStatus.CONFLICT,"invalid_caption_ack");
                if(s.captionRenderedVersion!=s.captionVersion){s.captionRenderedVersion=s.captionVersion;s.captionRenderedAt=clock.millis();}
                return snapshot(s);
            }
        }
        if(!phase.equals("rendered"))throw error(HttpStatus.BAD_REQUEST,"invalid_output_phase");
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
            if(!s.state.equals("RUNNING")||outputCount(s)==0||s.card==null||s.card.expiresAt()<=clock.millis()
                    ||version<s.cueVersion||version>s.version)throw error(HttpStatus.CONFLICT,"invalid_render_ack");
            if(s.renderedEpoch!=epoch||s.renderedVersion<s.cueVersion){s.renderedEpoch=epoch;s.renderedVersion=s.cueVersion;s.renderedCount++;s.hintRenderedAt=clock.millis();}
            return snapshot(s);
        }
    }
    /** Session-only background. Loading never dispatches a model or becomes a transcript. */
    public Snapshot setBackground(String owner,String id,long epoch,String text){
        if(text==null||text.length()>8000||text.indexOf('\0')>=0)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"context_limit");
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
            if(s.capture!=null)throw error(HttpStatus.CONFLICT,"capture_active");
            cancelWork(s);s.background=text;s.card=null;s.version++;s.reason="background_ready";return snapshot(s);
        }
    }
    int backgroundChars(String owner,String id){var s=owned(owner,id);synchronized(s){return s.background.length();}}
    /** Bounded control-panel test: no microphone, provider, or hint generation. */
    Snapshot displayTest(String owner,String id,long epoch,String text){
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");
            if(s.capture!=null)throw error(HttpStatus.CONFLICT,"capture_active");cancelWork(s);s.card=null;
            long now=clock.millis();s.caption=new Caption("display-test-"+UUID.randomUUID(),1,true,text,null,List.of(),now,now+captionTtl(s.owner));
            s.captionVersion=++s.version;s.captionEpoch=s.epoch;s.reason="DISPLAY_TEST";return snapshot(s);
        }
    }
    /** Ended PCM segment gets a fresh audio epoch; its pending hint remains valid until new speech. */
    Snapshot nextSegment(String owner,String id,long epoch){
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
            if(!s.state.equals("RUNNING")||s.capture!=null||(!"finished".equals(s.audio.runtime().get("stopReason"))&&!"WAITING".equals(s.audio.state())&&!"API_PAUSED".equals(s.audio.state())))throw error(HttpStatus.CONFLICT,"segment_not_finished");
            s.segmentHintEpoch=s.epoch;s.epoch++;s.version++;s.policy.clear();s.audioOrder.clear();s.contextResetAudioMark=Map.of();
            s.audio=new AudioMetrics(0,0,0,0,0,"OFF");s.reason="SEGMENT_READY";return snapshot(s);
        }
    }
    public Snapshot prepare(String owner,String id,long epoch,List<PreparedMaterialReader.Material> material){
        if(material==null||material.size()>8||material.stream().mapToInt(m->m.text().length()).sum()>65536)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"prepared_material_limit");
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);closeCapture(s);cancelWork(s);s.epoch++;s.policy.clear();s.context.clear();resetCueTracking(s);s.materials=List.copyOf(material);s.card=null;s.version++;s.reason="materials_selected";return snapshot(s);}
    }
    void registerCapture(String owner,String id,long epoch,AutoCloseable capture){var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");if(s.capture!=null)throw error(HttpStatus.CONFLICT,"capture_active");
        // Provider utterance IDs restart only with a new capture; text fallback retains deduplication.
        s.policy.clear();s.capture=capture;diagnostic(s,"audio_resumed",Map.of("transcriptPreserved",s.caption!=null));}}
    // Keep the final caption and its pending hint while detaching only this finished capture.
    // The next audio/start advances the existing epoch before accepting new PCM.
    void captureFinished(String owner,String id,long epoch,AutoCloseable capture){var s=sessions.get(id);if(s==null||!s.owner.equals(owner))return;synchronized(s){if(s.epoch==epoch&&s.capture==capture){s.capture=null;s.audio=new AudioMetrics(s.audio.chunks(),s.audio.partials(),s.audio.finals(),s.audio.duplicates(),s.audio.lastAsrMs(),s.publicDisplay&&!"finished".equals(s.audio.runtime().get("stopReason"))?"WAITING":"STOPPED",s.audio.runtime());s.version++;}}}
    void audioMetrics(String owner,String id,long epoch,AudioMetrics metrics){var s=sessions.get(id);if(s==null||!s.owner.equals(owner))return;synchronized(s){if(s.epoch==epoch&&s.state.equals("RUNNING")){s.audio=metrics;s.version++;}}}
    void captureFailed(String owner,String id,long epoch,String reason){var s=sessions.get(id);if(s==null||!s.owner.equals(owner))return;synchronized(s){if(s.epoch==epoch&&s.state.equals("RUNNING")){
        if(s.publicDisplay||rollingEnabled&&apiLimited(reason)){var capture=s.capture;s.capture=null;s.reason=reason;s.audio=new AudioMetrics(s.audio.chunks(),s.audio.partials(),s.audio.finals(),s.audio.duplicates(),s.audio.lastAsrMs(),apiLimited(reason)?"API_PAUSED":"WAITING",s.audio.runtime());s.version++;diagnostic(s,"audio_waiting",Map.of("reason",reason));diagnostic(s,"transcript_preserved",Map.of("present",s.caption!=null));if(capture!=null)try{capture.close();}catch(Exception ignored){} }
        else pause(s,reason,true);
    }}}
    private static boolean apiLimited(String reason){return reason!=null&&reason.matches("ASR_(?:BUDGET_[A-Z_]+|QUOTA_EXCEEDED|RATE_LIMITED|AUTH_FAILED|HTTP_(?:401|402|403|429))");}
    public Snapshot submit(String owner,String id,long epoch,ConversateQuestionPolicy.Utterance utterance){
        return submit(owner,id,epoch,utterance,"direct",null);
    }
    public Snapshot submit(String owner,String id,long epoch,ConversateQuestionPolicy.Utterance utterance,String inputPath,String requestId){
        if(inputPath==null||!Set.of("direct","glasses_input","phone_voice","test","openai_direct").contains(inputPath))throw error(HttpStatus.BAD_REQUEST,"invalid_input_path");
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");
            if("phone_voice".equals(inputPath)&&staleAudio(s,utterance)){s.duplicates++;return snapshot(s);}
            if("phone_voice".equals(inputPath)&&preResetAudio(s,utterance)){s.duplicates++;diagnostic(s,"CONTEXT_STALE_AUDIO",Map.of("contextEpoch",s.contextEpoch));return snapshot(s);}
            boolean rolling=rollingEnabled&&"phone_voice".equals(inputPath);
            boolean focusInput="phone_voice".equals(inputPath)&&novaFocus!=null&&novaFocus.audio(owner,id,epoch,utterance);
            var decision=(pipeline.usesApiCues()||rolling)?s.policy.acceptForCue(utterance):s.publicDisplay&&!"phone_voice".equals(inputPath)?s.policy.acceptExplicit(utterance):s.policy.accept(utterance);s.reason=decision.kind();s.version++;
            s.inputDecision=decision.kind();s.inputPath=inputPath;
            if(utterance.isFinal()&&!Set.of("DUPLICATE","STALE").contains(decision.kind())){
                s.lastFinalAt=clock.millis();s.lastFinalNanos=System.nanoTime();
                s.inputRequestId=requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,128}")?requestId:
                        "assist-"+org.apache.commons.codec.digest.DigestUtils.sha256Hex(s.id+":"+epoch+":"+utterance.utteranceId()).substring(0,24);
            }
            if(decision.kind().equals("DUPLICATE")||decision.kind().equals("STALE")){s.duplicates++;return snapshot(s);}
            if("phone_voice".equals(inputPath)){
                rememberAudioOrder(s,utterance);
                long now=clock.millis();
                if(s.caption==null||s.captionEpoch!=s.epoch||!s.caption.utteranceId().equals(utterance.utteranceId())){s.firstTranscriptAt=now;s.finalTranscriptAt=null;if(!rolling){cancelWork(s);s.card=null;}}
                if(utterance.isFinal())s.finalTranscriptAt=now;
                String visible=rolling?rollingText(s,utterance):utterance.text();
                s.caption=new Caption(utterance.utteranceId(),utterance.revision(),utterance.isFinal(),visible,utterance.confidence(),rolling?List.of():utterance.words(),now,rolling?ROLLING_EXPIRY:now+captionTtl(s.owner));
                diagnostic(s,utterance.isFinal()?"STT_FINAL":"STT_PARTIAL",Map.of("chars",utterance.text().length()));
                s.captionVersion=s.version;s.captionEpoch=s.epoch;s.captionRenderedAt=null;
                if(focusInput||!s.hintsEnabled){if(utterance.isFinal()){pruneContext(s,now);remember(s,rolling?s.epoch+":"+utterance.questionId():utterance.questionId(),utterance.text(),now);}if(focusInput){cancelWork(s);s.focusWasActive=true;}s.suppressed++;return snapshot(s);}
            }
            if(!utterance.isFinal()){s.suppressed++;return snapshot(s);}
            if(utterance.text().isBlank()){s.suppressed++;return snapshot(s);}
            if(!rolling&&!pipeline.usesApiCues()&&decision.kind().equals("RESOLVED")){cancelWork(s);s.context.clear();s.card=null;s.suppressed++;return snapshot(s);}
            pruneContext(s,clock.millis());
            if(!rolling&&!pipeline.usesApiCues()&&decision.kind().equals("NEW_INFORMATION")){remember(s,utterance.questionId(),utterance.text(),clock.millis());s.suppressed++;return snapshot(s);}
            if(!rolling&&!pipeline.usesApiCues()&&!Set.of("QUESTION","CORRECTION","HINT").contains(decision.kind())){s.suppressed++;return snapshot(s);}
            String contextKey=rolling?s.epoch+":"+utterance.questionId():utterance.questionId();
            s.context.removeIf(t->t.key().equals(contextKey));
            var context=new ArrayList<String>();
            if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
            context.addAll(selectedContext(s,clock.millis()));
            String current=pipeline.usesApiCues()||rolling?utterance.text():decision.question();
            remember(s,contextKey,current,clock.millis());
            if(rolling){
                diagnostic(s,"CUE_CANDIDATE",Map.of("contextChars",s.context.stream().mapToInt(t->t.text().length()).sum()));
                long now=clock.millis();int delta=transcriptDeltaChars(s.hintBaselineNorm,joinContext(context,current));
                String skip=s.context.stream().mapToInt(t->t.text().length()).sum()<20?"context_short":now<s.hintHoldUntil?"display_hold":s.inflight!=null?"generating":s.lastCueAt>=0&&now-s.lastCueAt<cueCooldown(s.owner)?"cooldown":delta<triggerMinDelta()?"delta_below":"";
                if(!skip.isEmpty()){diagnostic(s,"CUE_SKIPPED",Map.of("reason",skip,"normalHintTrigger",true,"forcedHintTrigger",false,"elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta));s.suppressed++;return snapshot(s);}
                s.lastCueAt=now;diagnostic(s,"CUE_TRIGGERED",Map.of("contextChars",context.stream().mapToInt(String::length).sum(),"triggerReason","utterance_end","normalHintTrigger",true,"forcedHintTrigger",false,"elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta));
            }
            // Every selected utterance supersedes the old answer, even with a different question ID.
            // cancelWork retains an executing worker until it physically exits, so capacity stays honest.
            s.dropped+=s.queue.size();cancelWork(s);if(!rolling)s.card=null;
            s.workExpiresAt=clock.millis()+(pipeline.usesApiCues()?15_000:s.publicDisplay?85_000:20_000);
            s.queue.addLast(new Work(current,context,s.workExpiresAt,s.inputRequestId,inputPath,s.lastFinalAt,false,rolling?"utterance_end":"manual",s.contextEpoch));dispatch(s);return snapshot(s);
        }
    }
    private void dispatch(Session s){
        if(s.inflight!=null||s.queue.isEmpty()||!s.state.equals("RUNNING"))return;
        while(!s.queue.isEmpty()&&s.queue.peekFirst().expiresAt()<=clock.millis()){s.queue.removeFirst();s.expired++;s.dropped++;}
        if(s.queue.isEmpty())return;
        var work=s.queue.removeFirst();String question=work.question();long generation=++s.generation,epoch=s.epoch;var material=s.materials;
        s.inflightGeneration=generation;
        try{s.inflight=workers.submit(()->{
            synchronized(s){if(s.generation!=generation||!hintEpoch(s,epoch)||!s.state.equals("RUNNING")||work.ctxEpoch()!=s.contextEpoch)return;s.executing=true;}
            long began=System.nanoTime();
            var previousBudget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            if(s.publicDisplay)com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(80_000));
            try(var lease=admission.tryAcquire(s.owner).orElseThrow(()->error(HttpStatus.TOO_MANY_REQUESTS,"assist_generation_capacity"))){
                s.costCheck.run();
                synchronized(s){if(s.generation!=generation||!hintEpoch(s,epoch)||!s.state.equals("RUNNING")||work.ctxEpoch()!=s.contextEpoch)return;s.started++;}
                var answer=s.publicDisplay&&"openai_direct".equals(work.inputPath())?pipeline.answerPublicDisplayDirect(question,work.requestId()):s.publicDisplay?pipeline.answerPublicDisplay(question,work.context(),clock.millis(),work.requestId()):pipeline.answerLive(question,work.context(),material,clock.millis(),work.inputPath(),work.requestId(),work.forceHint(),hintTargetChars(s.owner));
                synchronized(s){s.searchAttempts+=answer.searchAttempts();s.queryRefinements+=answer.queryRefinements();s.generationAttempts+=answer.generationAttempts();s.complexJudgments+=answer.complexJudgments();s.lastGenerationMs=answer.generationMs();if(answer.generationAttempts()>0&&!answer.reason().equals("GENERATED"))s.verificationHolds++;if(s.generation==generation&&hintEpoch(s,epoch)&&s.state.equals("RUNNING")&&work.ctxEpoch()==s.contextEpoch&&work.expiresAt()>clock.millis()){s.stages=answer.stages();var card=answer.card();long displayExpires=clock.millis()+displayTtl(s.owner);s.card=card==null?null:new Card(card.decision(),card.kind(),card.text(),card.sourceIds(),displayExpires,work.requestId(),card.sourceTitles(),card.detailPages());
                    if(pipeline.usesApiCues()){
                        s.inputDecision=String.valueOf(answer.stages().cue().getOrDefault("cueDecision",answer.reason()));
                        var prefs=lensPrefs(s.owner);
                        if(Boolean.TRUE.equals(answer.stages().cue().get("topicChanged"))&&(prefs==null||prefs.topicResetEnabled())){long cut=clock.millis();s.context.clear();s.contextEpoch++;s.contextEpochStartAt=cut;s.contextResetAudioMark=new HashMap<>(s.audioOrder);diagnostic(s,"CONTEXT_EPOCH",Map.of("reason","topic_changed","contextEpoch",s.contextEpoch));remember(s,"current:"+work.requestId(),work.question(),cut);}
                        if(!rollingEnabled&&card!=null&&"SHOW".equals(card.decision()))remember(s,"hint:"+work.requestId(),"[assistant cue] "+card.text(),clock.millis());
                        if("NO_CUE".equals(answer.reason()))s.suppressed++;
                        if(card!=null&&"SHOW".equals(card.decision())){
                            long okAt=clock.millis();s.lastSuccessfulHintAt=okAt;s.hintBaselineAt=okAt;s.hintHoldUntil=okAt+displayTtl(s.owner);
                            s.hintBaselineNorm=normalizeTranscript(joinContext(work.context(),work.question()));
                            diagnostic(s,"HINT_GENERATED",Map.of(
                                "hintGenerated",true,
                                "triggerReason",work.triggerReason(),
                                "forcedHintTrigger",work.forceHint(),
                                "normalHintTrigger",!work.forceHint(),
                                "selectedModel",String.valueOf(answer.stages().cue().getOrDefault("selectedModel","unobserved")),
                                "transcriptDeltaChars",answer.stages().cue().getOrDefault("transcriptDeltaChars",0),
                                "elapsedSinceLastHint",answer.stages().cue().getOrDefault("elapsedSinceLastHint",0)
                            ));
                        }
                    }
                    if(!rollingEnabled&&card!=null&&s.caption!=null&&Objects.equals(s.finalTranscriptAt,work.finalAt())){
                        var caption=s.caption;s.caption=new Caption(caption.utteranceId(),caption.revision(),caption.isFinal(),caption.text(),caption.confidence(),caption.words(),caption.receivedAt(),displayExpires);
                    }
                    s.reason=answer.reason();s.hintCompletedAt=card==null?null:clock.millis();s.hintForFinalAt=card==null?null:work.finalAt();s.hintRenderedAt=null;s.version++;s.cueVersion=s.version;}else {s.dropped++;if(work.expiresAt()<=clock.millis())s.expired++;}}
            }catch(RuntimeException failure){synchronized(s){
                if(s.generation==generation&&hintEpoch(s,epoch)&&s.state.equals("RUNNING")&&work.ctxEpoch()==s.contextEpoch&&work.expiresAt()>clock.millis()){
                    s.reason=failure instanceof ResponseStatusException r
                            ?(r.getStatusCode().value()==429?"RATE_LIMITED":r.getStatusCode().value()==503?"ADMISSION_UNAVAILABLE":"PROCESSING_FAILED")
                            :s.publicDisplay?ConversateLocalCardGenerator.classify(failure):"PROCESSING_FAILED";
                    if(s.publicDisplay&&!s.reason.equals("CANCELLED")&&!"openai_direct".equals(work.inputPath())){
                        String text=switch(s.reason){
                            case "GENERATION_DENIED","MODEL_NOT_FOUND","ADMISSION_UNAVAILABLE"->"답변 서비스에 연결하지 못했어요. 잠시 후 다시 입력해 주세요.";
                            case "GENERATION_RATE_LIMITED","RATE_LIMITED"->"요청이 많아 답변을 받지 못했어요. 잠시 후 다시 입력해 주세요.";
                            case "GENERATION_TIMEOUT"->"시간 안에 답변을 받지 못했어요. 잠시 후 다시 입력해 주세요.";
                            default->"답변을 만들지 못했어요. 질문을 짧게 다시 입력해 주세요.";
                        };
                        s.card=new Card("ASK","STATUS",text,List.of(),clock.millis()+60_000,work.requestId(),List.of());
                    }
                    s.version++;if(s.card!=null)s.cueVersion=s.version;
                }
            }}
            finally{if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.clear();else com.abandonware.ai.addons.budget.TimeBudgetContext.set(previousBudget);synchronized(s){s.samples++;s.lastProcessingMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);if(s.inflightGeneration==generation){s.executing=false;s.inflight=null;dispatch(s);}}}
        });}catch(RejectedExecutionException busy){s.reason="QUEUE_LIMIT";s.dropped++;s.version++;}
    }
    public Snapshot control(String owner,String id,long epoch,String action){
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
            switch(action){
                case "producer_changed" -> {pause(s,"producer_changed");s.state="RUNNING";s.caption=null;s.captionEpoch=0;s.audioOrder.clear();s.reason="READY";}
                case "hints_on", "hints_off" -> {s.hintsEnabled=action.equals("hints_on");cancelWork(s);resetCueTracking(s);s.card=null;s.version++;}
                case "pause" -> pause(s,"user_pause");
                case "transport_pause" -> {if(s.state.equals("RUNNING"))pause(s,"output_lost",true);}
                case "text_fallback" -> {
                    if(!s.state.equals("RUNNING")&&!(s.state.equals("PAUSED")&&s.reason.startsWith("ASR_")))throw error(HttpStatus.CONFLICT,"assist_paused");
                    pause(s,"ASR_TEXT_FALLBACK",true);s.state="RUNNING";s.reason="TEXT_INPUT_FALLBACK";s.inputPath="direct";
                    s.disconnectedAt=outputCount(s)==0?clock.millis():-1;
                }
                case "resume" -> {if(!s.state.equals("PAUSED"))throw error(HttpStatus.CONFLICT,"assist_not_paused");s.state="RUNNING";s.reason="resumed";s.version++;s.disconnectedAt=outputCount(s)==0?clock.millis():-1;}
                case "context_reset" -> {cancelWork(s);s.context.clear();s.contextEpoch++;s.contextEpochStartAt=clock.millis();s.contextResetAudioMark=new HashMap<>(s.audioOrder);resetCueTracking(s);s.card=null;s.version++;s.reason="CONTEXT_RESET";diagnostic(s,"CONTEXT_EPOCH",Map.of("reason","user_reset","contextEpoch",s.contextEpoch));}
                case "stop" -> {stop(s,"user_stop");sessions.remove(s.id,s);}
                default -> throw error(HttpStatus.BAD_REQUEST,"invalid_control");
            }return snapshot(s);
        }
    }
    /** Only actual output subscriptions participate. Status/diagnostic reads never extend the grace. */
    public Flux<ServerSentEvent<Snapshot>> output(String owner,String id,long epoch) {
        var s=owned(owner,id);
        synchronized(s){checkEpoch(s,epoch);}
        return Flux.defer(()->{
            synchronized(s){checkEpoch(s,epoch);if(s.state.equals("STOPPED"))throw error(HttpStatus.GONE,"assist_stopped");if(outputCount(s)>=4)throw error(HttpStatus.TOO_MANY_REQUESTS,"output_capacity");s.outputs++;s.disconnectedAt=-1;s.version++;}
            var closed=new AtomicBoolean();long[] lastVersion={-1};
            return Flux.interval(Duration.ZERO,Duration.ofMillis(250)).map(tick->{
                synchronized(s){var current=snapshot(s);if(current.version()!=lastVersion[0]){lastVersion[0]=current.version();return ServerSentEvent.<Snapshot>builder(current).event("assist").build();}return ServerSentEvent.<Snapshot>builder().comment("keepalive").build();}
            }).takeUntil(event->event.data()!=null&&event.data().state().equals("STOPPED"))
            .doFinally(signal->{if(closed.compareAndSet(false,true))synchronized(s){s.outputs=Math.max(0,s.outputs-1);s.version++;if(outputCount(s)==0&&s.state.equals("RUNNING"))s.disconnectedAt=clock.millis();}});
        });
    }
    public boolean publish(String owner,String id,long epoch,Card card){
        var s=sessions.get(id);if(s==null||!s.owner.equals(owner))return false;
        synchronized(s){if(s.epoch!=epoch||!s.state.equals("RUNNING")||card.expiresAt()<=clock.millis()
                ||(s.requestEpoch==epoch&&s.requestId!=null&&card.requestId()!=null&&!s.requestId.equals(card.requestId())))return false;s.card=card;s.version++;s.cueVersion=s.version;return true;}
    }
    /** Client-reported request state only; fixed labels never contain an unreviewed answer. */
    public Snapshot publishRequestStatus(String owner,String id,long epoch,String requestId,String requestState,long sequence){
        var s=owned(owner,id);
        if(requestId==null||!requestId.matches("[A-Za-z0-9._:-]{1,128}")||sequence<1||sequence>9_007_199_254_740_991L||requestState==null)throw error(HttpStatus.BAD_REQUEST,"invalid_request_status");
        String text=switch(requestState){
            case "LOADING" -> "질문 처리 중입니다. 응답을 기다리고 있습니다.";
            case "RESULT" -> "응답이 도착했습니다. 검토한 답변의 전송을 기다립니다.";
            case "FALLBACK" -> "모델 생성 미확인. 대체 응답을 검토해 주세요.";
            case "rate-limited" -> "요청이 제한되었습니다. 잠시 후 다시 시도해 주세요.";
            case "admission-unavailable" -> "요청 접수에 실패했습니다. 서버 상태를 확인해 주세요.";
            case "outcome-unknown" -> "연결이 중단되어 요청 결과를 확인하지 못했습니다.";
            case "server-error" -> "응답을 확인하지 못했습니다. 서버 상태를 확인해 주세요.";
            default -> throw error(HttpStatus.BAD_REQUEST,"invalid_request_status");
        };
        synchronized(s){
            checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");
            if(s.requestEpoch!=epoch){s.requestSequence=0;s.requestTerminal=false;s.requestId=null;s.requestState=null;s.requestEpoch=epoch;}
            boolean terminal=!requestState.equals("LOADING");
            if(sequence<s.requestSequence)throw error(HttpStatus.CONFLICT,"stale_request_status");
            if(sequence==s.requestSequence){
                if(s.requestTerminal){
                    if(terminal&&requestState.equals(s.requestState)&&requestId.equals(s.requestId))return snapshot(s);
                    throw error(HttpStatus.CONFLICT,"stale_request_status");
                }
                if(!terminal){if(requestId.equals(s.requestId))return snapshot(s);throw error(HttpStatus.CONFLICT,"stale_request_status");}
            }
            s.requestSequence=sequence;s.requestId=requestId;s.requestState=requestState;s.requestTerminal=terminal;
            s.card=new Card(requestState,"STATUS",text,List.of(),clock.millis()+60_000,requestId,List.of());s.version++;s.cueVersion=s.version;return snapshot(s);
        }
    }
    /** Explicit fixture mode only, two bounded scheduled writes through the same card/output lifecycle. */
    public Snapshot fixture(String owner,String id,long epoch){
        var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");if(!s.pending.isEmpty())throw error(HttpStatus.CONFLICT,"fixture_in_progress");s.fixtureRuns++;
            for(int i=1;i<=2;i++){int n=i;s.pending.add(scheduler.schedule(()->{
                String text=n==1?"테스트 답변 1\n자동 표시 연결을 확인합니다.":"테스트 답변 2\n추가 동작 없이 갱신되었습니다.";
                publish(owner,id,epoch,new Card("SHOW","TEST",text,List.of(),clock.millis()+20_000));
                if(n==2)synchronized(s){s.pending.clear();}
            },i==1?300:1400,TimeUnit.MILLISECONDS));}return snapshot(s);
        }
    }
    void maintain(){long now=clock.millis();for(var s:sessions.values())synchronized(s){
        if(s.publicDisplay&&s.inflight!=null&&s.workExpiresAt>0&&now>=s.workExpiresAt){cancelWork(s);s.workExpiresAt=0;s.card=null;s.reason="RAG_TIMEOUT";s.version++;}
        if(s.pollOutputs.entrySet().removeIf(entry->now-entry.getValue()>GRACE_MS)){s.version++;if(outputCount(s)==0&&s.disconnectedAt<0)s.disconnectedAt=now;}
        pruneContext(s,now);
        boolean focusActive=novaFocus!=null&&novaFocus.active(s.id);
        if(focusActive){if(!s.focusWasActive)cancelWork(s);s.focusWasActive=true;}
        else {
            if(s.focusWasActive){s.focusWasActive=false;s.hintBaselineAt=now;s.lastSuccessfulHintAt=now;s.lastCueAt=now;
                s.hintBaselineNorm=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));}
            maybeAccumulatedHint(s,now);maybeForceRollingHint(s,now);
        }
        if(s.state.equals("PAUSED")&&s.capture==null&&outputCount(s)==0&&s.disconnectedAt>=0&&now-s.disconnectedAt>=DISCONNECTED_RETENTION_MS){stop(s,"disconnected_cleanup");sessions.remove(s.id,s);continue;}
        if(s.state.equals("RUNNING")&&outputCount(s)==0&&s.disconnectedAt>=0&&now-s.disconnectedAt>=GRACE_MS&&!(s.publicDisplay&&(s.capture!=null||Set.of("WAITING","API_PAUSED").contains(s.audio.state()))&&now-s.disconnectedAt<DISCONNECTED_RETENTION_MS))pause(s,"output_lost",true);
        if(s.card!=null&&s.card.expiresAt()<=now){s.card=null;s.version++;}
        if(s.caption!=null&&s.caption.expiresAt()<=now){s.caption=null;s.version++;}
    }}
    private void pause(Session s,String reason){pause(s,reason,false);}
    private void pause(Session s,String reason,boolean retainEvidence){if(novaFocus!=null)novaFocus.detach(s.id,reason);s.state="PAUSED";s.reason=reason;s.epoch++;s.pollOutputs.clear();
        if(!retainEvidence){s.policy.clear();s.context.clear();s.card=null;}else {pruneContext(s,clock.millis());if(s.card!=null&&s.card.expiresAt()<=clock.millis())s.card=null;}
        resetCueTracking(s);s.version++;closeCapture(s);cancelPending(s);}
    private void closeCapture(Session s){var capture=s.capture;s.capture=null;s.caption=null;s.visible.clear();s.firstTranscriptAt=null;s.finalTranscriptAt=null;s.captionRenderedAt=null;s.audioOrder.clear();s.contextResetAudioMark=Map.of();if(capture!=null)try{capture.close();}catch(Exception ignored){}s.audio=new AudioMetrics(s.audio.chunks(),s.audio.partials(),s.audio.finals(),s.audio.duplicates(),s.audio.lastAsrMs(),"STOPPED",s.audio.runtime());}
    private void stop(Session s,String reason){pause(s,reason);s.state="STOPPED";s.materials=List.of();s.policy.clear();}
    private static boolean hintEpoch(Session s,long epoch){return s.epoch==epoch||s.segmentHintEpoch==epoch;}
    private void cancelWork(Session s){s.segmentHintEpoch=0;s.generation++;s.hintCompletedAt=null;s.hintRenderedAt=null;s.hintForFinalAt=null;s.stages=ConversateAnswerPipeline.Stages.unobserved();if(s.inflight!=null){if(s.inflight.cancel(true))s.cancelled++;if(!s.executing){s.inflight=null;workers.purge();}}s.queue.clear();}
    /** Provider stream IDs carry a monotonic suffix; retain its high watermark across ledger eviction. */
    private static boolean staleAudio(Session s,ConversateQuestionPolicy.Utterance u){
        if(u==null||u.utteranceId()==null||!u.utteranceId().matches("(?:dg|asr|sx-[a-f0-9-]{36})-[0-9]{1,10}"))return false;
        int split=u.utteranceId().lastIndexOf('-');String stream=u.utteranceId().substring(0,split);long number=Long.parseLong(u.utteranceId().substring(split+1));
        Long prior=s.audioOrder.get(stream);if(prior!=null&&number<prior)return true;
        if(prior==null&&s.audioOrder.size()>=4)throw error(HttpStatus.CONFLICT,"audio_stream_limit");
        return false;
    }
    /** A context reset keeps the epoch and the capture, so it snapshots the stream
        high-water: a transcript event for an utterance already streaming before
        the reset stays out of the new epoch (never re-captioned, never context). */
    private static boolean preResetAudio(Session s,ConversateQuestionPolicy.Utterance u){
        if(u==null||u.utteranceId()==null||!u.utteranceId().matches("(?:dg|asr|sx-[a-f0-9-]{36})-[0-9]{1,10}"))return false;
        int split=u.utteranceId().lastIndexOf('-');
        Long mark=s.contextResetAudioMark.get(u.utteranceId().substring(0,split));
        return mark!=null&&Long.parseLong(u.utteranceId().substring(split+1))<=mark;
    }
    private static void rememberAudioOrder(Session s,ConversateQuestionPolicy.Utterance u){
        if(u.utteranceId().matches("(?:dg|asr|sx-[a-f0-9-]{36})-[0-9]{1,10}")){
            int split=u.utteranceId().lastIndexOf('-');s.audioOrder.put(u.utteranceId().substring(0,split),Long.parseLong(u.utteranceId().substring(split+1)));
        }
    }
    private static void pruneContext(Session s,long now){s.context.removeIf(t->t.expiresAt()<=now);}
    private long displayTtl(String owner){var prefs=lensPrefs(owner);return Math.max(1000,Math.min(100000,prefs==null?displayTtlMs:prefs.hintTtlMs()));}
    /** Transcript hold is its own 1-100 s setting, independent of the hint clock. */
    private long captionTtl(String owner){var prefs=lensPrefs(owner);return Math.max(1000,Math.min(100000,prefs==null?displayTtlMs:prefs.transcriptTtlMs()));}
    private int triggerMinDelta(){return Math.max(20,Math.min(600,triggerMinDeltaChars));}
    private long triggerQuiet(String owner){
        var prefs=lensPrefs(owner);
        long ms=prefs==null?triggerQuietMs:prefs.triggerQuietMs();
        return Math.max(LensDisplayPrefs.MIN_TRIGGER_QUIET_MS,Math.min(LensDisplayPrefs.MAX_TRIGGER_QUIET_MS,ms));
    }
    private long cueCooldown(String owner){
        var prefs=lensPrefs(owner);
        long ms=prefs==null?cueCooldownMs:prefs.cueCooldownMs();
        return Math.max(LensDisplayPrefs.MIN_CUE_COOLDOWN_MS,Math.min(LensDisplayPrefs.MAX_CUE_COOLDOWN_MS,ms));
    }
    private long forceAfter(String owner){
        var prefs=lensPrefs(owner);
        long ms=prefs==null?forceAfterMs:prefs.forceAfterMs();
        return Math.max(LensDisplayPrefs.MIN_FORCE_AFTER_MS,Math.min(LensDisplayPrefs.MAX_FORCE_AFTER_MS,ms));
    }
    private static void resetCueTracking(Session s){s.lastCueAt=-1;s.lastSuccessfulHintAt=-1;s.hintHoldUntil=-1;s.lastTranscriptChangeAt=-1;s.observedTranscriptNorm="";s.hintBaselineAt=-1;s.hintBaselineNorm="";}
    /** Past turns admitted to the next hint request: enabled/window/chars/tokens bounds plus the context epoch cutoff.
        The newest turns win; the oldest kept turn may be head-truncated to fit the char budget. */
    private List<String> selectedContext(Session s,long now){
        var prefs=lensPrefs(s.owner);
        long windowMs=prefs==null?0:prefs.historyWindowMs();
        int maxChars=prefs==null?0:prefs.historyMaxChars(),maxTokens=prefs==null?0:prefs.historyMaxTokens();
        var picked=new ArrayList<String>();int chars=0,tokens=0;
        if(prefs==null||prefs.historyEnabled()){
            var turns=new ArrayList<Turn>();
            for(Turn t:s.context)if(t.expiresAt()>now&&t.at()>=s.contextEpochStartAt&&(windowMs<=0||now-t.at()<=windowMs))turns.add(t);
            for(int i=turns.size()-1;i>=0;i--){
                String text=turns.get(i).text();int tChars=text.length();
                if(maxChars>0&&chars+tChars>maxChars){int room=maxChars-chars;if(room<40)break;int begin=text.length()-room;if(Character.isLowSurrogate(text.charAt(begin)))begin++;text=text.substring(begin);tChars=text.length();}
                int tTokens=estTokens(text);
                if(maxTokens>0&&tokens+tTokens>maxTokens)break;
                picked.add(0,text);chars+=tChars;tokens+=tTokens;
            }
        }
        s.lastContextTurns=picked.size();s.lastContextChars=chars;s.lastContextTokens=tokens;
        return picked;
    }
    private void remember(Session s,String key,String text,long now){
        if(rollingEnabled){int limit=Math.max(1600,Math.min(3000,contextChars));if(text.length()>limit){int begin=text.length()-limit;if(Character.isLowSurrogate(text.charAt(begin)))begin++;text=text.substring(begin);}}
        s.context.removeIf(t->t.key().equals(key));s.context.addLast(new Turn(key,text,now,now+Math.max(15000,Math.min(300000,contextTtlMs))));
        if(rollingEnabled){while(s.context.size()>1&&s.context.stream().mapToInt(t->t.text().length()).sum()>Math.max(1600,Math.min(3000,contextChars)))s.context.removeFirst();
            diagnostic(s,"CONTEXT_BUFFER",Map.of("chars",s.context.stream().mapToInt(t->t.text().length()).sum()));return;}
        // Keep the latest complete question and its cue even when a long question
        // exceeds the ordinary context budget. Evict whole older turns, never its tail.
        while(s.context.size()>(pipeline.usesApiCues()?12:4)||(s.context.size()>(pipeline.usesApiCues()?2:1)
                &&s.context.stream().mapToInt(t->t.text().length()).sum()>(pipeline.usesApiCues()?8192:2048)))s.context.removeFirst();
    }
    private String rollingText(Session s,ConversateQuestionPolicy.Utterance u){
        String key=s.epoch+":"+u.utteranceId();s.visible.removeIf(t->t.key().equals(key));
        if(u.isFinal())s.visible.addLast(new Turn(key,u.text(),clock.millis(),ROLLING_EXPIRY));
        String partial=u.isFinal()?"":u.text();
        while(s.visible.size()>1&&s.visible.stream().mapToInt(t->t.text().length()+1).sum()+partial.length()>Math.max(80,visibleChars)){
            var removed=s.visible.removeFirst();diagnostic(s,"TRANSCRIPT_EVICT",Map.of("removedChars",removed.text().length()));
        }
        var parts=new ArrayList<>(s.visible.stream().map(Turn::text).toList());if(!partial.isBlank())parts.add(partial);
        String text=String.join("\n",parts);diagnostic(s,"TRANSCRIPT_VISIBLE",Map.of("chars",text.length(),"utterances",s.visible.size()));return text;
    }
    
    private static String normalizeTranscript(String text){
        if(text==null||text.isBlank())return "";
        String n=text.replace('\r','\n').replaceAll("\\n+","\n").replaceAll("[ \\t\\u00A0]+"," ").strip();
        // drop consecutive duplicate lines (interim repeats)
        String[] lines=n.split("\n");StringBuilder out=new StringBuilder();String prev="";
        for(String line:lines){String t=line.strip();if(t.isEmpty()||t.equals(prev))continue;if(out.length()>0)out.append('\n');out.append(t);prev=t;}
        return out.toString();
    }
    private static String joinContext(List<String> context,String current){
        StringBuilder b=new StringBuilder();
        if(context!=null)for(String c:context){if(c==null||c.isBlank())continue;if(b.length()>0)b.append('\n');b.append(c);}
        if(current!=null&&!current.isBlank()){if(b.length()>0)b.append('\n');b.append(current);}
        return b.toString();
    }
    private static int transcriptDeltaChars(String baseline,String current){
        String a=normalizeTranscript(baseline),b=normalizeTranscript(current);
        if(b.isEmpty())return 0;
        if(a.isEmpty())return b.codePointCount(0,b.length());
        if(b.startsWith(a))return b.codePointCount(a.length(),b.length());
        int i=0,an=a.length(),bn=b.length();
        while(i<an&&i<bn&&a.charAt(i)==b.charAt(i))i++;
        return b.codePointCount(i,bn);
    }
    private void maybeAccumulatedHint(Session s,long now){
        if(!rollingEnabled||!pipeline.usesApiCues()||!s.hintsEnabled||!s.state.equals("RUNNING"))return;
        String current=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));
        if(!current.equals(s.observedTranscriptNorm)){s.observedTranscriptNorm=current;s.lastTranscriptChangeAt=now;}
        if(s.inflight!=null||!s.queue.isEmpty())return;
        if(s.hintBaselineAt<0){s.hintBaselineAt=now;s.hintBaselineNorm=current;return;}
        if(now<s.hintHoldUntil)return;
        int delta=transcriptDeltaChars(s.hintBaselineNorm,current);
        long quietSince=Math.max(s.lastTranscriptChangeAt,s.hintHoldUntil);
        if(delta<triggerMinDelta()||now-quietSince<triggerQuiet(s.owner)){
            if(delta>=triggerMinDelta()&&now-quietSince>0)diagnostic(s,"ACCUM_HINT_WATCH",Map.of("transcriptDeltaChars",delta,"quietMs",now-quietSince,"reason","awaiting_quiet","normalHintTrigger",false,"forcedHintTrigger",false));
            return;
        }
        if(s.lastCueAt>=0&&now-s.lastCueAt<cueCooldown(s.owner))return;
        String question=s.caption!=null&&!s.caption.text().isBlank()?s.caption.text():(s.context.isEmpty()?"":s.context.peekLast().text());
        if(question==null||question.isBlank())return;
        var context=new ArrayList<String>();
        if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
        context.addAll(selectedContext(s,now));
        diagnostic(s,"ACCUM_HINT_TRIGGERED",Map.of("triggerReason","transcript_delta","elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta,"quietMs",now-quietSince,"normalHintTrigger",true,"forcedHintTrigger",false));
        s.lastCueAt=now;s.dropped+=s.queue.size();cancelWork(s);
        s.workExpiresAt=now+15_000;
        s.queue.addLast(new Work(question,context,s.workExpiresAt,s.inputRequestId==null?"accum-hint":s.inputRequestId,"phone_voice",s.lastFinalAt,false,"transcript_delta",s.contextEpoch));
        dispatch(s);
    }
    private void maybeForceRollingHint(Session s,long now){
        if(!rollingEnabled||!pipeline.usesApiCues()||!s.hintsEnabled||!s.state.equals("RUNNING"))return;
        if(s.inflight!=null||!s.queue.isEmpty())return;
        if(now<s.hintHoldUntil)return;
        long anchor=s.lastSuccessfulHintAt>0?s.lastSuccessfulHintAt:(s.hintBaselineAt>0?s.hintBaselineAt:now);
        if(s.hintBaselineAt<0){s.hintBaselineAt=now;s.hintBaselineNorm=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));return;}
        long elapsed=now-anchor;
        String current=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));
        int delta=transcriptDeltaChars(s.hintBaselineNorm,current);
        long forceMs=forceAfter(s.owner);
        if(elapsed<forceMs){
            if(elapsed>=forceMs/2)diagnostic(s,"FORCE_HINT_WATCH",Map.of("elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",false,"normalHintTrigger",false,"forceAfterMs",forceMs));
            return;
        }
        if(delta<Math.max(20,forceMinDeltaChars)){
            diagnostic(s,"FORCE_HINT_SKIPPED",Map.of("elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",false,"reason","delta_below_threshold"));
            return;
        }
        String question=s.caption!=null&&!s.caption.text().isBlank()?s.caption.text():(s.context.isEmpty()?"":s.context.peekLast().text());
        if(question==null||question.isBlank())return;
        var context=new ArrayList<String>();
        if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
        context.addAll(selectedContext(s,now));
        diagnostic(s,"FORCE_HINT_TRIGGERED",Map.of("triggerReason","3min_watchdog","elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",true,"normalHintTrigger",false));
        s.lastCueAt=now;s.dropped+=s.queue.size();cancelWork(s);
        s.workExpiresAt=now+15_000;
        s.queue.addLast(new Work(question,context,s.workExpiresAt,s.inputRequestId==null?"force-hint":s.inputRequestId,"phone_voice",s.lastFinalAt,true,"3min_watchdog",s.contextEpoch));
        dispatch(s);
    }

    private void diagnostic(Session s,String event,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>(fields);row.put("event",event);row.put("at",clock.millis());s.events.addLast(Map.copyOf(row));while(s.events.size()>24)s.events.removeFirst();}
    Map<String,Object> transcriptDiagnostics(String owner,String id){var s=owned(owner,id);synchronized(s){
        var prefs=lensPrefs(s.owner);
        var history=new LinkedHashMap<String,Object>();
        history.put("enabled",prefs==null||prefs.historyEnabled());
        history.put("windowMs",prefs==null?0:prefs.historyWindowMs());
        history.put("maxChars",prefs==null?0:prefs.historyMaxChars());
        history.put("maxTokens",prefs==null?0:prefs.historyMaxTokens());
        history.put("topicReset",prefs==null||prefs.topicResetEnabled());
        history.put("contextEpoch",s.contextEpoch);
        var last=new LinkedHashMap<String,Object>();
        last.put("turns",s.lastContextTurns);last.put("chars",s.lastContextChars);last.put("estTokens",s.lastContextTokens);
        return Map.of("rolling",rollingEnabled,"visibleLimit",visibleChars,"contextLimit",contextChars,"visibleChars",s.caption==null?0:s.caption.text().length(),"contextChars",s.context.stream().mapToInt(t->t.text().length()).sum(),"contextTurns",s.context.size(),"history",history,"lastContextSelection",last,"events",List.copyOf(s.events));}}
    private void cancelPending(Session s){s.pending.forEach(f->f.cancel(true));s.pending.clear();cancelWork(s);}
    private Session owned(String owner,String id){var s=sessions.get(id);if(s==null||!s.owner.equals(owner))throw error(HttpStatus.NOT_FOUND,"assist_not_found");return s;}
    private static void checkEpoch(Session s,long epoch){if(s.epoch!=epoch)throw error(HttpStatus.CONFLICT,"stale_epoch");}
    private Snapshot snapshot(Session s){return new Snapshot(s.id,s.epoch,s.state,s.card,s.version,outputCount(s),s.reason,new Metrics(s.started,s.suppressed,s.duplicates,s.dropped,s.queue.size(),s.inflight==null?0:1,s.materials.size(),s.samples,s.lastProcessingMs,s.generationAttempts,s.complexJudgments,s.verificationHolds,s.lastGenerationMs,s.searchAttempts,s.queryRefinements,s.context.size(),s.cancelled,s.expired,s.outputAcks,s.lastOutputAckEpoch,s.lastOutputAckVersion,s.lastOutputAckAt,s.stages,s.fixtureRuns),s.audio,new WearDiagnostics(s.inputPath,s.inputRequestId,s.inputDecision,s.lastFinalAt,s.lastFinalNanos==0?null:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-s.lastFinalNanos),s.card==null?null:s.epoch+":"+s.cueVersion,s.renderedEpoch==s.epoch?s.renderedVersion:0,s.renderedCount,"not_observed",s.firstTranscriptAt,s.finalTranscriptAt,s.hintCompletedAt,s.captionRenderedAt,s.hintRenderedAt,s.hintForFinalAt),s.caption!=null&&s.caption.expiresAt()>clock.millis()?s.caption:null,novaFocus==null?null:novaFocus.view(s.owner,s.id,s.epoch));}
    static ResponseStatusException error(HttpStatus status,String code){return new ResponseStatusException(status,code);}
    int sessionCount(){return sessions.size();}
    @PreDestroy public synchronized void close(){for(var s:sessions.values())synchronized(s){stop(s,"server_shutdown");}sessions.clear();scheduler.shutdownNow();workers.shutdownNow();}
    private record Turn(String key,String text,long at,long expiresAt){@Override public String toString(){return "Turn[redacted]";}}
    private record Work(String question,List<String> context,long expiresAt,String requestId,String inputPath,long finalAt,boolean forceHint,String triggerReason,long ctxEpoch){@Override public String toString(){return "Work[redacted]";}}
    private static final class Session {
        final ArrayDeque<Turn> visible=new ArrayDeque<>();final ArrayDeque<Map<String,Object>> events=new ArrayDeque<>();long lastCueAt=-1;long lastSuccessfulHintAt=-1;long hintBaselineAt=-1;String hintBaselineNorm="";long hintHoldUntil=-1;long lastTranscriptChangeAt=-1;String observedTranscriptNorm="";
        String background="";boolean focusWasActive;
        final Map<String,Long> pollOutputs=new HashMap<>();
        final String id=UUID.randomUUID().toString(),owner;final Runnable costCheck;final boolean publicDisplay;
        final List<Future<?>> pending=new ArrayList<>(2);
        final ConversateQuestionPolicy policy=new ConversateQuestionPolicy();final ArrayDeque<Work> queue=new ArrayDeque<>(1);final ArrayDeque<Turn> context=new ArrayDeque<>(4);
        List<PreparedMaterialReader.Material> materials=List.of();Future<?> inflight;
        AutoCloseable capture;AudioMetrics audio=new AudioMetrics(0,0,0,0,0,"OFF");
        long generation,inflightGeneration,started,suppressed,duplicates,dropped,samples,lastProcessingMs,generationAttempts,complexJudgments,verificationHolds,lastGenerationMs,searchAttempts,queryRefinements,cancelled,expired,workExpiresAt;boolean executing;
        long outputAcks,lastOutputAckEpoch,lastOutputAckVersion,lastOutputAckAt,fixtureRuns;
        String inputPath="unobserved",inputRequestId,inputDecision="unobserved";
        Caption caption;long captionVersion,captionRenderedVersion,captionEpoch,segmentHintEpoch;boolean hintsEnabled=true;
        final Map<String,Long> audioOrder=new HashMap<>();
        Long firstTranscriptAt,finalTranscriptAt,hintCompletedAt,captionRenderedAt,hintRenderedAt,hintForFinalAt;
        long lastFinalAt,lastFinalNanos,cueVersion,renderedEpoch,renderedVersion,renderedCount;
        long contextEpoch=1,contextEpochStartAt;int lastContextTurns,lastContextChars,lastContextTokens;
        Map<String,Long> contextResetAudioMark=Map.of();
        long requestEpoch,requestSequence;boolean requestTerminal;String requestId,requestState;
        ConversateAnswerPipeline.Stages stages=ConversateAnswerPipeline.Stages.unobserved();
        long epoch=1,version=1,disconnectedAt;int outputs;String state="RUNNING",reason="started";Card card;
        Session(String owner,long now,Runnable costCheck,boolean publicDisplay){this.owner=owner;disconnectedAt=now;this.costCheck=costCheck;this.publicDisplay=publicDisplay;}
    }
}
