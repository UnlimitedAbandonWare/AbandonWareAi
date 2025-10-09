package com.example.lms.service.soak;

import com.example.lms.vector.TopicClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Simple implementation of {@link SoakQueryProvider} that returns an empty
 * list.  This shim allows the soak test pipeline to compile without
 * providing a concrete query source.  Future enhancements may pull
 * queries from embedding stores or other repositories based on the
 * supplied topic.
 */
@Component
@RequiredArgsConstructor
public class CombinedSoakQueryProvider implements SoakQueryProvider {
    private static final Logger log = LoggerFactory.getLogger(CombinedSoakQueryProvider.class);
    private final TopicClassifier topicClassifier;
    @Override
    public List<String> sample(int limit, Optional<String> topic) {
        log.info("SOAK_QUERY_LABEL topic={} fetched={} limit={}", topic.orElse("all"), 0, limit);
        return Collections.emptyList();
    }
}