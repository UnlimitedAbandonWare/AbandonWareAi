package com.abandonware.ai.agent.integrations.service.rag.planner;

import java.util.*;
public interface SubQuestionGenerator {
    List<String> generate(String query);
}