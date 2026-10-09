package com.example.lms.assist;

import java.util.*;

/** Pure, clock-driven Focus state; callers serialize access per assist session. */
final class NovaFocusState {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NovaFocusState.class);
    record Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType,Set<String> sourceIds,
                   NovaFocusSettings.AnswerSelection answerSelection,long settingsVersion,int answerLengthChars,boolean quickAnswerEnabled,Boolean webSearchEnabled,NovaFocusSettings.ReasoningPreset reasoningPreset) {
        Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType,Set<String> sourceIds,NovaFocusSettings.AnswerSelection selection,long version,int length,boolean quick,Boolean web){
            this(activationId,requestId,question,imageBase64,imageMediaType,sourceIds,selection,version,length,quick,web,NovaFocusSettings.ReasoningPreset.STANDARD);
        }
        Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType,Set<String> sourceIds,NovaFocusSettings.AnswerSelection selection,long version,int length,boolean quick){
            this(activationId,requestId,question,imageBase64,imageMediaType,sourceIds,selection,version,length,quick,null);
        }
        Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType,Set<String> sourceIds,NovaFocusSettings.AnswerSelection selection,long version){
            this(activationId,requestId,question,imageBase64,imageMediaType,sourceIds,selection,version,400,false);
        }
        Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType){this(activationId,requestId,question,imageBase64,imageMediaType,Set.of());}
        Request(String activationId,String requestId,String question,String imageBase64,String imageMediaType,Set<String> sourceIds){
            this(activationId,requestId,question,imageBase64,imageMediaType,sourceIds,NovaFocusSettings.AnswerSelection.defaults(),0);
        }
        Request {sourceIds=Set.copyOf(sourceIds);}
    }
    /** 확정된 질문 한 건에 연결되는 단발 촬영 명령. 읽기 전용 구독자에게는 내려가지 않는다. */
    record Command(String kind,String serverInstanceId,String activationId,String requestId,String captureId,
                   String source,long expiresInMs,long settingsVersion,boolean claimed) {}
    static final long SNAPSHOT_TIMEOUT_MS=15000;
    static final int SNAPSHOT_ACCEPTED=0,SNAPSHOT_DUPLICATE=1,SNAPSHOT_STALE=2;
    static final int CLAIM_REJECTED=0,CLAIM_GRANTED=1,CLAIM_JOINED=2;
    record View(String serverInstanceId,String activationId,String turnId,long stateVersion,long answerVersion,
                boolean active,String phase,String draftText,String questionText,String answerText,
                String renderTarget,String renderReceiptTicket,long idleRemainingMs,String reason,
                NovaFocusSettings.Presentation presentation,boolean answerTruncated,boolean hasMoreOnFold,int answerLengthChars,
                com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer grounding,boolean answerComplete) {
        View(String server,String activation,String turn,long version,long answerVersion,boolean active,String phase,String draft,String question,String answer,String target,String ticket,long idle,String reason,NovaFocusSettings.Presentation presentation,boolean truncated,boolean more,int length,com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer grounding){
            this(server,activation,turn,version,answerVersion,active,phase,draft,question,answer,target,ticket,idle,reason,presentation,truncated,more,length,grounding,true);
        }
        View(String server,String activation,String turn,long version,long answerVersion,boolean active,String phase,String draft,String question,String answer,String target,String ticket,long idle,String reason,NovaFocusSettings.Presentation presentation,boolean truncated,boolean more,int length){
            this(server,activation,turn,version,answerVersion,active,phase,draft,question,answer,target,ticket,idle,reason,presentation,truncated,more,length,null);
        }
        View(String server,String activation,String turn,long version,long answerVersion,boolean active,String phase,String draft,String question,String answer,String target,String ticket,long idle,String reason,NovaFocusSettings.Presentation presentation,boolean truncated,boolean more){
            this(server,activation,turn,version,answerVersion,active,phase,draft,question,answer,target,ticket,idle,reason,presentation,truncated,more,8000);
        }
        @Override public String toString(){return "NovaFocusView[redacted]";}
        @com.fasterxml.jackson.annotation.JsonProperty("answerPrefixStable")
        public boolean answerPrefixStable(){return !answerComplete;}
        View forTarget(String surface){boolean lens="lens".equals(surface);
            String visible=lens&&grounding!=null?"검색 답변은 휴대폰에서 확인하세요.":lens?NovaFocusAnswerService.lensText(answerText,answerComplete):answerText;
            boolean clipped=answerTruncated||!Objects.equals(visible,answerText);
            return new View(serverInstanceId,activationId,turnId,stateVersion,answerVersion,active,phase,draftText,questionText,visible,renderTarget,renderTarget.equals(surface)?renderReceiptTicket:null,idleRemainingMs,reason,presentation,clipped,clipped,answerLengthChars,lens?null:grounding,answerComplete);}
    }
    final String server;
    NovaFocusSettings settings;
    private final ConversateQuestionPolicy delivery=new ConversateQuestionPolicy();
    private final NovaFocusTurnAssembler draft=new NovaFocusTurnAssembler();
    private final Set<String> committed=new LinkedHashSet<>();
    private String phase="OFF",activation="",turn="",question="",answer="",receipt="",candidate="",target="lens",reason="";
    private String foldPartial="";
    private boolean partialVersionReserved;
    private com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer grounding;
    private long version,answerVersion,listenUntil,generationUntil,presentationUntil,idleUntil;
    private boolean inFlight,firstVisible,done,answerTruncated;
    private int runAnswerLength=400;
    private NovaFocusSettings.Presentation runPresentation;
    private String sourceNamespace="";
    private String inFlightRequest="";
    private String snapshotImageBase64;
    private String snapshotImageMediaType;
    private Request pendingRequest;
    private String pendingCaptureId,pendingCaptureSource,claimedCaptureId;
    private long snapshotUntil;
    private boolean snapshotReady;
    private int captureAttempts,capturesCompleted,duplicatesSuppressed,captureGrants,textFallbacks;
    private final Map<String,String> acceptedSnapshots=new LinkedHashMap<>();
    void sourceNamespace(String value){
        if(!sourceNamespace.equals(value)){
            if(phase.equals("WAKE_PREVIEW"))close("capture_changed");
            draft.discardUnconfirmed();draftKeys.removeIf(key->!draft.contains(key));
            delivery.clear();committed.clear();candidate="";sourceNamespace=value;version++;
        }
    }
    private String wakeTarget="fold";
    void defaultTarget(String value){if(Set.of("fold","lens").contains(value))wakeTarget=value;}
    NovaFocusState(String server,NovaFocusSettings settings){this.server=server;configure(settings,0);}
    void configure(NovaFocusSettings next){configure(next,0);}
    void configure(NovaFocusSettings next,long now){
        var before=settings;settings=Objects.requireNonNull(next);if(!active())phase=next.enabled()?"ARMED":"OFF";
        if(phase.equals("SNAPSHOT")&&pendingRequest!=null){
            String prevSource=before==null?null:before.snapshotOrDefault().source();
            var current=settings.snapshotOrDefault();
            // OFF 또는 장치 변경: 진행 중 촬영과 미전송 이미지를 함께 무효화하고 같은 질문을 사진 없이 한 번 진행한다.
            // 자동 재촬영은 없다 — 다음 질문이 새 captureId를 만든다.
            if(!current.enabled()||!Objects.equals(prevSource,current.source())){
                clearCapture();snapshotImageBase64=null;snapshotImageMediaType=null;
                if(!snapshotReady){snapshotReady=true;textFallbacks++;}
            }
        }
        version++;
    }
    boolean active(){return !Set.of("OFF","ARMED").contains(phase);}
    boolean inFlight(){return inFlight;}
    String activation(){return activation;}
    String turn(){return turn;}
    void open(long now,String renderTarget){
        if(!Set.of("lens","fold").contains(renderTarget))throw new IllegalArgumentException("invalid_focus_target");
        if(active())return;
        activation=UUID.randomUUID().toString();target=renderTarget;phase="LISTENING";draft.clear();draftKeys.clear();
        foldPartial="";partialVersionReserved=false;answer=question=turn=receipt=candidate=reason="";answerTruncated=false;idleUntil=0;listenUntil=now+settings.wakeListenTimeoutMs();version++;
        grounding=null;
        pendingRequest=null;clearCapture();snapshotReady=false;snapshotImageBase64=null;snapshotImageMediaType=null;acceptedSnapshots.clear();
    }
    void close(String cause){
        log.info("[AWX][nova-focus] close reason={} phase={} version={}",cause!=null&&cause.matches("[a-z][a-z0-9_]{0,63}")?cause:"unknown",phase,version+1);
        phase=settings.enabled()?"ARMED":"OFF";draft.clear();draftKeys.clear();foldPartial="";partialVersionReserved=false;answer=question=receipt="";inFlight=false;inFlightRequest="";idleUntil=0;reason=cause;
        grounding=null;
        pendingRequest=null;clearCapture();snapshotReady=false;snapshotImageBase64=null;snapshotImageMediaType=null;version++;}
    private String key(ConversateQuestionPolicy.Utterance u){
        String source=u.questionId()+":"+u.utteranceId();
        return source.startsWith("typed-")?source:sourceNamespace+"\n"+source;
    }
    String sourceId(ConversateQuestionPolicy.Utterance u){return NovaFocusHistoryService.digest(key(u));}
    /** Delivery identity is tracked even with voice wake off, so replay cannot wake later. */
    boolean receive(ConversateQuestionPolicy.Utterance u,long now){
        var decision=delivery.acceptForCue(u);
        if(Set.of("DUPLICATE","STALE").contains(decision.kind()))return active();
        String source=key(u);if(committed.contains(source))return active();
        if(!active()){
            if(!settings.enabled())return false;
            var wake=NovaWakeMatcher.find(u.text(),settings.wakeWord());if(wake.isEmpty())return false;
            open(now,wakeTarget);candidate=source;phase=u.isFinal()?"LISTENING":"WAKE_PREVIEW";
            draft.update(source,wake.get().question(),u.isFinal(),now);version++;return true;
        }
        if(phase.equals("WAKE_PREVIEW")){
            if(!source.equals(candidate))return true;
            var wake=NovaWakeMatcher.find(u.text(),settings.wakeWord());
            if(wake.isEmpty()){close("wake_retracted");return false;}
            draft.update(source,wake.get().question(),u.isFinal(),now);
            if(u.isFinal()){phase="LISTENING";listenUntil=now+settings.wakeListenTimeoutMs();}version++;return true;
        }
        String text=u.text();
        if(source.equals(candidate))text=NovaWakeMatcher.find(text,settings.wakeWord()).map(NovaWakeMatcher.Match::question).orElse("");
        if(phase.equals("WAITING")){phase="LISTENING";idleUntil=0;listenUntil=now+settings.wakeListenTimeoutMs();}
        if(!draft.contains(source)&&draft.hasInput()&&(source.startsWith("typed-")
                ||Set.of("SNAPSHOT","THINKING","ANSWER_READY","PRESENTING").contains(phase)&&draft.finalReady(now,settings.utteranceQuietMs()))){
            reason="focus_next_question_full";version++;return true;
        }
        if(draft.update(source,text,u.isFinal(),now))version++;
        return true;
    }
    void validateTyped(String request,String text){
        if(!active())throw new IllegalArgumentException("focus_not_active");
        validateManualInput(request,text);
    }
    static void validateManualInput(String request,String text){
        if(request==null||!request.matches("[A-Za-z0-9._:-]{1,128}")||text==null||text.isBlank()||text.codePointCount(0,text.length())>2000)
            throw new IllegalArgumentException("invalid_focus_question");
    }
    static String typedRequestId(String request){
        String source="typed-"+NovaFocusHistoryService.digest(request);
        return NovaFocusHistoryService.digest("typed:"+source+":"+source);
    }
    void alreadyAccepted(){reason="focus_request_already_accepted";version++;}
    void typed(String request,String text,long now){
        validateTyped(request,text);
        String source="typed-"+NovaFocusHistoryService.digest(request);
        if(draft.hasInput()&&!draft.contains(source+":"+source))
            throw new IllegalArgumentException("focus_next_question_full");
        input(new ConversateQuestionPolicy.Utterance(source,source,0,true,text),now);
    }
    Request pendingQuestion(){return pendingRequest;}
    Request tick(long now){return tick(now,true);}
    Request tick(long now,boolean capacity){
        return tick(now,capacity,0);
    }
    Request tick(long now,boolean capacity,long settingsVersion){
        if(!active())return null;
        if(draft.overLimit()){draft.clear();draftKeys.clear();reason="focus_input_limit";version++;}
        if(phase.equals("WAKE_PREVIEW")&&now>=listenUntil){close("wake_unconfirmed");return null;}
        if(inFlight&&now>=generationUntil){close("generation_timeout");return null;}
        if(Set.of("ANSWER_READY","PRESENTING").contains(phase)&&now>=presentationUntil){close("presentation_unconfirmed");return null;}
        if(phase.equals("WAITING")&&now>=idleUntil){close("idle_timeout");return null;}
        if(phase.equals("LISTENING")&&!draft.hasInput()&&now>=listenUntil){close("wake_no_question");return null;}
        if(draft.hasInput()&&!draft.finalized()&&now-draft.changedAt()>60000){close("input_unconfirmed");return null;}
        if(phase.equals("SNAPSHOT")&&!snapshotReady&&now>=snapshotUntil)snapshotFailed("snapshot_timeout",now);
        if(capacity&&phase.equals("SNAPSHOT")&&snapshotReady){
            var request=new Request(activation,pendingRequest.requestId(),pendingRequest.question(),snapshotImageBase64,snapshotImageMediaType,pendingRequest.sourceIds(),
                pendingRequest.answerSelection(),pendingRequest.settingsVersion(),pendingRequest.answerLengthChars(),pendingRequest.quickAnswerEnabled(),pendingRequest.webSearchEnabled(),pendingRequest.reasoningPreset());
            pendingRequest=null;snapshotImageBase64=null;snapshotImageMediaType=null;clearCapture();snapshotReady=false;
            phase="THINKING";inFlight=true;inFlightRequest=request.requestId();generationUntil=now+90000;version++;return request;
        }
        if(capacity&&phase.equals("LISTENING")&&draft.finalReady(now,settings.utteranceQuietMs())){
            question=draft.text();if(question.codePointCount(0,question.length())>2000){draft.clear();draftKeys.clear();reason="focus_input_limit";version++;return null;}
            String source=String.join("\n",draftKeys);
            String requestKey=NovaFocusHistoryService.digest((draftKeys.size()==1&&source.startsWith("typed-")?"typed:":"")+source);
            var request=new Request(activation,requestKey,question,null,null,draftKeys.stream().map(NovaFocusHistoryService::digest).collect(java.util.stream.Collectors.toSet()),
                settings.answerSelectionOrDefault(),settingsVersion,settings.effectiveAnswerLengthChars(),settings.quickAnswer(),settings.webSearchEnabled(),settings.effectiveReasoningPreset());
            runPresentation=settings.effectivePresentation();
            committed.addAll(draftKeys);while(committed.size()>128)committed.remove(committed.iterator().next());
            draft.clear();draftKeys.clear();foldPartial="";partialVersionReserved=false;answer=receipt="";turn="";
            grounding=null;
            if(snapshotEnabled()){
                pendingRequest=request;pendingCaptureId=newCaptureId();pendingCaptureSource=settings.snapshotOrDefault().source();
                claimedCaptureId=null;snapshotReady=false;snapshotImageBase64=null;snapshotImageMediaType=null;
                snapshotUntil=now+SNAPSHOT_TIMEOUT_MS;captureAttempts++;phase="SNAPSHOT";version++;return null;
            }
            phase="THINKING";inFlight=true;inFlightRequest=request.requestId();generationUntil=now+90000;version++;return request;
        }
        return null;
    }
    private boolean snapshotEnabled(){return settings.snapshotOrDefault().enabled();}
    private String newCaptureId(){return UUID.randomUUID().toString();}
    private void clearCapture(){pendingCaptureId=null;pendingCaptureSource=null;claimedCaptureId=null;}
    /** 촬영 실패는 확정된 질문을 버리지 않는다. 같은 pendingRequest를 사진 없이 한 번 발행한다. */
    private void snapshotFailed(String code,long now){
        clearCapture();snapshotImageBase64=null;snapshotImageMediaType=null;
        if(pendingRequest!=null&&!snapshotReady){snapshotReady=true;textFallbacks++;}
        reason=code;version++;
    }
    /** 생산자 폴링에 실려 나가는 단발 명령. claim되지 않은 명령만 신규 촬영을 허용한다. */
    Command pendingCommand(long now,long settingsVersion){
        if(!phase.equals("SNAPSHOT")||pendingRequest==null||pendingCaptureId==null||snapshotReady||now>=snapshotUntil)return null;
        return new Command("snapshot",server,activation,pendingRequest.requestId(),pendingCaptureId,pendingCaptureSource,
            Math.max(0,snapshotUntil-now),settingsVersion,claimedCaptureId!=null);
    }
    /** 서버 시각 기준 만료 검사를 포함한 단일 생산자 claim. 최초 claim만 촬영을 시작한다. */
    int claimSnapshot(String requestId,String captureId,long now){
        if(!(phase.equals("SNAPSHOT")&&pendingRequest!=null&&pendingRequest.requestId().equals(requestId)
            &&pendingCaptureId!=null&&pendingCaptureId.equals(captureId)&&!snapshotReady&&now<snapshotUntil))
            return CLAIM_REJECTED;
        if(claimedCaptureId==null){claimedCaptureId=captureId;captureGrants++;version++;return CLAIM_GRANTED;}
        return CLAIM_JOINED;
    }
    int acceptSnapshot(String requestId,String captureId,String imageBase64,String imageMediaType,long now){
        if(imageBase64==null||imageBase64.isBlank())throw new IllegalArgumentException("focus_snapshot_image_required");
        if(requestId==null||requestId.length()>256||captureId==null||captureId.isBlank()||captureId.length()>128)throw new IllegalArgumentException("invalid_focus_snapshot");
        String identity=requestId+":"+NovaFocusHistoryService.digest(Objects.toString(imageMediaType,"")+"\n"+imageBase64);
        var prior=acceptedSnapshots.get(captureId);
        if(prior!=null){
            if(prior.equals(identity)){duplicatesSuppressed++;return SNAPSHOT_DUPLICATE;}
            throw new IllegalArgumentException("focus_snapshot_conflict");
        }
        // 결과는 활성 요청·활성 capture·claim 소유권이 모두 일치할 때만 수락한다.
        if(phase.equals("SNAPSHOT")&&pendingRequest!=null&&pendingRequest.requestId().equals(requestId)
            &&pendingCaptureId!=null&&pendingCaptureId.equals(captureId)&&claimedCaptureId!=null
            &&claimedCaptureId.equals(captureId)&&!snapshotReady&&now<snapshotUntil){
            snapshotImageBase64=imageBase64;snapshotImageMediaType=imageMediaType;snapshotReady=true;capturesCompleted++;
            acceptedSnapshots.put(captureId,identity);
            while(acceptedSnapshots.size()>8)acceptedSnapshots.remove(acceptedSnapshots.keySet().iterator().next());
            version++;return SNAPSHOT_ACCEPTED;
        }
        duplicatesSuppressed++;return SNAPSHOT_STALE;
    }
    boolean failSnapshot(String requestId,String captureId,String code,long now){
        if(phase.equals("SNAPSHOT")&&pendingRequest!=null&&pendingRequest.requestId().equals(requestId)
            &&pendingCaptureId!=null&&pendingCaptureId.equals(captureId)&&!snapshotReady){
            snapshotFailed(code==null||code.isBlank()?"snapshot_failed":code,now);return true;
        }
        return false;
    }
    // Source keys are retained separately from content; close keeps the delivery watermark.
    private final Set<String> draftKeys=new LinkedHashSet<>();
    boolean input(ConversateQuestionPolicy.Utterance u,long now){
        boolean consumed=receive(u,now);if(consumed&&draft.contains(key(u))&&!committed.contains(key(u))&&draftKeys.size()<64)draftKeys.add(key(u));return consumed;
    }
    boolean accepts(Request request){return inFlight&&activation.equals(request.activationId())&&inFlightRequest.equals(request.requestId())&&phase.equals("THINKING");}
    void accepted(Request request,String turnId){if(accepts(request)){turn=turnId;version++;}}
    void foldPartial(Request request,String turnId,String text){
        if(!accepts(request)||!turn.equals(turnId)||text==null||text.length()>8000
                ||text.length()<=foldPartial.length()||!text.startsWith(foldPartial))return;
        if(!partialVersionReserved){answerVersion++;partialVersionReserved=true;}
        foldPartial=text;version++;
    }
    boolean foldPrefixMatches(String text){return foldPartial.isEmpty()||text!=null&&text.startsWith(foldPartial);}
    void answer(Request request,String turnId,String text,String ticket,long now){
        answer(request,turnId,text,ticket,now,null);
    }
    void answer(Request request,String turnId,String text,String ticket,long now,com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer grounded){
        if(!accepts(request)||!turnId.equals(turn))return;
        if(grounded!=null&&(!grounded.publicationReady()||!Objects.equals(text,grounded.originalText()))){close("focus_grounding_publication_held");return;}
        if(!foldPrefixMatches(text)){close("focus_stream_final_mismatch");return;}
        grounding=grounded;
        inFlight=false;runAnswerLength=request.answerLengthChars();answer=NovaFocusHistoryService.clip(text,8000);answerTruncated=!answer.equals(text);if(!partialVersionReserved)answerVersion++;foldPartial="";partialVersionReserved=false;receipt=ticket;phase="ANSWER_READY";reason="";
        firstVisible=done=false;idleUntil=0;
        presentationUntil=now+Math.max(120000,Math.min(1500000L,answer.codePointCount(0,answer.length())*(long)settings.presentation().charIntervalMs()+120000));
        version++;
    }
    void failed(Request request,String code){if(accepts(request))close(code);}
    boolean receipt(String instance,String activationId,String turnId,long answerVer,String ticket,String event,long now){
        if(!server.equals(instance)||!activation.equals(activationId)||!turn.equals(turnId)||answerVersion!=answerVer||receipt.isEmpty()||!receipt.equals(ticket)||!active())return false;
        if(event.equals("first_visible")){if(!firstVisible){firstVisible=true;phase="PRESENTING";version++;}return true;}
        if(!event.equals("presentation_done")||!firstVisible)return false;
        if(!done){done=true;phase=draft.hasInput()?"LISTENING":"WAITING";idleUntil=now+settings.followupIdleMs();listenUntil=idleUntil;version++;}return true;
    }
    View view(long now){return new View(server,activation,turn,version,answerVersion,active(),phase,
        NovaFocusHistoryService.clip(draft.text(),2000),question,inFlight&&!foldPartial.isEmpty()?foldPartial:answer,grounding==null?target:"fold",receipt,Math.max(0,idleUntil-now),reason,runPresentation==null?settings.effectivePresentation():runPresentation,answerTruncated,answerTruncated,runAnswerLength,grounding,!inFlight||foldPartial.isEmpty());}
    Map<String,Object> diagnostics(){var m=new LinkedHashMap<String,Object>();
        m.put("active",active());m.put("phase",phase);m.put("stateVersion",version);m.put("answerVersion",answerVersion);
        m.put("bufferedQuestions",draft.hasInput()?1:0);m.put("snapshotPending",phase.equals("SNAPSHOT")&&pendingRequest!=null);
        m.put("captureAttempts",captureAttempts);m.put("capturesCompleted",capturesCompleted);m.put("duplicateSuppressed",duplicatesSuppressed);
        m.put("captureGrants",captureGrants);m.put("textFallbacks",textFallbacks);return Map.copyOf(m);}
}
