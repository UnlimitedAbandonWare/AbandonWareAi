package com.example.lms.assist;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Jev (typesafe-ai/jev) fast decision signal: one bounded Gateway /v1/evaluate call per
 * confirmed question, verdict passed to the existing search/answer path. Never generates
 * text, never touches the PCM/voice/reconnect hot path, and fails open to the
 * deterministic local rules on any gap. mode: off | shadow | on (demo.jev.mode).
 */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class JevDecisionAdvisor implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(JevDecisionAdvisor.class);
    public enum Verdict { RECENT_ONLY, SCOPED_RAG, WEB, HYBRID, CLARIFY }
    /** verdict=null means fail-open defer; reasonCode always identifies the gate/outcome. */
    public record Advice(Verdict verdict,String reasonCode,String mode,long latencyMs){
        public String decision(){return verdict!=null?verdict.name():("off".equals(mode)?"off":"defer");}
        public boolean usable(){return verdict!=null;}
        static Advice off(){return new Advice(null,"disabled","off",-1);}
        static Advice defer(String reason,String mode){return new Advice(null,reason,mode,-1);}
        static Advice of(Verdict verdict,String mode,long latencyMs){return new Advice(verdict,"ok",mode,latencyMs);}
    }
    public record EvalRequest(String endpoint,String model,String credentialEnv,String question,String surface,String baseline,
                              int connectTimeoutMs,int requestTimeoutMs,int maxRequestBytes,int maxResponseBytes){}
    /** httpStatus 0 = transport never answered; verdict non-null = parsed contract answer. */
    public record EvalResponse(int httpStatus,Verdict verdict,String failure,Long retryAfterMs){}
    public interface Transport { EvalResponse evaluate(EvalRequest request); }

    private final Environment env;
    private final Clock clock;
    private final Transport transport;
    private final Function<String,String> secrets;
    private final Semaphore inFlight;
    private final ExecutorService pool;
    private final AtomicBoolean authBlocked=new AtomicBoolean();
    private volatile long rateLimitedUntilMs=0;

    @Autowired public JevDecisionAdvisor(Environment env){this(env,Clock.systemUTC(),new JevGatewayClient(),System::getenv);}
    JevDecisionAdvisor(Environment env,Clock clock,Transport transport,Function<String,String> secrets){
        this.env=env;this.clock=clock;this.transport=transport;this.secrets=secrets;
        int max=Math.max(1,intProp("max-in-flight",2,1,8));
        this.inFlight=new Semaphore(max);
        this.pool=Executors.newFixedThreadPool(max,r->{var t=new Thread(r,"jev-decision");t.setDaemon(true);return t;});
    }
    @Override @jakarta.annotation.PreDestroy public void close(){pool.shutdownNow();}

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
        else if(clock.millis()<rateLimitedUntilMs)reason="rate_limited";
        else if(!budgetAdmitted())reason="budget_skip";
        else reason="ready";
        var map=new LinkedHashMap<String,Object>();
        map.put("mode",mode);map.put("configured",configured);map.put("reason",reason);
        map.put("callsAllowed",configured&&"ready".equals(reason));
        return Map.copyOf(map);
    }
    /**
     * One evaluation per confirmed question. shadow submits a bounded async task and
     * returns immediately; on waits at most decision-wait-ms. Every failure defers.
     */
    public Advice advise(String surface,String question,String baseline){
        String mode=mode();
        if("off".equals(mode))return Advice.off();
        if(!configured())return Advice.defer("jev_not_configured",mode);
        if(authBlocked.get())return Advice.defer("auth_blocked",mode);
        if(clock.millis()<rateLimitedUntilMs)return Advice.defer("rate_limited",mode);
        if(!budgetAdmitted())return Advice.defer("budget_skip",mode);
        if(!inFlight.tryAcquire())return Advice.defer("busy",mode);
        var req=new EvalRequest(endpoint(),model(),credentialEnv(),bounded(question),
                surface==null?"unknown":surface,baseline==null?"":baseline,
                intProp("connect-timeout-ms",250,50,5000),intProp("request-timeout-ms",800,50,10000),
                intProp("max-state-bytes",8192,1024,65536),intProp("max-response-bytes",65536,1024,1048576));
        if("shadow".equals(mode)){
            pool.execute(()->{
                long began=clock.millis();String decision="defer",reason="transport_error";
                try{
                    var res=transport.evaluate(req);remember(res);
                    if(res.verdict()!=null){decision=res.verdict().name();reason="ok";}
                    else reason=res.failure()==null?"invalid_response":res.failure();
                }catch(RuntimeException failure){/* reason stays transport_error */}
                finally{inFlight.release();}
                LOG.info("[AWX][jev] surface={} mode=shadow decision={} reasonCode={} latencyMs={}",surface,decision,reason,clock.millis()-began);
            });
            return Advice.defer("shadow",mode);
        }
        long began=clock.millis();
        var future=CompletableFuture.supplyAsync(()->{
            try{return transport.evaluate(req);}
            catch(RuntimeException failure){return new EvalResponse(0,null,"transport_error",null);}
            finally{inFlight.release();}
        },pool);
        try{
            var res=future.get(intProp("decision-wait-ms",150,0,5000),TimeUnit.MILLISECONDS);
            remember(res);
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
    private void remember(EvalResponse res){
        if(res==null)return;
        if(res.httpStatus()==401||res.httpStatus()==403||"auth_blocked".equals(res.failure()))authBlocked.set(true);
        if("rate_limited".equals(res.failure()))
            rateLimitedUntilMs=clock.millis()+Math.max(0,Math.min(res.retryAfterMs()==null?30000:res.retryAfterMs(),300000));
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
    private int intProp(String name,int fallback,int min,int max){
        Integer value=env.getProperty("demo.jev."+name,Integer.class);
        return value==null?fallback:Math.max(min,Math.min(max,value));
    }
    private static String bounded(String question){
        if(question==null)return "";
        String text=question.strip().replaceAll("\\s+"," ");
        return text.length()<=1200?text:text.substring(0,1200);
    }
}
