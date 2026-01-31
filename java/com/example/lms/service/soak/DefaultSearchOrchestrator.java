package com.example.lms.service.soak;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Very conservative default orchestrator used when the application
 * does not provide a real search pipeline bean. Returns an empty list
 * to keep the soak test infrastructure available without hard failures.
 */
@Service
@Primary
public class DefaultSearchOrchestrator implements SearchOrchestrator {
    @Override
    public List<SearchResult> search(String query, int k) {
        return Collections.emptyList();
    }
}