package com.example.lms.service.rag.selfask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "selfask.enabled", havingValue = "true", matchIfMissing = false)
public class SelfAskPreprocessorHandler {

    private final SubQuestionGenerator generator;

    public SelfAskPreprocessorHandler() {
        // Default lightweight generator; avoids requiring other beans.
        this.generator = new HeuristicSubQuestionGenerator();
    }

    /** Returns a 3-way list of sub-queries based on the given user query. */
    public List<SubQuestion> preprocess(String userQuery, Map<String,Object> ctx) {
        return generator.generate(userQuery, ctx == null ? Collections.emptyMap() : ctx);
    }
}