package com.example.lms.service.soak;

import com.example.lms.vector.TopicClassifier;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CombinedSoakQueryProvider implements SoakQueryProvider {
    private static final Logger log = LoggerFactory.getLogger(CombinedSoakQueryProvider.class);
    private final TopicClassifier topicClassifier;

    @Override
    public List<String> queries(String topic) {
        String effective = (topic == null || topic.isBlank()) ? "all" : topic;
        // For now simply delegate to the default provider.
        List<String> list = new DefaultSoakQueryProvider().queries(effective);
        if (list == null) return Collections.emptyList();
        log.info("SOAK_QUERY_LABEL topic={} fetched={} limit={}", effective, list.size(), list.size());
        return list;
    }
}
