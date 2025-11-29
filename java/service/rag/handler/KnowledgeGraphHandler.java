package service.rag.handler;

/**
 * Minimal KG handler with plan-based knobs.
 */
public class KnowledgeGraphHandler {
    private boolean enabled = false;
    private int maxHops = 1;
    private int ttlMs = 1000;

    public void configure(boolean enabled, int maxHops, int ttlMs) {
        this.enabled = enabled;
        this.maxHops = maxHops;
        this.ttlMs = ttlMs;
    }
}