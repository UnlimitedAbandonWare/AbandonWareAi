package com.example.lms.service.rag.chain;


/**
 * Outcome values returned by each link in the chain.  Links can
 * instruct the chain to stop processing (SUCCESS_STOP), to continue
 * after performing some work (SUCCESS_PASS), or to simply pass
 * through without handling (PASS).  Additional values can be added
 * later to support more nuanced control flows.
 */
public enum ChainOutcome {
    /** Processing complete; do not execute remaining links. */
    SUCCESS_STOP,
    /** Processing complete for this link but continue chain execution. */
    SUCCESS_PASS,
    /** No processing was performed; proceed to the next link. */
    PASS
}