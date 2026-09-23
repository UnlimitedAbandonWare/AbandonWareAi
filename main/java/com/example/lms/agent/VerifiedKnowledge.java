// src/main/java/com/example/lms/agent/VerifiedKnowledge.java
package com.example.lms.agent;

import java.util.List;



public record VerifiedKnowledge(
        String domain,
        String entityName,
        String structuredDataJson,
        List<String> sources,
        double confidenceScore
) {}