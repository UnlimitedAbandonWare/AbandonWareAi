package com.acme.aicore.app;

import com.acme.aicore.domain.model.*;
import com.acme.aicore.domain.ports.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Comparator;
import java.util.List;




/**
 * Orchestrates the retrieval, ranking and generation pipeline to answer a
 * user query.  This service composes the various ports defined in the
 * domain layer and applies the plan returned by the {@link QueryPlanner}.
 * Non-blocking reactive primitives (Flux/Mono) are used throughout to avoid
 * blocking threads on I/O.  Downstream exceptions are not propagated but
 * result in partial answers when possible.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final QueryPlanner planner;
    private final List<WebSearchProvider> webProviders;
    private final VectorSearchPort vectorPort;
    private final RankingPort ranking;
    private final PromptPort promptPort;
    private final MemoryPort memory;
    private final ChatModelPort llm;

    public Flux<AnswerChunk> answer(SessionContext ctx, UserQuery q) {
        Plan plan = planner.decide(q);
        // Determine whether to execute web search
        Mono<SearchBundle> web = plan.useWeb()
                ? Flux.fromIterable(webProviders)
                    .sort(Comparator.comparingInt(WebSearchProvider::priority).reversed())
                    .flatMap(p -> p.search(WebSearchQuery.of(q))).take(plan.webFanout()).collectList()
                    .map(SearchBundle::merge)
                : Mono.empty();

        // Determine whether to execute vector search
        Mono<SearchBundle> vec = plan.useVector() ? vectorPort.search(VectorQuery.of(q)) : Mono.empty();

        Mono<List<RankedDoc>> fused = Mono.zip(web.defaultIfEmpty(SearchBundle.empty()),
                                                vec.defaultIfEmpty(SearchBundle.empty()))
                .flatMap(t -> ranking.fuseAndRank(List.of(t.getT1(), t.getT2()), plan.rankingParams()))
                .flatMap(docs -> plan.rerankTopN() > 0
                        ? ranking.rerank(docs.stream().limit(plan.rerankTopN()).toList(), plan.rerankParams())
                        : Mono.just(docs))
                .onErrorResume(ex -> Mono.just(List.of()));

        return memory.history(ctx.sessionId()).defaultIfEmpty(List.of()).zipWith(fused)
                .flatMapMany(tuple -> {
                    List<Message> history = tuple.getT1();
                    List<RankedDoc> docs = tuple.getT2();
                    Prompt prompt = promptPort.buildPrompt(ctx.withHistory(history), docs, plan.promptParams());
                    if (plan.stream()) {
                        return llm.stream(prompt, plan.generationParams())
                                .map(tc -> new AnswerChunk(tc.text()));
                    } else {
                        return llm.complete(prompt, plan.generationParams())
                                .flatMapMany(result -> Flux.just(new AnswerChunk(result)));
                    }
                })
                .doOnComplete(() -> memory.append(ctx.sessionId(), Message.assistant("/* ... *&#47;")).subscribe())
                .onErrorResume(ex -> Flux.just(new AnswerChunk("Sorry, an error occurred.")));
    }
}