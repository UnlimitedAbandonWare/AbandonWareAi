package com.example.lms.service.rag.chain;


/**
 * Represents the remainder of the chain after a given link.  Invoking
 * {@link #proceed(ChainContext)} on this object calls the next link in
 * the chain.  When no further links are available the call may
 * effectively be a no-op.  Implementations are free to build the
 * chain recursively or iteratively.
 */
public interface Chain {
    /**
     * Proceed to the next link in the chain with the given context.
     *
     * @param ctx the context to pass to the next link
     * @return the outcome from the next link
     */
    ChainOutcome proceed(ChainContext ctx);
}