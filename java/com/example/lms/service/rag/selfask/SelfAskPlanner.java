package com.example.lms.service.rag.selfask;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal 3-way Self-Ask planner producing sub-questions and a chosen branch.
 */
public class SelfAskPlanner {

    public static class Plan {
        public final Branch branch;
        public final List<String> subQuestions;
        public Plan(Branch b, List<String> sq){ this.branch=b; this.subQuestions=sq; }
    }

    public Plan plan(String query) {
        Branch b = Branch.RC;
        String ql = (query==null?"":query.toLowerCase());
        if (ql.contains("latest") || ql.contains("recent") || ql.contains("news")) b = Branch.BQ;
        else if (ql.startsWith("who is") || ql.startsWith("what is") || ql.contains("born")) b = Branch.ER;
        List<String> subs = new ArrayList<>();
        subs.add(query);
        return new Plan(b, subs);
    }
}