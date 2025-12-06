package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.function.ToIntFunction;




/**
 * A retrieval handler that estimates the token cost of an incoming query and
 * emits a hint when the estimated token count meets or exceeds a configured
 * threshold.  The guard does not short-circuit the retrieval chain; it
 * merely logs a message via the provided consumer so that the generation
 * stage can downgrade to a cheaper model when appropriate.  The token
 * estimator is pluggable to accommodate different heuristics.  When the
 * threshold is not reached the handler silently forwards the request.
 */
@Slf4j
@RequiredArgsConstructor
public class SearchCostGuardHandler extends AbstractRetrievalHandler {

    /**
     * Function used to estimate the token count of the query text.  This
     * estimator should be inexpensive to compute; a simple character
     * heuristic is sufficient for coarse gating.  The default estimator
     * divides the character length by three and caps the result at 16k.
     */
    private final ToIntFunction<String> tokenEstimator;
    /**
     * The threshold above which the guard will emit a relief hint.  When
     * the estimated tokens meet or exceed this value the supplied consumer
     * is invoked with a log message.  The chain is never halted.
     */
    private final int thresholdTokens;
    /**
     * Callback invoked when the threshold is exceeded.  Typical
     * implementations will log the hint so that downstream routing logic
     * can select an appropriate generation model.
     */
    private final java.util.function.Consumer<String> onRelief;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        int est = 0;
        try {
            est = tokenEstimator.applyAsInt(q.text());
        } catch (Exception e) {
            // Ignore estimation errors; default to zero
            est = 0;
        }
        if (est >= thresholdTokens) {
            try {
                onRelief.accept("[SearchCostGuard] token ≥ threshold → use relief model for generation stage");
            } catch (Exception ignore) {
                // ignore logging errors
            }
        }
        // Always continue the chain; this guard does not produce content
        return true;
    }
}