package com.example.lms.service.rag.safety;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Adapter exposing a convenience method to run retrieval tasks with a safe timeout.  Other
 * components can inject this adapter to wrap their retrieval logic rather than depending
 * directly on SafeRetrieverRunner.
 */
@Component
@RequiredArgsConstructor
public class DynamicRetrievalSafeAdapter {

    private final SafeRetrieverRunner safeRunner;

    /**
     * Runs the supplied callable with a default timeout of three seconds.  Returns null on failure.
     *
     * @param task the callable
     * @param <T> return type
     * @return the result or null if it fails or times out
     */
    public <T> T runWithTimeout(java.util.concurrent.Callable<T> task) {
        return safeRunner.run(task, Duration.ofSeconds(3), null);
    }
}
