package com.example.lms.service.rag;


/**
 * Represents the outcome of a retrieval chain stage.  Retrieval handlers may
 * return one of these values to indicate whether the chain should continue
 * processing or terminate early.  This enum exists for future integration
 * with the {@link com.example.lms.service.rag.handler.AbstractRetrievalHandler}
 * but is not currently wired into the chain.  Introducing a discrete
 * outcome type lays the groundwork for explicit control flow and improved
 * readability compared to boolean flags.
 */
public enum ChainOutcome {
    /** Continue executing subsequent handlers in the chain. */
    CONTINUE,
    /** Terminate the chain; no further handlers will be invoked. */
    TERMINATE
}