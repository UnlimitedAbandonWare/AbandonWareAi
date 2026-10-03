package com.example.lms.assist;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** One bounded postfusion relevance batch. Only original Content references change order. */
@Component
public final class JevCandidateSignal {
    private final Environment env;
    private final JevChoiceAdvisor advisor;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    public JevCandidateSignal(Environment env,ObjectProvider<JevChoiceAdvisor> advisor) {
        this(env,advisor.getIfAvailable());
    }
    public JevCandidateSignal(Environment env,JevChoiceAdvisor advisor) {
        this.env=env;this.advisor=advisor;
    }
    private boolean enabled() {
        return "true".equalsIgnoreCase(env.getProperty("demo.jev.candidate-signal.enabled","false"));
    }
    private boolean consent() {
        return "true".equalsIgnoreCase(env.getProperty("demo.jev.candidate-signal.external-consent","false"));
    }
    public List<Content> rerank(String query,List<Content> candidates) {
        if(!enabled()||!consent())return observed(candidates,"disabled");
        var run=ChatRunExecutionContext.current();
        var budget=TimeBudgetContext.get();
        var guard=GuardContextHolder.get();
        if(run==null||budget==null||guard==null)return observed(candidates,"request_context_missing");
        boolean privacy=!guard.isSensitiveTopic()&&!guard.planBool("privacy.boundary.block-web-search",false)
                &&!"true".equalsIgnoreCase(env.getProperty("privacy.boundary.block-web-search","false"));
        var parent=JevDecisionScope.capture();
        QuestionKey key=parent==null?new QuestionKey(UUID.randomUUID(),0,SafeRedactor.hashValue(query)):parent.key;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget.remainingMillis());
        if(parent!=null)deadline=Math.min(deadline,parent.admission.deadlineNanos());
        DecisionAdmission admission=new DecisionAdmission(()->enabled()&&consent()&&!budget.cancelled()&&run.admitCall(()->{})
                &&!guard.isSensitiveTopic()&&!guard.planBool("privacy.boundary.block-web-search",false)
                &&!"true".equalsIgnoreCase(env.getProperty("privacy.boundary.block-web-search","false"))
                &&(parent==null||parent.admission.current().getAsBoolean()),deadline,
                privacy&&(parent==null||parent.admission.privacyAllowed()));
        return rerank(query,candidates,key,admission);
    }
    public List<Content> rerank(String query,List<Content> candidates,QuestionKey question,DecisionAdmission admission) {
        List<Content> baseline=candidates==null?List.of():candidates;
        if(!enabled()||!consent()||"off".equals(new JevSurfacePolicy(env).resolve("main").mode()))
            return observed(baseline,"disabled");
        if(advisor==null)return observed(baseline,"advisor_unavailable");
        if(baseline.size()<2)return observed(baseline,"single_candidate");
        EvaluationHandle handle=null;
        if(baseline.stream().anyMatch(Objects::isNull))return observed(baseline,"candidate_invalid");
        List<Content> before=List.copyOf(baseline);
        try {
            if(before.stream().anyMatch(row->row.textSegment()==null||row.textSegment().text().isBlank()))
                return observed(before,"candidate_invalid");
            String digest=digest(before);
            int count=Math.min(4,before.size());
            List<Map<String,String>> excerpts=new ArrayList<>();
            for(int i=0;i<count;i++)excerpts.add(Map.of("id","candidate"+i,
                    "excerpt",bounded(SafeRedactor.redact(before.get(i).textSegment().text()),160)));
            String state=JSON.writeValueAsString(Map.of("purpose","candidate_relevance",
                    "question",bounded(SafeRedactor.redact(query),256),"candidates",excerpts));
            QuestionKey effective=new QuestionKey(question.localRequestNonce(),question.revision(),
                    SafeRedactor.hashValue("effective_query|"+question.localFingerprint()+"|"+SafeRedactor.hashValue(query)));
            handle=advisor.prefetchRelevance(effective,digest,"main",state,count,admission);
            ChoiceResult result=advisor.await(handle,JevChoiceAdvisor.candidateKey(effective,digest),admission.deadlineNanos());
            if(!digest.equals(digest(baseline)))return observed(baseline,"candidate_stale");
            if(!"ok".equals(result.reasonCode()))return observed(before,result.reasonCode());
            Set<String> expected=new HashSet<>();
            for(int i=0;i<count;i++)expected.add(JevChoiceAdvisor.RELEVANCE.get(i).id());
            if(!result.answers().keySet().equals(expected))return observed(before,"invalid_response");
            List<Integer> order=new ArrayList<>();
            for(int i=0;i<count;i++) {
                ChoiceObservation observation=result.answers().get(JevChoiceAdvisor.RELEVANCE.get(i).id());
                if(observation==null||!observation.schemaValid()||!observation.confidenceAccepted()
                        ||!Set.of("RELEVANT","IRRELEVANT","UNCERTAIN").contains(observation.choice()))
                    return observed(before,"unaccepted_signal");
                order.add(i);
            }
            order.sort(Comparator.comparingInt(i->rank(result.answers().get(JevChoiceAdvisor.RELEVANCE.get(i).id()).choice())));
            List<Content> ranked=new ArrayList<>(before.size());
            for(int index:order)ranked.add(before.get(index));
            ranked.addAll(before.subList(count,before.size()));
            if(Thread.currentThread().isInterrupted()||!admission.current().getAsBoolean())
                return observed(before,"cancelled");
            if(System.nanoTime()>=admission.deadlineNanos())return observed(before,"timeout");
            return observed(List.copyOf(ranked),"applied");
        } catch(CancellationException cancelled) {
            return observed(before,"cancelled");
        } catch(com.example.lms.llm.ModelSelectionException expired) {
            return observed(before,"timeout");
        } catch(Exception failure) {
            return observed(before,"invalid_response");
        } finally {
            if(handle!=null)advisor.discard(handle);
        }
    }
    private static int rank(String value) {return "RELEVANT".equals(value)?0:"UNCERTAIN".equals(value)?1:2;}
    private static String bounded(String value,int limit) {
        if(value==null)return "";
        int length=Math.min(limit,value.length());
        if(length>0&&length<value.length()&&Character.isHighSurrogate(value.charAt(length-1)))length--;
        return value.substring(0,length);
    }
    private static String digest(List<Content> candidates) {
        StringBuilder identity=new StringBuilder();
        for(Content row:candidates) {
            var metadata=new TreeMap<>(row.textSegment().metadata().toMap());
            var contentMetadata=new TreeMap<String,String>();
            row.metadata().forEach((key,value)->contentMetadata.put(String.valueOf(key),String.valueOf(value)));
            identity.append(SafeRedactor.hashValue(row.textSegment().text())).append('|')
                    .append(SafeRedactor.hashValue(metadata.toString())).append('|')
                    .append(SafeRedactor.hashValue(contentMetadata.toString())).append('\n');
        }
        return SafeRedactor.hashValue(identity.toString());
    }
    private static List<Content> observed(List<Content> candidates,String reason) {
        TraceStore.put("rag.jev.candidate.reasonCode",SafeRedactor.traceLabelOrFallback(reason,"invalid_response"));
        return candidates==null?List.of():candidates;
    }
}
