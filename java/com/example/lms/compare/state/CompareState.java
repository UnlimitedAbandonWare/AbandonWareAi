package com.example.lms.compare.state;

import java.util.List;
import java.util.Map;



/**
 * Immutable description of a comparative analysis request. This record bundles
 * together all of the key inputs used by the comparison subsystem such as
 * the entities under consideration, the criteria to evaluate them by,
 * component weights, optional team context, arbitrary constraints and
 * whether the caller has requested an explanatory narrative.
 */
public record CompareState(
        /** entities to compare (e.g. products A, B and C) */
        List<String> entities,
        /** criteria such as price, performance, reliability */
        List<String> criteria,
        /**
         * weightings for each scoring component. Standard keys include:
         * vec, team, ce, rrf and llm but arbitrary keys are accepted.
         */
        Map<String, Double> weights,
        /** optional set of entities representing user preferences or
         * constraints (e.g. preferred brands or banned items) */
        List<String> teamContext,
        /** additional arbitrary constraints such as region, price cap or
         * custom filters. Values may be strings, numbers or booleans. */
        Map<String, Object> constraints,
        /** whether the caller requests an explanation of the results */
        boolean explain
) {
}