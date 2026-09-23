package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import static com.example.lms.assist.ConversateSessionService.*;

/** Anonymous Display transport with explicitly enabled, owner-bound PCM input. */
@RestController
@ConditionalOnProperty(name={"conversate.enabled","conversate.display.enabled"},havingValue="true")
public class DisplayConversateController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DisplayConversateController.class);
    private static final Set<String> EVENTS=Set.of("focus","activate","input","change","compositionstart","compositionend","escape","submit");
    private final ConversateSessionService sessions;
    private final ClientOwnerKeyResolver owners;
    private final InterviewDemoPublicAddress address;
    private final Clock clock;
    private final DisplayRelay relay;
    @Autowired(required=false) private DisplayRuntimeDiagnostics runtimeDiagnostics;
    private final Map<String,Binding> bindings=new HashMap<>();
    // Existing cookie owners, transient codes and explicit receiver approval only.
    private final Map<String,String> phoneLinks=new HashMap<>();
    private final Map<String,LensGrant> lensGrants=new HashMap<>();
    /** Per-owner lens presentation settings; volatile like the rest of this controller. */
    private final Map<String,LensDisplayPrefs> lensPrefs=new HashMap<>();
    @org.springframework.beans.factory.annotation.Value("${conversate.cue.hint-target-chars:540}") private int defaultHintTargetChars=540;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.sticky-lens:${AWX_DISPLAY_STICKY_LENS:${CONVERSATE_DISPLAY_STICKY_LENS:false}}}") private boolean stickyLensEnabled;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.lens-conversation-chars:${CONVERSATE_LENS_CONVERSATION_CHARS:280}}") private int lensConversationChars=280;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.sticky-lens-store:.secrets/display-sticky-lens.json}") private String stickyLensPath=".secrets/display-sticky-lens.json";
    private StickyLensGrantStore stickyLensStore;
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    private final Window global=new Window();
    @Autowired(required=false) private com.example.lms.config.LocalLlmProcessManager localLlm;
    @Autowired(required=false) private com.example.lms.service.ChatService sharedRag;
    @Autowired(required=false) private ConversateAsrBridge asr;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.audio.enabled:${CONVERSATE_DISPLAY_AUDIO_ENABLED:false}}") private boolean audioEnabled;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.phone-test-enabled:${CONVERSATE_DISPLAY_PHONE_TEST_ENABLED:false}}") private boolean phoneTestEnabled;
    @org.springframework.beans.factory.annotation.Value("${conversate.display.segment-seconds:0}") private int defaultSegmentSeconds=0;
    @Autowired public DisplayConversateController(ConversateSessionService sessions,ClientOwnerKeyResolver owners,InterviewDemoPublicAddress address){this(sessions,owners,address,Clock.systemUTC());}
    DisplayConversateController(ConversateSessionService sessions,ClientOwnerKeyResolver owners,InterviewDemoPublicAddress address,Clock clock){this.sessions=sessions;this.owners=owners;this.address=address;this.clock=clock;this.relay=new DisplayRelay(clock);sessions.displayPrefs(this::prefsFor);}
    private LensDisplayPrefs prefsFor(String owner){var prefs=lensPrefs.get(owner);return prefs==null?LensDisplayPrefs.defaults(defaultHintTargetChars):prefs;}
    public record Connection(String assistId,long epoch,String clientId,boolean activate,boolean continuation){public Connection(String assistId,long epoch,String clientId){this(assistId,epoch,clientId,false,false);}}
    public record RelayPoll(String clientId,long eventId){}
    public record RelaySettings(String assistId,long epoch,String clientId,boolean enabled,int segmentSeconds){}
    public record RelayTest(String assistId,long epoch,String clientId,int number,boolean fromFold){}
    public record Input(String assistId,long epoch,String clientId,String requestId,String text,List<String> eventOrder,String verificationMode){@Override public String toString(){return "DisplayInput[redacted]";}}
    public record AudioChunk(String assistId,long epoch,String clientId,long sequence,String pcm){@Override public String toString(){return "DisplayAudio[redacted]";}}
    public record AudioStop(String assistId,long epoch,String clientId,boolean finish){}
    public record View(String assistId,long epoch,String state,String reason,long version,DisplayContentView.TextCard card,String requestId,boolean processing,boolean ready,boolean audioAvailable,String audioState,String voiceRequestId,
                       DisplayContentView.Transcript caption,long captionTtlMs,long cardTtlMs,boolean hintsEnabled,String role,boolean linked,boolean linkPending,String confirmation,boolean audioFinished,long audioRenewAfterMs,Map<String,Object> testStatus,NovaFocusState.View focus,boolean focusProducer){}
    @Autowired(required=false) private NovaFocusService novaFocus;
    public record DiagnosticRequest(String assistId,boolean enabled){}
    public record FocusCommand(String assistId,long epoch,String clientId,String renderTarget,String requestId,String text){
        @Override public String toString(){return "FocusCommand[redacted]";}
    }
    public record FocusSettings(String assistId,long epoch,String clientId,long settingsVersion,NovaFocusSettings settings){}
    public record FocusHistory(String assistId,long epoch,String clientId,Long beforeSequence,int limit){}
    private Binding focusBinding(String caller,String id,long epoch,String client){
        validateClient(client);Binding b=bound(caller,id);requireProducer(b,client);
        if(b.relayChannel==null||b.producerClient==null)throw error(HttpStatus.FORBIDDEN,"event_owner_required");
        var s=sessions.status(b.owner,b.id);if(s.epoch()!=epoch||!s.state().equals("RUNNING"))throw error(HttpStatus.CONFLICT,"focus_session_stale");
        if(novaFocus==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"focus_unavailable");return b;
    }
    private <T>ResponseEntity<T> focusCall(java.util.function.Supplier<T> action){
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get());}
        catch(IllegalArgumentException e){String code=Objects.toString(e.getMessage(),"invalid_focus_request");throw error(code.endsWith("conflict")||code.endsWith("stale")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST,code.startsWith("focus_")||code.startsWith("invalid_")?code:"invalid_focus_request");}
        catch(IllegalStateException e){throw error(HttpStatus.SERVICE_UNAVAILABLE,"focus_unavailable");}
    }
    @PostMapping("/api/assist/display/focus/settings/read")
    public synchronized ResponseEntity<?> focusSettingsRead(@RequestBody Connection r,HttpServletRequest http){
        String caller=owner(http);limited(caller,2);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->novaFocus.localStore(b.owner,b.id,r.epoch()));
    }
    @PostMapping("/api/assist/display/focus/settings")
    public synchronized ResponseEntity<?> focusSettings(@RequestBody FocusSettings r,HttpServletRequest http){
        String caller=owner(http);limited(caller,1);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->novaFocus.configure(b.owner,b.id,r.epoch(),r.settingsVersion(),r.settings()));
    }
    @PostMapping("/api/assist/display/focus/history")
    public synchronized ResponseEntity<?> focusHistory(@RequestBody FocusHistory r,HttpServletRequest http){
        String caller=owner(http);limited(caller,2);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->novaFocus.history(b.owner,b.id,r.epoch(),r.beforeSequence(),r.limit()));
    }
    @PostMapping("/api/assist/display/focus/open")
    public synchronized ResponseEntity<?> focusOpen(@RequestBody FocusCommand r,HttpServletRequest http){
        String caller=owner(http);limited(caller,1);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        String target=r.renderTarget()==null||r.renderTarget().equals("auto")?focusTarget(b):r.renderTarget();
        return focusCall(()->novaFocus.open(b.owner,b.id,r.epoch(),target));
    }
    @PostMapping("/api/assist/display/focus/input")
    public synchronized ResponseEntity<?> focusInput(@RequestBody FocusCommand r,HttpServletRequest http){
        String caller=owner(http);limited(caller,1);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->{novaFocus.input(b.owner,b.id,r.epoch(),r.requestId(),r.text());return novaFocus.view(b.owner,b.id,r.epoch());});
    }
    @PostMapping("/api/assist/display/focus/input/status")
    public synchronized ResponseEntity<?> focusInputStatus(@RequestBody FocusCommand r,HttpServletRequest http){
        String caller=owner(http);limited(caller,2);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->Map.of("accepted",novaFocus.inputAccepted(b.owner,b.id,r.epoch(),r.requestId(),r.text())));
    }
    @PostMapping("/api/assist/display/focus/close")
    public synchronized ResponseEntity<?> focusClose(@RequestBody FocusCommand r,HttpServletRequest http){
        String caller=owner(http);limited(caller,1);Binding b=focusBinding(caller,r.assistId(),r.epoch(),r.clientId());
        return focusCall(()->{novaFocus.close(b.owner,b.id,r.epoch(),"user_closed");return novaFocus.view(b.owner,b.id,r.epoch());});
    }
    @PostMapping("/api/assist/display/focus/rendered")
    public synchronized ResponseEntity<?> focusRendered(@RequestBody NovaFocusService.Receipt r,HttpServletRequest http){
        String caller=owner(http);limited(caller,6);
        if(novaFocus==null||!novaFocus.rendered(r))throw error(HttpStatus.CONFLICT,"focus_receipt_stale");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("received",true));
    }
    public record LensView(String assistId,long epoch,long version,boolean ready,DisplayContentView.TextCard card,DisplayContentView.Transcript caption,long captionTtlMs,long cardTtlMs){}
    public record LensRead(String token){@Override public String toString(){return "LensRead[redacted]";}}
    public record LensLink(String token,long expiresAt,boolean sticky){@Override public String toString(){return "LensLink[redacted]";}}
    public record LensText(String conversation,String hint,String hintId,long hintExpiresAt,long conversationExpiresAt,LensDisplayPrefs display,NovaFocusState.View focus){}
    public record LensSettings(String assistId,long epoch,String clientId,LensDisplayPrefs.Patch display,Boolean restoreDefaults){}
    record LensGrant(String owner,String assistId,long expiresAt){@Override public String toString(){return "LensGrant[redacted]";}}
    public record PairJoin(String clientId,String code,String requestId){@Override public String toString(){return "PairJoin[redacted]";}}
    public record Background(String assistId,long epoch,String clientId,String text){@Override public String toString(){return "DisplayBackground[redacted]";}}
    public record HintControl(String assistId,long epoch,String clientId,boolean enabled){}
    public record Ack(String assistId,long epoch,String clientId,long version,String phase){}

    /** Existing read-only admin capability; opt-in never grants authority. */
    @GetMapping("/api/diagnostics/display")
    public ResponseEntity<?> diagnosticsAccess(@RequestParam(required=false) String assistId,@RequestParam(defaultValue="false") boolean enabled,HttpServletRequest http,org.springframework.security.core.Authentication authentication){
        return diagnostics(new DiagnosticRequest(assistId,enabled),http,authentication);
    }
    private static void requireDeveloper(org.springframework.security.core.Authentication authentication){
        if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority())))throw error(HttpStatus.FORBIDDEN,"diagnostics_forbidden");
    }

    /** Observing never registers an output, creates a session, or starts/stops capture. */
    public synchronized ResponseEntity<?> diagnostics(@RequestBody DiagnosticRequest request,HttpServletRequest http,
            org.springframework.security.core.Authentication authentication){
        requireDeveloper(authentication);
        String caller=owner(http);
        if(!request.enabled())return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled",false));
        String linked=phoneLinks.get(caller);Binding existing=bindings.get(linked==null?caller:linked);
        String id=request.assistId()==null&&existing!=null?existing.id:request.assistId();
        Binding b=bound(caller,id);Snapshot s=sessions.status(b.owner,b.id);
        var result=new LinkedHashMap<String,Object>();
        result.put("enabled",true);result.put("observedAt",clock.millis());result.put("assistId",s.assistId());
        result.put("audioState",s.audio().state());result.put("audioChunks",s.audio().chunks());result.put("audioBytes",b.audioBytes);
        result.put("lastAudioReceivedAt",b.lastAudioAt==0?"not_observed":b.lastAudioAt);
        result.put("lastTranscriptReceivedAt",b.lastTranscriptAt==0?"not_observed":b.lastTranscriptAt);
        result.put("reconnects",Math.max(0,b.audioStarts-1));result.put("decision",s.card()!=null?"DISPLAY":s.reason().equals("DUPLICATE")?"DUPLICATE_SUPPRESSED":"HOLD");
        result.put("reason",s.reason());result.put("processingMs",s.metrics().lastProcessingMs());
        result.put("searchResults",s.metrics().stages().cue().getOrDefault("ragDocuments","not_observed"));
        result.put("embeddingModel",s.metrics().stages().cue().getOrDefault("embeddingModel","not_observed"));
        var audio=asr==null?Map.<String,Object>of():asr.displayDiagnostics(s.audio());
        result.put("audio",safeFields(audio,List.of("provider","transport","model","configuredProvider","configuredCloudModel","firstPartialMs","finalAfterStopMs","fallbackGapMs","shutdownMs","stopReason","failureReason","processedMs")));
        result.put("pipeline",pipelineDiagnostics(s.metrics().stages().cue()));
        if(novaFocus!=null)try{result.put("focus",novaFocus.diagnostics(b.owner,b.id,s.epoch()));}catch(IllegalArgumentException unavailable){result.put("focus",Map.of("available",false));}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private static Map<String,Object> safeFields(Map<?,?> values,List<String> fields){
        var safe=new LinkedHashMap<String,Object>();
        for(String field:fields){Object value=values.get(field);
            if(value instanceof Number||value instanceof Boolean)safe.put(field,value);
            else if(value instanceof String text&&text.matches("[A-Za-z0-9_.:/ -]{1,100}"))safe.put(field,text);
        }
        return safe;
    }
    private static Map<String,Object> pipelineDiagnostics(Map<String,Object> cue){
        var pipeline=safeFields(cue,List.of("selectedProvider","selectedModel","responseModel","gateProvider","gateModel","decisionSource","gateMs","retrievalMs","compressionMs","hintGenerationMs","totalLatencyMs","status","failureReason","cueDecision","decisionReason","apiAttempts","requestReservedUsd","retrievalEstimatedCostUsd","billedCostUsd","observedInputTokens","observedOutputTokens","observedReasoningTokens","reasoningUsageCoverage","tokenUsageCoverage","usageEstimatedCostUsd","usageCostCoverage","costEvidence","costLimitsEnforced","currency","cacheHit","cachedInputTokens","estimatedCost"));
        if(cue.get("stageCalls") instanceof Map<?,?> counts)
            pipeline.put("stageCalls",safeFields(counts,List.of("cueAdmission","retrieval","finalGeneration","transcriptPostprocess")));
        pipeline.putAll(safeFields(cue,List.of("searchNeeded","retrieval.web.failsoft.rawInputCount","retrieval.web.failsoft.outCount",
                "transcriptChars","contextUsed","contextChars","evidenceChars","ragDocuments","hintHash",
                "hintPath","evidenceStatus","evidenceInsufficient","usableEvidenceCount","fallbackReason","supplementStatus","supplementFailure")));
        var evidenceDigests=new ArrayList<Map<String,String>>();
        if(cue.get("evidenceDigests") instanceof List<?> rows)for(Object row:rows){
            if(evidenceDigests.size()==3)break;
            if(row instanceof Map<?,?> value&&value.get("id") instanceof String id&&id.matches("e[0-2]")
                    &&value.get("sha256") instanceof String hash&&hash.matches("[a-f0-9]{64}"))
                evidenceDigests.add(Map.of("id",id,"sha256",hash));
        }
        pipeline.put("evidenceDigests",List.copyOf(evidenceDigests));
        if(cue.get("citedEvidenceIds") instanceof List<?> ids)pipeline.put("citedEvidenceIds",ids.stream()
                .filter(id->id instanceof String&&evidenceDigests.stream().anyMatch(e->e.get("id").equals(id))).distinct().limit(3).toList());
        if(cue.get("transcriptHash") instanceof String hash&&hash.matches("[a-f0-9]{64}"))pipeline.put("transcriptHash",hash);
        for(String provider:List.of("NAVER","BRAVE"))if(cue.get("search."+provider) instanceof Map<?,?> search)
            pipeline.put("search."+provider,safeFields(search,List.of("provider","searchNeeded","requestCount","requestCountScope","resultCount","latencyMs","fallbackReason","failureReason","clientAttemptCount","httpResponseCount","clientAttemptCoverage")));
        if(cue.get("retrieval.dropReasonCounts") instanceof Map<?,?> drops)
            pipeline.put("retrieval.dropReasonCounts",safeFields(drops,List.of("tech_spam","duplicate_key","host_duplicate","officialOnly_clamped_fallback_candidate","officialOnly_exclude_devCommunity","officialOnly_stage_excluded","stage_excluded","target_filled","not_considered","not_selected")));
        for(String stage:List.of("gateAttempts","hintAttempts"))if(cue.get(stage) instanceof List<?> rows){
            var attempts=new ArrayList<Map<String,Object>>();
            for(Object row:rows){
                if(attempts.size()==3)break;
                if(!(row instanceof Map<?,?> values))continue;
                var attempt=safeFields(values,List.of("provider","model","responseModel","status","fallbackReason","outputValidation","latencyMs","reasoningTokens","visibleOutputTokens","usageEstimatedCostUsd","cachedInputTokens","cacheHit"));
                attempt.putAll(safeFields(values,List.of("outputHash","outputChars","outputTextChars","outputEvidenceCount")));
                if(values.get("actualTokens") instanceof Map<?,?> counts)attempt.put("actualTokens",safeFields(counts,List.of("input","output","total")));
                else attempt.put("actualTokens","not_observed");
                attempts.add(attempt);
            }
            pipeline.put(stage,attempts);
        }
        return pipeline;
    }

    /** A producer explicitly shares only its current text with a separate glasses browser. */
    @PostMapping("/api/assist/display/lens/link")
    public synchronized ResponseEntity<LensLink> lensLink(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,3);
        long now=clock.millis();
        if(stickyLensEnabled){
            String previous=lensGrants.entrySet().stream().filter(e->e.getValue().owner().equals(b.owner)&&e.getValue().expiresAt()>now).map(Map.Entry::getKey).findFirst().orElse(null);
            try{
                var grant=stickyLensStore().renew(b.owner,previous,now);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(new LensLink(grant.token(),grant.expiresAt(),true));
            }catch(java.io.IOException|IllegalArgumentException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_store_unavailable");}
        }
        lensGrants.entrySet().removeIf(e->e.getValue().expiresAt()<=now||e.getValue().owner().equals(b.owner)||!bindings.containsKey(e.getValue().owner()));
        if(lensGrants.size()>=256)throw limitedError(60);
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);long expiresAt=now+43_200_000;
        lensGrants.put(token,new LensGrant(b.owner,b.id,expiresAt));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(new LensLink(token,expiresAt,false));
    }
    /** Content-only read: no output registration, acknowledgement, session mutation or generation. */
    @PostMapping("/api/assist/display/lens/text")
    public synchronized ResponseEntity<LensText> lensText(@RequestBody LensRead request,HttpServletRequest http){
        String caller=owner(http);limited(caller,2);String token=request.token();
        LensGrant grant=token!=null&&token.matches("[a-f0-9]{64}")?lensGrants.get(token):null;
        boolean sticky=false;
        if(stickyLensEnabled&&token!=null&&token.matches("[a-f0-9]{64}")){
            try{var saved=stickyLensStore().find(token);if(saved!=null){grant=new LensGrant(saved.owner(),null,saved.expiresAt());sticky=true;}}
            catch(java.io.IOException|IllegalArgumentException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_store_unavailable");}
        }
        Binding b=grant==null?null:bindings.get(grant.owner());long now=clock.millis();
        if(grant==null||grant.expiresAt()<=now)throw error(HttpStatus.NOT_FOUND,"lens_link_expired");
        if(b==null||b.id==null||!sticky&&!grant.assistId().equals(b.id))throw error(sticky?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.NOT_FOUND,sticky?"lens_producer_waiting":"lens_link_expired");
        Snapshot s;
        try{s=sessions.status(b.owner,b.id);}catch(ResponseStatusException missing){if(sticky&&missing.getStatusCode().value()==404)throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_producer_waiting");throw missing;}
        b.lastFocusLensRead=now;
        var caption=DisplayContentView.caption(s.caption(),now);
        var hint=sessions.hintsEnabled(b.owner,b.id)?DisplayContentView.card(s.card(),now):null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LensText(shortLensText(caption==null?null:caption.text(),lensConversationChars),shortLensText(hint==null?null:hint.text(),ConversateSessionService.HINT_TEXT_MAX),hint==null?null:hint.requestId(),hint==null?0:hint.expiresAt(),caption==null?0:caption.expiresAt(),prefsFor(b.owner),lensFocus(b,s)));
    }
    private StickyLensGrantStore stickyLensStore(){if(stickyLensStore==null)stickyLensStore=new StickyLensGrantStore(stickyLensPath);return stickyLensStore;}
    private NovaFocusState.View foldFocus(Binding b,Snapshot s){var v=novaFocus==null?null:novaFocus.view(b.owner,b.id,s.epoch());return v==null?null:v.forTarget("fold");}
    private String focusTarget(Binding b){return b.lastFocusLensRead>0&&clock.millis()-b.lastFocusLensRead<10000&&b.relayChannel!=null&&relay.enabled(b.relayChannel)?"lens":"fold";}
    private NovaFocusState.View lensFocus(Binding b,Snapshot s){
        if(b==null||novaFocus==null||b.relayChannel==null||!producer(b).equals(relay.active(b.relayChannel))||!relay.enabled(b.relayChannel))return null;
        var v=novaFocus.view(b.owner,b.id,s.epoch());return v==null?null:v.forTarget("lens");
    }
    private static String shortLensText(String value){return shortLensText(value,280);}
    private static String shortLensText(String value,int maxChars){
        if(value==null)return "";String text=value.replaceAll("\\s+"," ").strip();int max=Math.max(24,Math.min(1400,maxChars));int count=text.codePointCount(0,text.length());
        if(count<=max)return text;return "\u2026"+text.substring(text.offsetByCodePoints(0,count-(max-1)));
    }

    @PostMapping("/api/assist/display/lens")
    public synchronized ResponseEntity<LensView> lens(@RequestBody Connection request,HttpServletRequest http){
        var response=request.assistId()==null?transcription(request,http):poll(request,http);
        return lensResponse(response.getBody());
    }
    @PostMapping("/api/assist/display/lens/ack")
    public synchronized ResponseEntity<LensView> lensAck(@RequestBody Ack request,HttpServletRequest http){return lensResponse(acknowledge(request,http).getBody());}
    private ResponseEntity<LensView> lensResponse(View view){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LensView(view.assistId(),view.epoch(),view.version(),view.ready(),view.hintsEnabled()?view.card():null,view.caption(),view.captionTtlMs(),view.cardTtlMs()));
    }

    /** Explicit development opt-in: this browser owns both capture and output. */
    @PostMapping("/api/assist/display/phone-test")
    public synchronized ResponseEntity<View> phoneTest(@RequestBody Connection request,HttpServletRequest http){
        if(!phoneTestEnabled)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        String caller=owner(http);validateClient(request.clientId());expireLinks();
        Binding existing=bindings.get(caller);
        if(phoneLinks.containsKey(caller)||(existing!=null&&(existing.phoneOwner!=null||existing.pendingPhone!=null)))throw error(HttpStatus.CONFLICT,"link_exists");
        transcription(request,http);
        Binding b=bindings.get(caller);b.standalone=true;
        var s=sessions.status(caller,b.id);
        // Keep the existing cue gate default and the user\'s explicit hint preference.
        String channel=relayChannel(http);
        var prior=relay.active(channel);
        var requested=new DisplayRelay.Producer(caller,request.clientId(),b.id);
        if(((request.activate()||request.assistId()==null)&&!requested.equals(prior))||(prior!=null&&prior.owner().equals(caller)&&prior.client().equals(request.clientId())&&!prior.assistId().equals(b.id))){
            if(prior!=null){Binding old=bindings.get(prior.owner());if(old!=null&&old.id!=null)try{var oldState=sessions.status(old.owner,old.id);sessions.control(old.owner,old.id,oldState.epoch(),"producer_changed");}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;}}
            b.relayChannel=channel;b.producerClient=request.clientId();b.segmentSeconds=defaultSegmentSeconds==0?0:Math.max(5,Math.min(60,defaultSegmentSeconds));
            var producer=relay.activate(channel,caller,request.clientId(),b.id);relay.configure(channel,producer,true,b.segmentSeconds);
            s=sessions.status(caller,b.id);s=sessions.pollOutput(caller,b.id,s.epoch(),request.clientId());
        }else if(requested.equals(prior))relay.presence(channel,requested);
        return result(b,s,caller,request.clientId());
    }

    /** Subscriber-only projection. It never registers session output or controls capture. */
    @PostMapping("/api/assist/display/relay/diagnostics")
    public synchronized ResponseEntity<?> runtimeDiagnostic(@RequestBody DisplayRuntimeDiagnostics.ClientEvent event,HttpServletRequest http){
        String caller=owner(http);limited(caller,7);
        if(runtimeDiagnostics==null)return ResponseEntity.noContent().build();
        if(!runtimeDiagnostics.client(http,event))throw error(HttpStatus.BAD_REQUEST,"invalid_display_diagnostic");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/api/assist/display/relay/poll")
    public synchronized ResponseEntity<DisplayRelay.Event> relayPoll(@RequestBody RelayPoll request,HttpServletRequest http){
        if(!phoneTestEnabled)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        String caller=owner(http),channel=relayChannel(http);validateClient(request.clientId());limited(caller,2);
        var p=relay.active(channel);
        if(p!=null)try{var s=sessions.status(p.owner(),p.assistId());relay.publish(channel,p,DisplayContentView.caption(s.caption(),clock.millis()),sessions.hintsEnabled(p.owner(),p.assistId())?DisplayContentView.card(s.card(),clock.millis()):null,lensFocus(bindings.get(p.owner()),s));}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;relay.publish(channel,p,null,null);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(relay.poll(channel,request.clientId(),p==null?null:prefsFor(p.owner())));
    }
    @PostMapping("/api/assist/display/relay/ack")
    public synchronized ResponseEntity<?> relayAck(@RequestBody RelayPoll request,HttpServletRequest http){
        if(!phoneTestEnabled)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        String caller=owner(http);validateClient(request.clientId());limited(caller,6);relay.ack(relayChannel(http),request.clientId(),request.eventId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("received",true));
    }
    @PostMapping("/api/assist/display/relay/settings")
    public synchronized ResponseEntity<View> relaySettings(@RequestBody RelaySettings request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        if(b.relayChannel==null)throw error(HttpStatus.CONFLICT,"event_owner_required");
        relay.configure(b.relayChannel,producer(b),request.enabled(),request.segmentSeconds());b.segmentSeconds=request.segmentSeconds();
        return result(b,sessions.status(b.owner,b.id),caller);
    }
    /** Developer display settings: validated here, echoed via testStatus, carried to the lens by lens/text and relay events. */
    @PostMapping("/api/assist/display/relay/lens-settings")
    public synchronized ResponseEntity<View> lensSettings(@RequestBody LensSettings request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        LensDisplayPrefs applied=Boolean.TRUE.equals(request.restoreDefaults())?LensDisplayPrefs.defaults(defaultHintTargetChars):prefsFor(b.owner).patch(request.display());
        lensPrefs.put(b.owner,applied);
        LOG.info("display.lensSettings applied={}",applied.describe());
        return result(b,sessions.status(b.owner,b.id),caller);
    }
    @PostMapping("/api/assist/display/relay/test")
    public synchronized ResponseEntity<View> relayTest(@RequestBody RelayTest request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        if(b.relayChannel==null||request.number()<1||request.number()>3)throw error(HttpStatus.BAD_REQUEST,"invalid_display_test");
        String text=request.fromFold()?"FOLD TEST #001":String.format(Locale.ROOT,"DISPLAY TEST #%03d",request.number());
        return result(b,sessions.displayTest(b.owner,b.id,request.epoch(),text),caller);
    }
    private static String relayChannel(HttpServletRequest http){String channel=http.getHeader("X-Display-Test-Channel");if(channel==null)return "live";if(!channel.matches("test-[a-f0-9]{16,32}"))throw error(HttpStatus.BAD_REQUEST,"invalid_test_channel");return channel;}
    private DisplayRelay.Producer producer(Binding b){return new DisplayRelay.Producer(b.owner,b.producerClient,b.id);}
    private void requireProducer(Binding b,String client){
        if(b.relayChannel!=null&&(!client.equals(b.producerClient)||!producer(b).equals(relay.active(b.relayChannel))))throw error(HttpStatus.FORBIDDEN,"event_owner_required");
    }

    /** Transcript readiness never depends on an LLM probe. */
    @PostMapping("/api/assist/display/transcription")
    public synchronized ResponseEntity<View> transcription(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());limited(caller,0);expireLinks();
        String displayOwner=phoneLinks.get(caller);Binding b=bindings.get(displayOwner==null?caller:displayOwner);
        if(displayOwner!=null&&(b==null||!caller.equals(b.phoneOwner)))throw error(HttpStatus.CONFLICT,"link_pending");
        Snapshot s=null;
        if(b.id!=null)try{s=sessions.status(b.owner,b.id);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;}
        if(s==null){if(displayOwner!=null)throw error(HttpStatus.NOT_FOUND,"link_expired");s=sessions.startPublicDisplay(caller);b.id=s.assistId();b.createdAt=clock.millis();b.transcription=false;}
        if(!b.transcription){s=sessions.control(b.owner,b.id,s.epoch(),sessions.usesApiCues()?"hints_on":"hints_off");b.transcription=true;}
        if(displayOwner==null){
            if(s.state().equals("PAUSED")&&(s.reason().equals("output_lost")||s.reason().startsWith("ASR_")))s=sessions.control(b.owner,b.id,s.epoch(),"resume");
            s=sessions.pollOutput(b.owner,b.id,s.epoch(),request.clientId());
        }
        return result(b,s,caller);
    }
    @PostMapping("/api/assist/display/link/code")
    public synchronized ResponseEntity<?> pairCode(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=receiver(caller,request.assistId());limited(caller,3);expireLinks();
        if(b.phoneOwner!=null||b.pendingPhone!=null)throw error(HttpStatus.CONFLICT,"link_exists");
        if(b.code==null){do{b.code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}while(codeCollision(b));b.codeExpires=clock.millis()+120_000;}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code",b.code,"validForMs",Math.max(0,b.codeExpires-clock.millis())));
    }
    private boolean codeCollision(Binding candidate){return bindings.values().stream().anyMatch(b->b!=candidate&&Objects.equals(b.code,candidate.code));}
    @PostMapping("/api/assist/display/link/join")
    public synchronized ResponseEntity<?> pairJoin(@RequestBody PairJoin request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());limited(caller,5);expireLinks();
        if(request.code()==null||!request.code().matches("[0-9]{6}")||request.requestId()==null||!request.requestId().matches("[a-f0-9-]{36}"))throw error(HttpStatus.BAD_REQUEST,"invalid_pair_request");
        Binding prior=bindings.get(phoneLinks.get(caller));
        if(prior!=null&&request.requestId().equals(prior.joinRequest))return pairing(prior);
        if(prior!=null)throw error(HttpStatus.CONFLICT,"link_exists");
        Binding b=bindings.values().stream().filter(v->request.code().equals(v.code)&&v.codeExpires>clock.millis()).findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"pair_code_invalid"));
        if(caller.equals(b.owner))throw error(HttpStatus.CONFLICT,"pair_same_browser");
        Binding own=bindings.get(caller);
        if(own!=null&&(own.phoneOwner!=null||own.pendingPhone!=null))throw error(HttpStatus.CONFLICT,"link_exists");
        if(own!=null&&own.id!=null){try{var s=sessions.status(caller,own.id);sessions.control(caller,own.id,s.epoch(),"stop");}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;}own.id=null;}
        b.code=null;b.pendingPhone=caller;b.joinRequest=request.requestId();b.confirmation=String.format(Locale.ROOT,"%04d",random.nextInt(10_000));b.codeExpires=clock.millis()+120_000;
        phoneLinks.put(caller,b.owner);return pairing(b);
    }
    private ResponseEntity<?> pairing(Binding b){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("pending",b.pendingPhone!=null,"confirmation",b.confirmation));}
    @PostMapping("/api/assist/display/link/approve")
    public synchronized ResponseEntity<View> approve(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=receiver(caller,request.assistId());limited(caller,3);expireLinks();
        if(b.pendingPhone==null&&b.phoneOwner==null)throw error(HttpStatus.CONFLICT,"pair_expired");
        if(b.pendingPhone!=null){b.phoneOwner=b.pendingPhone;b.pendingPhone=null;}return result(b,sessions.status(caller,b.id),caller);
    }
    @PostMapping("/api/assist/display/link/unlink")
    public synchronized ResponseEntity<View> unlink(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());limited(caller,3);
        var s=sessions.status(b.owner,b.id);
        // A paused receiver has already closed capture. Unlink must remain usable
        // from the phone when that receiver is offline; never resume it here.
        if("RUNNING".equals(s.state()))s=sessions.control(b.owner,b.id,s.epoch(),"text_fallback");
        phoneLinks.remove(b.phoneOwner);phoneLinks.remove(b.pendingPhone);b.phoneOwner=b.pendingPhone=b.code=b.confirmation=b.joinRequest=null;
        return result(b,s,caller);
    }
    @PostMapping("/api/assist/display/hints")
    public synchronized ResponseEntity<View> hints(@RequestBody HintControl request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        if(b.standalone&&!phoneTestEnabled)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        return result(b,sessions.control(b.owner,b.id,request.epoch(),request.enabled()?"hints_on":"hints_off"),caller);
    }
    @PostMapping("/api/assist/display/context-reset")
    public synchronized ResponseEntity<View> contextReset(@RequestBody Connection request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        if(b.standalone&&!phoneTestEnabled)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        return result(b,sessions.control(b.owner,b.id,request.epoch(),"context_reset"),caller);
    }
    @PostMapping("/api/assist/display/context")
    public synchronized ResponseEntity<View> background(@RequestBody Background request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=receiver(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
        if(!phoneTestEnabled||!b.standalone)throw error(HttpStatus.NOT_FOUND,"phone_test_disabled");
        return result(b,sessions.setBackground(caller,b.id,request.epoch(),request.text()),caller);
    }
    @PostMapping("/api/assist/display/ack")
    public synchronized ResponseEntity<View> acknowledge(@RequestBody Ack request,HttpServletRequest http){
        String caller=owner(http);validateClient(request.clientId());Binding b=receiver(caller,request.assistId());limited(caller,6);
        return result(b,sessions.acknowledge(caller,b.id,request.epoch(),request.version(),request.phase()),caller);
    }
    private Binding receiver(String caller,String id){Binding b=bound(caller,id);if(!caller.equals(b.owner))throw error(HttpStatus.FORBIDDEN,"receiver_required");return b;}
    private void expireLinks(){long now=clock.millis();for(Binding b:bindings.values())if(b.codeExpires<=now){b.code=null;if(b.pendingPhone!=null){phoneLinks.remove(b.pendingPhone);b.pendingPhone=b.confirmation=b.joinRequest=null;}}}

    @GetMapping("/assets/display/pcm-worklet.js")
    public ResponseEntity<org.springframework.core.io.Resource> pcmWorklet(){
        audioGuard();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/javascript"))
                .body(new org.springframework.core.io.ClassPathResource("static/conversate/pcm-worklet.js"));
    }

    @PostMapping("/api/assist/display/bootstrap")
    public synchronized ResponseEntity<View> bootstrap(@RequestBody Connection request,HttpServletRequest http){
        String owner=owner(http);validateClient(request.clientId());Binding b=limited(owner,0);
        if(!ready())throw error(HttpStatus.SERVICE_UNAVAILABLE,"display_preparing");
        Snapshot s=null;
        if(b.id!=null)try{s=sessions.status(owner,b.id);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;}
        if(s==null){s=sessions.startPublicDisplay(owner);b.id=s.assistId();b.createdAt=clock.millis();}
        if(s.state().equals("PAUSED")&&s.reason().equals("output_lost"))s=sessions.control(owner,s.assistId(),s.epoch(),"resume");
        if(s.state().equals("PAUSED")&&s.reason().startsWith("ASR_"))s=sessions.control(owner,s.assistId(),s.epoch(),"text_fallback");
        s=sessions.pollOutput(owner,s.assistId(),s.epoch(),request.clientId());
        return result(b,s);
    }
    @PostMapping("/api/assist/display/input")
    public synchronized ResponseEntity<View> input(@RequestBody Input request,HttpServletRequest http){
        String owner=owner(http);validateClient(request.clientId());
        if(request.text()==null||request.text().isBlank()||request.text().length()>2000||request.requestId()==null||!request.requestId().matches("[a-f0-9-]{36}"))throw error(HttpStatus.BAD_REQUEST,"invalid_display_input");
        if(request.eventOrder()==null||request.eventOrder().size()>12||request.eventOrder().stream().anyMatch(e->e==null||!EVENTS.contains(e)))throw error(HttpStatus.BAD_REQUEST,"invalid_input_events");
        Binding b=bound(owner,request.assistId());requireProducer(b,request.clientId());limited(owner,1);
        boolean direct="openai-direct".equals(request.verificationMode());
        if(request.verificationMode()!=null&&!direct)throw error(HttpStatus.BAD_REQUEST,"invalid_verification_mode");
        if(direct){
            if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(http.getRemoteAddr())
                    ||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(http.getServerName())
                    ||http.getHeader("Forwarded")!=null||http.getHeader("X-Forwarded-For")!=null)
                throw error(HttpStatus.FORBIDDEN,"local_verification_only");
            if(b.directRequestId!=null&&!b.directRequestId.equals(request.requestId()))throw error(HttpStatus.CONFLICT,"verification_already_attempted");
            b.directRequestId=request.requestId();
        }
        sessions.pollOutput(owner,b.id,request.epoch(),request.clientId());
        var utterance=new ConversateQuestionPolicy.Utterance(request.requestId(),request.requestId(),1,true,request.text());
        var s=sessions.submit(owner,b.id,request.epoch(),utterance,direct?"openai_direct":"glasses_input",request.requestId());
        LOG.info("display.conversate inputPath=glasses_input decision={} duplicates={} events={}",s.diagnostics().inputDecision(),s.metrics().duplicates(),request.eventOrder());
        return result(b,s);
    }
    @PostMapping("/api/assist/display/poll")
    public synchronized ResponseEntity<View> poll(@RequestBody Connection request,HttpServletRequest http){
        String owner=owner(http);validateClient(request.clientId());Binding b=bound(owner,request.assistId());limited(owner,2);
        if(b.relayChannel!=null&&request.clientId().equals(b.producerClient))relay.presence(b.relayChannel,producer(b));
        // Old control clients may observe, but never renew producer ownership.
        var state=sessions.status(b.owner,b.id);
        boolean current=b.relayChannel==null||request.clientId().equals(b.producerClient)&&producer(b).equals(relay.active(b.relayChannel));
        return result(b,owner.equals(b.owner)&&current?sessions.pollOutput(b.owner,b.id,state.epoch(),request.clientId()):state,owner,request.clientId());
    }
    @PostMapping("/api/assist/display/audio/start")
    public ResponseEntity<View> audioStart(@RequestBody Connection request,HttpServletRequest http){
        String owner=owner(http);Binding b;long epoch;
        synchronized(this){audioGuard();validateClient(request.clientId());b=audioBinding(owner,request.assistId());requireProducer(b,request.clientId());
            var current=sessions.status(b.owner,b.id);
            if(request.continuation()){
                long minimum=b.lastSegmentSeconds==0?asr.renewAfterMs():b.lastSegmentSeconds*1000L;
                if(Set.of("WAITING","API_PAUSED").contains(current.audio().state())||"finished".equals(current.audio().runtime().get("stopReason")))minimum=Math.min(minimum,1000);
                boolean firstRecovery=b.lastStartAt==0&&Set.of("WAITING","API_PAUSED").contains(current.audio().state());
                if((b.relayChannel==null&&b.lastSegmentSeconds!=0)||!firstRecovery&&(b.lastStartAt==0||clock.millis()-b.lastStartAt<minimum-500))throw error(HttpStatus.CONFLICT,"segment_not_ready");
                limited(owner,7);
            }else limited(owner,3);
            if(current.epoch()!=request.epoch())throw error(HttpStatus.CONFLICT,"stale_epoch");
            if(request.continuation())current=sessions.nextSegment(b.owner,b.id,request.epoch());
            else if(current.audio().state().equals("STOPPED")&&current.audio().runtime().containsKey("stopReason"))current=sessions.control(b.owner,b.id,request.epoch(),"text_fallback");
            epoch=current.epoch();if(!b.transcription)sessions.pollOutput(b.owner,b.id,epoch,request.clientId());}
        asr.start(b.owner,b.id,epoch);
        synchronized(this){requireProducer(b,request.clientId());b.audioStarts++;b.lastStartAt=clock.millis();b.lastSegmentSeconds=b.segmentSeconds;return result(b,sessions.status(b.owner,b.id),owner,request.clientId());}
    }
    @PostMapping("/api/assist/display/audio/chunk")
    public ResponseEntity<View> audioChunk(@RequestBody AudioChunk request,HttpServletRequest http){
        String owner=owner(http);Binding b;
        synchronized(this){audioGuard();validateClient(request.clientId());b=audioBinding(owner,request.assistId());requireProducer(b,request.clientId());limited(owner,4);}
        Snapshot s=asr.chunk(b.owner,b.id,request.epoch(),request.sequence(),request.pcm());
        synchronized(this){requireProducer(b,request.clientId());if(request.sequence()>b.lastAudioSequence||request.epoch()!=b.lastAudioEpoch){b.audioBytes+=Base64.getDecoder().decode(request.pcm()).length;b.lastAudioAt=clock.millis();b.lastAudioSequence=request.sequence();b.lastAudioEpoch=request.epoch();}return result(b,s,owner,request.clientId());}
    }
    @PostMapping("/api/assist/display/audio/stop")
    public ResponseEntity<View> audioStop(@RequestBody AudioStop request,HttpServletRequest http){
        String owner=owner(http);Binding b;
        synchronized(this){audioGuard();validateClient(request.clientId());b=bound(owner,request.assistId());requireProducer(b,request.clientId());
            // Omitted/false preserves the existing immediate-cancellation contract.
            if(!request.finish())return result(b,sessions.control(b.owner,b.id,request.epoch(),"text_fallback"),owner);}
        var stopped=asr.finish(b.owner,b.id,request.epoch());
        synchronized(this){return result(b,stopped,owner);}
    }
    private void audioGuard(){if(!audioEnabled)throw error(HttpStatus.NOT_FOUND,"display_audio_disabled");if(asr==null)throw error(HttpStatus.SERVICE_UNAVAILABLE,"asr_disabled");}
    private Binding bound(String owner,String id){expireLinks();String linked=phoneLinks.get(owner);Binding b=bindings.get(linked==null?owner:linked);if(b==null||id==null||!id.equals(b.id)||(linked!=null&&!owner.equals(b.phoneOwner)))throw error(HttpStatus.NOT_FOUND,"assist_not_found");sessions.status(b.owner,id);return b;}
    private Binding audioBinding(String owner,String id){Binding b=bound(owner,id);if(b.standalone){if(!phoneTestEnabled||!owner.equals(b.owner))throw error(HttpStatus.FORBIDDEN,"phone_test_disabled");}else if(b.transcription&&!owner.equals(b.phoneOwner))throw error(HttpStatus.FORBIDDEN,"paired_phone_required");return b;}
    private boolean ready(){
        if(sessions.usesApiCues())return true;
        if(sharedRag==null)return false;
        if(localLlm==null)return true;
        var d=localLlm.diagnostics();
        // An externally started model has no probe history in the disabled lifecycle manager.
        // READY here means the transport can accept input; the answer path retains its timeout/failure handling.
        if(Boolean.FALSE.equals(d.get("enabled"))||Boolean.FALSE.equals(d.get("autostart")))return true;
        return !Boolean.FALSE.equals(d.get("serviceResponding"))&&!Boolean.FALSE.equals(d.get("modelPresent"));
    }
    private ResponseEntity<View> result(Binding b,Snapshot s){
        return result(b,s,b.owner);
    }
    private ResponseEntity<View> result(Binding b,Snapshot s,String caller){return result(b,s,caller,b.producerClient);}
    private ResponseEntity<View> result(Binding b,Snapshot s,String caller,String client){
        if(novaFocus!=null&&b.relayChannel!=null&&s.state().equals("RUNNING")){
            try{novaFocus.attach(b.owner,b.relayChannel,b.id,s.epoch());novaFocus.defaultTarget(b.owner,b.id,s.epoch(),focusTarget(b));}catch(RuntimeException unavailable){/* Existing transcription stays available when Focus storage is unavailable. */}
        }
        if(!Objects.equals(b.loggedState,s.state()+":"+s.reason())){
            b.loggedState=s.state()+":"+s.reason();LOG.info("display.conversate state={} reason={}",s.state(),s.reason());
        }
        long now=clock.millis();
        if(s.caption()!=null)b.lastTranscriptAt=Math.max(b.lastTranscriptAt,s.caption().receivedAt());
        var card=DisplayContentView.card(s.card(),now);var caption=DisplayContentView.caption(s.caption(),now);
        if(b.relayChannel!=null)relay.publish(b.relayChannel,producer(b),caption,sessions.hintsEnabled(b.owner,b.id)?card:null,lensFocus(b,s));
        var view=new View(s.assistId(),s.epoch(),s.state(),publicReason(s.reason()),s.version(),card,s.diagnostics().requestId(),s.metrics().inFlight()>0||s.metrics().queueLength()>0,s.state().equals("RUNNING"),audioEnabled&&asr!=null&&asr.available(),s.audio().state(),"phone_voice".equals(s.diagnostics().inputPath())?s.diagnostics().requestId():null,
                caption,caption==null?0:Math.max(0,caption.expiresAt()-now),card==null?0:Math.max(0,card.expiresAt()-now),sessions.hintsEnabled(b.owner,b.id),caller.equals(b.owner)?(b.standalone?"STANDALONE":"DISPLAY"):"PHONE",b.phoneOwner!=null,b.pendingPhone!=null,b.confirmation,"finished".equals(s.audio().runtime().get("stopReason")),asr==null?540000:asr.renewAfterMs(),b.standalone?testStatus(b,s,client):null,foldFocus(b,s),
                novaFocus!=null&&b.relayChannel!=null&&Objects.equals(client,b.producerClient)&&producer(b).equals(relay.active(b.relayChannel)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(view);
    }
    private Map<String,Object> testStatus(Binding b,Snapshot s,String client){
        var status=new LinkedHashMap<String,Object>();
        status.put("transcript",sessions.transcriptDiagnostics(b.owner,b.id));
        if(novaFocus!=null)try{status.put("focus",novaFocus.diagnostics(b.owner,b.id,s.epoch()));}catch(IllegalArgumentException unavailable){status.put("focus",Map.of("available",false));}
        if(runtimeDiagnostics!=null)status.put("displayRuntime",runtimeDiagnostics.snapshot());
        status.put("backgroundChars",sessions.backgroundChars(b.owner,b.id));
        status.put("lastTranscriptReceivedAt",b.lastTranscriptAt);status.put("lastAudioReceivedAt",b.lastAudioAt);
        if(b.relayChannel!=null)status.put("relay",relay.debug(b.relayChannel,new DisplayRelay.Producer(b.owner,client==null?b.producerClient:client,b.id)));
        status.put("lensDisplay",prefsFor(b.owner).describe());
        status.put("processing",s.metrics().inFlight()>0||s.metrics().queueLength()>0);
        status.put("processingMs",s.metrics().lastProcessingMs());
        status.put("asr",asr==null?Map.of():safeFields(asr.displayDiagnostics(s.audio()),List.of("provider","transport","model","configuredProvider","configuredCloudModel","firstPartialMs","finalAfterStopMs","stopReason","failureReason","renewAfterMs")));
        status.put("asrUsage",asr==null?Map.of():asr.displayUsage());
        status.put("audio",Map.of("bindingAcceptedAudioMs",b.audioBytes/32,"captureChunks",s.audio().chunks(),"capturePartials",s.audio().partials(),"captureFinals",s.audio().finals(),"captureDuplicates",s.audio().duplicates(),"utteranceDuplicates",s.metrics().duplicates()));
        var cue=s.metrics().stages().cue();
        status.put("pipeline",pipelineDiagnostics(cue));
        return status;
    }
    private Binding limited(String owner,int operation){
        long now=clock.millis();
        bindings.entrySet().removeIf(e->now-e.getValue().lastSeen>3_600_000);
        phoneLinks.entrySet().removeIf(e->!bindings.containsKey(e.getValue()));
        Binding b=bindings.get(owner);
        if(b==null){if(bindings.size()>=256)throw limitedError(60);b=new Binding();b.owner=owner;bindings.put(owner,b);}
        global.reset(now);b.window.reset(now);b.lastSeen=now;
        int[] perOwner={20,6,120,6,360,5,360,18},all={120,30,3000,30,720,30,720,60};
        if(b.window.counts[operation]>=perOwner[operation]||global.counts[operation]>=all[operation])throw limitedError(Math.max(1,(int)((60_000-(now-b.window.started)+999)/1000)));
        b.window.counts[operation]++;global.counts[operation]++;return b;
    }
    private static ResponseStatusException limitedError(int seconds){return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"display_rate_limited"){@Override public HttpHeaders getHeaders(){var h=new HttpHeaders();h.set("Retry-After",Integer.toString(seconds));return h;}};}
    private static void validateClient(String client){if(client==null||!client.matches("[a-f0-9]{32}"))throw error(HttpStatus.BAD_REQUEST,"invalid_output_client");}
    private String owner(HttpServletRequest request){
        String origin=request.getHeader("Origin");
        if(origin==null&&"GET".equals(request.getMethod())&&"/api/diagnostics/display".equals(request.getRequestURI().substring(request.getContextPath().length()))&&"same-origin".equals(request.getHeader("Sec-Fetch-Site"))&&"1".equals(request.getHeader("X-Display-Client")))return DigestUtils.sha256Hex("public-display:"+owners.ownerKey());
        if(!"1".equals(request.getHeader("X-Display-Client"))||origin==null||"cross-site".equals(request.getHeader("Sec-Fetch-Site")))throw error(HttpStatus.FORBIDDEN,"display_origin_required");
        boolean valid=false;
        try{
            URI uri=URI.create(origin);int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
            boolean shape=uri.getHost()!=null&&uri.getRawUserInfo()==null&&(uri.getRawPath()==null||uri.getRawPath().isEmpty())&&uri.getRawQuery()==null&&uri.getRawFragment()==null;
            valid=shape&&request.getScheme().equals(uri.getScheme())&&request.getServerName().equalsIgnoreCase(uri.getHost())&&request.getServerPort()==port;
            boolean loopback=Set.of("127.0.0.1","::1").contains(request.getRemoteAddr());
            valid|=shape&&loopback&&origin.equals(address.currentOrigin())&&request.getServerName().equalsIgnoreCase(uri.getHost());
        }catch(IllegalArgumentException ignored){}
        if(!valid)throw error(HttpStatus.FORBIDDEN,"display_origin_required");
        String testChannel=relayChannel(request);
        return DigestUtils.sha256Hex("public-display:"+owners.ownerKey()+(testChannel.equals("live")?"":":"+testChannel));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException error,HttpServletRequest request){return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).cacheControl(CacheControl.noStore()).body(request.getRequestURI().startsWith("/api/assist/display/lens")?Map.of("retryable",error.getStatusCode().is5xxServerError()):Map.of("reason",Objects.toString(error.getReason(),"display_failed")));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("reason","invalid_display_request"));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception failure){LOG.warn("display.request_failed type={}",failure.getClass().getSimpleName());return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore()).body(Map.of("retryable",false));}
    private static final class Window {long started;final int[] counts=new int[8];void reset(long now){if(now-started>=60_000){started=now;Arrays.fill(counts,0);}}}
    private static String publicReason(String reason){
        return switch(reason){
            case "API_CUE","RAG_ANSWER","MATCH","MATCH_REFINED" -> "CONTENT_READY";
            case "started","resumed" -> "READY";
            case "READY","RUNNING","DUPLICATE","NO_CUE","OPENAI_DIRECT_OK","OPENAI_DIRECT_ERROR","CONTEXT_RESET" -> reason;
            default -> "PROCESSING_FAILED";
        };
    }
    private static final class Binding {String relayChannel,producerClient;int segmentSeconds=0,lastSegmentSeconds=0;long lastStartAt,lastFocusLensRead;String id,owner,phoneOwner,pendingPhone,code,confirmation,joinRequest,directRequestId,loggedState;boolean transcription,standalone;long createdAt,lastSeen,codeExpires,audioBytes,lastAudioAt,lastTranscriptAt,audioStarts,lastAudioEpoch,lastAudioSequence=-1;final Window window=new Window();}
}
