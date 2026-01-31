package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.vector.TopicClassifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Combines simple heuristics with the default provider to return
 * representative soak-test queries for a given topic.
 */
@Component("combinedSoakQueryProvider")
@Primary
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CombinedSoakQueryProvider implements SoakQueryProvider {
    private static final Logger log = LoggerFactory.getLogger(CombinedSoakQueryProvider.class);
    private final TopicClassifier topicClassifier;
    private final SoakQueryProvider delegate;

    public CombinedSoakQueryProvider(TopicClassifier topicClassifier,
                                    DefaultSoakQueryProvider delegate) {
        this.topicClassifier = topicClassifier;
        this.delegate = delegate;
    }

    @Override
    public List<String> queries(String topic) {
        String effective = (topic == null || topic.isBlank()) ? "all" : topic;
        // Delegate to the configured seed provider (application.yml driven).
        List<String> list = delegate != null ? delegate.queries(effective) : Collections.emptyList();
        if (list == null) return Collections.emptyList();
        log.info("SOAK_QUERY_LABEL topic={} fetched={} limit={}", effective, list.size(), list.size());
        return list;
    }
}