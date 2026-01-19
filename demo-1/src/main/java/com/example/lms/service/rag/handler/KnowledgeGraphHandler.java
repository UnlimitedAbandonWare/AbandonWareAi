package com.example.lms.service.rag.handler;

import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;

/**
 * Minimal KG handler placeholder to stabilize wiring.
 * Replace with real KG backend integration when ready.
 */
@Component
public class KnowledgeGraphHandler {
    public List<Object> query(String question, int k) {
        return Collections.emptyList();
    }
}
