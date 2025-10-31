package com.example.lms.service.rag.handler;

import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * A shim retrieval handler that checks whether a query appears to be a
 * location related request.  When such an intent is detected this
 * handler simply returns without adding any content to the accumulator.
 * Downstream handlers may choose to process the location query via
 * other mechanisms such as a dedicated chat handler.  This class
 * currently does not perform any enrichment and serves as a shim
 * for future integration.
 */
@RequiredArgsConstructor
@Component
public class LocationAwareHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(LocationAwareHandler.class);
    private final LocationService locationService;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            String text = (q != null && q.text() != null) ? q.text().trim() : "";
            LocationIntent intent = locationService.detectIntent(text);
            if (intent != null && intent != LocationIntent.NONE) {
                // Logging for debugging; no content is added for location queries.
                log.debug("[LocationAware] Detected location intent {} for query '{}'; skipping retrieval", intent, text);
                // Returning false stops the chain; however the surrounding chains
                // currently do not include this handler so this is effectively a
                // no-op.  Future integration may short-circuit retrieval.
                return false;
            }
        } catch (Exception e) {
            log.debug("[LocationAware] error {}", e.toString());
        }
        return true;
    }
}