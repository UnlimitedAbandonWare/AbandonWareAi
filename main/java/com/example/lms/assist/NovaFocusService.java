package com.example.lms.assist;

import com.example.lms.api.PublicChatAdmissionGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
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
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Slot> scopes=new ConcurrentHashMap<>(),sessions=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"nova-focus-answer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"nova-focus-clock");t.setDaemon(true);return t;});
    private static final class Slot {
        final String owner,channel;
        final NovaFocusState state;
        String assistId;
        long epoch,lastSeen;
        boolean busy;
        Long room;
        String pendingTurn;
        final ArrayDeque<NovaFocusHistoryService.Pair> recent=new ArrayDeque<>();
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
            s=new Slot(owner,channel,server,settings.settings());scopes.put(scope,s);
        }
        synchronized(s){
            if(!Objects.equals(s.assistId,assistId)||s.epoch!=epoch){
                if(s.assistId!=null)sessions.remove(s.assistId,s);
                cancel(s,"capture_changed");s.assistId=assistId;s.epoch=epoch;s.state.sourceNamespace(assistId+":"+epoch);sessions.put(assistId,s);
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
    public record LocalStore(String cacheScope,long settingsVersion,NovaFocusSettings settings){}
    public LocalStore localStore(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);var value=history.settings(owner,s.channel);
        return new LocalStore(NovaFocusHistoryService.digest("local-cache:"+NovaFocusHistoryService.scope(owner,s.channel)),value.settingsVersion(),value.settings());
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
            s.state.configure(stored.settings());
            if(disabled)cancel(s,"focus_disabled");
            return stored;
        }
    }
    public NovaFocusHistoryService.Page history(String owner,String assistId,long epoch,Long before,int limit){
        var s=owned(owner,assistId,epoch);return history.page(owner,s.channel,before,limit);
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
    /** Hot ASR path: no DB/provider call and no hint-side executor/cancellation. */
    public boolean audio(String owner,String assistId,long epoch,ConversateQuestionPolicy.Utterance utterance){
        var s=sessions.get(assistId);if(s==null||!s.owner.equals(owner)||s.epoch!=epoch)return false;
        synchronized(s){s.lastSeen=clock.millis();return s.state.input(utterance,clock.millis());}
    }
    public boolean active(String assistId){var s=sessions.get(assistId);if(s==null)return false;synchronized(s){return s.state.active();}}
    public Map<String,Object> diagnostics(String owner,String assistId,long epoch){
        var s=owned(owner,assistId,epoch);synchronized(s){var result=new LinkedHashMap<>(s.state.diagnostics());result.put("busy",s.busy);return Map.copyOf(result);}
    }
    public NovaFocusState.View view(String owner,String assistId,long epoch){
        var s=sessions.get(assistId);if(s==null||!s.owner.equals(owner)||s.epoch!=epoch)return null;
        synchronized(s){return s.state.view(clock.millis());}
    }
    public void close(String owner,String assistId,long epoch,String reason){
        var s=owned(owner,assistId,epoch);synchronized(s){cancel(s,reason);}
    }
    public void detach(String assistId,String reason){
        var s=sessions.remove(assistId);if(s!=null)synchronized(s){cancel(s,reason);}
    }
    private void cancel(Slot s,String reason){
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
            synchronized(s){
                boolean was=s.state.active();request=s.state.tick(now,!s.busy);
                if(was&&!s.state.active())cancel(s,s.state.view(now).reason());
                if(request!=null)s.busy=true;
            }
            if(request!=null)try{workers.execute(()->generate(s,request));}
            catch(RejectedExecutionException full){synchronized(s){s.busy=false;s.state.failed(request,"focus_busy");}}
        }
    }
    private void generate(Slot s,NovaFocusState.Request request){
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
            NovaFocusHistoryService.Context memory;
            synchronized(s){memory=new NovaFocusHistoryService.Context(List.copyOf(s.recent),"",List.of());}
            String answer=adapter.answer(accepted.chatSessionId(),request.question(),memory,()->{synchronized(s){return s.state.accepts(request);}});
            synchronized(s){
                if(!s.state.accepts(request)){history.terminal(s.owner,s.channel,id,"CANCELLED",null);return;}
                if(history.terminal(s.owner,s.channel,id,"COMPLETED",answer)){
                    s.recent.addLast(new NovaFocusHistoryService.Pair(0,id,"COMPLETED",
                        NovaFocusHistoryService.memoryClip(request.question(),350),NovaFocusHistoryService.memoryClip(answer,350)));
                    while(s.recent.size()>2)s.recent.removeFirst();
                    byte[] bytes=new byte[32];random.nextBytes(bytes);
                    s.state.answer(request,id,answer,HexFormat.of().formatHex(bytes),clock.millis());
                }else s.state.failed(request,"focus_outcome_unknown");
            }
        }catch(RuntimeException failure){
            synchronized(s){
                if(id!=null)try{history.terminal(s.owner,s.channel,id,"OUTCOME_UNKNOWN",null);}catch(RuntimeException unavailable){}
                s.state.failed(request,failure instanceof PublicChatAdmissionGuard.Rejection?"focus_busy":"focus_answer_unavailable");
                if(!s.state.active())s.recent.clear();
            }
        }finally{if(lease!=null)lease.close();synchronized(s){s.pendingTurn=null;s.busy=false;}}
    }
    @Override @PreDestroy public void close(){
        timer.shutdownNow();workers.shutdownNow();
        for(var s:scopes.values())synchronized(s){s.recent.clear();s.state.close("server_shutdown");}
        sessions.clear();scopes.clear();
    }
}
