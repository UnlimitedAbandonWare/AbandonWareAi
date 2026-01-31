package com.example.lms.service.rag.planner;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.planner.QueryComplexityClassifier
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.planner.QueryComplexityClassifier
role: config
*/
public class QueryComplexityClassifier {
    public enum Level { SIMPLE, COMPLEX }
    public static class Complexity {
        private final Level level;
        public Complexity(Level l){this.level=l;}
        public boolean isComplex(){ return level==Level.COMPLEX; }
        public Level level(){ return level; }
    }
    public Complexity score(String q){
        if (q == null) return new Complexity(Level.SIMPLE);
        int len = q.length();
        boolean hasWhen = q.matches(".*(최근|언제|latest|today|올해).*");
        boolean multi = q.contains("와") || q.contains(" vs ") || q.contains(",");
        int s = (len>40?1:0) + (hasWhen?1:0) + (multi?1:0);
        return new Complexity(s>=2 ? Level.COMPLEX : Level.SIMPLE);
    }
}