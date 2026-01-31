package com.abandonwareai.nova.router;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local-only LLM stub for idle AutoLearn.
 * Generates deterministic placeholder answers with pseudo citations.
 */
@Service
public class LocalLlmService {

    private final String engine;
    private final AtomicInteger counter = new AtomicInteger(0);

    public LocalLlmService(@Value("${idle.llm.engine:llama.cpp-local}") String engine) {
        this.engine = engine;
    }

    public String answer(String question, String plan, String sessionId) {
        int i = counter.incrementAndGet();
        String citations = String.format("[1] [2] [3]");
        return "Plan=" + plan + " | Engine=" + engine + " | Q: " + question + " -> A" + i + " with citations " + citations;
    }
}
