package com.example.lms.compare.api;

import com.example.lms.compare.common.CompareResult;
import com.example.lms.compare.state.CompareState;



/**
 * Strategy interface for computing comparative scores across a set of entities.
 *
 * <p>The {@link #compute(CompareState)} method accepts a {@link CompareState}
 * describing the entities to compare, the criteria on which they should be
 * compared and any additional weighting or team context. Implementations
 * should return a {@link CompareResult} capturing the final ranked list of
 * entities along with a breakdown of component scores. The concrete scoring
 * logic is intentionally left open to allow experimentation with various
 * hybrid approaches (vector similarity, set embeddings, cross-encoders,
 * reciprocal rank fusion, LLM judgements, etc.).</p>
 */
public interface ComparatorCalculator {

    /**
     * Compute a comparative ranking over the supplied entities.
     *
     * @param state comparison definition containing entities, criteria, weights
     *              and user context
     * @return a result containing a ranked list and component score breakdown
     */
    CompareResult compute(CompareState state);
}