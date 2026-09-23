package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.plan.PlanHints;
import com.example.lms.service.ChatConversationContext;
import com.example.lms.service.ChatService;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded public answer route: explicit Focus memory, existing retrieval and model workflow. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class NovaFocusAnswerService implements NovaFocusAnswer {
    private final ChatService chat;
    private final PublicRequestBudgetGuard budgets;
    private final ChatRunRegistry runs;
    private final FocusMemoryService memories;
    private final Map<Long,ChatRunExecutionContext> active=new ConcurrentHashMap<>();
    private final SearchDecisionService decisions=new SearchDecisionService();
    public NovaFocusAnswerService(ChatService chat,PublicRequestBudgetGuard budgets,ChatRunRegistry runs){this(chat,budgets,runs,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public NovaFocusAnswerService(ChatService chat,PublicRequestBudgetGuard budgets,ChatRunRegistry runs,FocusMemoryService memories){this.chat=chat;this.budgets=budgets;this.runs=runs;this.memories=memories;}
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory){
        return answer(room,question,memory,()->true);
    }
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory,java.util.function.BooleanSupplier current){
        return answer(room,question,memory,null,current);
    }
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        boolean web=decisions.decide(question,SearchMode.AUTO,null,3,false).shouldSearch()
                ||ConversateAnswerPipeline.focusEvidenceRequested(question);
        var request=ChatRequestDto.builder().message(question).sessionId(null).memoryMode("EPHEMERAL")
            .searchMode(web?SearchMode.AUTO:SearchMode.OFF).useWebSearch(web).useRag(false).useVerification(false)
            .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web,false)).maxTokens(1024).webTopK(3).build();
        budgets.validateChatProjected(request,PlanHints.empty("nova-focus"),web,scope!=null&&scope.recallEnabled());
        var started=runs.beginOrJoin(room);
        if(!started.owner())throw new IllegalStateException("focus_busy");
        var run=started.context();active.put(room,run);
        var previousBudget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(60000));
        try(var binding=ChatRunExecutionContext.bind(run)){
            if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
            ChatRunExecutionContext.throwIfCancelled();
            var retrieval=memories==null?FocusMemoryService.Result.empty(FocusMemoryService.Status.OFF,"scope_absent"):memories.retrieve(scope,question,current);
            if(retrieval.status()==FocusMemoryService.Status.BLOCKED_SCOPE)throw new java.util.concurrent.CancellationException("focus_memory_revoked");
            var context=new ChatConversationContext(memory.recent().stream().map(NovaFocusAnswerService::pair).toList(),
                memory.summary(),memory.relevant().stream().map(NovaFocusAnswerService::pair).toList(),true,retrieval.evidence());
            // Project bounded extra input into the existing public admission guard; the actual DTO remains unchanged.
            var projected=request.toBuilder().message(question+"\n"+context.memoryText()+"\n"+String.join("\n",context.interpretationHistory())).build();
            budgets.validateChatProjected(projected,PlanHints.empty("nova-focus"),web,scope!=null&&scope.recallEnabled());
            com.example.lms.search.TraceStore.put("focus.memory.status",retrieval.status().name());
            com.example.lms.search.TraceStore.put("focus.memory.mode",retrieval.retrievalMode());
            com.example.lms.search.TraceStore.put("focus.memory.vectorHitCount",retrieval.vectorHits());
            com.example.lms.search.TraceStore.put("focus.memory.graphHitCount",retrieval.graphHits());
            com.example.lms.search.TraceStore.put("focus.memory.evidenceCount",retrieval.evidence().size());
            com.example.lms.search.TraceStore.put("focus.memory.evidenceBytes",retrieval.evidenceBytes());
            com.example.lms.search.TraceStore.put("focus.memory.graphHops",retrieval.graphHops());
            com.example.lms.search.TraceStore.put("focus.memory.tookMs",retrieval.tookMs());
            if(!current.getAsBoolean()||(memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
            String answer=chat.continueChat(request,null,context).content();
            ChatRunExecutionContext.throwIfCancelled();
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
            if(!current.getAsBoolean()||(memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            if(answer==null||answer.isBlank())throw new IllegalStateException("focus_empty_answer");
            return answer;
        }finally{active.remove(room,run);runs.markDone(run);if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.clear();else com.abandonware.ai.addons.budget.TimeBudgetContext.set(previousBudget);}
    }
    private static ChatConversationContext.Turn pair(NovaFocusHistoryService.Pair value){return new ChatConversationContext.Turn(value.question(),value.answer());}
    @Override public void cancel(Long room){var run=active.get(room);if(run!=null)runs.cancelExact(room,run.clientToken());}
}
