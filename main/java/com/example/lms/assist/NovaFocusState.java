package com.example.lms.assist;

import java.util.*;

/** Pure, clock-driven Focus state; callers serialize access per assist session. */
final class NovaFocusState {
    record Request(String activationId,String requestId,String question) {}
    record View(String serverInstanceId,String activationId,String turnId,long stateVersion,long answerVersion,
                boolean active,String phase,String draftText,String questionText,String answerText,
                String renderTarget,String renderReceiptTicket,long idleRemainingMs,String reason,
                NovaFocusSettings.Presentation presentation,boolean answerTruncated,boolean hasMoreOnFold) {
        @Override public String toString(){return "NovaFocusView[redacted]";}
        View forTarget(String surface){return new View(serverInstanceId,activationId,turnId,stateVersion,answerVersion,active,phase,draftText,questionText,answerText,renderTarget,renderTarget.equals(surface)?renderReceiptTicket:null,idleRemainingMs,reason,presentation,answerTruncated,hasMoreOnFold);}
    }
    final String server;
    NovaFocusSettings settings;
    private final ConversateQuestionPolicy delivery=new ConversateQuestionPolicy();
    private final NovaFocusTurnAssembler draft=new NovaFocusTurnAssembler();
    private final Set<String> committed=new LinkedHashSet<>();
    private String phase="OFF",activation="",turn="",question="",answer="",receipt="",candidate="",target="lens",reason="";
    private long version,answerVersion,listenUntil,generationUntil,presentationUntil,idleUntil;
    private boolean inFlight,firstVisible,done,answerTruncated;
    private String sourceNamespace="";
    void sourceNamespace(String value){
        if(!sourceNamespace.equals(value)){delivery.clear();committed.clear();sourceNamespace=value;}
    }
    private String wakeTarget="fold";
    void defaultTarget(String value){if(Set.of("fold","lens").contains(value))wakeTarget=value;}
    NovaFocusState(String server,NovaFocusSettings settings){this.server=server;configure(settings);}
    void configure(NovaFocusSettings next){settings=Objects.requireNonNull(next);if(!active())phase=next.enabled()?"ARMED":"OFF";version++;}
    boolean active(){return !Set.of("OFF","ARMED").contains(phase);}
    boolean inFlight(){return inFlight;}
    String activation(){return activation;}
    String turn(){return turn;}
    void open(long now,String renderTarget){
        if(!Set.of("lens","fold").contains(renderTarget))throw new IllegalArgumentException("invalid_focus_target");
        if(active())return;
        activation=UUID.randomUUID().toString();target=renderTarget;phase="LISTENING";draft.clear();draftKeys.clear();
        answer=question=turn=receipt=candidate=reason="";answerTruncated=false;idleUntil=0;listenUntil=now+settings.wakeListenTimeoutMs();version++;
    }
    void close(String cause){phase=settings.enabled()?"ARMED":"OFF";draft.clear();draftKeys.clear();answer=question=receipt="";inFlight=false;idleUntil=0;reason=cause;version++;}
    private String key(ConversateQuestionPolicy.Utterance u){return u.questionId()+":"+u.utteranceId();}
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
                ||Set.of("THINKING","ANSWER_READY","PRESENTING").contains(phase)&&draft.finalReady(now,settings.utteranceQuietMs()))){
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
    Request tick(long now){return tick(now,true);}
    Request tick(long now,boolean capacity){
        if(!active())return null;
        if(draft.overLimit()){draft.clear();draftKeys.clear();reason="focus_input_limit";version++;}
        if(phase.equals("WAKE_PREVIEW")&&now>=listenUntil){close("wake_unconfirmed");return null;}
        if(inFlight&&now>=generationUntil){close("generation_timeout");return null;}
        if(Set.of("ANSWER_READY","PRESENTING").contains(phase)&&now>=presentationUntil){close("presentation_unconfirmed");return null;}
        if(phase.equals("WAITING")&&now>=idleUntil){close("idle_timeout");return null;}
        if(phase.equals("LISTENING")&&!draft.hasInput()&&now>=listenUntil){close("wake_no_question");return null;}
        if(draft.hasInput()&&!draft.finalized()&&now-draft.changedAt()>60000){close("input_unconfirmed");return null;}
        if(capacity&&phase.equals("LISTENING")&&draft.finalReady(now,settings.utteranceQuietMs())){
            question=draft.text();if(question.codePointCount(0,question.length())>2000){draft.clear();draftKeys.clear();reason="focus_input_limit";version++;return null;}
            String source=String.join("\n",draftKeys);
            String requestKey=NovaFocusHistoryService.digest((draftKeys.size()==1&&source.startsWith("typed-")?"typed:":sourceNamespace+"\n")+source);
            var request=new Request(activation,requestKey,question);
            committed.addAll(draftKeys);while(committed.size()>128)committed.remove(committed.iterator().next());
            draft.clear();draftKeys.clear();answer=receipt="";turn="";phase="THINKING";inFlight=true;generationUntil=now+90000;version++;return request;
        }
        return null;
    }
    // Source keys are retained separately from content; close keeps the delivery watermark.
    private final Set<String> draftKeys=new LinkedHashSet<>();
    boolean input(ConversateQuestionPolicy.Utterance u,long now){
        boolean consumed=receive(u,now);if(consumed&&draft.contains(key(u))&&!committed.contains(key(u))&&draftKeys.size()<64)draftKeys.add(key(u));return consumed;
    }
    boolean accepts(Request request){return inFlight&&activation.equals(request.activationId())&&phase.equals("THINKING");}
    void accepted(Request request,String turnId){if(accepts(request)){turn=turnId;version++;}}
    void answer(Request request,String turnId,String text,String ticket,long now){
        if(!accepts(request)||!turnId.equals(turn))return;
        inFlight=false;answer=NovaFocusHistoryService.clip(text,8000);answerTruncated=!answer.equals(text);answerVersion++;receipt=ticket;phase="ANSWER_READY";reason="";
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
        NovaFocusHistoryService.clip(draft.text(),2000),question,answer,target,receipt,Math.max(0,idleUntil-now),reason,settings.presentation(),answerTruncated,answerTruncated);}
    Map<String,Object> diagnostics(){return Map.of("active",active(),"phase",phase,"stateVersion",version,"answerVersion",answerVersion,"bufferedQuestions",draft.hasInput()?1:0);}
}
