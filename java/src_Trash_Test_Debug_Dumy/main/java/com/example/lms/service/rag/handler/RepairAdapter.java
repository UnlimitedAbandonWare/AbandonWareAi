package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;

/**
 * Adapter that integrates the {@link EvidenceRepairHandler} into the retrieval
 * chain.  This handler consumes the accumulated evidence from previous
 * handlers, invokes the repair stage and then replaces the accumulator with
 * the repaired evidence.  Returning {@code false} stops further handler
 * execution which is appropriate because repair is always the terminal
 * stage of the retrieval chain.
 */
public class RepairAdapter extends AbstractRetrievalHandler {

    private final EvidenceRepairHandler repairHandler;

    /**
     * Create a new adapter around the given repair handler.
     *
     * @param repairHandler the evidence repair handler to delegate to
     */
    public RepairAdapter(EvidenceRepairHandler repairHandler) {
        this.repairHandler = repairHandler;
    }

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        try {
            // Perform repair.  When no repaired content is returned the
            // accumulator is left unchanged to preserve existing evidence.
            List<Content> repaired = repairHandler.retrieve(query);
            if (repaired != null && !repaired.isEmpty()) {
                accumulator.clear();
                accumulator.addAll(repaired);
            }
        } catch (Exception ignore) {
            // Any exception from the repair stage should not disrupt the
            // retrieval chain.  Swallow and continue.
        }
        // Repair is always the terminal stage; do not continue.
        return false;
    }
}