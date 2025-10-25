package com.example.lms.service.reinforcement;


/**
 * Represents a single reinforcement job to be processed in a batch.
 */
public record ReinforcementTask(
        long memoryId,
        String queryText,
        double reward,
        String sourceTag
) {
    // record provides canonical constructor and getters
}