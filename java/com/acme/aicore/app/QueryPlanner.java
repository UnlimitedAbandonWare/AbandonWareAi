package com.acme.aicore.app;

import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Plan;
import com.acme.aicore.domain.model.PromptParams;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import com.acme.aicore.domain.model.UserQuery;
import org.springframework.stereotype.Component;



/**
 * Decides which retrieval strategies to invoke based on simple heuristics of
 * the incoming user query.  This planner is intentionally lightweight and
 * transparent.  For example, queries containing temporal keywords will
 * favour web search, whereas numeric factoids will favour vector search.
 * Both channels are used for general queries.
 */
@Component
public class QueryPlanner {
    public Plan decide(UserQuery q) {
        String text = q.text();
        boolean isDefinition = text.matches(".*(무엇|정의|meaning).*");
        boolean isFresh = text.matches(".*(최신|오늘|어제|올해|뉴스).*");
        boolean isFactoid = text.matches(".*(언제|누가|몇|수치|숫자).*");
        Plan.Builder b = Plan.builder().stream(true).webFanout(2).rerankTopN(12);
        if (isFresh) {
            b.useWeb(true).useVector(false).rerankTopN(0);
        } else if (isFactoid) {
            b.useVector(true).useWeb(false).rerankTopN(8);
        } else if (isDefinition) {
            b.useWeb(true).useVector(true).rerankTopN(12);
        } else {
            b.useWeb(true).useVector(true).rerankTopN(10);
        }
        return b.rankingParams(RankingParams.defaults())
                .rerankParams(new RerankParams())
                .promptParams(PromptParams.defaults())
                .generationParams(GenerationParams.streaming())
                .build();
    }
}