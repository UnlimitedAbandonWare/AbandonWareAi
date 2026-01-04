package com.example.lms.service.rag.planner;

import java.util.Arrays;
import java.util.List;

public class SelfAskPlanner {
    public record SubQ(String text, String axis) {}
    public List<SubQ> generateSubQuestions(String q) {
        String qq = q == null ? "" : q.trim();
        return Arrays.asList(
            new SubQ("정의/용어 명세: " + qq, "domain"),
            new SubQ("동의어·별칭 정규화: " + qq, "alias"),
            new SubQ("연관 관계·가설: " + qq, "relation")
        );
    }
}