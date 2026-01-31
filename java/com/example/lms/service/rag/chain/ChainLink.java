package com.example.lms.service.rag.chain;


/**
 * A single link in the RAG chain.  Each link is responsible for handling
 * the current context and delegating to the next link when appropriate.
 * Implementations should be fail-soft: exceptions should be caught and
 * forwarded to the next link rather than propagating.
 */
public interface ChainLink {
    /**
     * Handle the current context or delegate to the next link.  The
     * returned outcome determines whether the chain should continue
     * processing or terminate early.
     *
     * @param ctx the current chain context
     * @param next an object representing the remainder of the chain
     * @return the outcome of this stage
     */
    ChainOutcome handle(ChainContext ctx, Chain next);
}