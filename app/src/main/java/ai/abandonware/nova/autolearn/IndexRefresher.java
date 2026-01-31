package ai.abandonware.nova.autolearn;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder that pretends to refresh vector indices.
 * Replace with FederatedEmbeddingStore integration over time.
 */
@Component
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class IndexRefresher {
    private static final Logger log = LoggerFactory.getLogger(IndexRefresher.class);

    public int refreshIncremental() {
        // In early integration we just return 0 and log.
        log.info("[IndexRefresher] Incremental index refresh requested (stub).");
        return 0;
    }
}