package com.example.lms.assist;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;
import com.example.lms.assist.JevDecisionAdvisor.*;
import com.example.lms.assist.JevChoiceAdvisor.*;

/** Single JVM owner of Jev transport, admission latches, permits and UTC dispatch quota. */
public final class JevEvaluationRuntime implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(JevDecisionAdvisor.class);
    private final Environment env;
    private final Clock clock;
    private final Transport transport;
    private final Function<String,String> secrets;
    final Semaphore inFlight;
    private final ExecutorService pool;
    private final AtomicBoolean authBlocked=new AtomicBoolean();
    private final AtomicBoolean planGated=new AtomicBoolean();
    private final AtomicBoolean recheckInFlight=new AtomicBoolean();
    private final Object latchLock=new Object();
    private long latchGeneration;
    private final AtomicLong rateLimitedUntilMs=new AtomicLong();
    record DailyCalls(long epochDay,long count){}
    private static final DailyCalls UNMETERED=new DailyCalls(Long.MIN_VALUE,0);
    /** Process-local UTC dispatch counter; restarting the process resets it.
     * Scope: current JVM only, UTC reservation day; not restart / multi-JVM / account / USD. */
    final AtomicReference<DailyCalls> dailyCalls=new AtomicReference<>(UNMETERED);

    private final ChoiceTransport choiceTransport;
    private final java.util.function.LongSupplier nanos;
    public JevEvaluationRuntime(Environment env) {
        this(env,Clock.systemUTC(),new JevGatewayClient(
                env.getProperty("jev.gateway.zero-data-retention",Boolean.class,false),System::getenv),System::getenv);
    }
    JevEvaluationRuntime(Environment env,Clock clock,Transport transport,Function<String,String> secrets) {
        this(env,clock,transport,choiceTransport(transport),secrets,null,System::nanoTime);
    }
    private static ChoiceTransport choiceTransport(Transport transport) {
        if(transport instanceof JevGatewayClient client)return (request,questions)->client.evaluateChoices(request,questions);
        return (request,questions)->new ChoiceResponse(empty("invalid_response"),null);
    }
    JevEvaluationRuntime(Environment env,Clock clock,Transport transport,ChoiceTransport choiceTransport,
                         Function<String,String> secrets,ExecutorService pool,java.util.function.LongSupplier nanos) {
        this.env=env;this.clock=clock;this.transport=transport;this.choiceTransport=choiceTransport;
        this.secrets=secrets;this.nanos=nanos;
        int max=Math.max(1,intProp("max-in-flight",2,1,8));
        this.inFlight=new Semaphore(max);
        this.pool=pool==null?Executors.newFixedThreadPool(max,r->{var t=new Thread(r,"jev-decision");t.setDaemon(true);return t;}):pool;
    }
    /** Permit ownership follows execution, independently of logical future cancellation. */
    private final class EvaluationTask<T> implements Runnable {
        private final AtomicInteger phase=new AtomicInteger(); // queued=0, running=1, finished=2, cancelled=3
        private final DailyCalls reservation;
        private final Supplier<T> action;
        private final Runnable afterFinish;
        final CompletableFuture<T> future=new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                cancelBeforeStart();
                return super.cancel(interrupt);
            }
        };
        EvaluationTask(DailyCalls reservation,Supplier<T> action,Runnable afterFinish){
            this.reservation=reservation;this.action=action;this.afterFinish=afterFinish;
        }
        private void cancelBeforeStart(){
            if(phase.compareAndSet(0,3)){refundDailyCall(reservation);inFlight.release();afterFinish.run();}
        }
        private void finish(){
            if(phase.compareAndSet(1,2)){inFlight.release();afterFinish.run();}
        }
        @Override public void run(){
            if(!phase.compareAndSet(0,1))return;
            try {
                T result=action.get();
                finish();
                future.complete(result);
            } catch(Throwable failure) {
                finish();
                future.completeExceptionally(failure);
            }
        }
    }
    private <T> CompletableFuture<T> submitEvaluation(DailyCalls reservation,Supplier<T> action){
        return submitEvaluation(reservation,action,()->{});
    }
    private <T> CompletableFuture<T> submitEvaluation(DailyCalls reservation,Supplier<T> action,Runnable afterFinish){
        var task=new EvaluationTask<T>(reservation,action,afterFinish);
        try{pool.execute(task);}
        catch(RejectedExecutionException rejected){task.future.cancel(false);throw rejected;}
        return task.future;
    }
    @Override public void close(){
        for(Runnable pending:pool.shutdownNow())
            if(pending instanceof JevEvaluationRuntime.EvaluationTask<?> task)task.future.cancel(false);
    }

    public String mode(){
        String value=env.getProperty("demo.jev.mode","off");
        value=value==null?"off":value.trim().toLowerCase(Locale.ROOT);
        return "shadow".equals(value)||"on".equals(value)?value:"off";
    }
    /** Owner-safe status for the phone settings surface; never carries keys or raw text. */
    public Map<String,Object> status(){
        String mode=mode();boolean configured=configured();
        String reason;
        if("off".equals(mode))reason="disabled";
        else if(!configured)reason="jev_not_configured";
        else if(authBlocked.get())reason="auth_blocked";
        else if(planGated.get())reason="plan_gate";
        else if(clock.millis()<rateLimitedUntilMs.get())reason="rate_limited";
        else if(!budgetAdmitted()||!dailyCapAdmitted())reason="budget_skip";
        else reason="ready";
        var map=new LinkedHashMap<String,Object>();
        map.put("mode",mode);
        var policies=new JevSurfacePolicy(env);
        map.put("surfaceModes",Map.of("focus",policies.resolve("focus").mode(),"cue",policies.resolve("cue").mode(),"main",policies.resolve("main").mode()));map.put("configured",configured);map.put("reason",reason);
        map.put("callsAllowed",configured&&"ready".equals(reason));
        return Map.copyOf(map);
    }
    /**
     * One evaluation per confirmed question. shadow submits a bounded async task and
     * returns immediately; on waits at most decision-wait-ms. Every failure defers.
     */
    public Advice advise(String surface,String question,String baseline){
        var policy=new JevSurfacePolicy(env).resolve(surface);
        String mode=policy.mode();
        if("off".equals(mode))return Advice.off();
        if(!configured())return Advice.defer("jev_not_configured",mode);
        if(authBlocked.get())return Advice.defer("auth_blocked",mode);
        if(planGated.get())return Advice.defer("plan_gate",mode);
        if(clock.millis()<rateLimitedUntilMs.get())return Advice.defer("rate_limited",mode);
        if(!budgetAdmitted())return Advice.defer("budget_skip",mode);
        if(!inFlight.tryAcquire())return Advice.defer("busy",mode);
        var reservation=reserveDailyCall();
        if(reservation==null){inFlight.release();return Advice.defer("budget_skip",mode);}
        var req=new EvalRequest(endpoint(),model(),credentialEnv(),bounded(question),
                surface==null?"unknown":surface,baseline==null?"":baseline,
                intProp("connect-timeout-ms",250,50,5000),policy.requestTimeoutMs(),
                intProp("max-state-bytes",8192,1024,65536),intProp("max-response-bytes",65536,1024,1048576));
        if("shadow".equals(mode)){
            try{submitEvaluation(reservation,()->{
                long began=clock.millis();String decision="defer",reason="transport_error";
                int httpStatus=0;
                try{
                    var res=transport.evaluate(req);remember(res);
                    httpStatus=res.httpStatus();
                    if(res.verdict()!=null){decision=res.verdict().name();reason="ok";}
                    else reason=res.failure()==null?"invalid_response":res.failure();
                }catch(RuntimeException failure){/* reason stays transport_error */}
                String observedBaseline=req.baseline().isEmpty()?"none":
                        Set.of("CUE","RAG_CUE","WEB","RECENT_ONLY").contains(req.baseline())?req.baseline():"other";
                LOG.info("[AWX][jev] surface={} mode=shadow decision={} reasonCode={} latencyMs={} baseline={} httpStatus={}",
                        surface,decision,reason,clock.millis()-began,observedBaseline,httpStatus);
                return null;
            });}catch(RejectedExecutionException rejected){
                return Advice.defer("busy",mode);
            }
            return Advice.defer("shadow",mode);
        }
        long began=clock.millis();
        CompletableFuture<EvalResponse> future;
        try{future=submitEvaluation(reservation,()->{
            try{
                var res=transport.evaluate(req);
                remember(res);
                return res;
            }
            catch(RuntimeException failure){return new EvalResponse(0,null,"transport_error",null);}
        });}catch(RejectedExecutionException rejected){
            return Advice.defer("busy",mode);
        }
        try{
            var res=future.get(policy.decisionWaitMs(),TimeUnit.MILLISECONDS);
            if(res.verdict()==null)return Advice.defer(res.failure()==null?"invalid_response":res.failure(),mode);
            return Advice.of(res.verdict(),mode,clock.millis()-began);
        }catch(TimeoutException timeout){
            future.cancel(true);return Advice.defer("timeout",mode);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();future.cancel(true);return Advice.defer("cancelled",mode);
        }catch(ExecutionException failure){
            return Advice.defer("transport_error",mode);
        }
    }
    /** Internal, explicit ops request only. No polling or endpoint, and no admission bypass. */
    Advice recheck(String surface,boolean approved) {
        var policy=new JevSurfacePolicy(env).resolve(surface);
        String mode=policy.mode();
        if("off".equals(mode))return Advice.off();
        if(!approved)return Advice.defer("recheck_approval_required",mode);
        if(!configured())return Advice.defer("jev_not_configured",mode);
        if(clock.millis()<rateLimitedUntilMs.get())return Advice.defer("rate_limited",mode);
        if(!budgetAdmitted())return Advice.defer("budget_skip",mode);
        if(!recheckInFlight.compareAndSet(false,true))return Advice.defer("busy",mode);
        final long generation;
        synchronized(latchLock) {
            generation=latchGeneration;
            if(!authBlocked.get()&&!planGated.get()) {
                recheckInFlight.set(false);
                return Advice.defer("not_blocked",mode);
            }
        }
        if(!inFlight.tryAcquire()){recheckInFlight.set(false);return Advice.defer("busy",mode);}
        var reservation=reserveDailyCall();
        if(reservation==null){inFlight.release();recheckInFlight.set(false);return Advice.defer("budget_skip",mode);}
        var request=new EvalRequest(endpoint(),model(),credentialEnv(),"Approved connectivity recheck",
                surface==null?"unknown":surface,"RECENT_ONLY",intProp("connect-timeout-ms",250,50,5000),
                policy.requestTimeoutMs(),intProp("max-state-bytes",8192,1024,65536),
                intProp("max-response-bytes",65536,1024,1048576));
        long began=clock.millis();
        long deadline=nanos.getAsLong()+TimeUnit.MILLISECONDS.toNanos(policy.decisionWaitMs());
        AtomicBoolean current=new AtomicBoolean(true);
        CompletableFuture<EvalResponse> future;
        try {
            future=submitEvaluation(reservation,()->{
                EvalResponse result;
                try {result=transport.evaluate(request);}
                catch(RuntimeException failure){return new EvalResponse(0,null,"transport_error",null);}
                remember(result);
                if(result!=null&&result.httpStatus()>=200&&result.httpStatus()<300
                        &&result.verdict()!=null&&result.failure()==null) {
                    synchronized(latchLock) {
                        if(current.get()&&nanos.getAsLong()<deadline&&latchGeneration==generation) {
                            authBlocked.set(false);planGated.set(false);
                        }
                    }
                }
                return result;
            },()->recheckInFlight.set(false));
        } catch(RejectedExecutionException rejected){return Advice.defer("busy",mode);}
        try {
            EvalResponse result=future.get(policy.decisionWaitMs(),TimeUnit.MILLISECONDS);
            if(result==null||result.verdict()==null||result.failure()!=null
                    ||result.httpStatus()<200||result.httpStatus()>=300)
                return Advice.defer(result==null||result.failure()==null?"invalid_response":result.failure(),mode);
            return Advice.of(result.verdict(),mode,clock.millis()-began);
        } catch(TimeoutException timeout) {
            current.set(false);future.cancel(true);return Advice.defer("timeout",mode);
        } catch(InterruptedException interrupted) {
            current.set(false);Thread.currentThread().interrupt();future.cancel(true);return Advice.defer("cancelled",mode);
        } catch(ExecutionException failure) {return Advice.defer("transport_error",mode);}
    }

    private void remember(EvalResponse res){
        if(res==null)return;
        synchronized(latchLock) {
            boolean auth=res.httpStatus()==401||(res.httpStatus()==403&&!"plan_gate".equals(res.failure()))
                    ||"auth_blocked".equals(res.failure());
            boolean plan="plan_gate".equals(res.failure());
            if(auth)authBlocked.set(true);
            if(plan)planGated.set(true);
            if(auth||plan)latchGeneration++;
        }
        if("rate_limited".equals(res.failure()))
            rateLimitedUntilMs.accumulateAndGet(
                    clock.millis()+Math.max(0,Math.min(res.retryAfterMs()==null?30000:res.retryAfterMs(),300000)),
                    Math::max);
    }
    private boolean configured(){return StringUtils.hasText(secrets.apply(credentialEnv()));}
    private String credentialEnv(){return env.getProperty("demo.jev.credential-env","AI_GATEWAY_API_KEY");}
    private String endpoint(){return env.getProperty("demo.jev.endpoint","https://ai-gateway.vercel.sh/v1/evaluate");}
    private String model(){return env.getProperty("demo.jev.model","typesafe-ai/jev");}
    /** Free promo ended 2026-09-25 (Vercel notice). Past the window with unknown pricing,
     *  free-only + !allow-paid skips the call; allow-paid is the explicit paid opt-in. */
    private boolean budgetAdmitted(){
        boolean freeOnly=env.getProperty("demo.jev.free-only",Boolean.class,true);
        boolean allowPaid=env.getProperty("demo.jev.allow-paid",Boolean.class,false);
        if(!(freeOnly&&!allowPaid))return true;
        String end=env.getProperty("demo.jev.free-window-end","2026-09-26T00:00:00Z");
        try{return clock.instant().isBefore(java.time.Instant.parse(end.trim()));}
        catch(RuntimeException malformed){return false;}
    }
    private boolean dailyCapEnabled(){return env.getProperty("demo.jev.budget.enabled",Boolean.class,false);}
    private long dailyLimit(){return Math.max(0,env.getProperty("demo.jev.budget.daily-max-calls",Long.class,0L));}
    private long utcDay(){return Math.floorDiv(clock.millis(),86_400_000L);}
    private boolean dailyCapAdmitted(){
        if(!dailyCapEnabled())return true;
        long limit=dailyLimit();var current=dailyCalls.get();
        return limit>0&&(utcDay()>current.epochDay()||current.count()<limit);
    }
    DailyCalls reserveDailyCall(){
        if(!dailyCapEnabled())return UNMETERED;
        long limit=dailyLimit();
        while(true){
            var current=dailyCalls.get();long day=Math.max(utcDay(),current.epochDay());
            long count=current.epochDay()==day?current.count():0;
            if(count>=limit)return null;
            var reserved=new DailyCalls(day,count+1);
            if(dailyCalls.compareAndSet(current,reserved))return reserved;
        }
    }
    void refundDailyCall(DailyCalls reservation){
        if(reservation==UNMETERED)return;
        while(true){
            var current=dailyCalls.get();
            if(current.epochDay()!=reservation.epochDay()||current.count()==0)return;
            if(dailyCalls.compareAndSet(current,new DailyCalls(current.epochDay(),current.count()-1)))return;
        }
    }
    private int intProp(String name,int fallback,int min,int max){
        Integer value=env.getProperty("demo.jev."+name,Integer.class);
        return value==null?fallback:Math.max(min,Math.min(max,value));
    }
    private static String bounded(String question){
        if(question==null)return "";
        String text=question.strip().replaceAll("\\s+"," ");
        return text.length()<=1200?text:text.substring(0,1200);
    }

    public record DecisionAdmission(java.util.function.BooleanSupplier current,long deadlineNanos,boolean privacyAllowed) {
        public DecisionAdmission { Objects.requireNonNull(current); }
    }
    public record ChoiceResponse(ChoiceResult result,Long retryAfterMs) {}
    public interface ChoiceTransport {
        ChoiceResponse evaluate(EvalRequest request,List<ChoiceQuestion> questions);
    }
    /** Opaque request-local handle: neither a future nor local identity is exported. */
    public static final class EvaluationHandle {
        private final JevEvaluationRuntime owner;
        private final QuestionKey key;
        private final DecisionAdmission admission;
        private final String surface,mode;
        private final long deadlineNanos;
        private final CompletableFuture<ChoiceResult> future;
        private final AtomicBoolean discarded=new AtomicBoolean();
        private volatile boolean staleDiscarded,privacyBlocked;
        private EvaluationHandle(JevEvaluationRuntime owner,QuestionKey key,DecisionAdmission admission,
                String surface,String mode,long deadlineNanos,CompletableFuture<ChoiceResult> future) {
            this.owner=owner;this.key=key;this.admission=admission;this.surface=surface;this.mode=mode;
            this.deadlineNanos=deadlineNanos;this.future=future;
        }
        public boolean staleDiscarded(){return staleDiscarded;}
        public boolean privacyBlocked(){return privacyBlocked;}
    }
    static ChoiceResult empty(String reason){return new ChoiceResult(Map.of(),0,reason,0,Optional.empty());}
    EvaluationHandle rejected(QuestionKey key,String surface,DecisionAdmission admission,String reason,boolean privacy) {
        String mode=new JevSurfacePolicy(env).resolve(surface).mode();
        if(!"off".equals(mode))LOG.info("[AWX][jev][choice] phase=admission surface={} mode={} reasonCode={} dispatched=false",
                choiceSurface(surface),mode,choiceReason(reason));
        var h=new EvaluationHandle(this,key,admission,surface,"off",nanos.getAsLong(),
                CompletableFuture.completedFuture(empty(reason)));
        h.privacyBlocked=privacy;return h;
    }
    void requireParent(DecisionAdmission admission,long deadline) {
        if(Thread.currentThread().isInterrupted()||!admission.current().getAsBoolean())
            throw new CancellationException("request_cancelled");
        long now=nanos.getAsLong();
        if(admission.deadlineNanos()-now<=0||deadline-now<=0)
            throw new com.example.lms.llm.ModelSelectionException("backend_timeout");
    }
    EvaluationHandle prefetch(QuestionKey key,String surface,String question,List<ChoiceQuestion> questions,DecisionAdmission admission) {
        var policy=new JevSurfacePolicy(env).resolve(surface);String mode=policy.mode();
        // Keep the legacy admission order, including busy before UTC quota reservation.
        String reason=null;
        if("off".equals(mode))reason="disabled";
        else if(!configured())reason="jev_not_configured";
        else if(authBlocked.get())reason="auth_blocked";
        else if(planGated.get())reason="plan_gate";
        else if(clock.millis()<rateLimitedUntilMs.get())reason="rate_limited";
        else if(!budgetAdmitted())reason="budget_skip";
        if(reason!=null)return rejected(key,surface,admission,reason,false);
        requireParent(admission,admission.deadlineNanos());
        if(!inFlight.tryAcquire())return rejected(key,surface,admission,"busy",false);
        var reservation=reserveDailyCall();
        if(reservation==null){inFlight.release();return rejected(key,surface,admission,"budget_skip",false);}
        long submitted=nanos.getAsLong();
        long deadline=submitted+TimeUnit.MILLISECONDS.toNanos(policy.decisionWaitMs());
        CompletableFuture<ChoiceResult> future;
        try {
            future=submitEvaluation(reservation,()->{
                try {
                    requireParent(admission,admission.deadlineNanos());
                    long remaining=TimeUnit.NANOSECONDS.toMillis(admission.deadlineNanos()-nanos.getAsLong());
                    if(remaining<=0)return empty("timeout");
                    var req=new EvalRequest(endpoint(),model(),credentialEnv(),question,surface,"",
                            intProp("connect-timeout-ms",250,50,5000),(int)Math.min(policy.requestTimeoutMs(),remaining),
                            intProp("max-state-bytes",8192,1024,65536),intProp("max-response-bytes",65536,1024,1048576));
                    var response=choiceTransport.evaluate(req,questions);
                    if(response==null||response.result()==null)return empty("invalid_response");
                    var result=response.result();
                    remember(new EvalResponse(result.httpStatus(),null,result.reasonCode(),response.retryAfterMs()));
                    return new ChoiceResult(result.answers(),result.httpStatus(),result.reasonCode(),nanos.getAsLong(),result.billedUsd());
                } catch(CancellationException cancelled) { return empty("cancelled"); }
                catch(com.example.lms.llm.ModelSelectionException expired) { return empty("timeout"); }
                catch(RuntimeException failure) { return empty("transport_error"); }
            });
        } catch(RejectedExecutionException rejected) {
            return rejected(key,surface,admission,"busy",false);
        }
        // Accounting belongs to completion, including shadow/discarded requests with no consumer.
        future=future.whenComplete((result,failure)->LOG.info(
                "[AWX][jev][choice] phase=completion surface={} mode={} questions={} reasonCode={} latencyMs={} httpStatus={} billedUsd={}",
                choiceSurface(surface),mode,questions.size(),choiceReason(result==null?"transport_error":result.reasonCode()),
                TimeUnit.NANOSECONDS.toMillis(Math.max(0,nanos.getAsLong()-submitted)),result==null?0:result.httpStatus(),
                result==null?"none":result.billedUsd().map(java.math.BigDecimal::toPlainString).orElse("none")));
        return new EvaluationHandle(this,key,admission,surface,mode,deadline,future);
    }
    private static String choiceSurface(String surface) {
        return surface!=null&&Set.of("main","cue","focus").contains(surface)?surface:"unknown";
    }
    private static String choiceReason(String reason) {
        return reason!=null&&(Set.of("ok","timeout","budget_skip","disabled","shadow","cancelled","busy",
                "auth_blocked","auth_invalid","plan_gate","permission_denied","rate_limited","jev_not_configured",
                "invalid_response","transport_error","upstream_error","network","error","billing-blocked",
                "endpoint_invalid","endpoint_not_allowed","endpoint_not_https","encode_failed","state_oversized",
                "oversized_response","redirect","model_unverified","wrong_model").contains(reason)
                ||reason.matches("http_[1-5][0-9]{2}"))?reason:"invalid_response";
    }
    ChoiceResult await(EvaluationHandle handle,QuestionKey current,long requestDeadline) {
        if(handle==null||handle.owner!=this)return empty("invalid_response");
        requireParent(handle.admission,requestDeadline);
        if(handle.discarded.get()||!Objects.equals(handle.key,current)){
            handle.staleDiscarded=true;return empty("cancelled");
        }
        if("off".equals(handle.mode))return handle.future.getNow(empty("disabled"));
        String effective=new JevSurfacePolicy(env).resolve(handle.surface).mode();
        if("off".equals(effective))return empty("disabled");
        boolean shadow="shadow".equals(handle.mode)||"shadow".equals(effective);
        ChoiceResult result;
        if(shadow)result=handle.future.getNow(null);
        else {
            long now=nanos.getAsLong();
            long remaining=Math.min(handle.deadlineNanos-now,Math.min(handle.admission.deadlineNanos()-now,requestDeadline-now));
            if(remaining<=0)return empty("timeout");
            try {result=handle.future.get(remaining,TimeUnit.NANOSECONDS);}
            catch(TimeoutException timeout){requireParent(handle.admission,requestDeadline);return empty("timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("request_cancelled");}
            catch(ExecutionException failure){return empty("transport_error");}
        }
        requireParent(handle.admission,requestDeadline);
        if(handle.discarded.get()){handle.staleDiscarded=true;return empty("cancelled");}
        if(result==null)return empty("shadow");
        if(!shadow&&result.completedNanos()-handle.deadlineNanos>0)return empty("timeout");
        var observations=new LinkedHashMap<String,ChoiceObservation>();
        for(var entry:result.answers().entrySet()){
            var observation=entry.getValue();var threshold=threshold(handle.surface,entry.getKey(),observation.choice());
            boolean accepted=!shadow&&observation.schemaValid()&&observation.probability().isPresent()
                    &&threshold.isPresent()&&observation.probability().getAsDouble()>=threshold.getAsDouble();
            observations.put(entry.getKey(),new ChoiceObservation(observation.choice(),observation.probability(),observation.schemaValid(),accepted));
        }
        return new ChoiceResult(observations,result.httpStatus(),result.reasonCode(),result.completedNanos(),result.billedUsd());
    }
    private OptionalDouble threshold(String surface,String id,String choice) {
        String suffix="routeDecision".equals(id)&&"focus".equals(surface)?"focus":"default";
        if("webNeed".equals(id))suffix=switch(choice){case "NONE"->"web-disable";case "LIGHT"->"web-light";case "DEEP"->"web-deep";default->"default";};
        if("complexity".equals(id)&&"SIMPLE".equals(choice))suffix="complexity-simple";
        try {
            String raw=env.getProperty("demo.jev.confidence."+suffix);
            if(raw==null)return OptionalDouble.empty();
            double value=Double.parseDouble(raw);
            return Double.isFinite(value)&&value>=0&&value<=1?OptionalDouble.of(value):OptionalDouble.empty();
        }catch(RuntimeException malformed){return OptionalDouble.empty();}
    }
    void discard(EvaluationHandle handle){if(handle!=null&&handle.owner==this)handle.discarded.set(true);}
}
