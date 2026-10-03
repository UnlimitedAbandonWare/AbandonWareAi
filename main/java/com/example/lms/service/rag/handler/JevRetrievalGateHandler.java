package com.example.lms.service.rag.handler;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.assist.*;
import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import com.example.lms.gptsearch.decision.*;
import com.example.lms.gptsearch.decision.JevSearchNeedAdvisor.SearchPermission;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.Content;
import org.springframework.core.env.Environment;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Optional decorator. Request proof and observations stay local; only narrowing hints enter a query copy. */
public final class JevRetrievalGateHandler implements RetrievalHandler {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private final RetrievalHandler delegate;
    private final boolean dynamic;
    private final Environment env;
    private final JevChoiceAdvisor advisor;
    private final SearchDecisionService decisions;
    private JevRetrievalGateHandler(RetrievalHandler delegate, boolean dynamic, Environment env,
            JevChoiceAdvisor advisor, SearchDecisionService decisions) {
        this.delegate=delegate; this.dynamic=dynamic; this.env=env; this.advisor=advisor; this.decisions=decisions;
    }
    public static RetrievalHandler wrapIfEnabled(RetrievalHandler delegate, boolean dynamic,
            Environment env, JevChoiceAdvisor advisor, SearchDecisionService decisions) {
        if (delegate==null || env==null || advisor==null || decisions==null || !enabled(env)) return delegate;
        return new JevRetrievalGateHandler(delegate,dynamic,env,advisor,decisions);
    }
    private static boolean enabled(Environment env) {
        return !"off".equals(new JevSurfacePolicy(env).resolve("main").mode())
                && "true".equalsIgnoreCase(env.getProperty("demo.jev.choice.enabled","false"))
                && "true".equalsIgnoreCase(env.getProperty("demo.jev.prefetch.enabled","false"))
                && (Set.of("on","shadow").contains(env.getProperty("demo.jev.seams.search-need","off"))
                    || Set.of("on","shadow").contains(env.getProperty("demo.jev.seams.complexity","off")));
    }
    @Override public void handle(Query query, List<Content> accumulator) {
        // A parent focus/cue request owns its reservation and revision. Never mint main authority for it.
        if (!enabled(env) || CURRENT.get()!=null || JevDecisionScope.capture()!=null) {
            delegate.handle(query,accumulator); return;
        }
        var run=ChatRunExecutionContext.current();
        var budget=TimeBudgetContext.get();
        var guard=GuardContextHolder.get();
        if (run==null || budget==null || guard==null) { delegate.handle(query,accumulator); return; }
        boolean privacy=!guard.isSensitiveTopic() && !guard.planBool("privacy.boundary.block-web-search",false)
                && !"true".equalsIgnoreCase(env.getProperty("privacy.boundary.block-web-search","false"));
        var state=new State(this,run,budget,privacy);
        CURRENT.set(state);
        try {
            state.requireCurrent();
            if (!dynamic) { prefetch(query); query=applyHints(query); }
            delegate.handle(query,accumulator);
        } finally {
            try { if(state.handle!=null)advisor.discard(state.handle); }
            finally { if(state.scope!=null)state.scope.close(); CURRENT.remove(); }
        }
    }
    private static final class State {
        final JevRetrievalGateHandler owner;
        final ChatRunExecutionContext run;
        final TimeBudget budget;
        final boolean privacy;
        final com.example.lms.service.guard.GuardContext privacyContext;
        final long deadline;
        QuestionKey key;
        DecisionAdmission admission;
        EvaluationHandle handle;
        JevDecisionScope scope;
        ChoiceResult result;
        Query appliedQuery;
        boolean stale;
        boolean complexityAssessed;
        boolean searchApplied;
        String complexitySource="none";
        com.example.lms.service.rag.QueryComplexityGate.Level complexityObservedLevel;
        com.example.lms.service.rag.QueryComplexityGate.Level complexityLevel;
        State(JevRetrievalGateHandler owner, ChatRunExecutionContext run, TimeBudget budget, boolean privacy) {
            this.owner=owner; this.run=run; this.budget=budget; this.privacy=privacy;
            this.privacyContext=GuardContextHolder.get();
            deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget.remainingMillis());
        }
        boolean privacyAllowed() {
            return GuardContextHolder.get()==privacyContext && dispatchPrivacyAllowed();
        }
        boolean dispatchPrivacyAllowed() {
            var guard=privacyContext;
            return privacy && guard!=null && !guard.isSensitiveTopic()
                    && !guard.planBool("privacy.boundary.block-web-search",false)
                    && !"true".equalsIgnoreCase(owner.env.getProperty("privacy.boundary.block-web-search","false"));
        }
        void requireCurrent() {
            if(Thread.currentThread().isInterrupted() || budget.cancelled() || !run.admitCall(()->{}))
                throw new CancellationException("request_cancelled");
            if(budget.expired() || System.nanoTime()-deadline>=0)
                throw new com.example.lms.llm.ModelSelectionException("backend_timeout");
        }
        boolean matches(Query query) {
            return !stale && key!=null && query!=null && key.localFingerprint().equals(SafeRedactor.hashValue(query.text()));
        }
    }
    /** Dynamic chains call this only after alias correction has selected the effective query. */
    static void prefetch(Query query) {
        var state=CURRENT.get();
        if(state==null || query==null || !enabled(state.owner.env)) return;
        state.requireCurrent();
        if(state.key!=null) {
            if(!state.matches(query)) { state.stale=true; state.owner.advisor.discard(state.handle); }
            return;
        }
        state.key=new QuestionKey(UUID.randomUUID(),0,SafeRedactor.hashValue(query.text()));
        boolean privacyAtSubmission=state.privacyAllowed();
        state.admission=new DecisionAdmission(()->!state.budget.cancelled() && state.run.admitCall(()->{})
                && (!privacyAtSubmission || state.dispatchPrivacyAllowed()),state.deadline,privacyAtSubmission);
        state.scope=JevDecisionScope.bind("main",state.key,state.admission);
        // The registry admission callback is short: submit only, never await the remote result under its lock.
        boolean admitted=state.run.admitCall(()->state.handle=state.owner.advisor.prefetch(
                state.key,"main",query.text(),List.of(JevChoiceAdvisor.WEB_NEED,JevChoiceAdvisor.COMPLEXITY),state.admission));
        if(!admitted)throw new CancellationException("request_cancelled");
    }
    private static State observed(Query query) {
        var state=CURRENT.get();
        if(state==null || state.handle==null || !enabled(state.owner.env))return null;
        state.requireCurrent();
        if(!state.matches(query)) {
            state.stale=true;state.owner.advisor.discard(state.handle);return null;
        }
        // Re-check runtime policy/currentness at every consumer, using the original handle/deadline.
        state.result=state.owner.advisor.await(state.handle,state.key,state.deadline);
        TraceStore.putInternal("rag.jev.prefetch.hit",validWebObservation(state));
        return state;
    }
    static Query applyHints(Query query) {
        var state=observed(query);
        if(state==null)return query;
        var mode=mode(query);
        var md=QueryUtils.metadata(query);
        boolean infer=bool(md,"inferGeneralQuestions",true);
        var baseline=state.owner.decisions.decide(query.text(),mode,null,null,infer);
        var adjusted=adjust(state,query,baseline,mode);
        var complexity = complexityOn(state) ? com.example.lms.service.rag.JevComplexityClassifier
                .acceptedLevel(observation(state,"complexity")) : java.util.Optional
                .<com.example.lms.service.rag.QueryComplexityGate.Level>empty();
        if(adjusted==baseline && complexity.isEmpty())return query;
        Map<String,Object> hints=new LinkedHashMap<>();
        if(adjusted!=baseline && !adjusted.shouldSearch()) {
            hints.put("allowWeb",false);hints.put("useWebSearch",false);hints.put("retrieval.web.enabled",false);
            hints.put("enableSelfAsk",false);hints.put("enableAnalyze",false);
        } else if(adjusted!=baseline) {
            // Permission booleans never become true. Depth changes retain every existing budget/provider hint.
            hints.put("depth",adjusted.depth().name());
        }
        if(complexity.isPresent()) {
            var level=complexity.get();
            if(!state.complexityAssessed)recordComplexity(level,level,"jev");
            if(level!=com.example.lms.service.rag.QueryComplexityGate.Level.COMPLEX)hints.put("enableSelfAsk",false);
            if(level==com.example.lms.service.rag.QueryComplexityGate.Level.SIMPLE)hints.put("enableAnalyze",false);
            if(!bool(md,"allowWeb",true) || !bool(md,"retrieval.web.enabled",true) || !bool(md,"useWebSearch",true)) {
                hints.put("enableSelfAsk",false);hints.put("enableAnalyze",false);
            }
        }
        state.appliedQuery=QueryUtils.rebuild(query,query.text());
        QueryUtils.mergeMetadata(state.appliedQuery,hints);
        return state.appliedQuery;
    }
    private static boolean complexityOn(State state) {
        return "on".equals(new JevSurfacePolicy(state.owner.env).resolve("main").mode())
                && "on".equals(state.owner.env.getProperty("demo.jev.seams.complexity","off"));
    }
    /** One dynamic classification for the request; failed observations delegate to the existing gate once. */
    static com.example.lms.service.rag.QueryComplexityGate.Level complexityLevel(Query query,
            com.example.lms.service.rag.QueryComplexityGate gate) {
        var state=observed(query);
        if(state==null || gate==null || !complexityOn(state))return null;
        if(!state.complexityAssessed) {
            try {
                var observation=observation(state,"complexity");
                var accepted=com.example.lms.service.rag.JevComplexityClassifier.acceptedLevel(observation);
                state.complexityLevel=gate.assess(query.text(),observation);
                if(accepted.isPresent()&&state.complexityLevel==accepted.get()){
                    state.complexityObservedLevel=accepted.get();state.complexitySource="jev";
                }else if(state.complexityLevel!=null)state.complexitySource="baseline";
            } catch (CancellationException | com.example.lms.llm.ModelSelectionException parentFailure) {
                throw parentFailure;
            } catch (RuntimeException classifierFailure) {
                // Preserve the dynamic chain's existing fail-soft baseline gate path.
                state.complexityLevel=null;
            }
            state.complexityAssessed=true;
        }
        recordComplexity(state.complexityObservedLevel,state.complexityLevel,state.complexitySource);
        return state.complexityLevel;
    }
    private static void recordComplexity(com.example.lms.service.rag.QueryComplexityGate.Level observed,
            com.example.lms.service.rag.QueryComplexityGate.Level effective,String source) {
        TraceStore.putInternal("rag.jev.complexity.level",observed==null?null:observed.name());
        TraceStore.putInternal("rag.jev.complexity.effectiveLevel",effective==null?"none":effective.name());
        TraceStore.putInternal("rag.jev.complexity.source",source);
        TraceStore.putInternal("rag.jev.complexity.applied","jev".equals(source));
    }
    static boolean hasApplied(Query query) {
        var state=CURRENT.get();
        return state!=null && state.appliedQuery==query && state.matches(query);
    }
    /** S11 consumes the same request bundle; metadata never supplies an observation. */
    public static SearchDecision applySearchDecision(Query query, SearchDecision baseline, SearchMode mode) {
        var state=observed(query);
        return state==null?baseline:adjust(state,query,baseline,mode);
    }
    private static SearchDecision adjust(State state,Query query,SearchDecision baseline,SearchMode mode) {
        var md=QueryUtils.metadata(query);
        boolean caller=bool(md,"useWebSearch",false);
        boolean scoped=bool(md,"allowWeb",true) && bool(md,"retrieval.web.enabled",true);
        boolean priorDeny=!caller || !scoped;
        var permission=new SearchPermission(caller,scoped,state.privacyAllowed(),priorDeny,bool(md,"inferGeneralQuestions",true));
        var observation=observation(state,"webNeed");
        var adjusted=new JevSearchNeedAdvisor(state.owner.env).apply(baseline,mode,observation,permission);
        boolean accepted=adjusted!=baseline;
        boolean changed=baseline!=null&&adjusted!=null&&(baseline.shouldSearch()!=adjusted.shouldSearch()
                ||baseline.depth()!=adjusted.depth()||!Objects.equals(baseline.providers(),adjusted.providers())
                ||baseline.topK()!=adjusted.topK());
        // Preserve the causal observation when a later consumer merely retains the changed baseline.
        if(!state.searchApplied||changed) {
            boolean shadow="shadow".equals(new JevSurfacePolicy(state.owner.env).resolve("main").mode())
                    ||"shadow".equals(state.owner.env.getProperty("demo.jev.seams.search-need","off"));
            TraceStore.putInternal("rag.jev.searchNeed.decision",validWebObservation(state)?observation.choice():"none");
            TraceStore.putInternal("rag.jev.searchNeed.reasonCode",safeReason(state.result.reasonCode()));
            TraceStore.putInternal("rag.jev.searchNeed.accepted",accepted);
            TraceStore.putInternal("rag.jev.searchNeed.applied",changed);
            TraceStore.putInternal("rag.jev.searchNeed.applyReasonCode",changed?"changed":shadow?"shadow":accepted?"no_effect":"baseline_retained");
        }
        state.searchApplied|=changed;
        return adjusted;
    }
    private static boolean validWebObservation(State state) {
        var observation=observation(state,"webNeed");
        if(observation==null||!observation.schemaValid()||observation.choice()==null
                ||!Set.of("NONE","LIGHT","DEEP").contains(observation.choice())
                ||observation.probability()==null||observation.probability().isEmpty())return false;
        double probability=observation.probability().getAsDouble();
        return Double.isFinite(probability)&&probability>=0&&probability<=1;
    }
    private static ChoiceObservation observation(State state,String id) {
        return state.result!=null&&state.result.httpStatus()==200&&"ok".equals(state.result.reasonCode())
                ?state.result.answers().get(id):null;
    }
    private static String safeReason(String reason) {
        return reason!=null&&(Set.of("ok","timeout","budget_skip","disabled","shadow","cancelled","busy",
                "auth_blocked","auth_invalid","plan_gate","permission_denied","rate_limited","jev_not_configured",
                "invalid_response","transport_error","upstream_error","network","error","billing-blocked",
                "endpoint_invalid","endpoint_not_allowed","endpoint_not_https","encode_failed","state_oversized",
                "oversized_response","redirect","model_unverified","wrong_model").contains(reason)
                ||reason.matches("http_[1-5][0-9]{2}"))?reason:"invalid_response";
    }
    private static SearchMode mode(Query query) {
        try { return SearchMode.valueOf(String.valueOf(QueryUtils.metadata(query).getOrDefault("searchMode","AUTO")).trim().toUpperCase(Locale.ROOT)); }
        catch(RuntimeException invalid) { return SearchMode.OFF; }
    }
    private static boolean bool(Map<String,Object> values,String key,boolean fallback) {
        Object value=values.get(key);return value==null?fallback:Boolean.parseBoolean(String.valueOf(value));
    }
}
