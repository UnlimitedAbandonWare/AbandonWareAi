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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    private final JevEvaluationRuntime runtime;
    private final boolean ownsRuntime;
    // Compatibility views of the same runtime-owned state; no independent cap or permit.
    private final Semaphore inFlight;
    private final AtomicReference<JevEvaluationRuntime.DailyCalls> dailyCalls;
    public JevDecisionAdvisor(Environment env){this(new JevEvaluationRuntime(env),true);}
    JevDecisionAdvisor(Environment env,Clock clock,Transport transport,Function<String,String> secrets){
        this(new JevEvaluationRuntime(env,clock,transport,secrets),true);
    }
    @Autowired public JevDecisionAdvisor(Environment env,JevEvaluationRuntime runtime){this(runtime,false);}
    private JevDecisionAdvisor(JevEvaluationRuntime runtime,boolean ownsRuntime){
        this.runtime=runtime;this.ownsRuntime=ownsRuntime;
        this.inFlight=runtime.inFlight;this.dailyCalls=runtime.dailyCalls;
    }
    @Override @jakarta.annotation.PreDestroy public void close(){if(ownsRuntime)runtime.close();}
    public String mode(){return runtime.mode();}
    public Map<String,Object> status(){return runtime.status();}
    public Advice advise(String surface,String question,String baseline){return runtime.advise(surface,question,baseline);}
    private JevEvaluationRuntime.DailyCalls reserveDailyCall(){return runtime.reserveDailyCall();}
    private void refundDailyCall(JevEvaluationRuntime.DailyCalls reservation){runtime.refundDailyCall(reservation);}
}
