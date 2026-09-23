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
    private final Map<Long,ChatRunExecutionContext> active=new ConcurrentHashMap<>();
    private final SearchDecisionService decisions=new SearchDecisionService();
    public NovaFocusAnswerService(ChatService chat,PublicRequestBudgetGuard budgets,ChatRunRegistry runs){this.chat=chat;this.budgets=budgets;this.runs=runs;}
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory){
        return answer(room,question,memory,()->true);
    }
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory,java.util.function.BooleanSupplier current){
        boolean web=decisions.decide(question,SearchMode.AUTO,null,3,false).shouldSearch()
                ||ConversateAnswerPipeline.focusEvidenceRequested(question);
        var request=ChatRequestDto.builder().message(question).sessionId(null).memoryMode("EPHEMERAL")
            .searchMode(web?SearchMode.AUTO:SearchMode.OFF).useWebSearch(web).useRag(false).useVerification(false)
            .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web,false)).maxTokens(1024).webTopK(3).build();
        budgets.validateChatProjected(request,PlanHints.empty("nova-focus"),web,false);
        var context=new ChatConversationContext(memory.recent().stream().map(NovaFocusAnswerService::pair).toList(),
            memory.summary(),memory.relevant().stream().map(NovaFocusAnswerService::pair).toList());
        var started=runs.beginOrJoin(room);
        if(!started.owner())throw new IllegalStateException("focus_busy");
        var run=started.context();active.put(room,run);
        try(var binding=ChatRunExecutionContext.bind(run)){
            if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
            ChatRunExecutionContext.throwIfCancelled();
            String answer=chat.continueChat(request,null,context).content();
            ChatRunExecutionContext.throwIfCancelled();
            if(answer==null||answer.isBlank())throw new IllegalStateException("focus_empty_answer");
            return answer;
        }finally{active.remove(room,run);runs.markDone(run);}
    }
    private static ChatConversationContext.Turn pair(NovaFocusHistoryService.Pair value){return new ChatConversationContext.Turn(value.question(),value.answer());}
    @Override public void cancel(Long room){var run=active.get(room);if(run!=null)runs.cancelExact(room,run.clientToken());}
}
