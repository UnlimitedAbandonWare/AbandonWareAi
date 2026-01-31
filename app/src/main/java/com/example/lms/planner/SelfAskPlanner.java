package com.example.lms.planner;
import java.util.*;
public final class SelfAskPlanner {
    public List<String> subQuestions(String q){
        if (q == null) q = "";
        q = q.trim();
        return List.of(
            "정의: " + q,
            "별칭/동의어: " + q,
            "관련 관계/가설: " + q
        );
    }
}